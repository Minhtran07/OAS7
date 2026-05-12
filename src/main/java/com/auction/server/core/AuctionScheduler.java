package com.auction.server.core;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.model.AuctionManager;
import com.auction.shared.AppConfig;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AuctionScheduler — Watchdog chạy mỗi giây để:
 *   1. OPEN  → RUNNING  khi now >= startTime
 *   2. RUNNING → FINISHED khi now >= endTime
 *     + cập nhật DB (auctions.status, items.status = SOLD nếu có winner, CLOSED nếu không)
 *     + broadcast AUCTION_FINISHED
 *     + dọn khỏi AuctionManager (không bị load lại ở restart nhờ DB đã FINISHED)
 *
 * Lý do cần có: AuctionManager chỉ cập nhật trạng thái khi có bid. Nếu không bidder
 * nào đặt giá đến hết giờ, phiên sẽ kẹt ở RUNNING mãi mãi.
 */
public class AuctionScheduler {
    private static final Logger logger = LoggerFactory.getLogger(AuctionScheduler.class);

    private final AuctionDAO auctionDAO;
    private final ItemDAO    itemDAO;
    private final ScheduledExecutorService exec;

    public AuctionScheduler(AuctionDAO auctionDAO, ItemDAO itemDAO) {
        this.auctionDAO = auctionDAO;
        this.itemDAO    = itemDAO;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        exec.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        // Quét timeout completion (FINISHED quá COMPLETION_TIMEOUT_MINUTES → CANCELED)
        exec.scheduleAtFixedRate(this::scanCompletionTimeouts,
                AppConfig.COMPLETION_SCAN_INTERVAL_SEC,
                AppConfig.COMPLETION_SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
        logger.info("AuctionScheduler đã khởi động. Completion timeout = {} phút, scan interval = {}s",
                AppConfig.COMPLETION_TIMEOUT_MINUTES, AppConfig.COMPLETION_SCAN_INTERVAL_SEC);
    }

    public void stop() {
        exec.shutdownNow();
    }

    /** Chạy mỗi giây — KHÔNG ném exception ra ngoài để scheduler không bị chặn. */
    private void tick() {
        try {
            Map<Integer, Auction> view = AuctionManager.getInstance().getActiveAuctionsView();
            LocalDateTime now = LocalDateTime.now();

            for (Map.Entry<Integer, Auction> e : view.entrySet()) {
                int auctionId = e.getKey();
                Auction auction = e.getValue();
                if (auction == null) continue;

                ReentrantLock lock = AuctionManager.getInstance().getLock(auctionId);
                if (lock == null) continue;

                lock.lock();
                try {
                    Role status = auction.getStatus();

                    // OPEN → RUNNING
                    if (status == Role.OPEN
                            && auction.getStartTime() != null
                            && !now.isBefore(auction.getStartTime())) {
                        auction.setStatus(Role.RUNNING);
                        auctionDAO.updateStatus(auctionId, "RUNNING");
                        logger.info("Phiên #{} OPEN → RUNNING", auctionId);
                    }

                    // RUNNING → FINISHED
                    if (auction.getStatus() == Role.RUNNING
                            && auction.getEndTime() != null
                            && !now.isBefore(auction.getEndTime())) {
                        finishAuction(auctionId, auction);
                    }
                } finally {
                    lock.unlock();
                }
            }
        } catch (Throwable t) {
            logger.error("Lỗi trong AuctionScheduler.tick(): {}", t.getMessage(), t);
        }
    }

    /** Gọi dưới lock. Đổi sang FINISHED, ghi DB, broadcast, dọn RAM. */
    private void finishAuction(int auctionId, Auction auction) {
        auction.setStatus(Role.FINISHED);

        int winnerId = auction.getHighestBidderId();
        String winnerName = (auction.getCurrentWinner() != null)
                ? auction.getCurrentWinner().getFullname() : null;
        double finalPrice = auction.getCurrentPrice() != null
                ? auction.getCurrentPrice().doubleValue() : 0.0;

        // 1. Cập nhật DB + ghi finished_at để scheduler timeout đếm chuẩn
        auctionDAO.updateStatus(auctionId, "FINISHED");
        auctionDAO.updateFinishedAt(auctionId);

        // 2. Cập nhật item status
        String itemName = null;
        if (auction.getItem() != null) {
            int itemId = auction.getItem().getId();
            itemName = auction.getItem().getName();
            String itemStatus = (winnerId > 0) ? "SOLD" : "CLOSED";
            itemDAO.updateStatus(itemId, itemStatus);
        }

        logger.info("Phiên #{} FINISHED. Winner={} finalPrice={}",
                auctionId, winnerName != null ? winnerName : "—", finalPrice);

        // 3. Broadcast AUCTION_FINISHED qua BidEventBus (cho subscriber đang mở bidding)
        BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.auctionFinished(auctionId,
                        winnerId,
                        winnerName != null ? winnerName : "Không có người thắng",
                        finalPrice)
        );

        // 4. Notification: cho TẤT CẢ bidder từng tham gia phiên này
        final String nameForMsg = (itemName != null) ? itemName : "phiên #" + auctionId;
        try {
            List<Integer> bidders = auctionDAO.getBidderIdsForAuction(auctionId);
            for (int b : bidders) {
                NotificationHub.getInstance().notifyAuctionFinishedForBidder(b, nameForMsg, auctionId);
            }
            // Notification riêng cho winner
            if (winnerId > 0) {
                NotificationHub.getInstance().notifyAuctionWon(winnerId, nameForMsg, auctionId, finalPrice);
            }
        } catch (Throwable t) {
            logger.warn("Không thể gửi notification kết thúc phiên #{}: {}", auctionId, t.getMessage());
        }

        // 5. Dọn RAM — DB đã FINISHED nên restart không load lại
        AuctionManager.getInstance().removeAuction(auctionId);
    }

    // ─── Completion timeout (FINISHED → CANCELED sau 12h chưa PAID) ──────────

    /**
     * Quét tất cả phiên FINISHED, nếu finished_at + COMPLETION_TIMEOUT_MINUTES <= now
     * → set CANCELED và gửi notification cho winner + seller.
     */
    private void scanCompletionTimeouts() {
        try {
            List<AuctionDAO.AuctionRow> pending = auctionDAO.getFinishedAwaitingPayment();
            if (pending.isEmpty()) return;

            LocalDateTime now = LocalDateTime.now();
            long timeoutMinutes = AppConfig.COMPLETION_TIMEOUT_MINUTES;

            for (AuctionDAO.AuctionRow row : pending) {
                if (row.finishedAt == null) continue;
                LocalDateTime finishedAt = parseSqliteTime(row.finishedAt);
                if (finishedAt == null) continue;
                long minutesElapsed = java.time.Duration.between(finishedAt, now).toMinutes();
                if (minutesElapsed < timeoutMinutes) continue;

                // → Hết hạn — chuyển CANCELED
                auctionDAO.updateStatus(row.id, "CANCELED");
                // Trả item về OPEN để seller có thể mở phiên mới (theo flow CANCELED).
                // Seller có thể chọn xóa sau khi xem chi tiết.
                itemDAO.updateStatus(row.itemId, "OPEN");

                String itemName = row.itemName != null ? row.itemName : "phiên #" + row.id;

                int sellerId = auctionDAO.getSellerIdForAuction(row.id);
                if (sellerId > 0) {
                    NotificationHub.getInstance().notifyAuctionCanceledToSeller(
                            sellerId, itemName, row.id,
                            "Người tham gia không hoàn thiện quy trình đấu giá");
                }
                if (row.winnerId > 0) {
                    NotificationHub.getInstance().notifyCompletionFailedToWinner(
                            row.winnerId, itemName, row.id);
                }
                logger.info("Auction #{} FINISHED → CANCELED do timeout {} phút",
                        row.id, timeoutMinutes);
            }
        } catch (Throwable t) {
            logger.error("Lỗi scanCompletionTimeouts: {}", t.getMessage(), t);
        }
    }

    /**
     * SQLite datetime('now','localtime') trả về "yyyy-MM-dd HH:mm:ss".
     * Parse thành LocalDateTime — chấp nhận cả 2 dạng (T hoặc space).
     */
    private static LocalDateTime parseSqliteTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                return null;
            }
        }
    }
}

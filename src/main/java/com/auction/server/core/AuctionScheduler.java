package com.auction.server.core;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.model.AuctionManager;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
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
        logger.info("AuctionScheduler đã khởi động (chu kỳ 1 giây).");
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

        // 1. Cập nhật DB
        auctionDAO.updateStatus(auctionId, "FINISHED");

        // 2. Cập nhật item status
        if (auction.getItem() != null) {
            int itemId = auction.getItem().getId();
            String itemStatus = (winnerId > 0) ? "SOLD" : "CLOSED";
            itemDAO.updateStatus(itemId, itemStatus);
        }

        logger.info("Phiên #{} FINISHED. Winner={} finalPrice={}",
                auctionId, winnerName != null ? winnerName : "—", finalPrice);

        // 3. Broadcast AUCTION_FINISHED
        BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.auctionFinished(auctionId,
                        winnerId,
                        winnerName != null ? winnerName : "Không có người thắng",
                        finalPrice)
        );

        // 4. Dọn RAM — DB đã FINISHED nên restart không load lại
        AuctionManager.getInstance().removeAuction(auctionId);
    }
}

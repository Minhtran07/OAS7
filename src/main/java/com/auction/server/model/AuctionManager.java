package com.auction.server.model;

import com.auction.server.core.BidEventBus;
import com.auction.server.core.NotificationHub;
import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import com.auction.shared.model.user.Bidder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AuctionManager — Singleton quản lý toàn bộ logic đấu giá realtime.
 *
 * Tính năng:
 * - Thread-safe placeBid() với ReentrantLock
 * - Bid ATOMIC: update RAM + ghi DB cùng trong 1 lock; nếu DB fail → rollback RAM,
 *   KHÔNG broadcast (tránh lost-update và inconsistency RAM vs DB).
 * - Auto-Bidding: đặt maxBid + increment, hệ thống tự counter-bid
 * - Anti-sniping: bid trong 30s cuối → gia hạn thêm 60s
 * - Observer: sau mỗi bid thành công broadcast event qua BidEventBus
 * - Snapshot thread-safe: trả về giá/endTime/winner nhất quán dưới lock.
 */
public class AuctionManager {
    private static final Logger logger = LoggerFactory.getLogger(AuctionManager.class);

    // ─── Singleton ────────────────────────────────────────────────────────────
    private static volatile AuctionManager instance;

    private AuctionManager() {
        activeAuctions  = new ConcurrentHashMap<>();
        autoBidQueues   = new ConcurrentHashMap<>();
        auctionLocks    = new ConcurrentHashMap<>();
    }

    public static AuctionManager getInstance() {
        if (instance == null) {
            synchronized (AuctionManager.class) {
                if (instance == null) {
                    instance = new AuctionManager();
                }
            }
        }
        return instance;
    }

    // ─── DAO injection (server-only; AuctionManager được dùng phía server) ───
    private AuctionDAO auctionDAO;
    private ItemDAO    itemDAO;

    /** Phải gọi 1 lần khi server khởi động, trước khi nhận client. */
    public void setDaos(AuctionDAO auctionDAO, ItemDAO itemDAO) {
        this.auctionDAO = auctionDAO;
        this.itemDAO    = itemDAO;
    }

    // ─── State ───────────────────────────────────────────────────────────────

    /** Các phiên đang hoạt động trong RAM */
    private final Map<Integer, Auction> activeAuctions;

    /**
     * Auto-bid registry: auctionId → PriorityQueue sắp xếp theo:
     * 1. maxBid giảm dần (người trả cao hơn ưu tiên hơn)
     * 2. Thời điểm đăng ký tăng dần (đăng ký sớm hơn ưu tiên khi bằng maxBid)
     */
    private final Map<Integer, PriorityQueue<AutoBidEntry>> autoBidQueues;

    /** Mỗi auction có 1 lock riêng — tránh các auction khác bị block */
    private final Map<Integer, ReentrantLock> auctionLocks;

    // Cấu hình anti-sniping
    private static final int ANTI_SNIPE_TRIGGER_SECONDS = 30;
    private static final int ANTI_SNIPE_EXTEND_SECONDS  = 60;

    // ─── Quản lý phiên ───────────────────────────────────────────────────────

    public void addAuction(int auctionId, Auction auction) {
        activeAuctions.put(auctionId, auction);
        auctionLocks.putIfAbsent(auctionId, new ReentrantLock());
        autoBidQueues.putIfAbsent(auctionId, new PriorityQueue<>(AutoBidEntry.COMPARATOR));
        logger.info("Đã thêm auction #{} vào AuctionManager", auctionId);
    }

    public Auction getAuction(int auctionId) {
        return activeAuctions.get(auctionId);
    }

    /** Map bất biến các phiên đang sống — dùng cho scheduler. */
    public Map<Integer, Auction> getActiveAuctionsView() {
        return Collections.unmodifiableMap(activeAuctions);
    }

    /**
     * Snapshot nhất quán (dưới lock) giá/endTime/winnerId của 1 phiên.
     * Dùng cho GET_AUCTION_STATE, tránh đọc lệch giữa các field.
     */
    public Snapshot snapshot(int auctionId) {
        ReentrantLock lock = auctionLocks.get(auctionId);
        Auction auction   = activeAuctions.get(auctionId);
        if (lock == null || auction == null) return null;

        lock.lock();
        try {
            Snapshot s = new Snapshot();
            s.currentPrice = auction.getCurrentPrice();
            s.endTime      = auction.getEndTime();
            s.status       = auction.getStatus();
            s.winnerId     = auction.getHighestBidderId();
            Bidder w = auction.getCurrentWinner();
            s.winnerName   = (w != null) ? w.getFullname() : null;
            return s;
        } finally {
            lock.unlock();
        }
    }

    /** Xóa phiên khỏi RAM khi đã FINISHED — scheduler gọi. */
    public void removeAuction(int auctionId) {
        activeAuctions.remove(auctionId);
        autoBidQueues.remove(auctionId);
        // Không remove lock để tránh NPE nếu có thread khác vừa lấy ra — GC sẽ dọn sau
        logger.info("Đã xóa auction #{} khỏi AuctionManager (FINISHED)", auctionId);
    }

    public ReentrantLock getLock(int auctionId) {
        return auctionLocks.get(auctionId);
    }

    // ─── Place Bid (ATOMIC: RAM + DB cùng trong lock) ────────────────────────

    /**
     * Đặt giá thủ công.
     * Thread-safe per-auction; ghi DB trong cùng lock với update RAM.
     * Nếu ghi DB thất bại → rollback giá RAM, KHÔNG broadcast, trả false.
     *
     * @return true nếu bid thành công (RAM + DB đều OK).
     */
    public boolean placeBid(int auctionId, Bidder bidder, BigDecimal bidAmount) {
        ReentrantLock lock = auctionLocks.get(auctionId);
        if (lock == null) {
            logger.warn("Phiên #{} không tồn tại", auctionId);
            return false;
        }

        lock.lock();
        try {
            Auction auction = activeAuctions.get(auctionId);
            if (auction == null) return false;

            auction.updateStatusBasedOnTime();

            if (auction.getStatus() != Role.RUNNING) {
                logger.warn("Từ chối bid phiên #{}: trạng thái {}", auctionId, auction.getStatus());
                return false;
            }

            // Lưu state cũ để rollback nếu DB fail
            BigDecimal prevPrice  = auction.getCurrentPrice();
            Bidder     prevWinner = auction.getCurrentWinner();
            int        prevWinId  = auction.getHighestBidderId();

            boolean accepted = auction.updateBid(bidder, bidAmount);
            if (!accepted) {
                logger.info("Bid bị từ chối: {} < giá hiện tại {}", bidAmount, auction.getCurrentPrice());
                return false;
            }

            // Ghi DB NGAY trong lock. Nếu fail → rollback RAM.
            if (auctionDAO != null) {
                boolean dbOk =
                        auctionDAO.recordBid(auctionId, bidder.getId(), bidAmount.doubleValue())
                     && auctionDAO.updateCurrentPrice(auctionId, bidAmount.doubleValue(), bidder.getId());

                if (!dbOk) {
                    // Rollback RAM — tránh trạng thái RAM ≠ DB
                    auction.setCurrentPrice(prevPrice);
                    auction.setCurrentWinner(prevWinner);
                    auction.setHighestBidderId(prevWinId);
                    logger.error("Rollback bid phiên #{} do DB fail", auctionId);
                    return false;
                }
            } else {
                logger.warn("AuctionManager chưa được set DAO — bid chỉ tồn tại trong RAM!");
            }

            logger.info("Bid thành công phiên #{}: {} đặt {}", auctionId, bidder.getUsername(), bidAmount);

            // 1. Anti-sniping — gia hạn nếu bid trong 30s cuối
            checkAntiSnipe(auctionId, auction);

            // 2. Observer — broadcast cho tất cả client đang xem
            BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.bidUpdate(
                    auctionId,
                    bidAmount.doubleValue(),
                    bidder.getId(),
                    bidder.getFullname()
                )
            );

            // 3. Auto-bidding — trigger counter-bids từ các auto-bid khác
            triggerAutoBids(auctionId, auction, bidder.getId());

            return true;

        } finally {
            lock.unlock();
        }
    }

    // ─── Auto-Bidding ─────────────────────────────────────────────────────────

    /**
     * Đăng ký auto-bid cho một Bidder.
     * Hệ thống sẽ tự động trả giá thay họ khi có bid mới từ đối thủ,
     * không vượt quá maxBid.
     */
    public boolean registerAutoBid(int auctionId, Bidder bidder,
                                   BigDecimal maxBid, BigDecimal increment) {
        ReentrantLock lock = auctionLocks.get(auctionId);
        if (lock == null) return false;

        lock.lock();
        try {
            Auction auction = activeAuctions.get(auctionId);
            if (auction == null || auction.getStatus() == Role.FINISHED) return false;

            PriorityQueue<AutoBidEntry> queue = autoBidQueues.get(auctionId);

            // Nếu bidder đã có auto-bid cũ → xóa đi để cập nhật mới
            queue.removeIf(e -> e.bidderId == bidder.getId());

            AutoBidEntry entry = new AutoBidEntry(
                bidder, maxBid, increment, LocalDateTime.now()
            );
            queue.offer(entry);

            logger.info("Đã đăng ký auto-bid phiên #{}: {} maxBid={} increment={}",
                    auctionId, bidder.getUsername(), maxBid, increment);

            // Thử trigger ngay — loại trừ winner hiện tại để không tự đẩy giá chính
            // mình khi vừa đăng ký xong mà đang dẫn đầu (Bug #4: trước đây truyền -1
            // khiến hệ thống có thể tự counter cho chính bidder vừa đăng ký).
            int currentWinnerId = auction.getHighestBidderId();
            triggerAutoBids(auctionId, auction, currentWinnerId);

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Số vòng counter tối đa trong 1 lần trigger — chống vòng lặp vô hạn nếu
     * có lỗi logic (ví dụ increment=0). Thực tế cuộc "war" giữa 2 auto-bid
     * sẽ kết thúc nhanh hơn nhiều (khi 1 bên hết maxBid).
     */
    private static final int AUTO_BID_MAX_ROUNDS = 100;

    /**
     * Sau mỗi bid thành công, duyệt queue auto-bid và tự counter-bid.
     * Gọi trong khi đang giữ lock.
     *
     * Bug #1 fix: chạy VÒNG LẶP đối đầu — sau mỗi nhịp counter của 1 bidder,
     * loại bidder đó ra khỏi vòng tiếp theo và tìm tiếp bidder khác có thể
     * counter. Cuộc war chỉ kết thúc khi không còn bidder nào (≠ winner hiện tại)
     * có thể đặt nổi `currentPrice + increment ≤ maxBid` — y chang cách proxy
     * bid của eBay vận hành.
     *
     * Trước đây hàm chỉ counter 1 lần rồi return → khi 2 client cùng đăng ký
     * auto-bid, người đăng ký sau (max cao hơn) chỉ đẩy giá lên 1 nấc rồi dừng,
     * người đăng ký trước không được phản công.
     *
     * @param excludeBidderId bidderId không được phép tự bid (winner hiện tại)
     */
    private void triggerAutoBids(int auctionId, Auction auction, int excludeBidderId) {
        PriorityQueue<AutoBidEntry> queue = autoBidQueues.get(auctionId);
        if (queue == null || queue.isEmpty()) return;

        int lastBidderId = excludeBidderId;

        // Trước khi vào vòng counter — kiểm tra các auto-bid đã hết khả năng counter
        // mà không phải đang là winner → thông báo "đã đạt max" (1 lần duy nhất).
        notifyAutoBidMaxReached(auctionId, auction, queue);

        for (int round = 0; round < AUTO_BID_MAX_ROUNDS; round++) {
            // Tìm auto-bid tốt nhất có thể counter (không phải người vừa thắng vòng trước)
            AutoBidEntry best = null;
            for (AutoBidEntry entry : queue) {
                if (entry.bidderId == lastBidderId) continue;
                BigDecimal nextBid = auction.getCurrentPrice().add(entry.increment);
                if (nextBid.compareTo(entry.maxBid) <= 0) {
                    best = entry;
                    break; // queue đã sắp xếp theo maxBid desc → best là người đầu tiên
                }
            }

            if (best == null) {
                // Không còn ai counter được → war kết thúc
                if (round > 0) {
                    logger.info("Auto-bid war phiên #{} kết thúc sau {} vòng, giá cuối: {}",
                            auctionId, round, auction.getCurrentPrice());
                }
                notifyAutoBidMaxReached(auctionId, auction, queue);
                return;
            }

            BigDecimal autoBidAmount = auction.getCurrentPrice().add(best.increment);

            // Lưu state cũ để rollback nếu DB fail
            BigDecimal prevPrice  = auction.getCurrentPrice();
            Bidder     prevWinner = auction.getCurrentWinner();
            int        prevWinId  = auction.getHighestBidderId();

            boolean ok = auction.updateBid(best.bidder, autoBidAmount);
            if (!ok) return;

            // Ghi DB cho auto-bid (cũng atomic như manual bid)
            if (auctionDAO != null) {
                boolean dbOk =
                        auctionDAO.recordBid(auctionId, best.bidderId, autoBidAmount.doubleValue())
                     && auctionDAO.updateCurrentPrice(auctionId, autoBidAmount.doubleValue(), best.bidderId);
                if (!dbOk) {
                    auction.setCurrentPrice(prevPrice);
                    auction.setCurrentWinner(prevWinner);
                    auction.setHighestBidderId(prevWinId);
                    logger.error("Rollback auto-bid phiên #{} do DB fail", auctionId);
                    return;
                }
            }

            logger.info("Auto-bid phiên #{} (vòng {}): {} tự động đặt {}",
                    auctionId, round + 1, best.bidder.getUsername(), autoBidAmount);

            checkAntiSnipe(auctionId, auction);

            BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.bidUpdate(
                    auctionId,
                    autoBidAmount.doubleValue(),
                    best.bidderId,
                    best.bidder.getFullname() + " (auto)"
                )
            );

            // Vòng tiếp theo: loại winner vừa rồi ra để tìm đối thủ phản công
            lastBidderId = best.bidderId;
        }

        logger.warn("Auto-bid phiên #{} đạt giới hạn {} vòng — dừng để chống vòng lặp",
                auctionId, AUTO_BID_MAX_ROUNDS);
    }

    // ─── Anti-Sniping ─────────────────────────────────────────────────────────

    /**
     * Nếu bid xảy ra trong ANTI_SNIPE_TRIGGER_SECONDS giây cuối
     * → gia hạn endTime thêm ANTI_SNIPE_EXTEND_SECONDS giây.
     * Gọi trong khi đang giữ lock.
     */
    private void checkAntiSnipe(int auctionId, Auction auction) {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime endTime = auction.getEndTime();
        if (endTime == null) return;

        long secondsLeft = java.time.temporal.ChronoUnit.SECONDS.between(now, endTime);

        if (secondsLeft >= 0 && secondsLeft <= ANTI_SNIPE_TRIGGER_SECONDS) {
            LocalDateTime newEnd = endTime.plusSeconds(ANTI_SNIPE_EXTEND_SECONDS);
            auction.setEndTime(newEnd);

            logger.info("Anti-sniping phiên #{}: gia hạn đến {}", auctionId, newEnd);

            // Broadcast thời gian mới cho client
            BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.auctionExtended(auctionId, newEnd.toString())
            );
        }
    }

    /**
     * Duyệt queue auto-bid, với mỗi entry KHÔNG phải winner hiện tại và
     * currentPrice + increment > maxBid → đánh dấu maxReached, gửi notification 1 lần.
     */
    private void notifyAutoBidMaxReached(int auctionId, Auction auction,
                                          PriorityQueue<AutoBidEntry> queue) {
        if (queue == null || queue.isEmpty()) return;
        int winnerId = auction.getHighestBidderId();
        String itemName = (auction.getItem() != null) ? auction.getItem().getName() : "phiên #" + auctionId;
        for (AutoBidEntry e : queue) {
            if (e.maxReachedNotified) continue;
            if (e.bidderId == winnerId) continue; // winner — đang dẫn đầu, chưa hết max
            BigDecimal nextBid = auction.getCurrentPrice().add(e.increment);
            if (nextBid.compareTo(e.maxBid) > 0) {
                e.maxReachedNotified = true;
                try {
                    NotificationHub.getInstance().notifyAutoBidMax(
                            e.bidderId, itemName, auctionId, e.maxBid.doubleValue());
                } catch (Throwable t) {
                    logger.warn("Không thể gửi notification auto-bid max cho bidder {}: {}",
                            e.bidderId, t.getMessage());
                }
            }
        }
    }

    // ─── Snapshot DTO ─────────────────────────────────────────────────────────

    public static class Snapshot {
        public BigDecimal currentPrice;
        public LocalDateTime endTime;
        public Role status;
        public int winnerId;
        public String winnerName;
    }

    // ─── AutoBid DTO ──────────────────────────────────────────────────────────

    private static class AutoBidEntry {
        final Bidder bidder;
        final int bidderId;
        final BigDecimal maxBid;
        final BigDecimal increment;
        final LocalDateTime registeredAt;
        /** Đã gửi notification "auto-bid đạt max" cho bidder này chưa (1 lần duy nhất). */
        boolean maxReachedNotified = false;

        AutoBidEntry(Bidder bidder, BigDecimal maxBid, BigDecimal increment,
                     LocalDateTime registeredAt) {
            this.bidder       = bidder;
            this.bidderId     = bidder.getId();
            this.maxBid       = maxBid;
            this.increment    = increment;
            this.registeredAt = registeredAt;
        }

        /**
         * Sắp xếp: maxBid giảm dần, nếu bằng nhau thì ai đăng ký trước ưu tiên hơn.
         */
        static final Comparator<AutoBidEntry> COMPARATOR = (a, b) -> {
            int cmp = b.maxBid.compareTo(a.maxBid); // desc
            if (cmp != 0) return cmp;
            return a.registeredAt.compareTo(b.registeredAt); // asc
        };
    }
}

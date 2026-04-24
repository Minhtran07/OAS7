package com.auction.server.model;

import com.auction.server.core.BidEventBus;
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
 * - Auto-Bidding: đặt maxBid + increment, hệ thống tự counter-bid
 * - Anti-sniping: bid trong 30s cuối → gia hạn thêm 60s
 * - Observer: sau mỗi bid thành công broadcast event qua BidEventBus
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
        auctionLocks.put(auctionId, new ReentrantLock());
        autoBidQueues.put(auctionId, new PriorityQueue<>(AutoBidEntry.COMPARATOR));
        logger.info("Đã thêm auction #{} vào AuctionManager", auctionId);
    }

    public Auction getAuction(int auctionId) {
        return activeAuctions.get(auctionId);
    }

    // ─── Place Bid ───────────────────────────────────────────────────────────

    /**
     * Đặt giá thủ công.
     * Thread-safe per-auction, tự động trigger auto-bid và anti-sniping.
     *
     * @return true nếu bid thành công
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

            boolean accepted = auction.updateBid(bidder, bidAmount);
            if (!accepted) {
                logger.info("Bid bị từ chối: {} < giá hiện tại {}", bidAmount, auction.getCurrentPrice());
                return false;
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

            // Thử trigger ngay nếu giá hiện tại thấp hơn maxBid của bidder này
            triggerAutoBids(auctionId, auction, -1);

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sau mỗi bid thành công, duyệt queue auto-bid và tự counter-bid.
     * Gọi trong khi đang giữ lock.
     *
     * @param excludeBidderId bidderId vừa thắng — không tự bid lại chính mình
     */
    private void triggerAutoBids(int auctionId, Auction auction, int excludeBidderId) {
        PriorityQueue<AutoBidEntry> queue = autoBidQueues.get(auctionId);
        if (queue == null || queue.isEmpty()) return;

        // Tìm auto-bid tốt nhất có thể counter (không phải người vừa thắng)
        AutoBidEntry best = null;
        for (AutoBidEntry entry : queue) {
            if (entry.bidderId == excludeBidderId) continue;
            BigDecimal nextBid = auction.getCurrentPrice().add(entry.increment);
            if (nextBid.compareTo(entry.maxBid) <= 0) {
                best = entry;
                break; // queue đã sắp xếp theo maxBid desc → best là người đầu tiên
            }
        }

        if (best == null) return;

        BigDecimal autoBidAmount = auction.getCurrentPrice().add(best.increment);
        boolean ok = auction.updateBid(best.bidder, autoBidAmount);

        if (ok) {
            logger.info("Auto-bid phiên #{}: {} tự động đặt {}", auctionId,
                    best.bidder.getUsername(), autoBidAmount);

            checkAntiSnipe(auctionId, auction);

            BidEventBus.getInstance().broadcast(auctionId,
                BidEventBus.BidEvent.bidUpdate(
                    auctionId,
                    autoBidAmount.doubleValue(),
                    best.bidderId,
                    best.bidder.getFullname() + " (auto)"
                )
            );
        }
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

    // ─── AutoBid DTO ──────────────────────────────────────────────────────────

    private static class AutoBidEntry {
        final Bidder bidder;
        final int bidderId;
        final BigDecimal maxBid;
        final BigDecimal increment;
        final LocalDateTime registeredAt;

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

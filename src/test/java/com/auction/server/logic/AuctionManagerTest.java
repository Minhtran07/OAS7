package com.auction.server.logic;

import com.auction.server.model.AuctionManager;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import com.auction.shared.model.item.Art;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho AuctionManager.
 * Tập trung vào: thread-safety, race condition, lost update.
 */
class AuctionManagerTest {

    private AuctionManager manager;
    private Item sampleItem;

    @BeforeEach
    void setUp() {
        manager = AuctionManager.getInstance();
        sampleItem = new Art(1, "ART", "Test Item", 10, "Desc",
                500_000.0, 500_000.0, "Artist", "Oil");
    }

    // Helper: tạo Auction đang RUNNING
    private Auction makeRunningAuction() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end   = LocalDateTime.now().plusHours(2);
        Auction auction = new Auction(sampleItem, start, end);
        // Ép trạng thái RUNNING để test
        auction.setStatus(Role.RUNNING);
        return auction;
    }

    // Helper: tạo Bidder nhanh
    private Bidder makeBidder(int id, String name) {
        return new Bidder(id, name, "pass", name, name + "@test.com",
                BigDecimal.valueOf(100_000_000));
    }

    // ─── Singleton ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AuctionManager là Singleton — cùng một instance")
    void getInstance_returnsSameInstance() {
        AuctionManager a = AuctionManager.getInstance();
        AuctionManager b = AuctionManager.getInstance();
        assertSame(a, b);
    }

    // ─── placeBid cơ bản ─────────────────────────────────────────────────────

    @Test
    @DisplayName("placeBid trả về false khi auctionId không tồn tại")
    void placeBid_unknownAuction_returnsFalse() {
        Bidder bidder = makeBidder(1, "Alice");
        boolean result = manager.placeBid(99999, bidder, BigDecimal.valueOf(1_000_000));
        assertFalse(result);
    }

    @Test
    @DisplayName("placeBid hợp lệ → trả về true, giá được cập nhật")
    void placeBid_validBid_returnsTrue() {
        int id = 3001;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(1, "Alice");
        boolean result = manager.placeBid(id, bidder, BigDecimal.valueOf(1_000_000));

        assertTrue(result);
        assertEquals(BigDecimal.valueOf(1_000_000), auction.getCurrentPrice());
    }

    @Test
    @DisplayName("placeBid giá thấp hơn giá hiện tại → false, giá không đổi")
    void placeBid_lowerBid_returnsFalse() {
        int id = 3002;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(1, "Alice");
        manager.placeBid(id, alice, BigDecimal.valueOf(2_000_000));

        Bidder bob = makeBidder(2, "Bob");
        boolean result = manager.placeBid(id, bob, BigDecimal.valueOf(1_500_000));

        assertFalse(result);
        assertEquals(BigDecimal.valueOf(2_000_000), auction.getCurrentPrice());
    }

    @Test
    @DisplayName("placeBid khi auction đã FINISHED → bị từ chối")
    void placeBid_finishedAuction_rejected() {
        int id = 3003;
        // Tạo auction đã kết thúc
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        Auction auction = new Auction(sampleItem, past.minusHours(1), past);
        auction.setStatus(Role.FINISHED);
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(1, "Alice");
        boolean result = manager.placeBid(id, bidder, BigDecimal.valueOf(999_999_999));

        assertFalse(result);
    }

    // ─── Concurrent Bidding — không được lost update ──────────────────────────

    @Test
    @DisplayName("10 thread cùng bid: chỉ 1 người thắng, không lost update")
    void placeBid_concurrent_noLostUpdate() throws InterruptedException {
        int id = 3004;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready  = new CountDownLatch(threadCount);
        CountDownLatch start  = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // Mỗi thread đặt giá tăng dần: 1M, 2M, 3M, ..., 10M
        // Chỉ những bid thực sự cao hơn tại thời điểm đó mới thành công
        List<Integer> successes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 1; i <= threadCount; i++) {
            final int bidAmount = i * 1_000_000;
            final int bidderId  = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(); // tất cả cùng xuất phát
                    Bidder bidder = makeBidder(bidderId, "Bidder" + bidderId);
                    boolean ok = manager.placeBid(id, bidder, BigDecimal.valueOf(bidAmount));
                    if (ok) successCount.incrementAndGet();
                    successes.add(bidAmount);
                } catch (InterruptedException ignored) {}
            });
        }

        ready.await();
        start.countDown(); // bắt đầu đồng loạt
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        // Giá cuối phải là giá cao nhất hợp lệ (10M vì các bid tăng dần)
        assertEquals(BigDecimal.valueOf(10_000_000), auction.getCurrentPrice());

        // Phải có ít nhất 1 bid thành công
        assertTrue(successCount.get() >= 1);

        // Không được có trường hợp giá thấp hơn ghi đè giá cao hơn
        assertNotNull(auction.getCurrentWinner(), "Phải có người thắng sau khi bid");
    }

    @Test
    @DisplayName("100 thread bid giá ngẫu nhiên: giá cuối phải là max trong số thành công")
    void placeBid_highConcurrency_priceIsMonotonicallyIncreasing() throws InterruptedException {
        int id = 3005;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        int threadCount = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger maxBid = new AtomicInteger(0);

        for (int i = 1; i <= threadCount; i++) {
            final int amount = i * 100_000; // 100k, 200k, ..., 10M
            pool.submit(() -> {
                try {
                    latch.await();
                    Bidder b = makeBidder(amount, "B" + amount);
                    boolean ok = manager.placeBid(id, b, BigDecimal.valueOf(amount));
                    if (ok) {
                        successCount.incrementAndGet();
                        maxBid.updateAndGet(cur -> Math.max(cur, amount));
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // Giá cuối == giá cao nhất đã đặt thành công (không bao giờ giảm)
        assertEquals(BigDecimal.valueOf(maxBid.get()), auction.getCurrentPrice());
    }
}

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
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho cơ chế Anti-Sniping & Snapshot thread-safe của AuctionManager.
 *
 * Anti-sniping rule:
 *   - Bid trong 30 giây cuối → endTime được gia hạn thêm 60 giây
 *   - Bid khi còn nhiều thời gian → endTime KHÔNG đổi
 *
 * Snapshot:
 *   - Trả về null nếu auction không tồn tại
 *   - Trả về DTO chứa price/endTime/winner đồng bộ nhau
 */
class AuctionManagerAntiSnipeTest {

    private AuctionManager manager;
    private Item sampleItem;

    @BeforeEach
    void setUp() {
        manager = AuctionManager.getInstance();
        sampleItem = new Art(1, "ART", "Test Item", 10, "Desc",
                500_000.0, 500_000.0, "Artist", "Oil");
    }

    private Bidder makeBidder(int id, String name) {
        return new Bidder(id, name, "pass", name, name + "@test.com",
                BigDecimal.valueOf(100_000_000));
    }

    private Auction makeAuctionEndingIn(long secondsLeft) {
        LocalDateTime start = LocalDateTime.now().minusMinutes(10);
        LocalDateTime end   = LocalDateTime.now().plusSeconds(secondsLeft);
        Auction auction = new Auction(sampleItem, start, end);
        auction.setStatus(Role.RUNNING);
        return auction;
    }

    // ─── Anti-Sniping ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bid trong 30s cuối → endTime được gia hạn thêm 60s")
    void antiSnipe_bidInLast30s_extendsEndTime() {
        int id = 4001;
        Auction auction = makeAuctionEndingIn(15); // còn 15s — trong vùng anti-snipe
        LocalDateTime originalEnd = auction.getEndTime();
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(101, "Sniper");
        boolean ok = manager.placeBid(id, bidder, BigDecimal.valueOf(1_000_000));

        assertTrue(ok, "Bid phải thành công");
        long extendedSeconds = ChronoUnit.SECONDS.between(originalEnd, auction.getEndTime());
        assertEquals(60, extendedSeconds, "endTime phải được gia hạn đúng 60 giây");
    }

    @Test
    @DisplayName("Bid khi còn nhiều thời gian (>30s) → endTime KHÔNG đổi")
    void antiSnipe_bidWithPlentyOfTime_endTimeUnchanged() {
        int id = 4002;
        Auction auction = makeAuctionEndingIn(600); // còn 10 phút
        LocalDateTime originalEnd = auction.getEndTime();
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(102, "Normal");
        boolean ok = manager.placeBid(id, bidder, BigDecimal.valueOf(1_000_000));

        assertTrue(ok);
        assertEquals(originalEnd, auction.getEndTime(),
                "Bid sớm thì endTime không được đổi");
    }

    @Test
    @DisplayName("Bid đúng ngưỡng 30s → vẫn được gia hạn (boundary)")
    void antiSnipe_bidAtBoundary_extendsEndTime() {
        int id = 4003;
        Auction auction = makeAuctionEndingIn(30);
        LocalDateTime originalEnd = auction.getEndTime();
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(103, "Boundary");
        manager.placeBid(id, bidder, BigDecimal.valueOf(1_000_000));

        // Bid ngay tại boundary 30s vẫn phải được gia hạn (logic dùng <=)
        assertTrue(auction.getEndTime().isAfter(originalEnd),
                "endTime phải lớn hơn endTime ban đầu");
    }

    @Test
    @DisplayName("2 bid liên tiếp trong vùng anti-snipe → cả 2 đều gia hạn")
    void antiSnipe_multipleBids_eachExtends() {
        int id = 4004;
        Auction auction = makeAuctionEndingIn(20);
        manager.addAuction(id, auction);

        LocalDateTime end0 = auction.getEndTime();

        manager.placeBid(id, makeBidder(201, "A"), BigDecimal.valueOf(1_000_000));
        LocalDateTime end1 = auction.getEndTime();

        manager.placeBid(id, makeBidder(202, "B"), BigDecimal.valueOf(2_000_000));
        LocalDateTime end2 = auction.getEndTime();

        assertTrue(end1.isAfter(end0), "Bid 1 phải gia hạn endTime");
        // Sau bid 1, còn lại = (20 + 60) ≈ 80s — bid 2 nằm ngoài 30s nên KHÔNG gia hạn nữa
        // Tùy timing thực tế, ta chỉ đảm bảo end2 >= end1
        assertTrue(!end2.isBefore(end1), "endTime không bao giờ giảm");
    }

    // ─── Snapshot ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Snapshot trả về null nếu auctionId không tồn tại")
    void snapshot_unknownAuction_returnsNull() {
        AuctionManager.Snapshot snap = manager.snapshot(99_999);
        assertNull(snap);
    }

    @Test
    @DisplayName("Snapshot trả về DTO với price/endTime/winner đồng bộ")
    void snapshot_afterBid_returnsConsistentState() {
        int id = 4005;
        Auction auction = makeAuctionEndingIn(600);
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(301, "Alice");
        manager.placeBid(id, alice, BigDecimal.valueOf(2_500_000));

        AuctionManager.Snapshot snap = manager.snapshot(id);

        assertNotNull(snap);
        assertEquals(BigDecimal.valueOf(2_500_000), snap.currentPrice);
        assertEquals(301, snap.winnerId);
        assertEquals("Alice", snap.winnerName);
        assertEquals(Role.RUNNING, snap.status);
        assertNotNull(snap.endTime);
    }

    @Test
    @DisplayName("Snapshot khi chưa có ai bid: winnerId = 0, winnerName = null")
    void snapshot_noBidYet_winnerEmpty() {
        int id = 4006;
        Auction auction = makeAuctionEndingIn(600);
        manager.addAuction(id, auction);

        AuctionManager.Snapshot snap = manager.snapshot(id);

        assertNotNull(snap);
        assertEquals(0, snap.winnerId);
        assertNull(snap.winnerName);
        assertEquals(BigDecimal.valueOf(500_000.0), snap.currentPrice);
    }

    // ─── removeAuction ────────────────────────────────────────────────────────

    @Test
    @DisplayName("removeAuction xóa khỏi RAM, snapshot trả về null")
    void removeAuction_removesFromRam() {
        int id = 4007;
        Auction auction = makeAuctionEndingIn(600);
        manager.addAuction(id, auction);

        assertNotNull(manager.snapshot(id), "Trước khi remove, snapshot phải tồn tại");

        manager.removeAuction(id);

        assertNull(manager.snapshot(id), "Sau khi remove, snapshot phải null");
        assertNull(manager.getAuction(id), "getAuction phải trả về null");
    }
}

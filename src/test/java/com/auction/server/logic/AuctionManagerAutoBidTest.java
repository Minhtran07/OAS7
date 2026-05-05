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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho cơ chế Auto-Bidding của AuctionManager.
 *
 * Auto-bid rule:
 *   - Bidder đăng ký maxBid + increment
 *   - Khi đối thủ bid, hệ thống tự counter-bid (currentPrice + increment)
 *     không vượt quá maxBid của bidder đó
 *   - Nếu nhiều người đăng ký auto-bid: ai có maxBid lớn hơn ưu tiên thắng
 *   - Cùng maxBid: ai đăng ký trước thắng
 */
class AuctionManagerAutoBidTest {

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

    private Auction makeRunningAuction() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end   = LocalDateTime.now().plusHours(2);
        Auction auction = new Auction(sampleItem, start, end);
        auction.setStatus(Role.RUNNING);
        return auction;
    }

    // ─── Đăng ký auto-bid ────────────────────────────────────────────────────

    @Test
    @DisplayName("registerAutoBid trên auction không tồn tại → false")
    void registerAutoBid_unknownAuction_returnsFalse() {
        Bidder bidder = makeBidder(1, "Alice");
        boolean ok = manager.registerAutoBid(99_999, bidder,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));
        assertFalse(ok);
    }

    @Test
    @DisplayName("registerAutoBid hợp lệ → true")
    void registerAutoBid_validAuction_returnsTrue() {
        int id = 5001;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(1, "Alice");
        boolean ok = manager.registerAutoBid(id, bidder,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));
        assertTrue(ok);
    }

    @Test
    @DisplayName("registerAutoBid trên auction FINISHED → false")
    void registerAutoBid_finishedAuction_returnsFalse() {
        int id = 5002;
        Auction auction = makeRunningAuction();
        auction.setStatus(Role.FINISHED);
        manager.addAuction(id, auction);

        Bidder bidder = makeBidder(1, "Alice");
        boolean ok = manager.registerAutoBid(id, bidder,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));
        assertFalse(ok);
    }

    // ─── Auto counter-bid ────────────────────────────────────────────────────

    @Test
    @DisplayName("Auto-bid counter khi có người bid: tự đặt = currentPrice + increment")
    void autoBid_countersOpponentBid() {
        int id = 5003;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        // Alice đăng ký auto-bid: max 10M, increment 100k
        Bidder alice = makeBidder(1, "Alice");
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(100_000));

        // Bob bid thủ công 1M
        Bidder bob = makeBidder(2, "Bob");
        manager.placeBid(id, bob, BigDecimal.valueOf(1_000_000));

        // Sau bid của Bob, hệ thống phải tự counter cho Alice = 1M + 100k = 1.1M
        assertEquals(BigDecimal.valueOf(1_100_000), auction.getCurrentPrice(),
                "Auto-bid của Alice phải counter lên 1.1M");
        assertEquals(alice.getId(), auction.getHighestBidderId(),
                "Người thắng phải là Alice (auto-bid)");
    }

    @Test
    @DisplayName("Auto-bid không vượt quá maxBid")
    void autoBid_doesNotExceedMaxBid() {
        int id = 5004;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        // Alice maxBid = 1.5M, increment = 100k
        Bidder alice = makeBidder(1, "Alice");
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(1_500_000), BigDecimal.valueOf(100_000));

        // Bob bid 2M — đã vượt qua maxBid của Alice
        Bidder bob = makeBidder(2, "Bob");
        manager.placeBid(id, bob, BigDecimal.valueOf(2_000_000));

        // Alice không thể auto-bid lên 2.1M (vượt maxBid 1.5M)
        // → Bob giữ ngôi
        assertEquals(BigDecimal.valueOf(2_000_000), auction.getCurrentPrice());
        assertEquals(bob.getId(), auction.getHighestBidderId(),
                "Bob phải giữ ngôi vì Alice không thể vượt 1.5M");
    }

    @Test
    @DisplayName("2 auto-bid: người maxBid cao hơn thắng (test cuộc war)")
    void autoBid_higherMaxWins() {
        int id = 5005;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(1, "Alice");
        Bidder bob   = makeBidder(2, "Bob");

        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(3_000_000), BigDecimal.valueOf(100_000));
        manager.registerAutoBid(id, bob,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));

        // Một bidder thứ 3 bid để kích hoạt counter
        Bidder charlie = makeBidder(3, "Charlie");
        manager.placeBid(id, charlie, BigDecimal.valueOf(1_000_000));

        // Bob (maxBid cao hơn) phải counter trước
        assertEquals(bob.getId(), auction.getHighestBidderId(),
                "Bob có maxBid cao hơn nên thắng");

        // Bug #1 fix verification: cuộc war phải chạy đến khi Alice (max=3M)
        // không thể counter nổi nữa. Bob đẩy giá lên đúng 1 increment trên
        // ngưỡng Alice không với tới: 3,000,000 + 100,000 = 3,100,000.
        // Dùng compareTo() == 0 để bỏ qua scale (BigDecimal.add() có thể
        // làm tăng scale: 3100000 vs 3100000.0).
        assertEquals(0, auction.getCurrentPrice().compareTo(BigDecimal.valueOf(3_100_000)),
                "Cuộc war phải kết thúc khi Bob đẩy giá vượt maxBid của Alice " +
                "đúng 1 increment, thực tế: " + auction.getCurrentPrice());
    }

    @Test
    @DisplayName("Bug #1: 2 auto-bid đăng ký TUẦN TỰ phải chạy war đầy đủ")
    void autoBid_sequentialRegister_runsFullWar() {
        // Đây là kịch bản thực tế: Client 1 đăng ký auto-bid trước, sau đó
        // Client 2 đăng ký. Trước khi sửa Bug #1, triggerAutoBids chỉ chạy 1
        // nhịp counter rồi return → Client 1 không được phản công.
        int id = 5008;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder client1 = makeBidder(101, "Client1");
        Bidder client2 = makeBidder(102, "Client2");

        // Client 1 đăng ký TRƯỚC với max thấp hơn
        manager.registerAutoBid(id, client1,
                BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(500_000));

        // Client 2 đăng ký SAU với max cao hơn — sẽ kích hoạt cuộc war
        manager.registerAutoBid(id, client2,
                BigDecimal.valueOf(18_000_000), BigDecimal.valueOf(500_000));

        // Sau khi war kết thúc:
        //  - Client 2 (max 18M) phải đang dẫn
        //  - Giá phải ở mức Client 1 không thể counter: 10M + 500k = 10.5M
        assertEquals(client2.getId(), auction.getHighestBidderId(),
                "Client 2 (max cao hơn) phải dẫn sau cuộc war");
        // compareTo để bỏ qua scale BigDecimal (10500000 vs 10500000.0)
        assertEquals(0, auction.getCurrentPrice().compareTo(BigDecimal.valueOf(10_500_000)),
                "Giá phải dừng ngay trên ngưỡng Client 1 hết maxBid, thực tế: "
                + auction.getCurrentPrice());
    }

    @Test
    @DisplayName("Bug #4: registerAutoBid khi đang dẫn → KHÔNG tự đẩy giá chính mình")
    void autoBid_registerWhileLeading_doesNotSelfCounter() {
        int id = 5009;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(1, "Alice");

        // Alice bid thủ công 1M → đang dẫn
        manager.placeBid(id, alice, BigDecimal.valueOf(1_000_000));
        assertEquals(BigDecimal.valueOf(1_000_000), auction.getCurrentPrice());

        // Alice đăng ký auto-bid cho phòng hờ (max 5M, increment 100k).
        // Trước khi fix: triggerAutoBids(-1) sẽ không loại trừ ai → Alice tự
        //   counter chính mình lên 1.1M (lãng phí tiền).
        // Sau khi fix: excludeBidderId = winner hiện tại (Alice) → không bid.
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));

        assertEquals(BigDecimal.valueOf(1_000_000), auction.getCurrentPrice(),
                "Đang dẫn rồi đăng ký auto-bid không được tự đẩy giá");
        assertEquals(alice.getId(), auction.getHighestBidderId());
    }

    @Test
    @DisplayName("Đăng ký auto-bid 2 lần cho cùng bidder → entry mới ghi đè entry cũ")
    void autoBid_reRegisterReplaces() {
        int id = 5006;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(1, "Alice");

        // Đăng ký lần 1: maxBid 1M
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(100_000));

        // Đăng ký lần 2: maxBid 5M
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(100_000));

        // Bob bid 2M — Alice (entry mới = 5M) phải counter được
        Bidder bob = makeBidder(2, "Bob");
        manager.placeBid(id, bob, BigDecimal.valueOf(2_000_000));

        // Alice counter lên 2.1M (entry mới có maxBid 5M, không bị giới hạn 1M cũ)
        assertEquals(BigDecimal.valueOf(2_100_000), auction.getCurrentPrice());
        assertEquals(alice.getId(), auction.getHighestBidderId());
    }

    @Test
    @DisplayName("Auto-bid không tự counter chính bidder vừa thắng")
    void autoBid_doesNotCounterSelf() {
        int id = 5007;
        Auction auction = makeRunningAuction();
        manager.addAuction(id, auction);

        Bidder alice = makeBidder(1, "Alice");
        // Alice đăng ký auto-bid
        manager.registerAutoBid(id, alice,
                BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(100_000));

        // Alice tự bid thủ công 1M
        manager.placeBid(id, alice, BigDecimal.valueOf(1_000_000));

        // Sau bid của Alice, hệ thống KHÔNG được auto-bid lại cho chính Alice
        assertEquals(BigDecimal.valueOf(1_000_000), auction.getCurrentPrice(),
                "Auto-bid không được tự counter mình");
    }
}

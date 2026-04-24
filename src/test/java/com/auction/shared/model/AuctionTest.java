package com.auction.shared.model;

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
 * Unit tests cho Auction model.
 * Kiểm tra logic chuyển trạng thái và đặt giá.
 */
class AuctionTest {

    private Item sampleItem;
    private Bidder bidder1;
    private Bidder bidder2;

    @BeforeEach
    void setUp() {
        sampleItem = new Art(1, "ART", "Mona Lisa", 10, "Tranh nổi tiếng",
                1_000_000.0, 1_000_000.0, "Da Vinci", "Sơn dầu");
        bidder1 = new Bidder(1, "alice", "pass", "Alice", "alice@email.com",
                BigDecimal.valueOf(10_000_000));
        bidder2 = new Bidder(2, "bob", "pass", "Bob", "bob@email.com",
                BigDecimal.valueOf(10_000_000));
    }

    // ─── Trạng thái ban đầu ───────────────────────────────────────────────────

    @Test
    @DisplayName("Auction mới tạo phải có trạng thái OPEN")
    void newAuction_shouldBeOpen() {
        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        LocalDateTime end   = start.plusHours(1);
        Auction auction = new Auction(sampleItem, start, end);

        assertEquals(Role.OPEN, auction.getStatus());
    }

    @Test
    @DisplayName("Giá khởi điểm phải bằng startingPrice của item")
    void newAuction_currentPriceShouldEqualStartingPrice() {
        LocalDateTime start = LocalDateTime.now().plusMinutes(5);
        Auction auction = new Auction(sampleItem, start, start.plusHours(1));

        assertEquals(BigDecimal.valueOf(1_000_000.0), auction.getCurrentPrice());
    }

    // ─── Chuyển trạng thái theo thời gian ────────────────────────────────────

    @Test
    @DisplayName("OPEN → RUNNING khi đã qua giờ bắt đầu")
    void updateStatus_openToRunning_whenStartTimePassed() {
        LocalDateTime start = LocalDateTime.now().minusSeconds(1); // đã qua
        LocalDateTime end   = LocalDateTime.now().plusHours(2);
        Auction auction = new Auction(sampleItem, start, end);

        auction.updateStatusBasedOnTime();

        assertEquals(Role.RUNNING, auction.getStatus());
    }

    @Test
    @DisplayName("RUNNING → FINISHED khi đã qua giờ kết thúc")
    void updateStatus_runningToFinished_whenEndTimePassed() {
        LocalDateTime start = LocalDateTime.now().minusHours(2); // bắt đầu 2h trước
        LocalDateTime end   = LocalDateTime.now().minusSeconds(1); // kết thúc 1s trước
        Auction auction = new Auction(sampleItem, start, end);

        // Lần 1: chuyển sang RUNNING
        auction.updateStatusBasedOnTime();
        // Lần 2: chuyển sang FINISHED
        auction.updateStatusBasedOnTime();

        assertEquals(Role.FINISHED, auction.getStatus());
    }

    @Test
    @DisplayName("Không chuyển trạng thái nếu chưa đến giờ bắt đầu")
    void updateStatus_staysOpen_whenStartTimeNotReached() {
        LocalDateTime start = LocalDateTime.now().plusHours(1); // chưa đến
        Auction auction = new Auction(sampleItem, start, start.plusHours(2));

        auction.updateStatusBasedOnTime();

        assertEquals(Role.OPEN, auction.getStatus());
    }

    // ─── updateBid ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bid hợp lệ: giá cao hơn giá hiện tại → trả về true, cập nhật giá")
    void updateBid_validBid_returnsTrue() {
        Auction auction = new Auction(sampleItem,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        boolean result = auction.updateBid(bidder1, BigDecimal.valueOf(2_000_000));

        assertTrue(result);
        assertEquals(BigDecimal.valueOf(2_000_000), auction.getCurrentPrice());
        assertEquals(bidder1, auction.getCurrentWinner());
    }

    @Test
    @DisplayName("Bid không hợp lệ: giá bằng giá hiện tại → từ chối")
    void updateBid_samePrice_returnsFalse() {
        Auction auction = new Auction(sampleItem,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        boolean result = auction.updateBid(bidder1, BigDecimal.valueOf(1_000_000.0));

        assertFalse(result);
    }

    @Test
    @DisplayName("Bid không hợp lệ: giá thấp hơn → từ chối, giá không đổi")
    void updateBid_lowerPrice_returnsFalse_priceUnchanged() {
        Auction auction = new Auction(sampleItem,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        // Đặt giá cao trước
        auction.updateBid(bidder1, BigDecimal.valueOf(3_000_000));

        // Bidder2 thử đặt thấp hơn
        boolean result = auction.updateBid(bidder2, BigDecimal.valueOf(2_000_000));

        assertFalse(result);
        assertEquals(BigDecimal.valueOf(3_000_000), auction.getCurrentPrice());
        assertEquals(bidder1, auction.getCurrentWinner()); // người thắng vẫn là bidder1
    }

    @Test
    @DisplayName("Nhiều lần bid liên tiếp: người cuối đặt cao nhất thắng")
    void updateBid_multipleBids_highestWins() {
        Auction auction = new Auction(sampleItem,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));

        auction.updateBid(bidder1, BigDecimal.valueOf(2_000_000));
        auction.updateBid(bidder2, BigDecimal.valueOf(3_000_000));
        auction.updateBid(bidder1, BigDecimal.valueOf(4_000_000));

        assertEquals(BigDecimal.valueOf(4_000_000), auction.getCurrentPrice());
        assertEquals(bidder1, auction.getCurrentWinner());
    }
}

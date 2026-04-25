package com.auction.server.core;

import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import com.auction.shared.model.item.Art;
import com.auction.shared.model.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test logic chuyển trạng thái mà AuctionScheduler dựa vào.
 * Scheduler gọi auction.updateStatusBasedOnTime() mỗi giây để
 * đẩy OPEN → RUNNING → FINISHED ngay cả khi không có bid.
 *
 * Đây là phần CỐT LÕI của fix #5/#6 (auction kẹt RUNNING khi không có bid).
 */
class AuctionStateTransitionTest {

    private Item makeItem() {
        return new Art(1, "ART", "Test", 10, "Desc",
                500_000.0, 500_000.0, "x", "y");
    }

    @Test
    @DisplayName("Scheduler scenario: now < startTime → trạng thái phải vẫn là OPEN")
    void beforeStart_stateRemainsOpen() {
        LocalDateTime start = LocalDateTime.now().plusMinutes(5);
        LocalDateTime end   = start.plusHours(1);
        Auction auction = new Auction(makeItem(), start, end);

        // Mô phỏng scheduler tick
        auction.updateStatusBasedOnTime();
        assertEquals(Role.OPEN, auction.getStatus());
    }

    @Test
    @DisplayName("Scheduler scenario: startTime <= now < endTime → OPEN tự chuyển RUNNING")
    void afterStart_beforeEnd_transitionsToRunning() {
        LocalDateTime start = LocalDateTime.now().minusSeconds(2);
        LocalDateTime end   = LocalDateTime.now().plusHours(1);
        Auction auction = new Auction(makeItem(), start, end);

        auction.updateStatusBasedOnTime();
        assertEquals(Role.RUNNING, auction.getStatus());
    }

    @Test
    @DisplayName("Scheduler scenario: now >= endTime → RUNNING tự chuyển FINISHED")
    void afterEnd_transitionsToFinished() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end   = LocalDateTime.now().minusSeconds(1);
        Auction auction = new Auction(makeItem(), start, end);
        auction.setStatus(Role.RUNNING); // ép RUNNING

        auction.updateStatusBasedOnTime();
        assertEquals(Role.FINISHED, auction.getStatus());
    }

    @Test
    @DisplayName("Scheduler scenario: chuyển trạng thái idempotent — gọi nhiều lần không gây vấn đề")
    void multipleScheduleTicks_idempotent() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end   = LocalDateTime.now().minusSeconds(1);
        Auction auction = new Auction(makeItem(), start, end);

        // Gọi 5 lần liên tiếp như scheduler chạy mỗi giây
        for (int i = 0; i < 5; i++) auction.updateStatusBasedOnTime();

        assertEquals(Role.FINISHED, auction.getStatus(),
                "Sau nhiều tick, trạng thái phải dừng ở FINISHED");
    }

    @Test
    @DisplayName("Auction không có bid mà hết giờ → vẫn được scheduler đóng (winnerId=0)")
    void auctionEndsWithoutBid_winnerIsZero() {
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        LocalDateTime end   = LocalDateTime.now().minusSeconds(1);
        Auction auction = new Auction(makeItem(), start, end);
        auction.setStatus(Role.RUNNING);

        auction.updateStatusBasedOnTime();

        assertEquals(Role.FINISHED, auction.getStatus());
        assertEquals(0, auction.getHighestBidderId(),
                "Không ai bid nên winnerId vẫn là 0");
        assertNull(auction.getCurrentWinner(),
                "Không ai bid nên currentWinner = null");
    }

    @Test
    @DisplayName("setEndTime sau anti-snipe: scheduler kiểm tra với endTime mới")
    void afterAntiSnipe_schedulerUsesNewEndTime() {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        LocalDateTime end   = LocalDateTime.now().minusSeconds(5); // đã hết giờ
        Auction auction = new Auction(makeItem(), start, end);
        auction.setStatus(Role.RUNNING);

        // Anti-snipe gia hạn endTime — auction tiếp tục
        auction.setEndTime(LocalDateTime.now().plusMinutes(1));

        auction.updateStatusBasedOnTime();
        assertEquals(Role.RUNNING, auction.getStatus(),
                "Sau gia hạn, scheduler phải thấy auction còn đang chạy");
    }
}

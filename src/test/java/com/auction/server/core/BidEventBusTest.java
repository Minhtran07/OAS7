package com.auction.server.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho BidEventBus (Observer Pattern).
 *
 * Mỗi PrintWriter đại diện cho 1 client subscribe.
 * Test broadcast push tới tất cả subscriber với prefix "PUSH:".
 */
class BidEventBusTest {

    private final Gson gson = new Gson();

    /** Helper: tạo PrintWriter ghi vào StringWriter để verify nội dung. */
    private static PrintWriter newWriter(StringWriter buffer) {
        return new PrintWriter(buffer, true);
    }

    // ─── Subscribe / Broadcast ───────────────────────────────────────────────

    @Test
    @DisplayName("Singleton — 2 lần getInstance trả về cùng object")
    void getInstance_isSingleton() {
        assertSame(BidEventBus.getInstance(), BidEventBus.getInstance());
    }

    @Test
    @DisplayName("Subscribe + broadcast: client nhận được message với prefix PUSH:")
    void broadcast_subscribedClient_receivesPush() {
        BidEventBus bus = BidEventBus.getInstance();
        int auctionId = 7001;

        StringWriter sw = new StringWriter();
        PrintWriter out = newWriter(sw);
        bus.subscribe(auctionId, out);

        BidEventBus.BidEvent event = BidEventBus.BidEvent.bidUpdate(
                auctionId, 1_500_000, 42, "Alice");
        bus.broadcast(auctionId, event);

        String written = sw.toString().trim();
        assertTrue(written.startsWith("PUSH:"), "Message phải bắt đầu bằng 'PUSH:'");

        // Parse phần JSON sau "PUSH:" để verify nội dung
        String json = written.substring("PUSH:".length());
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        assertEquals("BID_UPDATE", obj.get("type").getAsString());
        assertEquals(auctionId, obj.get("auctionId").getAsInt());
        assertEquals(1_500_000.0, obj.get("newPrice").getAsDouble());
        assertEquals(42, obj.get("winnerId").getAsInt());
        assertEquals("Alice", obj.get("winnerName").getAsString());

        bus.unsubscribeAll(out);
    }

    @Test
    @DisplayName("Broadcast tới auction không có subscriber → không lỗi")
    void broadcast_noSubscribers_noException() {
        BidEventBus bus = BidEventBus.getInstance();

        BidEventBus.BidEvent event = BidEventBus.BidEvent.bidUpdate(
                99_999, 1_000_000, 1, "Nobody");

        // Không nên ném exception
        assertDoesNotThrow(() -> bus.broadcast(99_999, event));
    }

    @Test
    @DisplayName("Broadcast tới đúng auctionId — client subscribe auction khác KHÔNG nhận")
    void broadcast_isolatedByAuctionId() {
        BidEventBus bus = BidEventBus.getInstance();

        StringWriter swA = new StringWriter();
        StringWriter swB = new StringWriter();
        PrintWriter outA = newWriter(swA);
        PrintWriter outB = newWriter(swB);

        bus.subscribe(7002, outA); // client A xem auction 7002
        bus.subscribe(7003, outB); // client B xem auction 7003

        // Broadcast cho auction 7002 — chỉ A nhận
        bus.broadcast(7002, BidEventBus.BidEvent.bidUpdate(7002, 1_000_000, 1, "X"));

        assertTrue(swA.toString().contains("PUSH:"), "Client A phải nhận push");
        assertEquals("", swB.toString(), "Client B không được nhận push của auction khác");

        bus.unsubscribeAll(outA);
        bus.unsubscribeAll(outB);
    }

    // ─── Unsubscribe ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unsubscribe rồi broadcast → client không nhận được nữa")
    void unsubscribe_clientNoLongerReceivesPush() {
        BidEventBus bus = BidEventBus.getInstance();
        int auctionId = 7004;

        StringWriter sw = new StringWriter();
        PrintWriter out = newWriter(sw);

        bus.subscribe(auctionId, out);
        bus.unsubscribe(auctionId, out);

        bus.broadcast(auctionId, BidEventBus.BidEvent.bidUpdate(auctionId, 1, 1, "x"));

        assertEquals("", sw.toString(), "Sau unsubscribe, client không được nhận push");
    }

    @Test
    @DisplayName("unsubscribeAll: hủy mọi subscription của client")
    void unsubscribeAll_removesFromAllAuctions() {
        BidEventBus bus = BidEventBus.getInstance();

        StringWriter sw = new StringWriter();
        PrintWriter out = newWriter(sw);

        bus.subscribe(7005, out);
        bus.subscribe(7006, out);
        bus.subscribe(7007, out);

        bus.unsubscribeAll(out);

        bus.broadcast(7005, BidEventBus.BidEvent.bidUpdate(7005, 1, 1, "a"));
        bus.broadcast(7006, BidEventBus.BidEvent.bidUpdate(7006, 1, 1, "b"));
        bus.broadcast(7007, BidEventBus.BidEvent.bidUpdate(7007, 1, 1, "c"));

        assertEquals("", sw.toString(),
                "Sau unsubscribeAll, client không được nhận bất kỳ push nào");
    }

    // ─── Concurrent broadcast — không interleave ─────────────────────────────

    @Test
    @DisplayName("Concurrent broadcast: mỗi message nguyên vẹn (không bị interleave)")
    void broadcast_concurrent_noInterleave() throws InterruptedException {
        BidEventBus bus = BidEventBus.getInstance();
        int auctionId = 7008;

        StringWriter sw = new StringWriter();
        PrintWriter out = newWriter(sw);
        bus.subscribe(auctionId, out);

        int threads = 20;
        int eventsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        bus.broadcast(auctionId,
                                BidEventBus.BidEvent.bidUpdate(auctionId,
                                        tid * 1000 + i, tid, "T" + tid));
                    }
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // Mỗi dòng phải là 1 PUSH:{json} hợp lệ — không có dòng nào bị xen lẫn bytes.
        String[] lines = sw.toString().split("\\R");
        int validCount = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            assertTrue(line.startsWith("PUSH:"),
                    "Mỗi dòng phải bắt đầu bằng PUSH: — không bị interleave: '" + line + "'");
            String json = line.substring("PUSH:".length());
            // JSON parse phải không lỗi
            assertDoesNotThrow(() -> gson.fromJson(json, JsonObject.class),
                    "JSON phải hợp lệ — bytes không bị xen lẫn: '" + json + "'");
            validCount++;
        }
        assertEquals(threads * eventsPerThread, validCount,
                "Phải nhận đúng " + (threads * eventsPerThread) + " event");

        bus.unsubscribeAll(out);
    }

    // ─── BidEvent factory ────────────────────────────────────────────────────

    @Test
    @DisplayName("BidEvent.bidUpdate tạo event với type=BID_UPDATE")
    void bidEvent_bidUpdate_correctFields() {
        BidEventBus.BidEvent e = BidEventBus.BidEvent.bidUpdate(1, 100.0, 7, "Alice");
        assertEquals("BID_UPDATE", e.type);
        assertEquals(1, e.auctionId);
        assertEquals(100.0, e.newPrice);
        assertEquals(7, e.winnerId);
        assertEquals("Alice", e.winnerName);
    }

    @Test
    @DisplayName("BidEvent.auctionExtended tạo event với type=AUCTION_EXTENDED + newEndTime")
    void bidEvent_auctionExtended_correctFields() {
        BidEventBus.BidEvent e = BidEventBus.BidEvent.auctionExtended(2, "2025-01-01T12:00");
        assertEquals("AUCTION_EXTENDED", e.type);
        assertEquals(2, e.auctionId);
        assertEquals("2025-01-01T12:00", e.newEndTime);
    }

    @Test
    @DisplayName("BidEvent.auctionFinished tạo event với type=AUCTION_FINISHED + winner + finalPrice")
    void bidEvent_auctionFinished_correctFields() {
        BidEventBus.BidEvent e = BidEventBus.BidEvent.auctionFinished(3, 9, "Bob", 5_000_000);
        assertEquals("AUCTION_FINISHED", e.type);
        assertEquals(3, e.auctionId);
        assertEquals(9, e.winnerId);
        assertEquals("Bob", e.winnerName);
        assertEquals(5_000_000, e.newPrice);
    }
}

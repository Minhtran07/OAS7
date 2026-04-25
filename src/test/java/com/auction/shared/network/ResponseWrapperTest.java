package com.auction.shared.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test format JSON wrapper mà server gửi xuống client cho việc đồng bộ đồng hồ
 * (Issue #25). Server thêm field "serverNow" vào response của:
 *   - GET_AUCTION_STATE: { auctionId, currentPrice, endTime, ..., serverNow, history }
 *   - GET_AUCTIONS: { auctions: [...], serverNow }
 *
 * Client parse "serverNow" + so với LocalDateTime.now() để tính clockOffset
 * và dùng cho countdown UI.
 */
class ResponseWrapperTest {

    private final Gson gson = new Gson();

    @Test
    @DisplayName("Response wrapper GET_AUCTIONS chứa serverNow + auctions array")
    void getAuctionsWrapper_containsServerNow() {
        // Mô phỏng wrapper server tạo
        JsonObject wrapper = new JsonObject();
        wrapper.add("auctions", gson.toJsonTree(new int[]{1, 2, 3}));
        wrapper.addProperty("serverNow", LocalDateTime.now().toString());

        // Roundtrip JSON
        String json = wrapper.toString();
        JsonObject parsed = gson.fromJson(json, JsonObject.class);

        assertTrue(parsed.has("auctions"));
        assertTrue(parsed.has("serverNow"));

        // Parse được thành LocalDateTime
        LocalDateTime serverNow = LocalDateTime.parse(parsed.get("serverNow").getAsString());
        long secondsDiff = Math.abs(ChronoUnit.SECONDS.between(serverNow, LocalDateTime.now()));
        assertTrue(secondsDiff < 5, "serverNow phải gần với now (cùng máy test)");
    }

    @Test
    @DisplayName("Response wrapper GET_AUCTION_STATE chứa serverNow")
    void getAuctionStateWrapper_containsServerNow() {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("auctionId", 100);
        wrapper.addProperty("currentPrice", 1_500_000.0);
        wrapper.addProperty("endTime", LocalDateTime.now().plusHours(1).toString());
        wrapper.addProperty("winnerId", 5);
        wrapper.addProperty("winnerName", "Alice");
        wrapper.addProperty("serverNow", LocalDateTime.now().toString());

        String json = wrapper.toString();
        JsonObject parsed = gson.fromJson(json, JsonObject.class);

        assertEquals(100, parsed.get("auctionId").getAsInt());
        assertEquals(1_500_000.0, parsed.get("currentPrice").getAsDouble());
        assertEquals(5, parsed.get("winnerId").getAsInt());
        assertTrue(parsed.has("serverNow"), "Phải có field serverNow để client tính offset");
    }

    @Test
    @DisplayName("Client tính clockOffset đúng từ serverNow")
    void clockOffset_calculation() {
        // Giả lập: server đi nhanh hơn client 5 giây
        LocalDateTime clientNow = LocalDateTime.now();
        LocalDateTime serverNow = clientNow.plusSeconds(5);

        long offsetSeconds = ChronoUnit.SECONDS.between(clientNow, serverNow);
        assertEquals(5, offsetSeconds);

        // Khi UI cần "giờ server hiện tại" = clientNow + offset
        LocalDateTime serverNowComputed = clientNow.plusSeconds(offsetSeconds);
        assertEquals(serverNow, serverNowComputed);
    }

    @Test
    @DisplayName("Response object: SUCCESS với payload hợp lệ → parse được")
    void response_success_parses() {
        Response resp = new Response("SUCCESS", "OK", "{\"x\":1}");
        String json = gson.toJson(resp);
        Response back = gson.fromJson(json, Response.class);
        assertEquals("SUCCESS", back.getStatus());
        assertEquals("OK", back.getMessage());
        assertNotNull(back.getPayload());
        assertTrue(back.getPayload().contains("\"x\":1"));
    }

    @Test
    @DisplayName("Request có action + payload — parse roundtrip không mất data")
    void request_roundtrip() {
        Request req = new Request("PLACE_BID", "{\"auctionId\":1,\"amount\":100}");
        String json = gson.toJson(req);
        Request back = gson.fromJson(json, Request.class);
        assertEquals("PLACE_BID", back.getAction());
        assertTrue(back.getPayload().contains("auctionId"));
    }
}

package com.auction.server.core;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observer Pattern — server-side event bus.
 *
 * Mỗi client vào màn hình bidding sẽ gửi SUBSCRIBE_AUCTION → ClientHandler
 * đăng ký PrintWriter của client đó vào đây.
 *
 * Khi có bid mới thành công, AuctionManager gọi broadcast() → tất cả client
 * đang xem phiên đó nhận được event ngay lập tức (không cần polling).
 */
public class BidEventBus {
    private static final Logger logger = LoggerFactory.getLogger(BidEventBus.class);
    private static volatile BidEventBus instance;

    // auctionId → tập hợp các PrintWriter của client đang subscribe
    private final Map<Integer, Set<PrintWriter>> subscribers = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private BidEventBus() {}

    public static BidEventBus getInstance() {
        if (instance == null) {
            synchronized (BidEventBus.class) {
                if (instance == null) {
                    instance = new BidEventBus();
                }
            }
        }
        return instance;
    }

    // ─── Subscribe / Unsubscribe ─────────────────────────────────────────────

    public void subscribe(int auctionId, PrintWriter clientOut) {
        subscribers
            .computeIfAbsent(auctionId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(clientOut);
        logger.info("Client subscribe auction #{}, tổng subscribers: {}",
                auctionId, subscribers.get(auctionId).size());
    }

    public void unsubscribe(int auctionId, PrintWriter clientOut) {
        Set<PrintWriter> set = subscribers.get(auctionId);
        if (set != null) {
            set.remove(clientOut);
        }
    }

    /** Hủy toàn bộ subscription của một client (khi client disconnect). */
    public void unsubscribeAll(PrintWriter clientOut) {
        subscribers.values().forEach(set -> set.remove(clientOut));
    }

    // ─── Broadcast ───────────────────────────────────────────────────────────

    /**
     * Push BidEvent đến tất cả client đang xem phiên auctionId.
     * Tự động remove các PrintWriter bị broken (client đã disconnect).
     */
    public void broadcast(int auctionId, BidEvent event) {
        Set<PrintWriter> set = subscribers.get(auctionId);
        if (set == null || set.isEmpty()) return;

        String json = gson.toJson(event);
        // Dùng "PUSH:" prefix để client phân biệt đây là push (không phải response)
        String line = "PUSH:" + json;

        Set<PrintWriter> broken = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (PrintWriter out : set) {
            out.println(line);
            if (out.checkError()) {
                broken.add(out); // client đã mất kết nối
            }
        }
        set.removeAll(broken);

        logger.info("Broadcast bid event auction #{} đến {} clients (xóa {} broken)",
                auctionId, set.size(), broken.size());
    }

    // ─── Event DTO ───────────────────────────────────────────────────────────

    /** Payload được push đến client khi có bid mới hoặc auction gia hạn. */
    public static class BidEvent {
        public String type;        // "BID_UPDATE" | "AUCTION_EXTENDED" | "AUCTION_FINISHED"
        public int auctionId;
        public double newPrice;
        public int winnerId;
        public String winnerName;
        public String newEndTime;  // dùng khi anti-sniping gia hạn

        public static BidEvent bidUpdate(int auctionId, double price, int winnerId, String winnerName) {
            BidEvent e = new BidEvent();
            e.type       = "BID_UPDATE";
            e.auctionId  = auctionId;
            e.newPrice   = price;
            e.winnerId   = winnerId;
            e.winnerName = winnerName;
            return e;
        }

        public static BidEvent auctionExtended(int auctionId, String newEndTime) {
            BidEvent e = new BidEvent();
            e.type       = "AUCTION_EXTENDED";
            e.auctionId  = auctionId;
            e.newEndTime = newEndTime;
            return e;
        }

        public static BidEvent auctionFinished(int auctionId, int winnerId, String winnerName, double finalPrice) {
            BidEvent e = new BidEvent();
            e.type       = "AUCTION_FINISHED";
            e.auctionId  = auctionId;
            e.winnerId   = winnerId;
            e.winnerName = winnerName;
            e.newPrice   = finalPrice;
            return e;
        }
    }
}

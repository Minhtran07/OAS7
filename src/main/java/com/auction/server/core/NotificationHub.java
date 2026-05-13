package com.auction.server.core;

import com.auction.server.dao.NotificationDAO;
import com.auction.server.dao.UserDAO;
import com.auction.shared.model.notification.Notification;
import com.auction.shared.model.notification.NotificationType;
import com.auction.shared.model.user.User;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý các kết nối PrintWriter của từng userId (sau khi LOGIN thành công)
 * để có thể push thông báo realtime. Tương tự BidEventBus nhưng key theo userId.
 *
 * Cách dùng:
 *  - ClientHandler sau khi handleLogin thành công gọi register(userId, out).
 *  - Khi disconnect, ClientHandler gọi unregister(out).
 *  - Bất kỳ chỗ nào cần gửi notification → gọi notifyUser(userId, type, title, message, ...)
 *    → DAO insert vào DB + push realtime đến mọi PrintWriter của user đó.
 */
public class NotificationHub {

    private static final Logger logger = LoggerFactory.getLogger(NotificationHub.class);
    private static volatile NotificationHub instance;

    private final Map<Integer, Set<PrintWriter>> connections = new ConcurrentHashMap<>();
    private final NotificationDAO dao = new NotificationDAO();
    private final UserDAO userDAO = new UserDAO();
    private final Gson gson = new Gson();

    private NotificationHub() {}

    public static NotificationHub getInstance() {
        if (instance == null) {
            synchronized (NotificationHub.class) {
                if (instance == null) instance = new NotificationHub();
            }
        }
        return instance;
    }

    // ─── Registry ────────────────────────────────────────────────────────────

    public void register(int userId, PrintWriter out) {
        connections.computeIfAbsent(userId,
                k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(out);
        logger.info("NotificationHub: user #{} đã register (tổng socket: {})",
                userId, connections.get(userId).size());
    }

    public void unregister(PrintWriter out) {
        connections.values().forEach(set -> set.remove(out));
    }

    // ─── Push core ───────────────────────────────────────────────────────────

    /**
     * Tạo notification mới (lưu DB) + push realtime đến mọi kết nối của user.
     * Trả về Notification đã có id/createdAt.
     */
    public Notification notifyUser(int userId, NotificationType type, String title, String message,
                                   Integer auctionId, Integer itemId) {
        Notification n = dao.create(userId, type, title, message, auctionId, itemId);
        push(userId, n);
        return n;
    }

    private void push(int userId, Notification n) {
        Set<PrintWriter> set = connections.get(userId);
        if (set == null || set.isEmpty()) return;

        // Format đặc biệt: "PUSH:NOTIFICATION:" để client dễ phân biệt với "PUSH:" của BidEventBus
        JsonObject payload = new JsonObject();
        payload.addProperty("id",       n.id);
        payload.addProperty("type",     n.type);
        payload.addProperty("title",    n.title);
        if (n.message   != null) payload.addProperty("message",   n.message);
        if (n.createdAt != null) payload.addProperty("createdAt", n.createdAt);
        if (n.relatedAuctionId != null) payload.addProperty("relatedAuctionId", n.relatedAuctionId);
        if (n.relatedItemId    != null) payload.addProperty("relatedItemId",    n.relatedItemId);
        payload.addProperty("isRead", n.isRead);
        payload.addProperty("unreadCount", dao.unreadCount(userId));

        String line = "PUSH:NOTIFICATION:" + gson.toJson(payload);

        Set<PrintWriter> broken = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (PrintWriter out : set) {
            synchronized (out) {
                out.println(line);
                if (out.checkError()) broken.add(out);
            }
        }
        set.removeAll(broken);
    }

    /** Push một notification có sẵn trong DB (không insert lại). */
    public void pushExisting(int userId, Notification n) {
        push(userId, n);
    }

    // ─── Convenience helpers ─────────────────────────────────────────────────

    public void notifyLoginSuccess(User user) {
        notifyUser(user.getId(), NotificationType.LOGIN_SUCCESS,
                "Đăng nhập thành công",
                "Chào mừng " + user.getFullname() + " đã quay lại!",
                null, null);
    }

    public void notifyItemListed(int sellerId, String itemName, int auctionId, int itemId) {
        notifyUser(sellerId, NotificationType.ITEM_LISTED,
                "Sản phẩm đã được đưa lên đấu giá",
                "Sản phẩm \"" + itemName + "\" đã được đăng lên phiên đấu giá #"
                        + auctionId + " thành công.",
                auctionId, itemId);
    }

    /**
     * (Bidder) Đặt giá thành công cho 1 phiên.
     */
    public void notifyBidPlaced(int bidderId, String itemName, int auctionId, double amount) {
        notifyUser(bidderId, NotificationType.BID_PLACED,
                "Đã đặt giá thành công",
                "Bạn đã đấu giá thành công sản phẩm \"" + itemName
                        + "\" với giá " + formatVnd(amount) + ".",
                auctionId, null);
    }

    public void notifyOutbid(int previousBidderId, String itemName, int auctionId,
                             String newBidderName, double newAmount) {
        String msg = "Mức đấu giá của bạn cho \"" + itemName + "\" đã bị vượt qua: "
                + newBidderName + " vừa đặt " + formatVnd(newAmount)
                + ". Hãy vào lại phiên để đặt giá tiếp nếu bạn vẫn quan tâm!";
        notifyUser(previousBidderId, NotificationType.BID_OUTBID,
                "Bạn đã bị vượt giá", msg, auctionId, null);
    }

    public void notifyAutoBidMax(int bidderId, String itemName, int auctionId, double maxAmount) {
        notifyUser(bidderId, NotificationType.AUTO_BID_MAX_REACHED,
                "Auto-Bid đã đạt giới hạn",
                "Mức đấu giá tự động cho \"" + itemName + "\" đã đạt mức tối đa " + formatVnd(maxAmount) + ".",
                auctionId, null);
    }

    public void notifyAuctionFinishedForBidder(int bidderId, String itemName, int auctionId) {
        notifyUser(bidderId, NotificationType.AUCTION_FINISHED,
                "Phiên đấu giá đã kết thúc",
                "Phiên đấu giá \"" + itemName + "\" đã kết thúc.",
                auctionId, null);
    }

    /**
     * Thông báo kết quả cho 1 bidder THUA — biết được người thắng cuộc.
     * Gửi đồng thời cho tất cả các bidder không phải winner trong phiên.
     */
    public void notifyAuctionLost(int bidderId, String itemName, int auctionId,
                                  String winnerName, double finalPrice) {
        String winnerLabel = (winnerName == null || winnerName.isBlank())
                ? "(không có người thắng)" : winnerName;
        notifyUser(bidderId, NotificationType.AUCTION_LOST,
                "Phiên đấu giá đã kết thúc",
                "Phiên đấu giá \"" + itemName + "\" (phiên #" + auctionId + ") đã kết thúc. "
                        + "Người thắng cuộc là " + winnerLabel
                        + " với giá " + formatVnd(finalPrice) + ".",
                auctionId, null);
    }

    /**
     * (Seller) Thông báo kết quả phiên đấu giá: ai là người thắng cuộc.
     * Nếu không có ai bid thì winnerName = null.
     */
    public void notifyAuctionResultForSeller(int sellerId, String itemName, int auctionId,
                                             String winnerName, double finalPrice) {
        String title, msg;
        if (winnerName == null || winnerName.isBlank()) {
            title = "Phiên đấu giá kết thúc — không có người thắng";
            msg = "Phiên đấu giá #" + auctionId + " với sản phẩm \"" + itemName
                    + "\" đã kết thúc nhưng không có người đặt giá.";
        } else {
            title = "Phiên đấu giá đã có người thắng";
            msg = "Người chiến thắng phiên đấu giá #" + auctionId
                    + " với sản phẩm \"" + itemName + "\" là " + winnerName
                    + " (giá thắng: " + formatVnd(finalPrice) + ").";
        }
        notifyUser(sellerId, NotificationType.AUCTION_RESULT_SELLER,
                title, msg, auctionId, null);
    }

    public void notifyAuctionWon(int winnerId, String itemName, int auctionId, double finalPrice) {
        notifyUser(winnerId, NotificationType.AUCTION_WON,
                "Bạn đã thắng phiên đấu giá!",
                "Chúc mừng! Bạn đã thắng \"" + itemName + "\" (phiên #" + auctionId
                        + ") với giá " + formatVnd(finalPrice)
                        + ". Vui lòng hoàn thiện thông tin trong thời hạn để hoàn tất giao dịch.",
                auctionId, null);
    }

    public void notifyAuctionPaidToSeller(int sellerId, String itemName, int auctionId) {
        notifyUser(sellerId, NotificationType.AUCTION_PAID,
                "Người thắng đã hoàn thiện thông tin",
                "Người thắng phiên \"" + itemName + "\" đã hoàn thiện thông tin.",
                auctionId, null);
    }

    public void notifyCompletionFailedToWinner(int winnerId, String itemName, int auctionId) {
        notifyUser(winnerId, NotificationType.INFO_COMPLETION_FAILED,
                "Hoàn thiện thông tin thất bại",
                "Bạn không hoàn thiện thông tin cho phiên \"" + itemName
                        + "\" trong thời hạn. Vui lòng liên hệ CSKH để được tư vấn.",
                auctionId, null);
    }

    public void notifyAuctionCanceledToSeller(int sellerId, String itemName, int auctionId, String reason) {
        notifyUser(sellerId, NotificationType.AUCTION_CANCELED,
                "Phiên đấu giá đã bị hủy",
                "Phiên đấu giá \"" + itemName + "\" đã bị hủy. Lý do: " + reason
                        + ". Vui lòng kiểm tra trạng thái sản phẩm.",
                auctionId, null);
    }

    public void notifyAuctionRelisted(int previousBidderId, String itemName, int newAuctionId) {
        notifyUser(previousBidderId, NotificationType.AUCTION_RELISTED,
                "Sản phẩm đã được mở đấu giá lại",
                "Sản phẩm \"" + itemName + "\" đã được mở đấu giá lại, hãy thử sức nào!",
                newAuctionId, null);
    }

    private static String formatVnd(double v) {
        return String.format("%,.0f VNĐ", v);
    }
}

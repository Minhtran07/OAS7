package com.auction.server.dao;

import com.auction.shared.model.notification.Notification;
import com.auction.shared.model.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO cho bảng notifications.
 */
public class NotificationDAO {
    private static final Logger logger = LoggerFactory.getLogger(NotificationDAO.class);

    /**
     * Insert 1 thông báo mới. Trả về id vừa tạo, hoặc -1 nếu thất bại.
     */
    public int insert(Notification n) {
        String sql = "INSERT INTO notifications " +
                "(user_id, type, title, message, related_auction_id, related_item_id) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, n.userId);
            ps.setString(2, n.type);
            ps.setString(3, n.title);
            if (n.message == null) ps.setNull(4, java.sql.Types.VARCHAR);
            else                   ps.setString(4, n.message);
            if (n.relatedAuctionId == null) ps.setNull(5, java.sql.Types.INTEGER);
            else                            ps.setInt(5, n.relatedAuctionId);
            if (n.relatedItemId == null) ps.setNull(6, java.sql.Types.INTEGER);
            else                         ps.setInt(6, n.relatedItemId);

            int rows = ps.executeUpdate();
            if (rows == 0) return -1;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int id = keys.getInt(1);
                    n.id = id;
                    return id;
                }
            }
            return -1;
        } catch (SQLException e) {
            logger.error("Lỗi insert notification", e);
            return -1;
        }
    }

    /** Tiện ích — tạo + insert + return notification đã có id, createdAt. */
    public Notification create(int userId, NotificationType type, String title, String message,
                               Integer auctionId, Integer itemId) {
        Notification n = new Notification(userId, type, title, message, auctionId, itemId);
        int id = insert(n);
        if (id > 0) {
            // load lại để có createdAt chuẩn
            Notification saved = findById(id);
            return saved != null ? saved : n;
        }
        return n;
    }

    public Notification findById(int id) {
        String sql = "SELECT id, user_id, type, title, message, related_auction_id, related_item_id, " +
                "is_read, created_at FROM notifications WHERE id=?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        } catch (SQLException e) {
            logger.error("Lỗi findById notification", e);
        }
        return null;
    }

    /** Danh sách notification của user (mới nhất trước). */
    public List<Notification> listByUser(int userId, int limit) {
        String sql = "SELECT id, user_id, type, title, message, related_auction_id, related_item_id, " +
                "is_read, created_at FROM notifications " +
                "WHERE user_id=? ORDER BY id DESC LIMIT ?";
        List<Notification> list = new ArrayList<>();
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, limit <= 0 ? 100 : limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            logger.error("Lỗi listByUser notification", e);
        }
        return list;
    }

    public int unreadCount(int userId) {
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id=? AND is_read=0";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Lỗi unreadCount notification", e);
        }
        return 0;
    }

    public boolean markRead(int notifId, int userId) {
        String sql = "UPDATE notifications SET is_read=1 WHERE id=? AND user_id=?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, notifId);
            ps.setInt(2, userId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("Lỗi markRead notification", e);
            return false;
        }
    }

    public int markAllRead(int userId) {
        String sql = "UPDATE notifications SET is_read=1 WHERE user_id=? AND is_read=0";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Lỗi markAllRead notification", e);
            return 0;
        }
    }

    private Notification map(ResultSet rs) throws SQLException {
        Notification n = new Notification();
        n.id = rs.getInt("id");
        n.userId = rs.getInt("user_id");
        n.type = rs.getString("type");
        n.title = rs.getString("title");
        n.message = rs.getString("message");
        int aid = rs.getInt("related_auction_id");
        n.relatedAuctionId = rs.wasNull() ? null : aid;
        int iid = rs.getInt("related_item_id");
        n.relatedItemId = rs.wasNull() ? null : iid;
        n.isRead = rs.getInt("is_read") != 0;
        n.createdAt = rs.getString("created_at");
        return n;
    }
}

package com.auction.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * DAO cho bảng bidder_info — thông tin "hoàn thiện" của winner.
 * 1 auction chỉ có 1 record (UNIQUE on auction_id).
 */
public class BidderInfoDAO {
    private static final Logger logger = LoggerFactory.getLogger(BidderInfoDAO.class);

    public static class BidderInfo {
        public int    id;
        public int    auctionId;
        public int    bidderId;
        public String fullName;
        public String phone;
        public String address;
        public String paymentMethod;  // "COD" | "BANK"
        public String bankAccount;
        public String completedAt;
    }

    /**
     * Insert (replace) bidder_info cho 1 auction.
     * Vì có UNIQUE(auction_id), thực hiện chiến lược "try-UPDATE-then-INSERT":
     *  - Nếu đã tồn tại record cho auction_id → UPDATE (giữ nguyên id).
     *  - Nếu chưa có → INSERT mới.
     *
     * Lý do KHÔNG dùng "INSERT OR REPLACE": trên một số bản SQLite + JDBC,
     * REPLACE thực hiện DELETE + INSERT trong cùng câu, vi phạm FOREIGN KEY
     * (bidder_info.auction_id) khi PRAGMA foreign_keys=ON → executeUpdate()
     * trả về 0 hoặc ném SQLException, làm upsert trả false dù dữ liệu hợp lệ.
     * Chiến lược UPDATE/INSERT thuần đơn giản và đáng tin cậy hơn.
     */
    public boolean upsert(BidderInfo info) {
        try (Connection conn = DatabaseConnection.getInstance().getConnection()) {
            // 1) Kiểm tra đã tồn tại chưa
            int existingId = -1;
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT id FROM bidder_info WHERE auction_id=?")) {
                q.setInt(1, info.auctionId);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) existingId = rs.getInt(1);
                }
            }

            if (existingId > 0) {
                // 2a) UPDATE
                String sql = "UPDATE bidder_info SET bidder_id=?, full_name=?, phone=?, "
                        + "address=?, payment_method=?, bank_account=?, "
                        + "completed_at=datetime('now','localtime') WHERE auction_id=?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, info.bidderId);
                    ps.setString(2, info.fullName != null ? info.fullName : "");
                    ps.setString(3, info.phone    != null ? info.phone    : "");
                    ps.setString(4, info.address  != null ? info.address  : "");
                    ps.setString(5, info.paymentMethod != null ? info.paymentMethod : "COD");
                    ps.setString(6, info.bankAccount   != null ? info.bankAccount   : "");
                    ps.setInt(7, info.auctionId);
                    int rows = ps.executeUpdate();
                    if (rows <= 0) {
                        logger.warn("upsert bidder_info UPDATE trả về 0 rows (auctionId={})",
                                info.auctionId);
                        return false;
                    }
                    info.id = existingId;
                    return true;
                }
            } else {
                // 2b) INSERT mới
                String sql = "INSERT INTO bidder_info "
                        + "(auction_id, bidder_id, full_name, phone, address, payment_method, bank_account, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now','localtime'))";
                try (PreparedStatement ps = conn.prepareStatement(sql,
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, info.auctionId);
                    ps.setInt(2, info.bidderId);
                    ps.setString(3, info.fullName != null ? info.fullName : "");
                    ps.setString(4, info.phone    != null ? info.phone    : "");
                    ps.setString(5, info.address  != null ? info.address  : "");
                    ps.setString(6, info.paymentMethod != null ? info.paymentMethod : "COD");
                    ps.setString(7, info.bankAccount   != null ? info.bankAccount   : "");
                    int rows = ps.executeUpdate();
                    if (rows <= 0) {
                        logger.warn("upsert bidder_info INSERT trả về 0 rows (auctionId={}, bidderId={})",
                                info.auctionId, info.bidderId);
                        return false;
                    }
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) info.id = keys.getInt(1);
                    }
                    return true;
                }
            }
        } catch (SQLException e) {
            logger.error("Lỗi upsert bidder_info (auctionId={}, bidderId={}): {}",
                    info.auctionId, info.bidderId, e.getMessage(), e);
            return false;
        }
    }

    public BidderInfo findByAuction(int auctionId) {
        String sql = "SELECT id, auction_id, bidder_id, full_name, phone, address, " +
                "payment_method, bank_account, completed_at " +
                "FROM bidder_info WHERE auction_id=?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BidderInfo b = new BidderInfo();
                    b.id = rs.getInt("id");
                    b.auctionId = rs.getInt("auction_id");
                    b.bidderId = rs.getInt("bidder_id");
                    b.fullName = rs.getString("full_name");
                    b.phone = rs.getString("phone");
                    b.address = rs.getString("address");
                    b.paymentMethod = rs.getString("payment_method");
                    b.bankAccount = rs.getString("bank_account");
                    b.completedAt = rs.getString("completed_at");
                    return b;
                }
            }
        } catch (SQLException e) {
            logger.error("Lỗi findByAuction bidder_info", e);
        }
        return null;
    }
}

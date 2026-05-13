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
     * Vì có UNIQUE(auction_id), dùng INSERT OR REPLACE.
     *
     * Lưu ý: KHÔNG dùng Statement.RETURN_GENERATED_KEYS — với INSERT OR REPLACE
     * trên SQLite, một số phiên bản JDBC trả về behavior không nhất quán
     * (id cũ vs id mới). Ta query lại id bằng SELECT cho an toàn.
     */
    public boolean upsert(BidderInfo info) {
        String sql = "INSERT OR REPLACE INTO bidder_info " +
                "(auction_id, bidder_id, full_name, phone, address, payment_method, bank_account, completed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now','localtime'))";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, info.auctionId);
            ps.setInt(2, info.bidderId);
            ps.setString(3, info.fullName);
            ps.setString(4, info.phone);
            ps.setString(5, info.address);
            ps.setString(6, info.paymentMethod);
            ps.setString(7, info.bankAccount);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                logger.warn("upsert bidder_info: executeUpdate trả về 0 rows (auctionId={}, bidderId={})",
                        info.auctionId, info.bidderId);
                return false;
            }
            // Query lại id (UNIQUE auction_id nên 1 row)
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT id FROM bidder_info WHERE auction_id=?")) {
                q.setInt(1, info.auctionId);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) info.id = rs.getInt(1);
                }
            }
            return true;
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

package com.auction.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AuctionDAO — truy cập dữ liệu cho bảng auctions và bids.
 * Chỉ Server mới được dùng class này.
 */
public class AuctionDAO {
    private static final Logger logger = LoggerFactory.getLogger(AuctionDAO.class);

    // ─── CREATE ───────────────────────────────────────────────────────────────

    /**
     * Tạo phiên đấu giá mới, trả về id được DB tự sinh.
     * @return id của auction vừa tạo, hoặc -1 nếu thất bại.
     */
    public int createAuction(int itemId, double startingPrice,
                             LocalDateTime startTime, LocalDateTime endTime) {
        String sql = "INSERT INTO auctions(item_id, status, start_time, end_time, current_price) "
                   + "VALUES(?, 'OPEN', ?, ?, ?)";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setInt(1, itemId);
            pstmt.setString(2, startTime.toString());
            pstmt.setString(3, endTime.toString());
            pstmt.setDouble(4, startingPrice);

            pstmt.executeUpdate();

            ResultSet keys = pstmt.getGeneratedKeys();
            if (keys.next()) {
                int newId = keys.getInt(1);
                logger.info("Tạo phiên đấu giá thành công, id={}", newId);
                return newId;
            }

        } catch (SQLException e) {
            logger.error("Lỗi tạo phiên đấu giá: {}", e.getMessage());
        }
        return -1;
    }

    // ─── READ ────────────────────────────────────────────────────────────────

    /**
     * Lấy tất cả phiên đang OPEN hoặc RUNNING để load vào AuctionManager khi khởi động server.
     */
    public List<AuctionRow> getActiveAuctions() {
        List<AuctionRow> list = new ArrayList<>();
        String sql = "SELECT * FROM auctions WHERE status IN ('OPEN','RUNNING')";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy danh sách phiên đang hoạt động: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Lấy các phiên đang OPEN hoặc RUNNING để hiển thị danh sách cho client.
     * JOIN với bảng items để lấy kèm tên sản phẩm, category, description.
     * Phiên CLOSED/FINISHED bị ẩn — đúng nghĩa "phiên đang diễn ra".
     */
    public List<AuctionRow> getAllAuctions() {
        return queryAuctions(
                "WHERE a.status IN ('OPEN','RUNNING') ORDER BY a.end_time ASC");
    }

    /**
     * Lấy TẤT CẢ phiên (kể cả FINISHED, CLOSED) — dùng cho client view với
     * filter "Tất cả" hoặc "Đã kết thúc". Phiên đang chạy lên đầu, kế tiếp là
     * phiên kết thúc gần nhất.
     */
    public List<AuctionRow> getAuctionsByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            // Mặc định: tất cả status có ý nghĩa
            statuses = java.util.Arrays.asList("OPEN", "RUNNING", "FINISHED", "CLOSED");
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < statuses.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('\'').append(statuses.get(i).replace("'", "''")).append('\'');
        }
        String where = "WHERE a.status IN (" + placeholders + ") "
                     // OPEN/RUNNING trước (sắp kết thúc trước),
                     // FINISHED/CLOSED sau (mới kết thúc trước)
                     + "ORDER BY CASE a.status "
                     + "  WHEN 'RUNNING'  THEN 1 "
                     + "  WHEN 'OPEN'     THEN 2 "
                     + "  WHEN 'FINISHED' THEN 3 "
                     + "  WHEN 'CLOSED'   THEN 4 "
                     + "  ELSE 5 END, "
                     + "a.end_time DESC";
        return queryAuctions(where);
    }

    /** Helper chung cho getAllAuctions / getAuctionsByStatuses. */
    private List<AuctionRow> queryAuctions(String whereOrderClause) {
        List<AuctionRow> list = new ArrayList<>();
        String sql = "SELECT a.*, i.name AS item_name, i.category AS item_category, "
                   + "       i.description AS item_description, "
                   + "       u.fullname AS winner_name "
                   + "FROM auctions a "
                   + "LEFT JOIN items i ON a.item_id   = i.id "
                   + "LEFT JOIN users u ON a.winner_id = u.id "
                   + whereOrderClause;

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                AuctionRow row = mapRow(rs);
                try {
                    row.itemName        = rs.getString("item_name");
                    row.itemCategory    = rs.getString("item_category");
                    row.itemDescription = rs.getString("item_description");
                } catch (SQLException ignore) { /* bảng cũ không có cột */ }
                try {
                    row.winnerName = rs.getString("winner_name");
                } catch (SQLException ignore) { /* không có winner */ }
                list.add(row);
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy danh sách phiên: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Kiểm tra một item đã có phiên OPEN/RUNNING chưa.
     * Dùng để chặn seller tạo nhiều phiên trùng cho cùng 1 sản phẩm.
     */
    public boolean hasActiveAuctionForItem(int itemId) {
        String sql = "SELECT COUNT(*) FROM auctions WHERE item_id = ? AND status IN ('OPEN','RUNNING')";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, itemId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;

        } catch (SQLException e) {
            logger.error("Lỗi check active auction cho item {}: {}", itemId, e.getMessage());
            return false;
        }
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────────

    /**
     * Cập nhật giá hiện tại và người dẫn đầu sau mỗi bid hợp lệ.
     */
    public boolean updateCurrentPrice(int auctionId, double newPrice, int winnerId) {
        String sql = "UPDATE auctions SET current_price = ?, winner_id = ? WHERE id = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setDouble(1, newPrice);
            pstmt.setInt(2, winnerId);
            pstmt.setInt(3, auctionId);

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Lỗi cập nhật giá phiên {}: {}", auctionId, e.getMessage());
            return false;
        }
    }

    /**
     * Cập nhật trạng thái phiên (OPEN → RUNNING → FINISHED → CLOSED).
     */
    public boolean updateStatus(int auctionId, String status) {
        String sql = "UPDATE auctions SET status = ? WHERE id = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, status);
            pstmt.setInt(2, auctionId);

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Lỗi cập nhật trạng thái phiên {}: {}", auctionId, e.getMessage());
            return false;
        }
    }

    // ─── BID HISTORY ─────────────────────────────────────────────────────────

    /**
     * Ghi lại một lần đặt giá vào bảng bids.
     */
    public boolean recordBid(int auctionId, int bidderId, double amount) {
        String sql = "INSERT INTO bids(auction_id, bidder_id, amount) VALUES(?, ?, ?)";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, auctionId);
            pstmt.setInt(2, bidderId);
            pstmt.setDouble(3, amount);

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            logger.error("Lỗi ghi bid vào DB: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lấy toàn bộ lịch sử bid của 1 phiên, sắp xếp cũ → mới (để vẽ chart đúng chiều).
     * JOIN với bảng users để lấy kèm fullname của bidder.
     */
    public List<BidRow> getBidHistory(int auctionId) {
        List<BidRow> list = new ArrayList<>();
        String sql = "SELECT b.bidder_id, b.amount, b.bid_time, u.fullname "
                   + "FROM bids b LEFT JOIN users u ON b.bidder_id = u.id "
                   + "WHERE b.auction_id = ? "
                   + "ORDER BY b.bid_time ASC, b.id ASC";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, auctionId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                BidRow row = new BidRow();
                row.bidderId   = rs.getInt("bidder_id");
                row.bidderName = rs.getString("fullname");
                row.amount     = rs.getDouble("amount");
                row.bidTime    = rs.getString("bid_time");
                list.add(row);
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy bid history cho phiên {}: {}", auctionId, e.getMessage());
        }
        return list;
    }

    /**
     * Trả về danh sách các phiên đấu giá mà bidder đã tham gia (đã bid ít nhất 1 lần).
     * Mỗi row chứa: thông tin auction + item, kèm 2 field bổ sung:
     *  - myMaxBid: số tiền cao nhất bidder này đã đặt
     *  - myBidCount: số lượt bid của bidder này
     * Dùng cho màn hình "Giỏ đấu giá".
     */
    public List<MyBidRow> getMyBidAuctions(int bidderId) {
        List<MyBidRow> list = new ArrayList<>();
        // GROUP BY auction để mỗi phiên 1 row, tổng hợp max bid + count cho bidder này
        String sql = "SELECT a.id AS auction_id, a.item_id, a.status, a.start_time, a.end_time, "
                   + "       a.current_price, a.winner_id, "
                   + "       i.name AS item_name, i.category AS item_category, "
                   + "       i.description AS item_description, "
                   + "       u.fullname AS winner_name, "
                   + "       MAX(b.amount) AS my_max_bid, "
                   + "       COUNT(b.id)   AS my_bid_count, "
                   + "       MAX(b.bid_time) AS my_last_bid_time "
                   + "FROM bids b "
                   + "JOIN auctions a ON b.auction_id = a.id "
                   + "LEFT JOIN items i ON a.item_id   = i.id "
                   + "LEFT JOIN users u ON a.winner_id = u.id "
                   + "WHERE b.bidder_id = ? "
                   + "GROUP BY a.id "
                   // Phiên đang chạy lên trước, sau đó kết thúc gần nhất trước
                   + "ORDER BY CASE a.status "
                   + "  WHEN 'RUNNING'  THEN 1 "
                   + "  WHEN 'OPEN'     THEN 2 "
                   + "  WHEN 'FINISHED' THEN 3 "
                   + "  WHEN 'CLOSED'   THEN 4 "
                   + "  ELSE 5 END, "
                   + "a.end_time DESC";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, bidderId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                MyBidRow row = new MyBidRow();
                row.auctionId       = rs.getInt("auction_id");
                row.itemId          = rs.getInt("item_id");
                row.status          = rs.getString("status");
                row.startTime       = rs.getString("start_time");
                row.endTime         = rs.getString("end_time");
                row.currentPrice    = rs.getDouble("current_price");
                row.winnerId        = rs.getInt("winner_id");
                row.itemName        = rs.getString("item_name");
                row.itemCategory    = rs.getString("item_category");
                row.itemDescription = rs.getString("item_description");
                row.winnerName      = rs.getString("winner_name");
                row.myMaxBid        = rs.getDouble("my_max_bid");
                row.myBidCount      = rs.getInt("my_bid_count");
                row.myLastBidTime   = rs.getString("my_last_bid_time");
                list.add(row);
            }
        } catch (SQLException e) {
            logger.error("Lỗi getMyBidAuctions cho bidder {}: {}", bidderId, e.getMessage());
        }
        return list;
    }

    /** DTO cho row trong "Giỏ đấu giá". */
    public static class MyBidRow {
        public int    auctionId;
        public int    itemId;
        public String itemName;
        public String itemCategory;
        public String itemDescription;
        public String status;
        public String startTime;
        public String endTime;
        public double currentPrice;
        public int    winnerId;
        public String winnerName;
        public double myMaxBid;
        public int    myBidCount;
        public String myLastBidTime;
    }

    /**
     * Lấy thông tin 1 phiên kèm fullname của winner hiện tại (nếu có).
     * Dùng cho GET_AUCTION_STATE — snapshot ban đầu khi client mở màn hình.
     */
    public AuctionRow getAuctionById(int auctionId) {
        // JOIN thêm items để lấy starting_price thật của phiên — cần cho client
        // mới vào phiên để vẽ điểm "Bắt đầu" đúng giá khởi điểm, không phải giá
        // bid đầu tiên (vốn xấp xỉ và sai khi giá khởi điểm < giá bid đầu).
        // Đồng thời lấy luôn các thuộc tính chi tiết của item (artist/material/
        // brand/warranty/year) để hiển thị mô tả ở màn hình bidding.
        String sql = "SELECT a.*, u.fullname AS winner_name, "
                   + "       i.name AS item_name, "
                   + "       i.category AS item_category, "
                   + "       i.description AS item_description, "
                   + "       i.starting_price AS starting_price, "
                   + "       i.artist AS item_artist, "
                   + "       i.material AS item_material, "
                   + "       i.brand AS item_brand, "
                   + "       i.warranty_period AS item_warranty, "
                   + "       i.year AS item_year "
                   + "FROM auctions a "
                   + "LEFT JOIN users u ON a.winner_id = u.id "
                   + "LEFT JOIN items i ON a.item_id   = i.id "
                   + "WHERE a.id = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, auctionId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                AuctionRow row = mapRow(rs);
                try { row.winnerName      = rs.getString("winner_name");      } catch (SQLException ignore) {}
                try { row.startingPrice   = rs.getDouble("starting_price");   } catch (SQLException ignore) {}
                try { row.itemName        = rs.getString("item_name");        } catch (SQLException ignore) {}
                try { row.itemCategory    = rs.getString("item_category");    } catch (SQLException ignore) {}
                try { row.itemDescription = rs.getString("item_description"); } catch (SQLException ignore) {}
                try { row.itemArtist      = rs.getString("item_artist");      } catch (SQLException ignore) {}
                try { row.itemMaterial    = rs.getString("item_material");    } catch (SQLException ignore) {}
                try { row.itemBrand       = rs.getString("item_brand");       } catch (SQLException ignore) {}
                try { row.itemWarranty    = rs.getInt("item_warranty");       } catch (SQLException ignore) {}
                try { row.itemYear        = rs.getInt("item_year");           } catch (SQLException ignore) {}
                return row;
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy auction #{}: {}", auctionId, e.getMessage());
        }
        return null;
    }

    // ─── HELPER ──────────────────────────────────────────────────────────────

    private AuctionRow mapRow(ResultSet rs) throws SQLException {
        AuctionRow row = new AuctionRow();
        row.id           = rs.getInt("id");
        row.itemId       = rs.getInt("item_id");
        row.status       = rs.getString("status");
        row.startTime    = rs.getString("start_time");
        row.endTime      = rs.getString("end_time");
        row.currentPrice = rs.getDouble("current_price");
        row.winnerId     = rs.getInt("winner_id");
        return row;
    }

    /**
     * DTO đơn giản chứa dữ liệu thô từ bảng auctions.
     * ClientHandler sẽ dùng để build response JSON.
     */
    public static class AuctionRow {
        public int    id;
        public int    itemId;
        public String itemName;        // Tên sản phẩm (JOIN với bảng items)
        public String itemCategory;    // ART | ELECTRONICS | VEHICLE — để chọn emoji
        public String itemDescription;
        // Thuộc tính chi tiết theo loại — chỉ populate trong getAuctionById
        public String itemArtist;      // ART
        public String itemMaterial;    // ART
        public String itemBrand;       // ELECTRONICS
        public int    itemWarranty;    // ELECTRONICS — tháng
        public int    itemYear;        // VEHICLE
        public String status;          // String thay vì enum — Gson serialize đúng
        public String startTime;       // String thay vì LocalDateTime — Gson serialize đúng
        public String endTime;
        public double currentPrice;
        public double startingPrice;   // Giá khởi điểm gốc (JOIN từ items.starting_price)
        public int    winnerId;
        public String winnerName;      // fullname của winner hiện tại (JOIN với users)
    }

    /**
     * DTO cho một bản ghi bid — dùng cho GET_AUCTION_STATE.
     */
    public static class BidRow {
        public int    bidderId;
        public String bidderName;
        public double amount;
        public String bidTime;   // String (SQLite DATETIME) — Gson serialize OK
    }
}

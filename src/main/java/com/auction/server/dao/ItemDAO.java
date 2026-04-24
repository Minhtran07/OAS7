package com.auction.server.dao;

import com.auction.shared.model.item.*;
import com.auction.shared.model.user.Seller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ItemDAO {
    private static final Logger logger = LoggerFactory.getLogger(ItemDAO.class);

    // ─── CREATE ──────────────────────────────────────────────────────────────

    /**
     * Thêm item mới, trả về id được DB tự sinh (hoặc -1 nếu thất bại).
     */
    public int addItem(Item item, int sellerId) {
        String sql = "INSERT INTO items(name, description, starting_price, current_price, category, "
                + "artist, material, brand, warranty_period, year, seller_id) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, item.getName());
            pstmt.setString(2, item.getDescription());
            pstmt.setDouble(3, item.getStartingPrice());
            pstmt.setDouble(4, item.getStartingPrice()); // current_price = starting_price ban đầu
            pstmt.setString(5, item.getCategory());

            if (item instanceof Art art) {
                pstmt.setString(6, art.getArtist());
                pstmt.setString(7, art.getMaterial());
                pstmt.setNull(8, Types.VARCHAR);
                pstmt.setNull(9, Types.INTEGER);
                pstmt.setNull(10, Types.INTEGER);
            } else if (item instanceof Electronics elec) {
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.VARCHAR);
                pstmt.setString(8, elec.getBrand());
                pstmt.setInt(9, elec.getWarrantyPeriod());
                pstmt.setNull(10, Types.INTEGER);
            } else if (item instanceof Vehicle veh) {
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.VARCHAR);
                pstmt.setNull(8, Types.VARCHAR);
                pstmt.setNull(9, Types.INTEGER);
                pstmt.setInt(10, veh.getYear());
            } else {
                // Generic item — set all specifics null
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.VARCHAR);
                pstmt.setNull(8, Types.VARCHAR);
                pstmt.setNull(9, Types.INTEGER);
                pstmt.setNull(10, Types.INTEGER);
            }

            pstmt.setInt(11, sellerId);
            pstmt.executeUpdate();

            ResultSet keys = pstmt.getGeneratedKeys();
            if (keys.next()) {
                int newId = keys.getInt(1);
                logger.info("Thêm item thành công, id={}", newId);
                return newId;
            }

        } catch (SQLException e) {
            logger.error("Lỗi thêm item: {}", e.getMessage());
        }
        return -1;
    }

    // ─── READ ────────────────────────────────────────────────────────────────

    /**
     * Lấy tất cả item thuộc về một seller cụ thể.
     */
    public List<ItemRow> getItemsBySeller(int sellerId) {
        List<ItemRow> list = new ArrayList<>();
        String sql = "SELECT * FROM items WHERE seller_id = ? ORDER BY id DESC";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, sellerId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy items của seller {}: {}", sellerId, e.getMessage());
        }
        return list;
    }

    /**
     * Lấy tất cả item (để hiển thị danh sách chung).
     */
    public List<ItemRow> getAllItems() {
        List<ItemRow> list = new ArrayList<>();
        String sql = "SELECT * FROM items ORDER BY id DESC";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                list.add(mapRow(rs));
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy tất cả items: {}", e.getMessage());
        }
        return list;
    }

    /**
     * Lấy Item object theo id (dùng cho AuctionManager).
     */
    public Item findById(int itemId) {
        ItemRow row = getItemById(itemId);
        if (row == null) return null;
        return switch (row.category != null ? row.category : "") {
            case "ART"         -> new Art(row.id, row.category, row.name, row.sellerId,
                                          row.description, row.startingPrice, row.currentPrice,
                                          row.artist != null ? row.artist : "",
                                          row.material != null ? row.material : "");
            case "ELECTRONICS" -> new Electronics(row.id, row.category, row.name, row.sellerId,
                                          row.description, row.startingPrice, row.currentPrice,
                                          row.brand != null ? row.brand : "", row.warrantyPeriod);
            case "VEHICLE"     -> new Vehicle(row.id, row.category, row.name, row.sellerId,
                                          row.description, row.startingPrice, row.currentPrice,
                                          row.year);
            default            -> new Art(row.id, row.category, row.name, row.sellerId,
                                          row.description, row.startingPrice, row.currentPrice, "", "");
        };
    }

    /**
     * Lấy item theo id.
     */
    public ItemRow getItemById(int itemId) {
        String sql = "SELECT * FROM items WHERE id = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, itemId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return mapRow(rs);
            }

        } catch (SQLException e) {
            logger.error("Lỗi lấy item id={}: {}", itemId, e.getMessage());
        }
        return null;
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────────

    /**
     * Cập nhật thông tin cơ bản của item (tên, mô tả, giá khởi điểm, danh mục).
     */
    public boolean updateItem(int itemId, String name, String description,
                              double startingPrice, String category,
                              String extra1, String extra2, int sellerId) {
        // Kiểm tra quyền: item phải thuộc seller này
        String checkSql = "SELECT seller_id FROM items WHERE id = ?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement check = conn.prepareStatement(checkSql)) {

            check.setInt(1, itemId);
            ResultSet rs = check.executeQuery();
            if (!rs.next() || rs.getInt("seller_id") != sellerId) {
                logger.warn("Seller {} không có quyền sửa item {}", sellerId, itemId);
                return false;
            }

        } catch (SQLException e) {
            logger.error("Lỗi kiểm tra quyền: {}", e.getMessage());
            return false;
        }

        String sql = "UPDATE items SET name=?, description=?, starting_price=?, category=?, "
                + "artist=?, brand=? WHERE id=?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, description);
            pstmt.setDouble(3, startingPrice);
            pstmt.setString(4, category);
            pstmt.setString(5, extra1);  // artist cho ART, null cho loại khác
            pstmt.setString(6, extra2);  // brand cho ELECTRONICS, null cho loại khác
            pstmt.setInt(7, itemId);

            boolean ok = pstmt.executeUpdate() > 0;
            if (ok) logger.info("Cập nhật item {} thành công", itemId);
            return ok;

        } catch (SQLException e) {
            logger.error("Lỗi cập nhật item {}: {}", itemId, e.getMessage());
            return false;
        }
    }

    /**
     * Cập nhật status của item (OPEN / IN_AUCTION / SOLD / CLOSED).
     * Gọi bởi ClientHandler khi tạo phiên đấu giá hoặc khi phiên kết thúc.
     */
    public boolean updateStatus(int itemId, String status) {
        String sql = "UPDATE items SET status = ? WHERE id = ?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, status);
            pstmt.setInt(2, itemId);
            boolean ok = pstmt.executeUpdate() > 0;
            if (ok) logger.info("Cập nhật item {} sang status={}", itemId, status);
            return ok;

        } catch (SQLException e) {
            logger.error("Lỗi cập nhật status item {}: {}", itemId, e.getMessage());
            return false;
        }
    }

    // ─── DELETE ──────────────────────────────────────────────────────────────

    /**
     * Xóa item — chỉ được xóa nếu chưa có phiên đấu giá liên kết.
     */
    public boolean deleteItem(int itemId, int sellerId) {
        // Kiểm tra quyền
        String checkSql = "SELECT seller_id FROM items WHERE id = ?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement check = conn.prepareStatement(checkSql)) {

            check.setInt(1, itemId);
            ResultSet rs = check.executeQuery();
            if (!rs.next() || rs.getInt("seller_id") != sellerId) {
                return false;
            }

        } catch (SQLException e) {
            logger.error("Lỗi kiểm tra quyền: {}", e.getMessage());
            return false;
        }

        // Kiểm tra item có đang dùng trong phiên nào không
        String auctionCheckSql =
                "SELECT COUNT(*) FROM auctions WHERE item_id=? AND status IN ('OPEN','RUNNING')";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(auctionCheckSql)) {

            pstmt.setInt(1, itemId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                logger.warn("Không thể xóa item {} đang trong phiên đấu giá", itemId);
                return false; // caller sẽ nhận FAIL
            }

        } catch (SQLException e) {
            logger.error("Lỗi kiểm tra auction: {}", e.getMessage());
            return false;
        }

        String sql = "DELETE FROM items WHERE id = ?";
        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, itemId);
            boolean ok = pstmt.executeUpdate() > 0;
            if (ok) logger.info("Xóa item {} thành công", itemId);
            return ok;

        } catch (SQLException e) {
            logger.error("Lỗi xóa item {}: {}", itemId, e.getMessage());
            return false;
        }
    }

    // ─── HELPER ──────────────────────────────────────────────────────────────

    private ItemRow mapRow(ResultSet rs) throws SQLException {
        ItemRow row = new ItemRow();
        row.id           = rs.getInt("id");
        row.name         = rs.getString("name");
        row.description  = rs.getString("description");
        row.startingPrice = rs.getDouble("starting_price");
        row.currentPrice = rs.getDouble("current_price");
        row.category     = rs.getString("category");
        row.artist       = rs.getString("artist");
        row.material     = rs.getString("material");
        row.brand        = rs.getString("brand");
        row.warrantyPeriod = rs.getInt("warranty_period");
        row.year         = rs.getInt("year");
        row.sellerId     = rs.getInt("seller_id");
        // Đọc status — có thể null cho record cũ
        try {
            String s = rs.getString("status");
            row.status = (s != null && !s.isBlank()) ? s : "OPEN";
        } catch (SQLException ignore) {
            row.status = "OPEN";
        }
        return row;
    }

    /**
     * DTO phẳng để truyền qua JSON (dễ serialize/deserialize).
     */
    public static class ItemRow {
        public int    id;
        public String name;
        public String description;
        public double startingPrice;
        public double currentPrice;
        public String category;   // ART | ELECTRONICS | VEHICLE
        public String artist;     // ART
        public String material;   // ART
        public String brand;      // ELECTRONICS
        public int    warrantyPeriod; // ELECTRONICS
        public int    year;       // VEHICLE
        public int    sellerId;
        public String status;     // OPEN | IN_AUCTION | SOLD | CLOSED
    }
}

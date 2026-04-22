package com.auction.server.dao;

import com.auction.shared.model.item.*; // Import các class Item, Art, Electronics...
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ItemDAO {

    // 1. Hàm thêm một sản phẩm mới vào Database
    public boolean addItem(Item item, int sellerId) {
        String sql = "INSERT INTO items(name, description, starting_price, current_price, category, artist, material, brand, warranty_period, year, seller_id) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, item.getName());
            pstmt.setString(2, item.getDescription());
            pstmt.setDouble(3, item.getStartingPrice());
            pstmt.setDouble(4, item.getCurrentPrice());
            pstmt.setString(5, item.getCategory());

            if (item instanceof Art) {
                Art art = (Art) item;
                pstmt.setString(6, art.getArtist());
                pstmt.setString(7, art.getMaterial());
                pstmt.setNull(8, Types.VARCHAR); // brand
                pstmt.setNull(9, Types.INTEGER); // warranty_period
                pstmt.setNull(10, Types.INTEGER); // year
            }
            else if (item instanceof Electronics) {
                Electronics elec = (Electronics) item;
                pstmt.setNull(6, Types.VARCHAR); // artist
                pstmt.setNull(7, Types.VARCHAR); // material
                pstmt.setString(8, elec.getBrand());
                pstmt.setInt(9, elec.getWarrantyPeriod()); // Ở đây class bạn dùng Int, mình để setInt
                pstmt.setNull(10, Types.INTEGER); // year
            }
            else if (item instanceof Vehicle) {
                Vehicle veh = (Vehicle) item;
                pstmt.setNull(6, Types.VARCHAR); // artist
                pstmt.setNull(7, Types.VARCHAR); // material
                pstmt.setNull(8, Types.VARCHAR); // brand
                pstmt.setNull(9, Types.INTEGER); // warranty_period
                pstmt.setInt(10, veh.getYear());
            }

            pstmt.setInt(11, sellerId); // ID của người bán

            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // 2. Hàm lấy danh sách tất cả sản phẩm
    public List<Item> getAllItems() {
        List<Item> itemList = new ArrayList<>();
        // Chỉ cần JOIN để lấy thêm username và store_name cho UI
        String sql = "SELECT i.*, u.username, u.store_name FROM items i " +
                "JOIN users u ON i.seller_id = u.id";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                // Lấy các thông tin từ database
                int id = rs.getInt("id");
                String category = rs.getString("category");
                String name = rs.getString("name");
                int sellerID = rs.getInt("seller_id");
                String desc = rs.getString("description");
                double startingPrice = rs.getDouble("starting_price");
                double currentPrice = rs.getDouble("current_price");

                String username = rs.getString("username");
                String storeName = rs.getString("store_name");

                // Các biến không cần thiết cho UI (như password, fullname, email) thì để trống ""
                Seller seller = new Seller(sellerID, username, "", "", "", storeName);

                Item item = null;

                // Dựa vào category để tạo đúng Object
                if ("ART".equals(category)) {
                    String artist = rs.getString("artist");
                    String material = rs.getString("material");
                    item = new Art(id, category, name, sellerID, desc, startingPrice, currentPrice, artist, material);
                }
                else if ("ELECTRONICS".equals(category)) {
                    String brand = rs.getString("brand");
                    int warranty = rs.getInt("warranty_period");
                    item = new Electronics(id, category, name, sellerID, desc, startingPrice, currentPrice, brand, warranty);
                }
                else if ("VEHICLE".equals(category)) {
                    int year = rs.getInt("year");
                    item = new Vehicle(id, category, name, sellerID, desc, startingPrice, currentPrice, year);
                }

                if (item != null) {
                    item.setCategory(category);

                    item.setSeller(seller);

                    itemList.add(item);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return itemList;
    }
}
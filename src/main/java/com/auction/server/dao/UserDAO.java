package com.auction.server.dao;

import com.auction.shared.model.user.Admin;
import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;

import java.math.BigDecimal;
import java.sql.*;

public class UserDAO {

    public boolean register(User user) {
        String sql = "INSERT INTO users(username, password, role, fullname, email, balance, store_name) VALUES(?,?,?,?,?,?,?)";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user.getUsername());
            pstmt.setString(2, user.getPassword());
            pstmt.setString(3, user.getRole().name());
            pstmt.setString(4, user.getEmail());
            pstmt.setString(5, user.getFullname());

            if (user instanceof Bidder) {
                pstmt.setBigDecimal(6, ((Bidder) user).getBalance());
                pstmt.setNull(7, java.sql.Types.VARCHAR);

            } else if (user instanceof Seller) {
                pstmt.setNull(6, java.sql.Types.DOUBLE);
                pstmt.setString(7, ((Seller) user).getStoreName());

            } else {
                pstmt.setNull(6, java.sql.Types.DOUBLE);
                pstmt.setNull(7, java.sql.Types.VARCHAR);
            }

            pstmt.executeUpdate();
            return true;

        } catch (SQLException e) {
            System.out.println("Lỗi đăng ký: " + e.getMessage());
            return false;
        }
    }

    public User login(String username, String password) {
        String sql = "SELECT * FROM users WHERE username = ? AND password = ?";

        try (Connection conn = DatabaseConnection.getInstance().getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, username);
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                int id = rs.getInt("id");
                String dbUsername = rs.getString("username");
                String dbPassword = rs.getString("password");
                String role = rs.getString("role");
                String fullname = rs.getString("fullname");
                String email = rs.getString("email");

                switch (role.toUpperCase()) {
                    case "BIDDER":
                        BigDecimal balance = rs.getBigDecimal("balance");
                        return new Bidder(id, dbUsername, dbPassword, fullname, email, balance);

                    case "SELLER":
                        String storeName = rs.getString("store_name");
                        return new Seller(id, dbUsername, dbPassword, fullname, email, storeName);

                    case "ADMIN":
                        return new Admin(id, dbUsername, dbPassword, fullname, email);

                    default:
                        System.out.println("Lỗi: Role trong Database không hợp lệ!");
                        return null;
                }
            }
        } catch (SQLException e) {
            System.out.println("Lỗi đăng nhập: " + e.getMessage());
        }
        return null;
    }
}
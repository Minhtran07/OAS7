package com.auction.server.dao;

import com.auction.server.dao.UserDAO;
import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.User;
import java.math.BigDecimal;

public class MainTest {
    public static void main(String[] args) {
        UserDAO dao = new UserDAO();

        // 1. Test Đăng ký
        System.out.println("--- TEST ĐĂNG KÝ ---");
        Bidder newBidder = new Bidder(0, "ngocminh7", "1234567", "Ngoc Minh", "minh@gmail.com", new BigDecimal("0"));
        boolean isRegistered = dao.register(newBidder);
        System.out.println("Đăng ký thành công? " + isRegistered);

        // 2. Test Đăng nhập
        System.out.println("\n--- TEST ĐĂNG NHẬP ---");
        User loggedInUser = dao.login("ngocminh7", "1234567");

        if (loggedInUser != null) {
            System.out.println("Đăng nhập thành công!");
            System.out.println("Xin chào: " + loggedInUser.getFullname());
            System.out.println("Role của bạn là: " + loggedInUser.getRole());

            // Ép kiểu ngược lại để test lấy số dư
            if (loggedInUser instanceof Bidder) {
                System.out.println("Số dư tài khoản: " + ((Bidder) loggedInUser).getBalance());
            }
        } else {
            System.out.println("Sai tài khoản hoặc mật khẩu!");
        }
    }
}
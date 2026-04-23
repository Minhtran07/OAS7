package com.auction.server.dao;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {
    // 1. Biến static lưu trữ instance duy nhất của class này
    private static DatabaseConnection instance;
    private static final String URL = "jdbc:sqlite:src/main/resources/server/db/auction.db";
    // 2. Private constructor: Ngăn chặn tạo object bằng từ khóa 'new' từ bên ngoài
    private DatabaseConnection() {
        System.out.println("Khởi tạo Database Manager...");
    }

    // 3. Hàm Get Instance (Đảm bảo chỉ có 1 DatabaseConnection được tạo ra)
    public static synchronized DatabaseConnection getInstance() {
        if (instance == null) {
            instance = new DatabaseConnection();
        }
        return instance;
    }

    // 4. Hàm getConnection: MỖI LẦN GỌI SẼ TRẢ VỀ MỘT KẾT NỐI MỚI
    // Nhờ vậy, nhiều luồng (Client) có thể thao tác DB cùng lúc mà không dẫm chân lên nhau
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }
}
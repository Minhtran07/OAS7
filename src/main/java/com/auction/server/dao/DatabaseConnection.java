package com.auction.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Singleton quản lý kết nối SQLite.
 *
 * Khi khởi tạo lần đầu, tự động chạy schema.sql để tạo các bảng
 * còn thiếu (dùng CREATE TABLE IF NOT EXISTS nên an toàn với DB đã có dữ liệu).
 */
public class DatabaseConnection {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnection.class);

    private static DatabaseConnection instance;

    /**
     * Đường dẫn tuyệt đối — tránh phụ thuộc working directory.
     * Nếu biến môi trường AUCTION_DB_PATH được set, dùng nó.
     * Mặc định: <project-root>/src/main/resources/server/db/auction.db
     */
    private static final String DB_PATH = resolveDbPath();
    private static final String URL     = "jdbc:sqlite:" + DB_PATH;

    private static String resolveDbPath() {
        String override = System.getenv("AUCTION_DB_PATH");
        if (override != null && !override.isBlank()) return override;

        String userDir = System.getProperty("user.dir");
        java.io.File f = new java.io.File(userDir, "src/main/resources/server/db/auction.db");
        // Đảm bảo thư mục cha tồn tại
        f.getParentFile().mkdirs();
        return f.getAbsolutePath();
    }

    /** Protected constructor — subclass trong test có thể override URL */
    protected DatabaseConnection() {
        logger.info("Khởi tạo Database Manager... Đường dẫn DB: {}", DB_PATH);
        initSchema();
    }

    public static synchronized DatabaseConnection getInstance() {
        if (instance == null) {
            instance = new DatabaseConnection();
        }
        return instance;
    }

    /**
     * Mỗi lần gọi trả về một Connection mới — an toàn đa luồng.
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    // ─── Schema init ─────────────────────────────────────────────────────────

    /**
     * Đọc schema.sql từ classpath và thực thi để tạo các bảng còn thiếu.
     * Sử dụng CREATE TABLE IF NOT EXISTS nên không ảnh hưởng dữ liệu đã có.
     */
    protected void initSchema() {
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream("server/db/schema.sql")) {
            if (is == null) {
                logger.warn("Không tìm thấy schema.sql trong classpath — bỏ qua init DB.");
                return;
            }

            String sql = new BufferedReader(new InputStreamReader(is))
                    .lines()
                    .collect(Collectors.joining("\n"));

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {

                // Tách các câu lệnh SQL theo dấu ";"
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        stmt.execute(trimmed);
                    }
                }
                logger.info("Schema DB đã được khởi tạo thành công.");
            }

        } catch (IOException e) {
            logger.error("Lỗi đọc schema.sql: {}", e.getMessage());
        } catch (SQLException e) {
            logger.error("Lỗi thực thi schema SQL: {}", e.getMessage());
        }
    }
}

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

            // Sau khi schema base đã có → chạy migration v2 (PAID/CANCELED).
            migrateAuctionsTableForV2();

        } catch (IOException e) {
            logger.error("Lỗi đọc schema.sql: {}", e.getMessage());
        } catch (SQLException e) {
            logger.error("Lỗi thực thi schema SQL: {}", e.getMessage());
        }
    }

    /**
     * Migration v2: bảng `auctions` của bản cũ có CHECK constraint chặn
     * 'PAID' và 'CANCELED'. SQLite không cho ALTER CHECK, nên ta:
     *   1. Đọc DDL hiện tại từ sqlite_master.
     *   2. Nếu DDL chứa CHECK với danh sách trạng thái cũ → recreate table:
     *      tạo auctions_new (không CHECK, có finished_at), copy dữ liệu, drop, rename.
     *   3. Ngoài ra đảm bảo cột finished_at có mặt (ALTER TABLE ADD COLUMN nếu thiếu).
     */
    private void migrateAuctionsTableForV2() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Đọc DDL của bảng auctions hiện hữu
            String existingDdl = null;
            try (var rs = stmt.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='auctions'")) {
                if (rs.next()) existingDdl = rs.getString(1);
            }

            if (existingDdl == null) {
                // Bảng chưa có (DB trắng) → schema.sql đã tạo bản mới rồi, OK.
                return;
            }

            boolean hasOldCheck = existingDdl.contains("CHECK(status IN")
                    || existingDdl.contains("CHECK (status IN")
                    || existingDdl.contains("CHECK( status IN")
                    || existingDdl.contains("CHECK ( status IN");
            boolean hasFinishedAt = existingDdl.toLowerCase().contains("finished_at");

            if (hasOldCheck) {
                logger.info("[migration v2] Phát hiện CHECK constraint cũ trên auctions.status → recreate table.");
                // Bật foreign key off để rename không vướng
                stmt.execute("PRAGMA foreign_keys=OFF");
                stmt.execute("BEGIN TRANSACTION");
                try {
                    // Trường hợp migration trước đó crash để lại bảng tạm — dọn trước
                    stmt.execute("DROP TABLE IF EXISTS auctions_v2");
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS auctions_v2 (" +
                        "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "  item_id       INTEGER NOT NULL," +
                        "  status        TEXT    DEFAULT 'OPEN'," +
                        "  start_time    DATETIME NOT NULL," +
                        "  end_time      DATETIME NOT NULL," +
                        "  current_price REAL    NOT NULL," +
                        "  winner_id     INTEGER," +
                        "  finished_at   DATETIME," +
                        "  FOREIGN KEY (item_id)   REFERENCES items(id)," +
                        "  FOREIGN KEY (winner_id) REFERENCES users(id)" +
                        ")"
                    );
                    stmt.execute(
                        "INSERT INTO auctions_v2 (id, item_id, status, start_time, end_time, current_price, winner_id) " +
                        "SELECT id, item_id, status, start_time, end_time, current_price, winner_id FROM auctions"
                    );
                    stmt.execute("DROP TABLE auctions");
                    stmt.execute("ALTER TABLE auctions_v2 RENAME TO auctions");
                    stmt.execute("COMMIT");
                    logger.info("[migration v2] auctions table đã được nâng cấp (không CHECK + finished_at).");
                } catch (SQLException ex) {
                    stmt.execute("ROLLBACK");
                    throw ex;
                } finally {
                    stmt.execute("PRAGMA foreign_keys=ON");
                }
            } else if (!hasFinishedAt) {
                // Không có CHECK cũ nhưng vẫn thiếu cột finished_at → chỉ cần ADD COLUMN
                logger.info("[migration v2] Thêm cột finished_at vào auctions.");
                stmt.execute("ALTER TABLE auctions ADD COLUMN finished_at DATETIME");
            }
        } catch (SQLException e) {
            logger.error("[migration v2] Lỗi migrate auctions: {}", e.getMessage(), e);
        }
    }
}

package com.auction.server.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
     *
     * Robust: từng statement được try/catch riêng, một câu lỗi không làm
     * abort các câu còn lại. Cuối cùng verify các bảng v2 (notifications,
     * bidder_info) thực sự tồn tại; nếu thiếu thì tạo trực tiếp.
     */
    protected void initSchema() {
        logger.info("[initSchema] === Bắt đầu khởi tạo schema. DB_PATH = {} ===", DB_PATH);

        // 1. ĐẢM BẢO tất cả bảng v2 tồn tại NGAY LẬP TỨC — chạy TRƯỚC mọi thứ
        //    để chắc chắn `notifications` và `bidder_info` có mặt, kể cả khi DB
        //    còn trắng hoặc schema.sql không load được từ classpath.
        ensureV2Tables();

        // 2. Migration v2 cho bảng auctions cũ (drop CHECK + add finished_at)
        try {
            migrateAuctionsTableForV2();
        } catch (Throwable t) {
            logger.error("[initSchema] Migration v2 thất bại: {}", t.getMessage(), t);
        }

        // 3. Chạy schema.sql (CREATE IF NOT EXISTS cho mọi bảng) — best-effort
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream("server/db/schema.sql")) {
            if (is == null) {
                logger.warn("Không tìm thấy schema.sql trong classpath — bỏ qua init DB.");
            } else {
                String sql = new BufferedReader(new InputStreamReader(is))
                        .lines()
                        .collect(Collectors.joining("\n"));
                try (Connection conn = getConnection();
                     Statement stmt = conn.createStatement()) {
                    int ok = 0, fail = 0;
                    for (String statement : sql.split(";")) {
                        String trimmed = statement.trim();
                        if (trimmed.isEmpty()) continue;
                        if (trimmed.startsWith("--") && !trimmed.contains("\n")) continue;
                        try {
                            stmt.execute(trimmed);
                            ok++;
                        } catch (SQLException ex) {
                            fail++;
                            logger.warn("[initSchema] Lỗi statement: {} → {}",
                                    trimmed.substring(0, Math.min(80, trimmed.length())),
                                    ex.getMessage());
                        }
                    }
                    logger.info("[initSchema] Đã chạy {} statements thành công, {} lỗi.", ok, fail);
                }
            }
        } catch (IOException e) {
            logger.error("Lỗi đọc schema.sql: {}", e.getMessage());
        } catch (SQLException e) {
            logger.error("Lỗi mở connection: {}", e.getMessage());
        }

        // 4. Verify lần nữa sau khi mọi thứ chạy xong — đảm bảo bảng v2 tồn tại.
        ensureV2Tables();
    }

    /**
     * Verify-and-create cho các bảng v2. Có thể do schema.sql parse split-by-;
     * trên một số môi trường không tạo đầy đủ — ở đây ta đảm bảo tường minh.
     */
    private void ensureV2Tables() {
        // Mỗi statement trong 1 try-block riêng — 1 lỗi không làm abort các câu sau.
        // Đảm bảo cả các bảng "base" (users/items/auctions/bids) cũng được tạo
        // để FK trong notifications/bidder_info có chỗ tham chiếu.
        execIgnore("CREATE TABLE IF NOT EXISTS users ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  username TEXT UNIQUE NOT NULL,"
                + "  password TEXT NOT NULL,"
                + "  role TEXT NOT NULL,"
                + "  fullname TEXT NOT NULL,"
                + "  email TEXT NOT NULL,"
                + "  balance REAL DEFAULT 0.0,"
                + "  store_name TEXT)");
        execIgnore("CREATE TABLE IF NOT EXISTS items ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  name TEXT NOT NULL,"
                + "  description TEXT,"
                + "  starting_price REAL,"
                + "  current_price REAL,"
                + "  category TEXT,"
                + "  artist TEXT, material TEXT, brand TEXT,"
                + "  warranty_period INTEGER, year INTEGER,"
                + "  seller_id INTEGER,"
                + "  end_time DATETIME,"
                + "  status TEXT DEFAULT 'OPEN',"
                + "  FOREIGN KEY (seller_id) REFERENCES users(id))");
        execIgnore("CREATE TABLE IF NOT EXISTS auctions ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  item_id INTEGER NOT NULL,"
                + "  status TEXT DEFAULT 'OPEN',"
                + "  start_time DATETIME NOT NULL,"
                + "  end_time DATETIME NOT NULL,"
                + "  current_price REAL NOT NULL,"
                + "  winner_id INTEGER,"
                + "  finished_at DATETIME,"
                + "  FOREIGN KEY (item_id) REFERENCES items(id),"
                + "  FOREIGN KEY (winner_id) REFERENCES users(id))");
        execIgnore("CREATE TABLE IF NOT EXISTS bids ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  auction_id INTEGER NOT NULL,"
                + "  bidder_id INTEGER NOT NULL,"
                + "  amount REAL NOT NULL,"
                + "  bid_time DATETIME DEFAULT (datetime('now','localtime')),"
                + "  FOREIGN KEY (auction_id) REFERENCES auctions(id),"
                + "  FOREIGN KEY (bidder_id) REFERENCES users(id))");

        // notifications (v2)
        execIgnore("CREATE TABLE IF NOT EXISTS notifications ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  user_id INTEGER NOT NULL,"
                + "  type TEXT NOT NULL,"
                + "  title TEXT NOT NULL,"
                + "  message TEXT,"
                + "  related_auction_id INTEGER,"
                + "  related_item_id INTEGER,"
                + "  is_read INTEGER DEFAULT 0,"
                + "  created_at DATETIME DEFAULT (datetime('now','localtime')),"
                + "  FOREIGN KEY (user_id) REFERENCES users(id),"
                + "  FOREIGN KEY (related_auction_id) REFERENCES auctions(id),"
                + "  FOREIGN KEY (related_item_id) REFERENCES items(id))");
        execIgnore("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, is_read)");

        // bidder_info (v2)
        execIgnore("CREATE TABLE IF NOT EXISTS bidder_info ("
                + "  id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "  auction_id INTEGER NOT NULL UNIQUE,"
                + "  bidder_id INTEGER NOT NULL,"
                + "  full_name TEXT,"
                + "  phone TEXT,"
                + "  address TEXT,"
                + "  payment_method TEXT,"
                + "  bank_account TEXT,"
                + "  completed_at DATETIME DEFAULT (datetime('now','localtime')),"
                + "  FOREIGN KEY (auction_id) REFERENCES auctions(id),"
                + "  FOREIGN KEY (bidder_id) REFERENCES users(id))");

        // Verify
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            int nCount = -1, bCount = -1;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='notifications'")) {
                if (rs.next()) nCount = rs.getInt(1);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='bidder_info'")) {
                if (rs.next()) bCount = rs.getInt(1);
            }
            logger.info("[initSchema] Verify v2 tables: notifications={}, bidder_info={} (1=tồn tại)",
                    nCount, bCount);
            if (nCount != 1 || bCount != 1) {
                logger.error("[initSchema] CẢNH BÁO: bảng v2 vẫn KHÔNG tồn tại sau bootstrap!");
            }
        } catch (SQLException e) {
            logger.error("[initSchema] Lỗi verify v2 tables: {}", e.getMessage(), e);
        }
    }

    /** Thực thi 1 câu DDL — nuốt lỗi, log warning để các câu sau vẫn chạy. */
    private void execIgnore(String sql) {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.warn("[initSchema] execIgnore lỗi: {} → {}",
                    sql.substring(0, Math.min(60, sql.length())), e.getMessage());
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

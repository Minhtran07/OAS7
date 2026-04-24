package com.auction.server.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Helper test: override DatabaseConnection để trỏ vào SQLite in-memory.
 * Dùng reflection để thay thế instance singleton trong test.
 */
public class TestDatabaseConnection {

    /**
     * Inject một Connection cố định vào DatabaseConnection singleton.
     * Mọi lời gọi getConnection() sau đó sẽ trả về connection này.
     *
     * initSchema() bị override thành no-op để test không đụng vào DB thật.
     * Schema của in-memory DB cần được apply riêng bởi test setup.
     */
    public static void setSharedConnection(Connection sharedConn) {
        try {
            DatabaseConnection testInstance = new DatabaseConnection() {
                @Override
                public Connection getConnection() {
                    return sharedConn;
                }

                @Override
                protected void initSchema() {
                    // No-op: test tự apply schema vào in-memory connection
                }
            };

            java.lang.reflect.Field field = DatabaseConnection.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, testInstance);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Không thể inject TestDatabaseConnection: " + e.getMessage(), e);
        }
    }

    /**
     * Apply schema DDL vào một in-memory connection để test có đủ bảng.
     */
    public static void applySchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    username   TEXT    UNIQUE NOT NULL,
                    password   TEXT    NOT NULL,
                    role       TEXT    NOT NULL,
                    fullname   TEXT    NOT NULL,
                    email      TEXT    NOT NULL,
                    balance    REAL    DEFAULT 0.0,
                    store_name TEXT
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS items (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    name            TEXT    NOT NULL,
                    description     TEXT,
                    starting_price  REAL,
                    current_price   REAL,
                    category        TEXT,
                    artist          TEXT,
                    material        TEXT,
                    brand           TEXT,
                    warranty_period INTEGER,
                    year            INTEGER,
                    seller_id       INTEGER,
                    end_time        DATETIME,
                    status          TEXT DEFAULT 'OPEN'
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id       INTEGER NOT NULL,
                    status        TEXT    DEFAULT 'OPEN',
                    start_time    DATETIME NOT NULL,
                    end_time      DATETIME NOT NULL,
                    current_price REAL    NOT NULL,
                    winner_id     INTEGER
                )
                """);
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bids (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    auction_id INTEGER NOT NULL,
                    bidder_id  INTEGER NOT NULL,
                    amount     REAL    NOT NULL,
                    bid_time   DATETIME DEFAULT (datetime('now','localtime'))
                )
                """);
        }
    }
}

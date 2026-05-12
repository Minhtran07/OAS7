-- ============================================================
-- Online Auction System — Schema (safe: IF NOT EXISTS only)
-- Chạy tự động khi server khởi động qua DatabaseConnection.initSchema()
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    username   TEXT    UNIQUE NOT NULL,
    password   TEXT    NOT NULL,
    role       TEXT    CHECK(role IN ('BIDDER','SELLER','ADMIN')) NOT NULL,
    fullname   TEXT    NOT NULL,
    email      TEXT    NOT NULL,
    balance    REAL    DEFAULT 0.0,
    store_name TEXT
);

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
    status          TEXT    DEFAULT 'OPEN',
    FOREIGN KEY (seller_id) REFERENCES users(id)
);

-- LƯU Ý: từ bản v2 trở đi cột `status` cho phép thêm 'PAID' và 'CANCELED'.
-- Không khai báo CHECK constraint ở đây để tránh xung đột với DB cũ;
-- DatabaseConnection.initSchema() sẽ tự migrate bảng cũ (nếu tồn tại CHECK)
-- sang bảng mới không có CHECK.
CREATE TABLE IF NOT EXISTS auctions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id       INTEGER NOT NULL,
    status        TEXT    DEFAULT 'OPEN',
    start_time    DATETIME NOT NULL,
    end_time      DATETIME NOT NULL,
    current_price REAL    NOT NULL,
    winner_id     INTEGER,
    finished_at   DATETIME,
    FOREIGN KEY (item_id)   REFERENCES items(id),
    FOREIGN KEY (winner_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS bids (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id INTEGER NOT NULL,
    bidder_id  INTEGER NOT NULL,
    amount     REAL    NOT NULL,
    bid_time   DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (auction_id) REFERENCES auctions(id),
    FOREIGN KEY (bidder_id)  REFERENCES users(id)
);

-- ============================================================
-- v2 — bảng thông báo (thay thế MyCart)
-- ============================================================
CREATE TABLE IF NOT EXISTS notifications (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             INTEGER NOT NULL,
    type                TEXT    NOT NULL,
    title               TEXT    NOT NULL,
    message             TEXT,
    related_auction_id  INTEGER,
    related_item_id     INTEGER,
    is_read             INTEGER DEFAULT 0,
    created_at          DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (user_id)            REFERENCES users(id),
    FOREIGN KEY (related_auction_id) REFERENCES auctions(id),
    FOREIGN KEY (related_item_id)    REFERENCES items(id)
);
CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, is_read);

-- ============================================================
-- v2 — bảng "hoàn thiện thông tin" sau khi thắng phiên
-- ============================================================
CREATE TABLE IF NOT EXISTS bidder_info (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id      INTEGER NOT NULL UNIQUE,
    bidder_id       INTEGER NOT NULL,
    full_name       TEXT,
    phone           TEXT,
    address         TEXT,
    payment_method  TEXT,           -- 'COD' | 'BANK'
    bank_account    TEXT,
    completed_at    DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (auction_id) REFERENCES auctions(id),
    FOREIGN KEY (bidder_id)  REFERENCES users(id)
);

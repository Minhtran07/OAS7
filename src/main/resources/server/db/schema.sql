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

CREATE TABLE IF NOT EXISTS auctions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id       INTEGER NOT NULL,
    status        TEXT    CHECK(status IN ('OPEN','RUNNING','FINISHED','CLOSED')) DEFAULT 'OPEN',
    start_time    DATETIME NOT NULL,
    end_time      DATETIME NOT NULL,
    current_price REAL    NOT NULL,
    winner_id     INTEGER,
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

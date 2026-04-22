CREATE TABLE IF NOT EXISTS users (
                                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                                     username TEXT UNIQUE NOT NULL,
                                     password TEXT NOT NULL,
                                     role TEXT CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,

    fullname TEXT NOT NULL,
    email TEXT NOT NULL,

    balance REAL DEFAULT 0.0,      -- Dành riêng cho Bidder
    store_name TEXT                -- Dành riêng cho Seller
    );

CREATE TABLE IF NOT EXISTS items (
                                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                                     name TEXT NOT NULL,
                                     description TEXT,
                                     starting_price REAL,
                                     current_price REAL,
                                     end_time DATETIME
);
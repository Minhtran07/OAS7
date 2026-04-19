CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    role TEXT DEFAULT 'bidder',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    first_price INTEGER NOT NULL,
    current_price INTEGER,
    start_time TEXT DEFAULT (CURRENT_TIMESTAMP),
    end_time TEXT,
    status TEXT DEFAULT 'ongoing'
);

CREATE TABLE bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER,
    product_id INTEGER,
    bid_amount INTEGER NOT NULL,
    bid_time TEXT DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE IF NOT EXISTS users (
                                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                                     username TEXT UNIQUE NOT NULL,
                                     password TEXT NOT NULL,
                                     role TEXT CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,

    fullname TEXT NOT NULL,
    email TEXT NOT NULL,

    balance REAL DEFAULT 0.0,
    store_name TEXT
    );

DROP TABLE IF EXISTS items;

CREATE TABLE items (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       name TEXT NOT NULL,
                       description TEXT,
                       starting_price REAL,
                       current_price REAL,
                       category TEXT,
                       artist TEXT,
                       material TEXT,
                       brand TEXT,
                       warranty_period INTEGER,
                       year INTEGER,
                       seller_id INTEGER,
                       end_time DATETIME,
                       status TEXT DEFAULT 'OPEN',
                       FOREIGN KEY (seller_id) REFERENCES users(id)
);
#!/usr/bin/env python3
"""
Quick-fix: tạo các bảng v2 còn thiếu (notifications, bidder_info)
trong CẢ HAI bản DB (src/main và target/classes) — phòng trường hợp
server đang chạy với DB cũ chưa migrate.

CHẠY KHI SERVER ĐÃ TẮT.
Usage:
    python3 fix_missing_tables.py
"""
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DB_CANDIDATES = [
    os.path.join(HERE, 'src', 'main', 'resources', 'server', 'db', 'auction.db'),
    os.path.join(HERE, 'target', 'classes', 'server', 'db', 'auction.db'),
]

NOTIFICATIONS_DDL = """
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
)
"""

NOTIFICATIONS_IDX = (
    "CREATE INDEX IF NOT EXISTS idx_notifications_user "
    "ON notifications(user_id, is_read)"
)

BIDDER_INFO_DDL = """
CREATE TABLE IF NOT EXISTS bidder_info (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id      INTEGER NOT NULL UNIQUE,
    bidder_id       INTEGER NOT NULL,
    full_name       TEXT,
    phone           TEXT,
    address         TEXT,
    payment_method  TEXT,
    bank_account    TEXT,
    completed_at    DATETIME DEFAULT (datetime('now','localtime')),
    FOREIGN KEY (auction_id) REFERENCES auctions(id),
    FOREIGN KEY (bidder_id)  REFERENCES users(id)
)
"""


def fix_db(path):
    if not os.path.exists(path):
        print(f"  [SKIP] {path} (không tồn tại)")
        return
    try:
        con = sqlite3.connect(path, timeout=30)
        cur = con.cursor()
        cur.execute(NOTIFICATIONS_DDL)
        cur.execute(NOTIFICATIONS_IDX)
        cur.execute(BIDDER_INFO_DDL)
        con.commit()
        tables = [r[0] for r in cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
        print(f"  [OK]   {path}")
        print(f"         Tables: {tables}")
        con.close()
    except sqlite3.OperationalError as e:
        msg = str(e)
        if 'database is locked' in msg or 'I/O' in msg:
            print(f"  [LOCK] {path} — DB đang được server dùng. Hãy TẮT server rồi chạy lại script.")
        else:
            print(f"  [LỖI] {path}: {e}")
    except Exception as e:
        print(f"  [LỖI] {path}: {e}")


def main():
    print("=== Fix missing v2 tables ===")
    for db in DB_CANDIDATES:
        fix_db(db)
    print()
    print("Xong! Bây giờ có thể khởi động lại server bình thường.")


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)

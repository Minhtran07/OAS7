#!/usr/bin/env python3
"""
Migration script — đưa DB từ bản v1 (CHECK constraint cũ, thiếu bảng v2)
sang v2 (drop CHECK, thêm cột finished_at, thêm bảng notifications + bidder_info).

CHẠY TRƯỚC KHI KHỞI ĐỘNG SERVER.
Yêu cầu: python3.

Usage:
    python3 migrate_db_v2.py
"""
import os
import sqlite3
import shutil
import sys

DB_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    'src', 'main', 'resources', 'server', 'db', 'auction.db'
)

def main():
    if not os.path.exists(DB_PATH):
        print(f"DB không tồn tại: {DB_PATH}")
        print("Khởi động server 1 lần để tạo DB mới rồi chạy lại nếu cần.")
        return

    # Backup
    bak = DB_PATH + '.bak.pre_v2'
    shutil.copy(DB_PATH, bak)
    print(f"[1/4] Backup: {bak}")

    con = sqlite3.connect(DB_PATH, timeout=30)
    con.isolation_level = None  # autocommit cho PRAGMA + DDL
    cur = con.cursor()

    # 2. Migrate auctions: drop CHECK + add finished_at
    ddl_row = cur.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='auctions'"
    ).fetchone()
    if ddl_row:
        ddl = ddl_row[0]
        has_check = ('CHECK(status IN' in ddl) or ('CHECK (status IN' in ddl)
        has_finished_at = 'finished_at' in ddl.lower()
        if has_check:
            print("[2/4] auctions: drop CHECK + add finished_at (recreate)")
            cur.execute("PRAGMA foreign_keys=OFF")
            cur.execute("BEGIN")
            try:
                cur.execute("DROP TABLE IF EXISTS auctions_v2")
                cur.execute("""
                    CREATE TABLE auctions_v2 (
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
                    )
                """)
                cur.execute("""
                    INSERT INTO auctions_v2
                        (id, item_id, status, start_time, end_time, current_price, winner_id)
                    SELECT id, item_id, status, start_time, end_time, current_price, winner_id
                    FROM auctions
                """)
                cur.execute("DROP TABLE auctions")
                cur.execute("ALTER TABLE auctions_v2 RENAME TO auctions")
                cur.execute("COMMIT")
            except Exception as e:
                cur.execute("ROLLBACK")
                print(f"   ROLLBACK do lỗi: {e}")
                raise
            finally:
                cur.execute("PRAGMA foreign_keys=ON")
        elif not has_finished_at:
            print("[2/4] auctions: ADD COLUMN finished_at")
            cur.execute("ALTER TABLE auctions ADD COLUMN finished_at DATETIME")
        else:
            print("[2/4] auctions: đã OK (không CHECK, có finished_at)")
    else:
        print("[2/4] Chưa có bảng auctions — bỏ qua migrate.")

    # 3. Tạo notifications
    cur.execute("""
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
    """)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, is_read)")
    print("[3/4] notifications: OK")

    # 4. Tạo bidder_info
    cur.execute("""
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
    """)
    print("[4/4] bidder_info: OK")

    # Verify
    print("\n== Tables ==")
    for r in cur.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        print(" -", r[0])

    print("\n== auctions DDL ==")
    print(cur.execute(
        "SELECT sql FROM sqlite_master WHERE name='auctions'").fetchone()[0])

    con.close()
    print("\nMigration thành công! Bây giờ có thể chạy server bình thường.")


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print(f"LỖI: {e}", file=sys.stderr)
        sys.exit(1)

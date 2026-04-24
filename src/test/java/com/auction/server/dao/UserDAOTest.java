package com.auction.server.dao;

import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho UserDAO.
 * Dùng SQLite in-memory (:memory:) để tránh ảnh hưởng DB thật.
 * Mỗi test dùng 1 kết nối riêng, schema được khởi tạo lại.
 */
class UserDAOTest {

    // Kết nối in-memory giữ mở suốt test class (đóng là mất data)
    private static Connection memConn;
    private UserDAO userDAO;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        memConn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = memConn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT UNIQUE NOT NULL,
                        password TEXT NOT NULL,
                        role TEXT CHECK(role IN ('BIDDER','SELLER','ADMIN')) NOT NULL,
                        fullname TEXT NOT NULL,
                        email TEXT NOT NULL,
                        balance REAL DEFAULT 0.0,
                        store_name TEXT
                    )
                    """);
        }

        // Override DatabaseConnection để dùng in-memory
        TestDatabaseConnection.setSharedConnection(memConn);
    }

    @BeforeEach
    void clearTable() throws Exception {
        // Xoá data trước mỗi test để các test độc lập nhau
        try (Statement stmt = memConn.createStatement()) {
            stmt.execute("DELETE FROM users");
        }
        userDAO = new UserDAO();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (memConn != null && !memConn.isClosed()) {
            memConn.close();
        }
    }

    // ─── register ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Đăng ký Bidder mới → thành công")
    void register_bidder_success() {
        Bidder bidder = new Bidder(0, "alice", "pass123", "Alice Nguyen",
                "alice@test.com", BigDecimal.valueOf(500_000));

        boolean result = userDAO.register(bidder);

        assertTrue(result);
    }

    @Test
    @DisplayName("Đăng ký Seller mới → thành công")
    void register_seller_success() {
        Seller seller = new Seller(0, "bob_seller", "pass456", "Bob Tran",
                "bob@test.com", "Bob's Store");

        boolean result = userDAO.register(seller);

        assertTrue(result);
    }

    @Test
    @DisplayName("Đăng ký username trùng → thất bại (false)")
    void register_duplicateUsername_returnsFalse() {
        Bidder first  = new Bidder(0, "charlie", "pass", "Charlie", "c@test.com", BigDecimal.ZERO);
        Bidder second = new Bidder(0, "charlie", "other", "Charlie 2", "c2@test.com", BigDecimal.ZERO);

        userDAO.register(first);
        boolean result = userDAO.register(second);

        assertFalse(result);
    }

    // ─── login ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Login đúng username + password → trả về User đúng loại")
    void login_correctCredentials_returnsUser() {
        Bidder bidder = new Bidder(0, "diana", "secret", "Diana",
                "diana@test.com", BigDecimal.valueOf(1_000_000));
        userDAO.register(bidder);

        User result = userDAO.login("diana", "secret");

        assertNotNull(result);
        assertInstanceOf(Bidder.class, result);
        assertEquals("diana", result.getUsername());
        assertEquals("Diana", result.getFullname());
    }

    @Test
    @DisplayName("Login sai password → trả về null")
    void login_wrongPassword_returnsNull() {
        Bidder bidder = new Bidder(0, "eve", "correct", "Eve",
                "eve@test.com", BigDecimal.ZERO);
        userDAO.register(bidder);

        User result = userDAO.login("eve", "wrong_password");

        assertNull(result);
    }

    @Test
    @DisplayName("Login username không tồn tại → trả về null")
    void login_unknownUsername_returnsNull() {
        User result = userDAO.login("nobody", "anypass");

        assertNull(result);
    }

    @Test
    @DisplayName("Login Seller → trả về Seller với storeName đúng")
    void login_seller_returnsSellerWithStoreName() {
        Seller seller = new Seller(0, "frank", "pw", "Frank",
                "frank@test.com", "Frank Shop");
        userDAO.register(seller);

        User result = userDAO.login("frank", "pw");

        assertNotNull(result);
        assertInstanceOf(Seller.class, result);
        assertEquals("Frank Shop", ((Seller) result).getStoreName());
    }

    @Test
    @DisplayName("Login Bidder → số dư balance được khôi phục đúng")
    void login_bidder_balanceRestoredCorrectly() {
        BigDecimal balance = BigDecimal.valueOf(7_500_000);
        Bidder bidder = new Bidder(0, "grace", "pw", "Grace",
                "grace@test.com", balance);
        userDAO.register(bidder);

        User result = userDAO.login("grace", "pw");

        assertNotNull(result);
        assertEquals(balance, ((Bidder) result).getBalance());
    }
}

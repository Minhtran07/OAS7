package com.auction.server.logic;

import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.dao.TestDatabaseConnection;
import com.auction.server.model.AuctionManager;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.auction.Role;
import com.auction.shared.model.item.Art;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test ATOMIC BID — chứng minh fix #1, #2 hoạt động:
 *   - Bid update RAM + ghi DB cùng trong 1 lock
 *   - Sau bid thành công, DB phải có đúng record + current_price khớp với RAM
 *   - 100 thread bid concurrent: số record bid trong DB == số bid thành công,
 *     không có bid "treo" (RAM update mà DB miss).
 *
 * Dùng SQLite in-memory để không đụng DB thật.
 */
class AuctionManagerAtomicBidTest {

    private static Connection memConn;
    private AuctionDAO auctionDAO;
    private ItemDAO    itemDAO;
    private AuctionManager manager;

    @BeforeAll
    static void setupDb() throws Exception {
        // Mỗi test class dùng connection riêng. Ép các test khác (chạy trước/sau)
        // không can thiệp bằng cách reset DatabaseConnection.instance ở đây.
        resetDatabaseConnectionSingleton();

        memConn = DriverManager.getConnection("jdbc:sqlite::memory:");
        TestDatabaseConnection.applySchema(memConn);
        TestDatabaseConnection.setSharedConnection(memConn);
    }

    @AfterAll
    static void closeDb() throws Exception {
        // KHÔNG đóng connection ngay — class test khác có thể đang share singleton.
        // Reset singleton để class kế tiếp tự inject connection riêng.
        if (memConn != null && !memConn.isClosed()) memConn.close();
        resetDatabaseConnectionSingleton();
    }

    /** Đặt DatabaseConnection.instance = null để test kế tiếp setup mới. */
    private static void resetDatabaseConnectionSingleton() {
        try {
            java.lang.reflect.Field f =
                    com.auction.server.dao.DatabaseConnection.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void setUp() throws Exception {
        // Dọn dữ liệu giữa các test
        try (Statement st = memConn.createStatement()) {
            st.execute("DELETE FROM bids");
            st.execute("DELETE FROM auctions");
            st.execute("DELETE FROM items");
        }

        auctionDAO = new AuctionDAO();
        itemDAO    = new ItemDAO();
        manager    = AuctionManager.getInstance();
        manager.setDaos(auctionDAO, itemDAO);
    }

    /** Helper: tạo 1 phiên đấu giá thật trong DB + RAM, return auctionId. */
    private int seedAuction(double startingPrice) throws Exception {
        // Insert item thẳng vào DB
        int itemId;
        try (PreparedStatement ps = memConn.prepareStatement(
                "INSERT INTO items(name, starting_price, current_price, category, seller_id, status) " +
                "VALUES('TestItem', ?, ?, 'ART', 1, 'IN_AUCTION')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setDouble(1, startingPrice);
            ps.setDouble(2, startingPrice);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            itemId = keys.getInt(1);
        }

        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end   = LocalDateTime.now().plusHours(1);
        int auctionId = auctionDAO.createAuction(itemId, startingPrice, start, end);
        assertTrue(auctionId > 0);

        // Đăng ký vào AuctionManager
        Item item = new Art(itemId, "ART", "TestItem", 1, "Desc",
                startingPrice, startingPrice, "x", "y");
        Auction auction = new Auction(item, start, end);
        auction.setStatus(Role.RUNNING);
        manager.addAuction(auctionId, auction);
        return auctionId;
    }

    private Bidder makeBidder(int id, String name) {
        return new Bidder(id, name, "pass", name, name + "@test.com",
                BigDecimal.valueOf(100_000_000));
    }

    private int countBidsInDb(int auctionId) throws Exception {
        try (PreparedStatement ps = memConn.prepareStatement(
                "SELECT COUNT(*) FROM bids WHERE auction_id = ?")) {
            ps.setInt(1, auctionId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private double getCurrentPriceFromDb(int auctionId) throws Exception {
        try (PreparedStatement ps = memConn.prepareStatement(
                "SELECT current_price FROM auctions WHERE id = ?")) {
            ps.setInt(1, auctionId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getDouble(1);
        }
    }

    // ─── Atomic bid: RAM khớp DB ─────────────────────────────────────────────

    @Test
    @DisplayName("Sau placeBid thành công: DB có 1 record bid + current_price khớp RAM")
    void placeBid_writesDbAtomically() throws Exception {
        int auctionId = seedAuction(500_000);

        Bidder bidder = makeBidder(1, "Alice");
        boolean ok = manager.placeBid(auctionId, bidder, BigDecimal.valueOf(1_500_000));

        assertTrue(ok);

        // RAM
        Auction auction = manager.getAuction(auctionId);
        assertEquals(BigDecimal.valueOf(1_500_000), auction.getCurrentPrice());

        // DB
        assertEquals(1, countBidsInDb(auctionId), "Phải có đúng 1 record trong bảng bids");
        assertEquals(1_500_000, getCurrentPriceFromDb(auctionId),
                "current_price trong DB phải khớp RAM");
    }

    @Test
    @DisplayName("placeBid bị từ chối (giá thấp) → KHÔNG ghi DB")
    void placeBid_rejected_doesNotWriteDb() throws Exception {
        int auctionId = seedAuction(500_000);

        // Bid 1: 2M — thành công
        Bidder alice = makeBidder(1, "Alice");
        manager.placeBid(auctionId, alice, BigDecimal.valueOf(2_000_000));

        // Bid 2: 1.5M — bị từ chối
        Bidder bob = makeBidder(2, "Bob");
        boolean rejected = manager.placeBid(auctionId, bob, BigDecimal.valueOf(1_500_000));

        assertFalse(rejected);
        assertEquals(1, countBidsInDb(auctionId),
                "Bid bị từ chối không được ghi DB");
        assertEquals(2_000_000, getCurrentPriceFromDb(auctionId));
    }

    // ─── Concurrent atomic bid ───────────────────────────────────────────────

    @Test
    @DisplayName("100 thread bid concurrent: số bid thành công == số record DB (atomic)")
    void placeBid_concurrent_dbConsistentWithRam() throws Exception {
        int auctionId = seedAuction(500_000);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 1; i <= threads; i++) {
            final int amount = i * 100_000; // 100k → 10M
            final int bidderId = i;
            pool.submit(() -> {
                try {
                    latch.await();
                    Bidder b = makeBidder(bidderId, "B" + bidderId);
                    boolean ok = manager.placeBid(auctionId, b, BigDecimal.valueOf(amount));
                    if (ok) successCount.incrementAndGet();
                } catch (InterruptedException ignored) {}
            });
        }

        latch.countDown();
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);

        // INVARIANT: số bid thành công trong RAM == số record trong DB
        int dbCount = countBidsInDb(auctionId);
        assertEquals(successCount.get(), dbCount,
                "Số bid thành công trong RAM phải bằng số record trong DB (atomic)");

        // Giá cuối trong RAM phải khớp DB
        double dbPrice = getCurrentPriceFromDb(auctionId);
        double ramPrice = manager.getAuction(auctionId).getCurrentPrice().doubleValue();
        assertEquals(ramPrice, dbPrice,
                "current_price RAM phải khớp DB sau concurrent bid");

        // Phải có ít nhất 1 bid thành công (giá cuối != startingPrice)
        assertTrue(dbPrice > 500_000, "Phải có ít nhất 1 bid thành công");
    }

    @Test
    @DisplayName("Snapshot dưới lock: state RAM khớp với state DB sau bid")
    void snapshot_consistentWithDb() throws Exception {
        int auctionId = seedAuction(500_000);

        Bidder bidder = makeBidder(99, "Charlie");
        manager.placeBid(auctionId, bidder, BigDecimal.valueOf(3_000_000));

        AuctionManager.Snapshot snap = manager.snapshot(auctionId);
        assertNotNull(snap);
        assertEquals(BigDecimal.valueOf(3_000_000), snap.currentPrice);
        assertEquals(99, snap.winnerId);
        assertEquals("Charlie", snap.winnerName);

        // So với DB
        assertEquals(snap.currentPrice.doubleValue(), getCurrentPriceFromDb(auctionId));
    }
}

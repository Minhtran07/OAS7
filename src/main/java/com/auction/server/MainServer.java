package com.auction.server;
// phụ trách điều phối các luồng tiếp nhận và phản hồi yêu cầu của client
import com.auction.server.core.ClientHandler;
import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.model.AuctionManager;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainServer {
    private static final Logger logger = LoggerFactory.getLogger(MainServer.class);
    private static final int PORT = resolvePort();
    private static final ExecutorService pool = Executors.newFixedThreadPool(50);

    /**
     * Port được lấy theo thứ tự:
     *   -Dauction.server.port > AUCTION_SERVER_PORT > 3667
     */
    private static int resolvePort() {
        String v = System.getProperty("auction.server.port");
        if (v == null || v.isBlank()) v = System.getenv("AUCTION_SERVER_PORT");
        try {
            if (v != null && !v.isBlank()) return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignore) {}
        return 3667;
    }

    public static void main(String[] args) {
        logger.info("Đang khởi động Máy chủ Đấu giá trực tuyến trên cổng {}...", PORT);
        printLocalIps();

        // Load tất cả phiên đang hoạt động từ DB vào AuctionManager
        loadActiveAuctions();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info("Máy chủ đã sẵn sàng. Đang chờ kết nối từ Client...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("Phát hiện kết nối mới từ: {}", clientSocket.getInetAddress().getHostAddress());

                ClientHandler clientThread = new ClientHandler(clientSocket);
                pool.execute(clientThread);
                // tạo 1 luồng từ clienthandler
            }
            // vòng lặp vô hạn cho server mạng
        } catch (IOException e) {
            logger.error("Lỗi khi khởi động máy chủ: {}", e.getMessage(), e);
        }
    }

    /**
     * Liệt kê các IPv4 non-loopback của máy chạy server.
     * Giúp admin biết client ở máy khác trong LAN phải trỏ đến IP nào.
     */
    private static void printLocalIps() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets =
                    java.net.NetworkInterface.getNetworkInterfaces();
            logger.info("Các địa chỉ IP máy chủ đang lắng nghe (dùng IP này cho client ở máy khác):");
            boolean any = false;
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress addr = ia.getAddress();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        logger.info("  → {}:{}  ({})", addr.getHostAddress(), PORT, ni.getDisplayName());
                        any = true;
                    }
                }
            }
            if (!any) {
                logger.info("  (không tìm thấy interface mạng non-loopback)");
            }
        } catch (Exception e) {
            logger.warn("Không liệt kê được IP local: {}", e.getMessage());
        }
    }

    /**
     * Load tất cả phiên OPEN/RUNNING từ DB vào AuctionManager khi server khởi động.
     * Dùng item giả (null-safe) vì AuctionManager chỉ cần giá và thời gian.
     */
    private static void loadActiveAuctions() {
        try {
            AuctionDAO auctionDAO = new AuctionDAO();
            ItemDAO    itemDAO    = new ItemDAO();
            List<AuctionDAO.AuctionRow> rows = auctionDAO.getAllAuctions();

            int loaded = 0;
            for (AuctionDAO.AuctionRow row : rows) {
                if ("FINISHED".equals(row.status) || "CLOSED".equals(row.status)) continue;

                // Lấy item từ DB để có startingPrice
                Item item = itemDAO.findById(row.itemId);
                if (item == null) continue;

                LocalDateTime start = LocalDateTime.parse(row.startTime);
                LocalDateTime end   = LocalDateTime.parse(row.endTime);

                Auction auction = new Auction(item, start, end);
                auction.setCurrentPrice(java.math.BigDecimal.valueOf(row.currentPrice));
                AuctionManager.getInstance().addAuction(row.id, auction);
                loaded++;
            }
            logger.info("Đã load {} phiên đấu giá vào AuctionManager.", loaded);
        } catch (Exception e) {
            logger.error("Lỗi khi load auction từ DB: {}", e.getMessage(), e);
        }
    }
}
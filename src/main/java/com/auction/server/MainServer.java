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
    private static final int PORT = 3667;
    private static final ExecutorService pool = Executors.newFixedThreadPool(50);

    public static void main(String[] args) {
        logger.info("Đang khởi động Máy chủ Đấu giá trực tuyến trên cổng {}...", PORT);

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
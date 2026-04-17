package com.auction.server;

import com.auction.server.core.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainServer {
    private static final Logger logger = LoggerFactory.getLogger(MainServer.class);
    private static final int PORT = 8080;
    private static final ExecutorService pool = Executors.newFixedThreadPool(50);

    public static void main(String[] args) {
        logger.info("Đang khởi động Máy chủ Đấu giá trực tuyến trên cổng {}...", PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info("Máy chủ đã sẵn sàng. Đang chờ kết nối từ Client...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                logger.info("Phát hiện kết nối mới từ: {}", clientSocket.getInetAddress().getHostAddress());

                ClientHandler clientThread = new ClientHandler(clientSocket);
                pool.execute(clientThread);
            }
        } catch (IOException e) {
            logger.error("Lỗi khi khởi động máy chủ: {}", e.getMessage(), e);
        }
    }
}
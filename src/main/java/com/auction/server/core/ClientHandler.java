package com.auction.server.core;

import com.auction.shared.network.Request;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final Gson gson;
    private PrintWriter out;
    private BufferedReader in;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.gson = new Gson();
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                logger.info("Nhận được gói tin thô: {}", inputLine);

                Request request = gson.fromJson(inputLine, Request.class);

                Response response = new Response("SUCCESS", "Đã nhận yêu cầu: " + request.getAction(), null);

                String jsonResponse = gson.toJson(response);
                out.println(jsonResponse);
            }
        } catch (IOException e) {
            logger.warn("Mất kết nối với Client: {}", socket.getInetAddress().getHostAddress());
        } finally {
            closeConnections();
        }
    }

    private void closeConnections() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
            logger.info("Đã dọn dẹp luồng kết nối.");
        } catch (IOException e) {
            logger.error("Lỗi khi đóng kết nối", e);
        }
    }
}
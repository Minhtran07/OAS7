package com.auction.server.core;
//luồng riêng cho 1 client để yêu cầu và nhận phản hồi
import com.auction.server.dao.UserDAO;
import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;
import com.auction.shared.network.Request;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.Socket;

public class ClientHandler implements Runnable { // implements Runnable để biến class thành thread
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket; // ống mạng
    private final Gson gson; // dịch ngôn ngữ
    private PrintWriter out; // gửi data
    private BufferedReader in; // nhận data

    private final UserDAO userDAO;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.gson = new Gson();
        this.userDAO = new UserDAO();
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            // mở khóa luồng dữ liệu vào và ra của socket
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                logger.info("Nhận được gói tin thô: {}", inputLine);

                Request request = gson.fromJson(inputLine, Request.class);

                Response response;
                switch (request.getAction()) {
                    case "LOGIN":
                        response = handleLogin(request.getPayload());
                        break;
                    case "REGISTER":
                        response = handleRegister(request.getPayload());
                        break;
                    case "PLACE_BID":
                        response = handlePlaceBid(request.getPayload());
                        break;
                    default:
                        response = new Response("ERROR", "Hệ thống không hiểu lệnh: " + request.getAction(), null);
                        break;
                }

                String jsonResponse = gson.toJson(response);
                out.println(jsonResponse);
            }
            // nhận chuỗi json -> biến thành đối tượng request -> xử lý -> tạo đối tượng response -> biến thành chuỗi json -> gửi đi
        } catch (IOException e) {
            logger.warn("Mất kết nối với Client: {}", socket.getInetAddress().getHostAddress());
            // client thoát hoặc văng
        } finally {
            closeConnections();
        }
    }

    private Response handleLogin(String payload) {
        try {
            JsonObject credentials = gson.fromJson(payload, JsonObject.class);
            String username = credentials.get("username").getAsString();
            String password = credentials.get("password").getAsString();

            User loggedInUser = userDAO.login(username, password);

            if (loggedInUser != null) {
                String userJson = gson.toJson(loggedInUser);
                return new Response("SUCCESS", "Đăng nhập thành công!", userJson);
            } else {
                return new Response("FAIL", "Sai tài khoản hoặc mật khẩu!", null);
            }
        } catch (Exception e) {
            logger.error("Lỗi khi xử lý Đăng nhập", e);
            return new Response("ERROR", "Dữ liệu gửi lên không đúng định dạng", null);
        }
    }

    private Response handleRegister(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);

            String username = data.get("username").getAsString();
            String password = data.get("password").getAsString();
            String fullname = data.get("fullname").getAsString();
            String email = data.get("email").getAsString();
            String role = data.get("role").getAsString().toUpperCase();

            User newUser = null;

            if (role.equals("BIDDER")) {
                // Nếu là Bidder, mặc định tạo tài khoản có 0 đồng (hoặc lấy từ JSON nếu có)
                BigDecimal initialBalance = new BigDecimal("0");
                newUser = new Bidder(id, username, password, fullname, email, initialBalance);

            } else if (role.equals("SELLER")) {
                String storeName = data.has("storeName") ? data.get("storeName").getAsString() : "Cửa hàng của " + fullname;
                newUser = new Seller( username, password, fullname, email, storeName);

            } else {
                return new Response("FAIL", "Lỗi: Role đăng ký không hợp lệ! Chỉ nhận BIDDER hoặc SELLER.", null);
            }

            boolean isSuccess = userDAO.register(newUser);

            if (isSuccess) {
                return new Response("SUCCESS", "Đăng ký tài khoản thành công!", null);
            } else {
                return new Response("FAIL", "Đăng ký thất bại! Có thể Username đã tồn tại.", null);
            }

        } catch (Exception e) {
            logger.error("Lỗi khi xử lý Đăng ký", e);
            return new Response("ERROR", "Dữ liệu JSON gửi lên bị thiếu trường hoặc sai định dạng", null);
        }
    }

    private Response handlePlaceBid(String payload) {
        return new Response("SUCCESS", "Đã nhận lệnh đặt giá", null);
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
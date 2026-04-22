package com.auction.client;

import com.auction.shared.network.Request;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class TestClient {
    public static void main(String[] args) {
        try (Socket socket = new Socket("localhost", 3667);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("Đã kết nối tới Server thành công!");
            Gson gson = new Gson();

            System.out.println("\nĐANG TEST ĐĂNG KÝ");
            JsonObject regData = new JsonObject();
            regData.addProperty("username", "testuser1");
            regData.addProperty("password", "123456");
            regData.addProperty("fullname", "Người Dùng Test");
            regData.addProperty("email", "test@gmail.com");
            regData.addProperty("role", "BIDDER");

            // Đóng gói thành Request và bắn đi
            Request regReq = new Request("REGISTER", regData.toString());
            out.println(gson.toJson(regReq));

            // Chờ Server
            System.out.println("Nhận từ Server: " + in.readLine());

            // KỊCH BẢN 2: TEST ĐĂNG NHẬP

            System.out.println("\n--- ĐANG TEST ĐĂNG NHẬP ---");
            JsonObject loginData = new JsonObject();
            loginData.addProperty("username", "testuser1");
            loginData.addProperty("password", "123456");

            // Đóng gói thành Request và bắn đi
            Request loginReq = new Request("LOGIN", loginData.toString());
            out.println(gson.toJson(loginReq));

            // Chờ Server gửi thông tin User về
            System.out.println("Nhận từ Server: " + in.readLine());

        } catch (Exception e) {
            System.err.println("Lỗi kết nối! Đã bật MainServer chưa ông ơi?");
            e.printStackTrace();
        }
    }
}
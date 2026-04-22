package com.auction.shared.network;
// FE ghi yc ở đây
public class Request {
    private String action; // action có thể là login, register,place_bid,...
    private String payload; // chứa dữ liệu chi tiết tương ứng với action

    public Request(String action, String payload) {
        this.action = action;
        this.payload = payload;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
    /* Giao diện thực hiện hành động (ví dụ đăng nhập)
    VAnh gom hết dữ liệu thành chuỗi json
    tạo đối tượng Request req = new Request("LOGIN", chuỗi_json_ở_bước_2);
    VAnh gom cả cái req đó bắn qua cổng mạng
    ClientHandler nhận được, dùng gson.fromJson(..., Request.class) để dịch nó thành một đối tượng Request trong RAM.
    lấy request.getAction() ra đọc
    đưa dữ liệu cho minh ktra database

     */
}

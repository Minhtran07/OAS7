package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.client.util.SceneUtil;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Màn hình "Giỏ đấu giá" — hiển thị các phiên user đã tham gia bid.
 * Server endpoint: GET_MY_BIDS với payload { bidderId }.
 *
 * Mỗi row hiện:
 *  - Tên sản phẩm + category
 *  - Status badge (đang dẫn / bị vượt / đã thắng / đã thua / đang chờ)
 *  - Giá hiện tại + giá cao nhất tôi đã đặt + số lần bid
 *  - Countdown nếu đang chạy, hoặc kết quả nếu đã kết thúc
 *  - Nút "Vào phiên" (nếu chưa kết thúc) hoặc "Xem kết quả"
 */
public class ControllerMyCart {

    @FXML private Label      welcomeLabel;
    @FXML private Label      statusLabel;
    @FXML private VBox       cartContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox       emptyState;

    private final Gson gson = new Gson();
    private final List<MyBid> data = new ArrayList<>();
    private Timeline countdownTimer;

    /** Offset đồng hồ client-server. */
    private volatile java.time.Duration clockOffset = java.time.Duration.ZERO;
    private LocalDateTime serverNow() { return LocalDateTime.now().plus(clockOffset); }

    @FXML
    public void initialize() {
        if (UserSession.getInstance().isLoggedIn()) {
            welcomeLabel.setText("Xin chào, " + UserSession.getInstance().getCurrentUser().getFullname());
        }

        countdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshCountdowns()));
        countdownTimer.setCycleCount(Timeline.INDEFINITE);
        countdownTimer.play();

        loadMyBids();
    }

    @FXML
    private void handleRefresh() {
        loadMyBids();
    }

    @FXML
    private void handleBack(ActionEvent event) throws IOException {
        stopTimer();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/auction_list.fxml"),
                "Online Auction System");
    }

    private void stopTimer() {
        if (countdownTimer != null) countdownTimer.stop();
    }

    // ─── Load ────────────────────────────────────────────────────────────────

    private void loadMyBids() {
        statusLabel.setText("Đang tải...");
        data.clear();
        cartContainer.getChildren().clear();

        if (!UserSession.getInstance().isLoggedIn()) {
            statusLabel.setText("Bạn cần đăng nhập để xem giỏ đấu giá.");
            return;
        }
        int bidderId = UserSession.getInstance().getCurrentUser().getId();

        JsonObject payload = new JsonObject();
        payload.addProperty("bidderId", bidderId);
        final String reqPayload = payload.toString();

        new Thread(() -> {
            Response resp = ServerConnection.getInstance().sendRequest("GET_MY_BIDS", reqPayload);
            Platform.runLater(() -> {
                if ("SUCCESS".equals(resp.getStatus()) && resp.getPayload() != null) {
                    try {
                        JsonObject obj = gson.fromJson(resp.getPayload(), JsonObject.class);
                        // Cập nhật offset đồng hồ
                        if (obj.has("serverNow") && !obj.get("serverNow").isJsonNull()) {
                            try {
                                LocalDateTime srv = LocalDateTime.parse(obj.get("serverNow").getAsString());
                                clockOffset = java.time.Duration.between(LocalDateTime.now(), srv);
                            } catch (Exception ignore) {}
                        }
                        JsonArray arr = obj.has("bids") && obj.get("bids").isJsonArray()
                                ? obj.getAsJsonArray("bids") : new JsonArray();
                        data.addAll(parse(arr));
                    } catch (Exception e) {
                        statusLabel.setText("Lỗi parse dữ liệu.");
                        return;
                    }
                    renderRows();
                } else {
                    statusLabel.setText("Lỗi: " + (resp.getMessage() != null ? resp.getMessage() : "không rõ"));
                    renderRows();
                }
            });
        }).start();
    }

    private List<MyBid> parse(JsonArray arr) {
        List<MyBid> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            MyBid b = new MyBid();
            b.auctionId    = o.get("auctionId").getAsInt();
            b.itemId       = o.has("itemId") ? o.get("itemId").getAsInt() : 0;
            b.itemName     = getStr(o, "itemName");
            b.itemCategory = getStr(o, "itemCategory");
            b.status       = getStr(o, "status");
            b.endTime      = getStr(o, "endTime");
            b.currentPrice = o.has("currentPrice") ? o.get("currentPrice").getAsDouble() : 0;
            b.winnerId     = o.has("winnerId") ? o.get("winnerId").getAsInt() : 0;
            b.winnerName   = getStr(o, "winnerName");
            b.myMaxBid     = o.has("myMaxBid") ? o.get("myMaxBid").getAsDouble() : 0;
            b.myBidCount   = o.has("myBidCount") ? o.get("myBidCount").getAsInt() : 0;
            b.leading      = o.has("leading") && o.get("leading").getAsBoolean();
            list.add(b);
        }
        return list;
    }

    private static String getStr(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private void renderRows() {
        cartContainer.getChildren().clear();

        boolean empty = data.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        scrollPane.setVisible(!empty);
        scrollPane.setManaged(!empty);

        if (empty) {
            statusLabel.setText("Chưa có phiên nào trong giỏ.");
            return;
        }

        statusLabel.setText("Tổng: " + data.size() + " phiên đã tham gia");
        for (MyBid b : data) {
            cartContainer.getChildren().add(buildRow(b));
        }
    }

    private HBox buildRow(MyBid b) {
        boolean finished = isFinished(b.status);
        int myId = UserSession.getInstance().getCurrentUser().getId();
        boolean iWon = finished && b.winnerId == myId;
        boolean iLost = finished && b.winnerId > 0 && b.winnerId != myId;

        HBox row = new HBox(16);
        row.setStyle("-fx-background-color: white; -fx-background-radius: 12; "
                + "-fx-padding: 18 22; -fx-border-color: " + (iWon ? "#10b981" : "#e9ecef") + "; "
                + "-fx-border-width: " + (iWon ? "2" : "1") + "; -fx-border-radius: 12; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        // Emoji block
        Label emoji = new Label(emojiFor(b.itemCategory));
        emoji.setStyle("-fx-font-size: 40px; -fx-min-width: 60;");

        // Center info
        VBox info = new VBox(4);
        HBox.setHgrow(info, Priority.ALWAYS);

        String displayName = (b.itemName != null && !b.itemName.isBlank())
                ? b.itemName : "Sản phẩm #" + b.itemId;
        Label name = new Label(displayName);
        name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a1a2e;");
        name.setWrapText(true);

        HBox badges = new HBox(6);
        Label catBadge = new Label(categoryLabel(b.itemCategory));
        catBadge.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4338ca; "
                + "-fx-padding: 3 9; -fx-background-radius: 10; "
                + "-fx-font-size: 10px; -fx-font-weight: bold;");
        Label myStatus = new Label(myStatusText(b, iWon, iLost));
        myStatus.setStyle(myStatusStyle(b, iWon, iLost));
        badges.getChildren().addAll(catBadge, myStatus);

        Label bidInfo = new Label(String.format(
                "💰 Giá tôi đã đặt: %,.0f VNĐ  •  Số lần đặt: %d", b.myMaxBid, b.myBidCount));
        bidInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #495057;");

        Label currentInfo;
        if (finished) {
            String winnerText = (b.winnerName != null && !b.winnerName.isBlank())
                    ? "👑 Người thắng: " + b.winnerName + "  •  Giá thắng: "
                          + String.format("%,.0f VNĐ", b.currentPrice)
                    : "Không có người thắng";
            currentInfo = new Label(winnerText);
        } else {
            currentInfo = new Label(String.format("📈 Giá hiện tại: %,.0f VNĐ  •  ⏰ %s",
                    b.currentPrice, formatRemaining(b.endTime)));
            currentInfo.setUserData(b.endTime);
            currentInfo.getProperties().put("role", "countdown");
            currentInfo.getProperties().put("price", b.currentPrice);
        }
        currentInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: "
                + (finished ? "#495057" : "#0f3460") + "; -fx-font-weight: bold;");
        currentInfo.setWrapText(true);

        info.getChildren().addAll(name, badges, bidInfo, currentInfo);

        // Action buttons
        VBox actions = new VBox(6);
        actions.setStyle("-fx-min-width: 140;");
        Button btn = new Button(finished ? "Xem kết quả" : "Vào phiên →");
        btn.setMaxWidth(Double.MAX_VALUE);
        String color = finished ? "#6c757d" : "#1a1a2e";
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand; "
                + "-fx-background-radius: 8; -fx-padding: 10 16;");
        btn.setOnAction(e -> openBidding(b, e));
        actions.getChildren().add(btn);

        Region spacer = new Region();
        Region rightSpacer = new Region();
        row.getChildren().addAll(emoji, info, rightSpacer, actions);
        HBox.setHgrow(rightSpacer, Priority.NEVER);
        return row;
    }

    private void openBidding(MyBid b, ActionEvent event) {
        try {
            stopTimer();
            FXMLLoader loader = new FXMLLoader(
                    MainClient.class.getResource("/client/fxml/bidding.fxml"));
            Parent root = loader.load();
            ControllerBidding controller = loader.getController();
            controller.setAuctionData(b.auctionId, b.itemId, b.currentPrice, b.endTime);

            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            double w = stage.getWidth();
            double h = stage.getHeight();
            stage.setScene(new Scene(root, w, h));
            stage.setTitle("Đấu giá — " + (b.itemName != null ? b.itemName : "Phiên #" + b.auctionId));
            stage.show();
        } catch (IOException e) {
            statusLabel.setText("Không thể mở màn hình đấu giá.");
        }
    }

    // ─── Countdown ───────────────────────────────────────────────────────────

    private void refreshCountdowns() {
        for (Node row : cartContainer.getChildren()) {
            if (!(row instanceof HBox)) continue;
            findCountdownLabels((javafx.scene.Parent) row);
        }
    }

    private void findCountdownLabels(javafx.scene.Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Label label) {
                Object role = label.getProperties().get("role");
                if ("countdown".equals(role) && label.getUserData() instanceof String endTime) {
                    Object price = label.getProperties().get("price");
                    double p = price instanceof Double ? (Double) price : 0;
                    label.setText(String.format("📈 Giá hiện tại: %,.0f VNĐ  •  ⏰ %s",
                            p, formatRemaining(endTime)));
                }
            }
            if (child instanceof javafx.scene.Parent p) {
                findCountdownLabels(p);
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isFinished(String s) {
        return "FINISHED".equalsIgnoreCase(s) || "CLOSED".equalsIgnoreCase(s);
    }

    private String myStatusText(MyBid b, boolean iWon, boolean iLost) {
        if (iWon)  return "🏆 Đã thắng";
        if (iLost) return "💔 Đã thua";
        if ("RUNNING".equalsIgnoreCase(b.status)) {
            return b.leading ? "👑 Đang dẫn đầu" : "⚠ Đã bị vượt qua";
        }
        if ("OPEN".equalsIgnoreCase(b.status)) return "⏳ Sắp diễn ra";
        return b.status == null ? "—" : b.status;
    }

    private String myStatusStyle(MyBid b, boolean iWon, boolean iLost) {
        String bg, fg;
        if (iWon)            { bg = "#ecfdf5"; fg = "#047857"; }
        else if (iLost)      { bg = "#fef2f2"; fg = "#b91c1c"; }
        else if ("RUNNING".equalsIgnoreCase(b.status) && b.leading) { bg = "#ecfdf5"; fg = "#047857"; }
        else if ("RUNNING".equalsIgnoreCase(b.status))              { bg = "#fffbeb"; fg = "#b45309"; }
        else                                                        { bg = "#f1f3f5"; fg = "#495057"; }
        return "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
             + "-fx-padding: 3 9; -fx-background-radius: 10; "
             + "-fx-font-size: 10px; -fx-font-weight: bold;";
    }

    private String emojiFor(String c) {
        if (c == null) return "🎁";
        return switch (c.toUpperCase()) {
            case "ART"         -> "🎨";
            case "ELECTRONICS" -> "💻";
            case "VEHICLE"     -> "🚗";
            default            -> "🎁";
        };
    }

    private String categoryLabel(String c) {
        if (c == null) return "SẢN PHẨM";
        return switch (c.toUpperCase()) {
            case "ART"         -> "NGHỆ THUẬT";
            case "ELECTRONICS" -> "ĐIỆN TỬ";
            case "VEHICLE"     -> "XE CỘ";
            default            -> c.toUpperCase();
        };
    }

    private String formatRemaining(String endTimeStr) {
        try {
            LocalDateTime end = LocalDateTime.parse(endTimeStr);
            LocalDateTime now = serverNow();
            if (!now.isBefore(end)) return "Đã kết thúc";
            long total = java.time.Duration.between(now, end).getSeconds();
            long d = total / 86400, h = (total % 86400) / 3600,
                 m = (total % 3600) / 60,  s = total % 60;
            if (d > 0) return String.format("Còn %dd %dh %dm", d, h, m);
            if (h > 0) return String.format("Còn %dh %dm %ds", h, m, s);
            if (m > 0) return String.format("Còn %dm %ds", m, s);
            return String.format("Còn %ds", s);
        } catch (Exception e) {
            return endTimeStr;
        }
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────
    public static class MyBid {
        public int    auctionId;
        public int    itemId;
        public String itemName;
        public String itemCategory;
        public String status;
        public String endTime;
        public double currentPrice;
        public int    winnerId;
        public String winnerName;
        public double myMaxBid;
        public int    myBidCount;
        public boolean leading;
    }
}

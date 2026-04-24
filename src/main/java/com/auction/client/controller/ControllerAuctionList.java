package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.shared.model.user.Role;
import com.auction.shared.network.Response;

import com.auction.client.util.SceneUtil;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ControllerAuctionList {

    @FXML private Label     welcomeLabel;
    @FXML private Label     statusLabel;
    @FXML private Button    sellerDashboardButton;
    @FXML private FlowPane  cardContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox      emptyState;

    private final Gson gson = new Gson();
    private final List<AuctionItem> data = new ArrayList<>();
    private Timeline countdownTimer;

    @FXML
    public void initialize() {
        // Welcome
        if (UserSession.getInstance().isLoggedIn()) {
            welcomeLabel.setText("Xin chào, " + UserSession.getInstance().getCurrentUser().getFullname());
        }

        // Seller button chỉ với SELLER
        if (sellerDashboardButton != null && UserSession.getInstance().isLoggedIn()) {
            boolean isSeller = UserSession.getInstance().getCurrentUser().getRole() == Role.SELLER;
            sellerDashboardButton.setVisible(isSeller);
            sellerDashboardButton.setManaged(isSeller);
        }

        // Timer cập nhật countdown mỗi giây (chỉ refresh text, không gọi server)
        countdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshCountdowns()));
        countdownTimer.setCycleCount(Timeline.INDEFINITE);
        countdownTimer.play();

        loadAuctions();
    }

    // ─── Refresh / Nav ───────────────────────────────────────────────────────

    @FXML
    private void handleRefresh() {
        loadAuctions();
    }

    @FXML
    private void handleSellerDashboard(ActionEvent event) throws IOException {
        stopTimer();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/seller_dashboard.fxml"),
                "Seller Dashboard — Quản lý sản phẩm");
    }

    @FXML
    private void handleLogout(ActionEvent event) throws IOException {
        stopTimer();
        UserSession.getInstance().logout();
        ServerConnection.getInstance().disconnect();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/home.fxml"),
                "Online Auction System");
    }

    private void stopTimer() {
        if (countdownTimer != null) countdownTimer.stop();
    }

    // ─── Load data ───────────────────────────────────────────────────────────

    private void loadAuctions() {
        statusLabel.setText("Đang tải...");
        data.clear();
        cardContainer.getChildren().clear();

        new Thread(() -> {
            Response response = ServerConnection.getInstance().sendRequest("GET_AUCTIONS", "{}");

            Platform.runLater(() -> {
                if ("SUCCESS".equals(response.getStatus()) && response.getPayload() != null) {
                    List<AuctionItem> items = parseAuctions(response.getPayload());
                    data.addAll(items);
                    renderCards();
                    statusLabel.setText(items.isEmpty()
                            ? "Hiện chưa có phiên nào — chờ seller đăng bán"
                            : items.size() + " phiên đấu giá đang diễn ra");
                } else {
                    statusLabel.setText("Lỗi: " + (response.getMessage() != null ? response.getMessage() : "không rõ"));
                    renderCards(); // show empty state
                }
            });
        }).start();
    }

    private List<AuctionItem> parseAuctions(String json) {
        List<AuctionItem> list = new ArrayList<>();
        try {
            JsonArray arr = gson.fromJson(json, JsonArray.class);
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                AuctionItem ai = new AuctionItem();
                ai.id           = obj.get("id").getAsInt();
                ai.itemId       = obj.get("itemId").getAsInt();
                if (obj.has("itemName") && !obj.get("itemName").isJsonNull()) {
                    ai.itemName = obj.get("itemName").getAsString();
                }
                if (obj.has("itemCategory") && !obj.get("itemCategory").isJsonNull()) {
                    ai.itemCategory = obj.get("itemCategory").getAsString();
                }
                if (obj.has("itemDescription") && !obj.get("itemDescription").isJsonNull()) {
                    ai.itemDescription = obj.get("itemDescription").getAsString();
                }
                ai.currentPrice = obj.get("currentPrice").getAsDouble();
                ai.status       = obj.get("status").getAsString();
                ai.endTime      = obj.get("endTime").getAsString();
                list.add(ai);
            }
        } catch (Exception e) {
            statusLabel.setText("Lỗi parse dữ liệu từ server.");
        }
        return list;
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private void renderCards() {
        cardContainer.getChildren().clear();

        boolean empty = data.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        scrollPane.setVisible(!empty);
        scrollPane.setManaged(!empty);

        if (empty) return;

        for (AuctionItem item : data) {
            cardContainer.getChildren().add(buildCard(item));
        }
    }

    /**
     * Tạo 1 card cho phiên đấu giá. Cấu trúc:
     *  ┌───────────────────────┐
     *  │     [Emoji lớn]       │   ← hero block màu gradient theo category
     *  │                       │
     *  ├───────────────────────┤
     *  │ Tên sản phẩm          │
     *  │ Category badge        │
     *  │ Giá hiện tại          │
     *  │ ⏰ Còn lại: 2h 15m    │
     *  │ [Vào đấu giá]         │
     *  └───────────────────────┘
     */
    private VBox buildCard(AuctionItem item) {
        VBox card = new VBox();
        card.setPrefWidth(260);
        card.setMaxWidth(260);
        card.setStyle("-fx-background-color: white; "
                + "-fx-background-radius: 12; "
                + "-fx-border-radius: 12; "
                + "-fx-border-color: #e9ecef; "
                + "-fx-border-width: 1; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 10, 0, 0, 3);");

        // ── Hero block ───────────────────────────────────────────────────
        StackPane hero = new StackPane();
        hero.setPrefHeight(140);
        String heroGradient = heroGradientForCategory(item.itemCategory);
        hero.setStyle("-fx-background-color: " + heroGradient + "; "
                + "-fx-background-radius: 12 12 0 0;");
        Label emojiLabel = new Label(emojiForCategory(item.itemCategory));
        emojiLabel.setStyle("-fx-font-size: 64px;");
        hero.getChildren().add(emojiLabel);

        // ── Body ─────────────────────────────────────────────────────────
        VBox body = new VBox(8);
        body.setStyle("-fx-padding: 16 16 18 16;");

        // Tên sản phẩm
        String displayName = (item.itemName != null && !item.itemName.isBlank())
                ? item.itemName : "Sản phẩm #" + item.itemId;
        Label name = new Label(displayName);
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1a1a2e;");
        name.setWrapText(true);

        // Badge category
        HBox badgeRow = new HBox(6);
        Label catBadge = new Label(categoryLabel(item.itemCategory));
        catBadge.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4338ca; "
                + "-fx-padding: 3 9; -fx-background-radius: 10; "
                + "-fx-font-size: 10px; -fx-font-weight: bold;");
        Label statusBadge = new Label("● " + ("RUNNING".equals(item.status) ? "Đang diễn ra" : "Mở"));
        statusBadge.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #047857; "
                + "-fx-padding: 3 9; -fx-background-radius: 10; "
                + "-fx-font-size: 10px; -fx-font-weight: bold;");
        badgeRow.getChildren().addAll(catBadge, statusBadge);

        // Giá
        Label priceLabel = new Label("Giá hiện tại");
        priceLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #6c757d; "
                + "-fx-padding: 6 0 0 0;");
        Label priceValue = new Label(String.format("%,.0f VNĐ", item.currentPrice));
        priceValue.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #e94560;");

        // Countdown — cập nhật bởi timeline. Lưu vào userData để refresh tìm lại.
        Label countdown = new Label("⏰ " + formatRemaining(item.endTime));
        countdown.setStyle("-fx-font-size: 11px; -fx-text-fill: #495057; -fx-font-weight: bold;");
        countdown.setUserData(item.endTime);
        countdown.getProperties().put("role", "countdown");

        // Nút
        Button btn = new Button("Vào đấu giá →");
        btn.setMaxWidth(Double.MAX_VALUE);
        String baseStyle = "-fx-background-color: #1a1a2e; -fx-text-fill: white; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand; "
                + "-fx-background-radius: 8; -fx-padding: 10 16;";
        btn.setStyle(baseStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(baseStyle.replace("#1a1a2e", "#e94560")));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        btn.setOnAction(e -> openBiddingScreen(item, e));

        Region spacer = new Region();
        spacer.setMinHeight(4);

        body.getChildren().addAll(name, badgeRow, priceLabel, priceValue, countdown, spacer, btn);
        VBox.setVgrow(body, Priority.ALWAYS);

        card.getChildren().addAll(hero, body);
        return card;
    }

    // ─── Countdown refresh ────────────────────────────────────────────────────

    private void refreshCountdowns() {
        for (Node card : cardContainer.getChildren()) {
            if (!(card instanceof VBox cardBox)) continue;
            findCountdownLabels(cardBox);
        }
    }

    private void findCountdownLabels(Node node) {
        if (node instanceof Label label) {
            Object role = label.getProperties().get("role");
            if ("countdown".equals(role) && label.getUserData() instanceof String endTime) {
                label.setText("⏰ " + formatRemaining(endTime));
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                findCountdownLabels(child);
            }
        }
    }

    // ─── Helpers: emoji / gradient / format ─────────────────────────────────

    private String emojiForCategory(String category) {
        if (category == null) return "🎁";
        return switch (category.toUpperCase()) {
            case "ART"         -> "🎨";
            case "ELECTRONICS" -> "💻";
            case "VEHICLE"     -> "🚗";
            default            -> "🎁";
        };
    }

    private String categoryLabel(String category) {
        if (category == null) return "SẢN PHẨM";
        return switch (category.toUpperCase()) {
            case "ART"         -> "NGHỆ THUẬT";
            case "ELECTRONICS" -> "ĐIỆN TỬ";
            case "VEHICLE"     -> "XE CỘ";
            default            -> category.toUpperCase();
        };
    }

    private String heroGradientForCategory(String category) {
        if (category == null) return "linear-gradient(to bottom right, #667eea, #764ba2)";
        return switch (category.toUpperCase()) {
            case "ART"         -> "linear-gradient(to bottom right, #f093fb, #f5576c)";
            case "ELECTRONICS" -> "linear-gradient(to bottom right, #4facfe, #00f2fe)";
            case "VEHICLE"     -> "linear-gradient(to bottom right, #fa709a, #fee140)";
            default            -> "linear-gradient(to bottom right, #667eea, #764ba2)";
        };
    }

    private String formatRemaining(String endTimeStr) {
        try {
            LocalDateTime end = LocalDateTime.parse(endTimeStr);
            LocalDateTime now = LocalDateTime.now();

            if (!now.isBefore(end)) return "Đã kết thúc";

            long totalSec = java.time.Duration.between(now, end).getSeconds();
            long days  = totalSec / 86400;
            long hours = (totalSec % 86400) / 3600;
            long mins  = (totalSec % 3600)  / 60;
            long secs  = totalSec % 60;

            if (days  > 0) return String.format("Còn %dd %dh %dm", days, hours, mins);
            if (hours > 0) return String.format("Còn %dh %dm %ds", hours, mins, secs);
            if (mins  > 0) return String.format("Còn %dm %ds", mins, secs);
            return String.format("Còn %ds", secs);

        } catch (Exception e) {
            return endTimeStr;
        }
    }

    // ─── Bidding screen ──────────────────────────────────────────────────────

    private void openBiddingScreen(AuctionItem item, ActionEvent event) {
        try {
            stopTimer();
            FXMLLoader loader = new FXMLLoader(
                    MainClient.class.getResource("/client/fxml/bidding.fxml")
            );
            Parent root = loader.load();

            ControllerBidding controller = loader.getController();
            controller.setAuctionData(item.id, item.itemId, item.currentPrice, item.endTime);

            Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
            double w = stage.getWidth();
            double h = stage.getHeight();
            stage.setScene(new Scene(root, w, h));
            stage.setTitle("Đấu giá — " + (item.itemName != null ? item.itemName : "Phiên #" + item.id));
            stage.show();

        } catch (IOException e) {
            statusLabel.setText("Không thể mở màn hình đấu giá.");
        }
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────
    public static class AuctionItem {
        public int    id;
        public int    itemId;
        public String itemName;
        public String itemCategory;
        public String itemDescription;
        public double currentPrice;
        public String status;
        public String endTime;
    }
}

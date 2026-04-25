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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
    @FXML private Label     headerTitleLabel;
    @FXML private Button    sellerDashboardButton;
    @FXML private Button    cartButton;
    @FXML private FlowPane  cardContainer;
    @FXML private ScrollPane scrollPane;
    @FXML private VBox      emptyState;
    // Filter tabs
    @FXML private Button    tabRunningButton;
    @FXML private Button    tabFinishedButton;
    @FXML private Button    tabAllButton;
    // Search + category filter
    @FXML private TextField           searchField;
    @FXML private ComboBox<String>    categoryFilter;

    private final Gson gson = new Gson();
    /** Toàn bộ phiên server trả về (chưa lọc). */
    private final List<AuctionItem> data = new ArrayList<>();
    private Timeline countdownTimer;

    /** Filter hiện tại: "RUNNING" (mặc định), "FINISHED", "ALL". */
    private String currentFilter = "RUNNING";

    /** Category filter: "ALL" mặc định. */
    private String currentCategory = "ALL";

    /** Search query (lowercase) - rỗng = không lọc. */
    private String currentSearch = "";

    /** Offset đồng hồ client-server (fix #25). */
    private volatile java.time.Duration clockOffset = java.time.Duration.ZERO;
    private LocalDateTime serverNow() {
        return LocalDateTime.now().plus(clockOffset);
    }

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

        applyTabStyles();
        setupSearchAndCategory();
        loadAuctions();
    }

    private void setupSearchAndCategory() {
        // Category combo
        if (categoryFilter != null) {
            categoryFilter.getItems().setAll(
                    "Tất cả loại", "🎨 Nghệ thuật", "💻 Điện tử", "🚗 Xe cộ");
            categoryFilter.getSelectionModel().selectFirst();
            categoryFilter.valueProperty().addListener((obs, old, val) -> {
                currentCategory = mapCategory(val);
                renderCards();
            });
        }
        // Search field — debounce nhẹ bằng cách chỉ render khi text thay đổi
        if (searchField != null) {
            searchField.textProperty().addListener((obs, old, val) -> {
                currentSearch = val == null ? "" : val.trim().toLowerCase();
                renderCards();
            });
        }
    }

    private String mapCategory(String label) {
        if (label == null) return "ALL";
        if (label.contains("Nghệ thuật")) return "ART";
        if (label.contains("Điện tử"))    return "ELECTRONICS";
        if (label.contains("Xe cộ"))      return "VEHICLE";
        return "ALL";
    }

    // ─── Filter tabs ─────────────────────────────────────────────────────────

    @FXML private void handleFilterRunning()  { switchFilter("RUNNING");  }
    @FXML private void handleFilterFinished() { switchFilter("FINISHED"); }
    @FXML private void handleFilterAll()      { switchFilter("ALL");      }

    private void switchFilter(String filter) {
        if (filter.equals(currentFilter)) return;
        currentFilter = filter;
        applyTabStyles();
        updateHeaderTitle();
        loadAuctions();
    }

    private void applyTabStyles() {
        styleTab(tabRunningButton,  "RUNNING");
        styleTab(tabFinishedButton, "FINISHED");
        styleTab(tabAllButton,      "ALL");
    }

    private void styleTab(Button btn, String filter) {
        if (btn == null) return;
        boolean active = filter.equals(currentFilter);
        if (active) {
            btn.setStyle("-fx-background-color: #1a1a2e; -fx-text-fill: white; "
                       + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand; "
                       + "-fx-background-radius: 8; -fx-padding: 8 18;");
        } else {
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #495057; "
                       + "-fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand; "
                       + "-fx-background-radius: 8; -fx-padding: 8 18; "
                       + "-fx-border-color: #dee2e6; -fx-border-radius: 8; -fx-border-width: 1;");
        }
    }

    private void updateHeaderTitle() {
        if (headerTitleLabel == null) return;
        String title = switch (currentFilter) {
            case "FINISHED" -> "Phiên đã kết thúc";
            case "ALL"      -> "Tất cả phiên đấu giá";
            default         -> "Phiên đấu giá đang diễn ra";
        };
        headerTitleLabel.setText(title);
    }

    // ─── Refresh / Nav ───────────────────────────────────────────────────────

    @FXML
    private void handleRefresh() {
        loadAuctions();
    }

    @FXML
    private void handleOpenCart(ActionEvent event) throws IOException {
        stopTimer();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/my_cart.fxml"),
                "Giỏ đấu giá — Phiên của tôi");
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

        // Gửi filter hiện tại (RUNNING/FINISHED/ALL) cho server
        JsonObject req = new JsonObject();
        req.addProperty("filter", currentFilter);
        final String reqPayload = req.toString();

        new Thread(() -> {
            Response response = ServerConnection.getInstance().sendRequest("GET_AUCTIONS", reqPayload);

            Platform.runLater(() -> {
                if ("SUCCESS".equals(response.getStatus()) && response.getPayload() != null) {
                    // Server giờ trả wrapper: { auctions: [...], serverNow: "..." }
                    // Fallback parse mảng trực tiếp nếu dùng server cũ.
                    String payload = response.getPayload();
                    String auctionsJson = payload;
                    try {
                        JsonObject wrapper = gson.fromJson(payload, JsonObject.class);
                        if (wrapper != null && wrapper.has("auctions")) {
                            auctionsJson = wrapper.get("auctions").toString();
                            if (wrapper.has("serverNow") && !wrapper.get("serverNow").isJsonNull()) {
                                try {
                                    LocalDateTime srv = LocalDateTime.parse(
                                            wrapper.get("serverNow").getAsString());
                                    clockOffset = java.time.Duration.between(LocalDateTime.now(), srv);
                                } catch (Exception ignore) {}
                            }
                        }
                    } catch (Exception ignore) {
                        // payload là array thuần → giữ nguyên
                    }

                    List<AuctionItem> items = parseAuctions(auctionsJson);
                    data.addAll(items);
                    renderCards(); // renderCards tự cập nhật statusLabel theo filter
                } else {
                    statusLabel.setText("Lỗi: " + (response.getMessage() != null ? response.getMessage() : "không rõ"));
                    renderCards(); // show empty state
                }
            });
        }).start();
    }

    private String filterLabel() {
        return switch (currentFilter) {
            case "FINISHED" -> "phiên đã kết thúc";
            case "ALL"      -> "phiên đấu giá";
            default         -> "phiên đấu giá đang diễn ra";
        };
    }

    private String emptyMessageForFilter() {
        return switch (currentFilter) {
            case "FINISHED" -> "Chưa có phiên nào kết thúc";
            case "ALL"      -> "Chưa có phiên đấu giá nào";
            default         -> "Hiện chưa có phiên nào — chờ seller đăng bán";
        };
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
                if (obj.has("winnerName") && !obj.get("winnerName").isJsonNull()) {
                    ai.winnerName = obj.get("winnerName").getAsString();
                }
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

        // Áp dụng search + category filter trên data đã có (không gọi server)
        List<AuctionItem> filtered = new ArrayList<>();
        for (AuctionItem item : data) {
            if (!matchesCategory(item)) continue;
            if (!matchesSearch(item))   continue;
            filtered.add(item);
        }

        boolean empty = filtered.isEmpty();
        emptyState.setVisible(empty);
        emptyState.setManaged(empty);
        scrollPane.setVisible(!empty);
        scrollPane.setManaged(!empty);

        if (!empty) {
            for (AuctionItem item : filtered) {
                cardContainer.getChildren().add(buildCard(item));
            }
        }

        // Cập nhật status text — phản ánh count sau filter
        if (data.isEmpty()) {
            statusLabel.setText(emptyMessageForFilter());
        } else if (filtered.size() == data.size()) {
            statusLabel.setText(data.size() + " " + filterLabel());
        } else {
            statusLabel.setText(filtered.size() + " / " + data.size() + " " + filterLabel()
                    + (currentSearch.isEmpty() && "ALL".equals(currentCategory)
                       ? "" : " (đã lọc)"));
        }
    }

    private boolean matchesCategory(AuctionItem item) {
        if ("ALL".equals(currentCategory)) return true;
        return item.itemCategory != null
                && currentCategory.equalsIgnoreCase(item.itemCategory);
    }

    private boolean matchesSearch(AuctionItem item) {
        if (currentSearch.isEmpty()) return true;
        String name = item.itemName == null ? "" : item.itemName.toLowerCase();
        String desc = item.itemDescription == null ? "" : item.itemDescription.toLowerCase();
        return name.contains(currentSearch) || desc.contains(currentSearch);
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
        boolean finished = isFinishedStatus(item.status);

        VBox card = new VBox();
        card.setPrefWidth(260);
        card.setMaxWidth(260);
        // Phiên kết thúc: opacity hạ, viền xám đậm hơn để phân biệt rõ
        String cardStyle = "-fx-background-color: white; "
                + "-fx-background-radius: 12; "
                + "-fx-border-radius: 12; "
                + "-fx-border-color: " + (finished ? "#ced4da" : "#e9ecef") + "; "
                + "-fx-border-width: 1; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 10, 0, 0, 3);";
        card.setStyle(cardStyle);
        if (finished) card.setOpacity(0.92);

        // ── Hero block ───────────────────────────────────────────────────
        StackPane hero = new StackPane();
        hero.setPrefHeight(140);
        // Phiên kết thúc dùng gradient xám thay cho gradient theo category
        String heroGradient = finished
                ? "linear-gradient(to bottom right, #6c757d, #495057)"
                : heroGradientForCategory(item.itemCategory);
        hero.setStyle("-fx-background-color: " + heroGradient + "; "
                + "-fx-background-radius: 12 12 0 0;");
        Label emojiLabel = new Label(finished ? "🏁" : emojiForCategory(item.itemCategory));
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

        // Badge category + status
        HBox badgeRow = new HBox(6);
        Label catBadge = new Label(categoryLabel(item.itemCategory));
        catBadge.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4338ca; "
                + "-fx-padding: 3 9; -fx-background-radius: 10; "
                + "-fx-font-size: 10px; -fx-font-weight: bold;");

        Label statusBadge = new Label("● " + statusDisplayText(item.status));
        statusBadge.setStyle(statusBadgeStyle(item.status));
        badgeRow.getChildren().addAll(catBadge, statusBadge);

        // Giá — đổi label theo trạng thái
        Label priceLabel = new Label(finished ? "Giá thắng" : "Giá hiện tại");
        priceLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #6c757d; "
                + "-fx-padding: 6 0 0 0;");
        Label priceValue = new Label(String.format("%,.0f VNĐ", item.currentPrice));
        priceValue.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: "
                + (finished ? "#495057" : "#e94560") + ";");

        // Countdown / Winner info
        Label infoLine;
        if (finished) {
            String winnerText = (item.winnerName != null && !item.winnerName.isBlank())
                    ? "👑 Người thắng: " + item.winnerName
                    : "Không có người thắng";
            infoLine = new Label(winnerText);
            infoLine.setStyle("-fx-font-size: 11px; -fx-text-fill: #495057; -fx-font-weight: bold;");
            infoLine.setWrapText(true);
        } else {
            infoLine = new Label("⏰ " + formatRemaining(item.endTime));
            infoLine.setStyle("-fx-font-size: 11px; -fx-text-fill: #495057; -fx-font-weight: bold;");
            infoLine.setUserData(item.endTime);
            infoLine.getProperties().put("role", "countdown");
        }

        // Nút — phiên kết thúc dùng button "Xem kết quả" màu xám
        Button btn = new Button(finished ? "Xem kết quả →" : "Vào đấu giá →");
        btn.setMaxWidth(Double.MAX_VALUE);
        String baseColor  = finished ? "#6c757d" : "#1a1a2e";
        String hoverColor = finished ? "#495057" : "#e94560";
        String baseStyle = "-fx-background-color: " + baseColor + "; -fx-text-fill: white; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand; "
                + "-fx-background-radius: 8; -fx-padding: 10 16;";
        btn.setStyle(baseStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(baseStyle.replace(baseColor, hoverColor)));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        btn.setOnAction(e -> openBiddingScreen(item, e));

        Region spacer = new Region();
        spacer.setMinHeight(4);

        body.getChildren().addAll(name, badgeRow, priceLabel, priceValue, infoLine, spacer, btn);
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

    // ─── Helpers: status display ─────────────────────────────────────────────

    private boolean isFinishedStatus(String status) {
        return "FINISHED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status);
    }

    private String statusDisplayText(String status) {
        if (status == null) return "—";
        return switch (status.toUpperCase()) {
            case "RUNNING"  -> "Đang diễn ra";
            case "OPEN"     -> "Sắp bắt đầu";
            case "FINISHED" -> "Đã kết thúc";
            case "CLOSED"   -> "Đã đóng";
            default         -> status;
        };
    }

    private String statusBadgeStyle(String status) {
        // (bg, text) cho từng trạng thái
        String bg, fg;
        switch (status == null ? "" : status.toUpperCase()) {
            case "RUNNING"  -> { bg = "#ecfdf5"; fg = "#047857"; }      // xanh lá
            case "OPEN"     -> { bg = "#fffbeb"; fg = "#b45309"; }      // vàng
            case "FINISHED" -> { bg = "#f1f3f5"; fg = "#495057"; }      // xám
            case "CLOSED"   -> { bg = "#f1f3f5"; fg = "#6c757d"; }      // xám nhạt
            default         -> { bg = "#eef2ff"; fg = "#4338ca"; }      // indigo
        }
        return "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; "
             + "-fx-padding: 3 9; -fx-background-radius: 10; "
             + "-fx-font-size: 10px; -fx-font-weight: bold;";
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
            // Dùng serverNow() thay vì LocalDateTime.now() để countdown đồng bộ
            // giữa các máy client có đồng hồ lệch (fix #25).
            LocalDateTime now = serverNow();

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
        public String winnerName;   // null nếu chưa có ai thắng
    }
}

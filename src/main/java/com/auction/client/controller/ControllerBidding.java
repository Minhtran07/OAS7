package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.client.util.MoneyField;
import com.auction.client.util.SceneUtil;
import com.auction.server.core.BidEventBus;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.event.ActionEvent;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ControllerBidding implements ServerConnection.PushListener {

    // ─── FXML bindings ───────────────────────────────────────────────────────

    @FXML private Label auctionTitleLabel;
    @FXML private Label countdownLabel;
    @FXML private Label currentPriceLabel;
    @FXML private Label winnerLabel;
    @FXML private Label bidMessageLabel;
    @FXML private Label bidHistoryLabel;
    @FXML private TextField bidAmountField;
    @FXML private Button    bidButton;

    // Auto-bid fields
    @FXML private TextField maxBidField;
    @FXML private TextField incrementField;

    // Chart
    @FXML private LineChart<String, Number> priceChart;
    @FXML private CategoryAxis              xAxis;
    @FXML private NumberAxis                yAxis;
    @FXML private Label                     chartStatsLabel;

    // ─── State ───────────────────────────────────────────────────────────────

    private final Gson gson = new Gson();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bidding-scheduler");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> countdownTask;

    private int           auctionId;
    private int           itemId;
    private double        currentPrice;
    private LocalDateTime endTime;

    private final StringBuilder historyLog = new StringBuilder();

    // Chart
    private XYChart.Series<String, Number> priceSeries;
    private static final int MAX_CHART_POINTS = 30;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ─── Init ────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        MoneyField.attach(bidAmountField);
        MoneyField.attach(maxBidField);
        MoneyField.attach(incrementField);
    }

    public void setAuctionData(int auctionId, int itemId,
                               double currentPrice, String endTimeStr) {
        this.auctionId    = auctionId;
        this.itemId       = itemId;
        this.currentPrice = currentPrice;
        this.endTime      = LocalDateTime.parse(endTimeStr);

        auctionTitleLabel.setText("Phiên đấu giá #" + auctionId + " — Item #" + itemId);
        updatePriceDisplay(currentPrice, "Chưa có");

        initChart(currentPrice);

        // Đăng ký push listener TRƯỚC khi subscribe để không bỏ lỡ event
        ServerConnection.getInstance().setPushListener(this);
        subscribeToAuction();

        // Snapshot hiện tại (winner, history, endTime mới nếu đã gia hạn)
        // để client vào SAU vẫn đồng bộ với client đã ở trong phiên.
        loadAuctionState();

        startCountdown();
    }

    // ─── Chart ───────────────────────────────────────────────────────────────

    private void initChart(double startingPrice) {
        priceSeries = new XYChart.Series<>();
        priceSeries.setName("Giá");
        priceChart.getData().add(priceSeries);

        // Điểm giá khởi điểm làm gốc
        priceSeries.getData().add(new XYChart.Data<>("Bắt đầu", startingPrice));

        yAxis.setAutoRanging(true);
        updateChartStats();
    }

    /**
     * Thêm điểm mới vào chart (phải gọi trên JavaFX thread).
     * Tự động xóa điểm cũ khi vượt MAX_CHART_POINTS.
     */
    private void addChartPoint(double price) {
        String timeLabel = LocalDateTime.now().format(TIME_FMT);

        // Nếu trùng giây thì thêm đuôi để label không bị collapse
        long sameCount = priceSeries.getData().stream()
                .filter(d -> d.getXValue().startsWith(timeLabel))
                .count();
        String label = sameCount == 0 ? timeLabel : timeLabel + "+" + sameCount;

        priceSeries.getData().add(new XYChart.Data<>(label, price));

        while (priceSeries.getData().size() > MAX_CHART_POINTS) {
            priceSeries.getData().remove(0);
        }

        updateChartStats();
    }

    private void updateChartStats() {
        int total = priceSeries.getData().size();
        if (total <= 1) {
            chartStatsLabel.setText("Chờ giá mới...");
            return;
        }
        double min = priceSeries.getData().stream()
                .mapToDouble(d -> d.getYValue().doubleValue()).min().orElse(0);
        double max = priceSeries.getData().stream()
                .mapToDouble(d -> d.getYValue().doubleValue()).max().orElse(0);
        chartStatsLabel.setText(String.format(
                "%d lượt đặt giá  |  Min: %s  |  Max: %s",
                total - 1, formatPrice(min), formatPrice(max)));
    }

    @FXML
    private void handleClearChart() {
        if (priceSeries != null) {
            double last = currentPrice;
            priceSeries.getData().clear();
            priceSeries.getData().add(new XYChart.Data<>("Bắt đầu lại", last));
            updateChartStats();
        }
    }

    // ─── Observer — nhận push từ server ──────────────────────────────────────

    @Override
    public void onPushEvent(String eventJson) {
        try {
            BidEventBus.BidEvent event =
                    gson.fromJson(eventJson, BidEventBus.BidEvent.class);

            if (event.auctionId != this.auctionId) return;

            switch (event.type) {
                case "BID_UPDATE":
                    currentPrice = event.newPrice;
                    updatePriceDisplay(event.newPrice, event.winnerName);
                    appendHistory(event.winnerName, event.newPrice);
                    addChartPoint(event.newPrice);
                    showBidMessage("💡 Có giá mới: " + formatPrice(event.newPrice), false);
                    break;

                case "AUCTION_EXTENDED":
                    endTime = LocalDateTime.parse(event.newEndTime);
                    showBidMessage("⏱ Phiên được gia hạn đến " +
                            endTime.format(TIME_FMT), false);
                    break;

                case "AUCTION_FINISHED":
                    currentPrice = event.newPrice;
                    updatePriceDisplay(event.newPrice, event.winnerName + " 🏆");
                    addChartPoint(event.newPrice);
                    bidButton.setDisable(true);
                    showBidMessage("🎉 Phiên kết thúc! Người thắng: " + event.winnerName, true);
                    // Đổi màu stats label thành đỏ
                    chartStatsLabel.setStyle(
                            "-fx-text-fill: #c0392b; -fx-font-size: 11px; -fx-font-weight: bold;");
                    break;
            }
        } catch (Exception e) {
            System.err.println("Lỗi parse push event: " + e.getMessage());
        }
    }

    // ─── Subscribe / Unsubscribe ─────────────────────────────────────────────

    private void subscribeToAuction() {
        JsonObject payload = new JsonObject();
        payload.addProperty("auctionId", auctionId);
        new Thread(() ->
            ServerConnection.getInstance().sendRequest("SUBSCRIBE_AUCTION", payload.toString())
        ).start();
    }

    /**
     * Fetch snapshot hiện tại của phiên (winner + toàn bộ bid history + endTime).
     * Cần thiết cho client vào SAU khi đã có bid, vì push event chỉ broadcast
     * các bid MỚI — ai vào sau sẽ thiếu context về winner và lịch sử.
     */
    private void loadAuctionState() {
        JsonObject payload = new JsonObject();
        payload.addProperty("auctionId", auctionId);

        new Thread(() -> {
            Response resp = ServerConnection.getInstance()
                    .sendRequest("GET_AUCTION_STATE", payload.toString());
            if (!"SUCCESS".equals(resp.getStatus()) || resp.getPayload() == null) {
                return;
            }
            try {
                JsonObject state = gson.fromJson(resp.getPayload(), JsonObject.class);

                double price = state.has("currentPrice")
                        ? state.get("currentPrice").getAsDouble() : currentPrice;
                String winnerName = (state.has("winnerName") && !state.get("winnerName").isJsonNull())
                        ? state.get("winnerName").getAsString() : null;
                String newEndTime = (state.has("endTime") && !state.get("endTime").isJsonNull())
                        ? state.get("endTime").getAsString() : null;

                JsonArray history = state.has("history") && state.get("history").isJsonArray()
                        ? state.getAsJsonArray("history")
                        : new JsonArray();

                Platform.runLater(() -> applyAuctionState(price, winnerName, newEndTime, history));

            } catch (Exception e) {
                System.err.println("Lỗi parse GET_AUCTION_STATE: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Áp snapshot lên UI (phải gọi trên JavaFX thread).
     * Rebuild chart từ history để đảm bảo chart đồng bộ với các client đã ở trong phiên.
     */
    private void applyAuctionState(double price, String winnerName,
                                   String newEndTime, JsonArray history) {
        this.currentPrice = price;

        // End time có thể đã bị anti-sniping gia hạn
        if (newEndTime != null) {
            try { this.endTime = LocalDateTime.parse(newEndTime); }
            catch (Exception ignore) { /* giữ endTime cũ nếu parse lỗi */ }
        }

        // Rebuild chart: điểm khởi đầu + từng bid lịch sử + điểm hiện tại
        priceSeries.getData().clear();

        double startingPrice = price;
        if (history.size() > 0) {
            // Giá "Bắt đầu" = giá trước bid đầu tiên. Nhưng ta không biết giá khởi
            // điểm chính xác ở đây (client không lưu), nên dùng giá bid đầu tiên
            // làm xấp xỉ — điểm "Bắt đầu" đặt thấp hơn 1 chút cho hợp lý về UI
            // nếu cần; đơn giản nhất là dùng giá bid đầu.
            startingPrice = history.get(0).getAsJsonObject().get("amount").getAsDouble();
        }
        priceSeries.getData().add(new XYChart.Data<>("Bắt đầu", startingPrice));

        // Clear history log và rebuild
        historyLog.setLength(0);

        for (JsonElement el : history) {
            JsonObject bid = el.getAsJsonObject();
            double amount     = bid.get("amount").getAsDouble();
            String bidderName = bid.has("bidderName") && !bid.get("bidderName").isJsonNull()
                    ? bid.get("bidderName").getAsString() : "?";
            String bidTime    = bid.has("bidTime") && !bid.get("bidTime").isJsonNull()
                    ? bid.get("bidTime").getAsString() : null;

            String timeLabel = parseBidTimeLabel(bidTime);
            addHistoricalChartPoint(timeLabel, amount);
            appendHistoryLine(timeLabel, bidderName, amount);
        }

        // Cập nhật history label
        bidHistoryLabel.setText(historyLog.length() == 0
                ? "Chưa có lượt đặt giá nào."
                : (historyLog.length() > 500
                        ? historyLog.substring(0, 500) + "..."
                        : historyLog.toString()));

        // Cập nhật giá + winner hiển thị
        updatePriceDisplay(price, (winnerName != null && !winnerName.isBlank())
                ? winnerName : "Chưa có");

        updateChartStats();
    }

    /**
     * Parse bid_time từ DB ("yyyy-MM-dd HH:mm:ss" theo SQLite 'localtime')
     * thành label "HH:mm:ss". Fallback: trả về nguyên chuỗi hoặc "—" nếu null.
     */
    private String parseBidTimeLabel(String bidTime) {
        if (bidTime == null || bidTime.isBlank()) return "—";
        try {
            // SQLite datetime('now','localtime') → "yyyy-MM-dd HH:mm:ss"
            LocalDateTime dt = LocalDateTime.parse(bidTime.replace(' ', 'T'));
            return dt.format(TIME_FMT);
        } catch (Exception e) {
            // Có thể đã là "HH:mm:ss" rồi; hoặc format lạ → trả về như cũ
            return bidTime.length() >= 8 ? bidTime.substring(bidTime.length() - 8) : bidTime;
        }
    }

    /**
     * Thêm 1 điểm chart với label cụ thể (từ history DB) thay vì dùng now().
     */
    private void addHistoricalChartPoint(String timeLabel, double price) {
        long sameCount = priceSeries.getData().stream()
                .filter(d -> d.getXValue().startsWith(timeLabel))
                .count();
        String label = sameCount == 0 ? timeLabel : timeLabel + "+" + sameCount;
        priceSeries.getData().add(new XYChart.Data<>(label, price));

        while (priceSeries.getData().size() > MAX_CHART_POINTS) {
            priceSeries.getData().remove(0);
        }
    }

    /**
     * Append 1 dòng history với timestamp cụ thể (dùng khi rebuild từ DB).
     */
    private void appendHistoryLine(String time, String name, double amount) {
        historyLog.insert(0,
                String.format("[%s] %s → %s\n", time, name, formatPrice(amount)));
    }

    private void unsubscribeFromAuction() {
        JsonObject payload = new JsonObject();
        payload.addProperty("auctionId", auctionId);
        ServerConnection.getInstance().sendRequest("UNSUBSCRIBE_AUCTION", payload.toString());
        ServerConnection.getInstance().clearPushListener();
    }

    // ─── Đặt giá thủ công ────────────────────────────────────────────────────

    @FXML
    private void handlePlaceBid(ActionEvent event) {
        double amount = MoneyField.getValue(bidAmountField);
        if (amount < 0) { showBidMessage("Vui lòng nhập số tiền hợp lệ!", false); return; }

        if (amount <= currentPrice) {
            showBidMessage("Giá phải cao hơn: " + formatPrice(currentPrice), false);
            return;
        }

        bidButton.setDisable(true);
        int bidderId = UserSession.getInstance().getCurrentUser().getId();

        JsonObject payload = new JsonObject();
        payload.addProperty("auctionId", auctionId);
        payload.addProperty("bidderId", bidderId);
        payload.addProperty("amount", amount);

        new Thread(() -> {
            Response response = ServerConnection.getInstance()
                    .sendRequest("PLACE_BID", payload.toString());
            Platform.runLater(() -> {
                bidButton.setDisable(false);
                if ("SUCCESS".equals(response.getStatus())) {
                    bidAmountField.clear();
                    showBidMessage("✅ Đặt giá thành công!", true);
                } else {
                    showBidMessage("❌ " + response.getMessage(), false);
                }
            });
        }).start();
    }

    // ─── Auto-Bid ────────────────────────────────────────────────────────────

    @FXML
    private void handleRegisterAutoBid(ActionEvent event) {
        double maxBid    = MoneyField.getValue(maxBidField);
        double increment = MoneyField.getValue(incrementField);

        if (maxBid < 0 || increment < 0) {
            showBidMessage("Nhập đủ maxBid và bước giá hợp lệ!", false);
            return;
        }

        try {

            if (maxBid <= currentPrice) {
                showBidMessage("maxBid phải lớn hơn giá hiện tại!", false);
                return;
            }
            if (increment <= 0) {
                showBidMessage("Bước giá phải lớn hơn 0!", false);
                return;
            }

            int bidderId = UserSession.getInstance().getCurrentUser().getId();
            JsonObject payload = new JsonObject();
            payload.addProperty("auctionId", auctionId);
            payload.addProperty("bidderId", bidderId);
            payload.addProperty("maxBid", maxBid);
            payload.addProperty("increment", increment);

            new Thread(() -> {
                Response resp = ServerConnection.getInstance()
                        .sendRequest("AUTO_BID", payload.toString());
                Platform.runLater(() -> {
                    if ("SUCCESS".equals(resp.getStatus())) {
                        showBidMessage("🤖 Auto-bid đã kích hoạt (max: " +
                                formatPrice(maxBid) + ")", true);
                        maxBidField.clear();
                        incrementField.clear();
                    } else {
                        showBidMessage("❌ " + resp.getMessage(), false);
                    }
                });
            }).start();

        } catch (NumberFormatException e) {
            showBidMessage("Số tiền không hợp lệ!", false);
        }
    }

    // ─── Countdown ───────────────────────────────────────────────────────────

    private void startCountdown() {
        countdownTask = scheduler.scheduleAtFixedRate(() -> {
            long secondsLeft = ChronoUnit.SECONDS.between(LocalDateTime.now(), endTime);
            Platform.runLater(() -> {
                if (secondsLeft <= 0) {
                    countdownLabel.setText("ĐÃ KẾT THÚC");
                    countdownLabel.setStyle(
                            "-fx-text-fill:#c0392b;-fx-font-size:16px;-fx-font-weight:bold;");
                    bidButton.setDisable(true);
                    if (countdownTask != null) countdownTask.cancel(false);
                } else {
                    long h = secondsLeft / 3600;
                    long m = (secondsLeft % 3600) / 60;
                    long s = secondsLeft % 60;
                    countdownLabel.setText(String.format("%02d:%02d:%02d", h, m, s));
                    String color = secondsLeft < 60 ? "#e74c3c" : "#ecf0f1";
                    countdownLabel.setStyle(
                            "-fx-text-fill:" + color + ";-fx-font-size:16px;-fx-font-weight:bold;");
                }
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    @FXML
    private void handleBack(ActionEvent event) throws IOException {
        unsubscribeFromAuction();
        stopTasks();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/auction_list.fxml"),
                "Auction — Danh sách phiên đấu giá");
    }

    private void stopTasks() {
        if (countdownTask != null) countdownTask.cancel(true);
        scheduler.shutdownNow();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void updatePriceDisplay(double price, String winner) {
        currentPriceLabel.setText(formatPrice(price));
        winnerLabel.setText(winner);
    }

    private void showBidMessage(String msg, boolean success) {
        bidMessageLabel.setText(msg);
        bidMessageLabel.setStyle(success
                ? "-fx-text-fill:#27ae60;-fx-font-size:12px;"
                : "-fx-text-fill:#e74c3c;-fx-font-size:12px;");
    }

    private void appendHistory(String name, double amount) {
        String time = LocalDateTime.now().format(TIME_FMT);
        historyLog.insert(0,
                String.format("[%s] %s → %s\n", time, name, formatPrice(amount)));
        bidHistoryLabel.setText(
                historyLog.length() > 500
                        ? historyLog.substring(0, 500) + "..."
                        : historyLog.toString());
    }

    private String formatPrice(double price) {
        return String.format("%,.0f VNĐ", price);
    }
}

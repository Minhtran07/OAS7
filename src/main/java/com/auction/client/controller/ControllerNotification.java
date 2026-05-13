package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller cho popup Thông báo (thay thế MyCart).
 * Hiển thị danh sách notification gần đây của user, đánh dấu đã đọc,
 * và sync badge số trên chuông qua callback.
 */
public class ControllerNotification {

    @FXML private Label countLabel;
    @FXML private VBox  listContainer;
    @FXML private Label emptyLabel;

    private final Gson gson = new Gson();
    private final List<NotifRow> data = new ArrayList<>();

    /** Callback gọi sau khi đóng popup hoặc khi unread count thay đổi. */
    private Runnable onChange;
    private Stage    stage;

    @FXML
    public void initialize() {
        loadNotifications();
    }

    // ─── Static helpers — mở popup ───────────────────────────────────────────

    /**
     * Mở Notification popup là Stage modal — không thay scene của AuctionList.
     * Sau khi đóng, callback onClose được gọi để parent refresh badge.
     */
    public static void openAsPopup(Stage owner, Runnable onClose) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                MainClient.class.getResource("/client/fxml/notification.fxml"));
        Parent root = loader.load();
        ControllerNotification c = loader.getController();
        c.onChange = onClose;

        Stage s = new Stage();
        s.initOwner(owner);
        s.initModality(Modality.WINDOW_MODAL);
        s.initStyle(StageStyle.DECORATED);
        s.setTitle("Thông báo");
        s.setScene(new Scene(root));
        s.setOnHidden(e -> { if (onClose != null) onClose.run(); });
        c.stage = s;
        s.show();
    }

    @FXML
    private void handleClose() {
        if (stage != null) stage.close();
        else if (onChange != null) onChange.run();
    }

    @FXML
    private void handleMarkAllRead() {
        if (!UserSession.getInstance().isLoggedIn()) return;
        int userId = UserSession.getInstance().getCurrentUser().getId();
        JsonObject p = new JsonObject();
        p.addProperty("userId", userId);
        new Thread(() -> {
            Response r = ServerConnection.getInstance()
                    .sendRequest("MARK_ALL_NOTIFICATIONS_READ", p.toString());
            Platform.runLater(() -> {
                if ("SUCCESS".equals(r.getStatus())) {
                    for (NotifRow n : data) n.isRead = true;
                    renderList();
                    if (onChange != null) onChange.run();
                }
            });
        }).start();
    }

    // ─── Load ────────────────────────────────────────────────────────────────

    private void loadNotifications() {
        if (!UserSession.getInstance().isLoggedIn()) return;
        int userId = UserSession.getInstance().getCurrentUser().getId();
        JsonObject p = new JsonObject();
        p.addProperty("userId", userId);
        p.addProperty("limit", 100);

        new Thread(() -> {
            Response r = ServerConnection.getInstance()
                    .sendRequest("GET_NOTIFICATIONS", p.toString());
            Platform.runLater(() -> {
                if ("SUCCESS".equals(r.getStatus()) && r.getPayload() != null) {
                    try {
                        JsonObject obj = gson.fromJson(r.getPayload(), JsonObject.class);
                        JsonArray arr = obj.getAsJsonArray("notifications");
                        data.clear();
                        for (JsonElement el : arr) data.add(parse(el.getAsJsonObject()));
                        int unread = obj.has("unreadCount") ? obj.get("unreadCount").getAsInt() : 0;
                        countLabel.setText("(" + data.size() + " thông báo, " + unread + " chưa đọc)");
                        renderList();
                    } catch (Exception ex) {
                        countLabel.setText("Lỗi parse dữ liệu.");
                    }
                } else {
                    countLabel.setText("Lỗi: " + (r.getMessage() != null ? r.getMessage() : "không rõ"));
                }
            });
        }).start();
    }

    private NotifRow parse(JsonObject o) {
        NotifRow n = new NotifRow();
        n.id        = o.get("id").getAsInt();
        n.type      = o.has("type") ? o.get("type").getAsString() : "";
        n.title     = o.has("title") ? o.get("title").getAsString() : "";
        n.message   = (o.has("message") && !o.get("message").isJsonNull())
                ? o.get("message").getAsString() : "";
        n.isRead    = o.has("isRead") && o.get("isRead").getAsBoolean();
        n.createdAt = (o.has("createdAt") && !o.get("createdAt").isJsonNull())
                ? o.get("createdAt").getAsString() : "";
        if (o.has("relatedAuctionId") && !o.get("relatedAuctionId").isJsonNull()) {
            n.relatedAuctionId = o.get("relatedAuctionId").getAsInt();
        }
        return n;
    }

    private void renderList() {
        listContainer.getChildren().clear();
        boolean empty = data.isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        if (empty) return;

        for (NotifRow n : data) {
            listContainer.getChildren().add(buildRow(n));
        }
    }

    private HBox buildRow(NotifRow n) {
        HBox row = new HBox(10);
        String bg = n.isRead ? "#f1f3f5" : "#fff3cd";
        String border = n.isRead ? "#dee2e6" : "#f0ad4e";
        row.setStyle("-fx-background-color: " + bg + "; -fx-padding: 10 12; "
                + "-fx-background-radius: 8; -fx-border-radius: 8; "
                + "-fx-border-color: " + border + "; -fx-border-width: 1;"
                + "-fx-cursor: hand;");

        Label icon = new Label(iconFor(n.type));
        icon.setStyle("-fx-font-size: 18px; -fx-min-width: 28;");

        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label title = new Label(n.title);
        title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1a1a2e;");
        title.setWrapText(true);
        Label msg = new Label(n.message);
        msg.setStyle("-fx-font-size: 11px; -fx-text-fill: #495057;");
        msg.setWrapText(true);
        Label time = new Label(formatTime(n.createdAt));
        time.setStyle("-fx-font-size: 10px; -fx-text-fill: #adb5bd;");
        info.getChildren().addAll(title, msg, time);

        Region spacer = new Region();
        Label dot = new Label(n.isRead ? "" : "●");
        dot.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 14px;");

        row.getChildren().addAll(icon, info, spacer, dot);

        row.setOnMouseClicked(ev -> {
            if (!n.isRead) markRead(n);
        });
        return row;
    }

    private void markRead(NotifRow n) {
        if (!UserSession.getInstance().isLoggedIn()) return;
        int userId = UserSession.getInstance().getCurrentUser().getId();
        JsonObject p = new JsonObject();
        p.addProperty("userId", userId);
        p.addProperty("notifId", n.id);
        new Thread(() -> {
            Response r = ServerConnection.getInstance()
                    .sendRequest("MARK_NOTIFICATION_READ", p.toString());
            if ("SUCCESS".equals(r.getStatus())) {
                Platform.runLater(() -> {
                    n.isRead = true;
                    renderList();
                    if (onChange != null) onChange.run();
                });
            }
        }).start();
    }

    private String iconFor(String type) {
        if (type == null) return "🔔";
        return switch (type) {
            case "LOGIN_SUCCESS"          -> "🟢";
            case "ITEM_LISTED"            -> "📦";
            case "BID_PLACED"             -> "💰";
            case "BID_OUTBID"             -> "⚠️";
            case "AUTO_BID_MAX_REACHED"   -> "🤖";
            case "AUCTION_FINISHED"       -> "🏁";
            case "AUCTION_WON"            -> "🏆";
            case "AUCTION_LOST"           -> "🥈";
            case "AUCTION_RESULT_SELLER"  -> "📣";
            case "AUCTION_PAID"           -> "✅";
            case "AUCTION_CANCELED"       -> "❌";
            case "INFO_COMPLETION_FAILED" -> "⏰";
            case "AUCTION_RELISTED"       -> "🔄";
            default                        -> "🔔";
        };
    }

    private String formatTime(String s) {
        if (s == null || s.isBlank()) return "";
        try {
            LocalDateTime t = LocalDateTime.parse(s.replace(' ', 'T'));
            return t.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"));
        } catch (Exception e) {
            return s;
        }
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────
    public static class NotifRow {
        public int     id;
        public String  type;
        public String  title;
        public String  message;
        public boolean isRead;
        public String  createdAt;
        public Integer relatedAuctionId;
    }
}

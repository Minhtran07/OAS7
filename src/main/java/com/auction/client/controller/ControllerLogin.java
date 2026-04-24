package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.shared.model.user.Admin;
import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.event.ActionEvent;
import javafx.scene.control.TextFormatter;
import com.auction.client.util.SceneUtil;

import java.io.IOException;

public class ControllerLogin {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label messageLabel;
    @FXML private Button loginButton;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        // Dùng TextFormatter để chặn ký tự non-ASCII ngay tại nguồn
        // (triệt để hơn listener, không bị race condition với IME tiếng Việt)
        TextFormatter<String> usernameFormatter = new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[\\x00-\\x7F]*")) return change;
            return null;
        });
        TextFormatter<String> passwordFormatter = new TextFormatter<>(change -> {
            String newText = change.getControlNewText();
            if (newText.matches("[\\x00-\\x7F]*")) return change;
            return null;
        });
        usernameField.setTextFormatter(usernameFormatter);
        passwordField.setTextFormatter(passwordFormatter);
    }

    @FXML
    private void handleLogin(ActionEvent event) {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty() || password.isEmpty()) {
            messageLabel.setText("Vui lòng nhập đầy đủ thông tin!");
            return;
        }

        // Validate password length
        if (password.length() < 6) {
            messageLabel.setText("Mật khẩu phải có ít nhất 6 ký tự!");
            return;
        }

        // Disable button to prevent double-click
        if (loginButton != null) loginButton.setDisable(true);
        messageLabel.setText("Đang đăng nhập...");

        String payload = gson.toJson(new LoginPayload(username, password));

        // Move network call off JavaFX thread
        new Thread(() -> {
            Response response;
            try {
                response = ServerConnection.getInstance().sendRequest("LOGIN", payload);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (loginButton != null) loginButton.setDisable(false);
                    showConnectionError("Không thể kết nối đến máy chủ:\n" + ex.getMessage());
                });
                return;
            }

            final Response res = response;
            Platform.runLater(() -> {
                if (loginButton != null) loginButton.setDisable(false);

                if ("SUCCESS".equals(res.getStatus())) {
                    try {
                        User user = deserializeUser(res.getPayload());
                        UserSession.getInstance().setCurrentUser(user);

                        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
                        SceneUtil.navigate(stage,
                                MainClient.class.getResource("/client/fxml/auction_list.fxml"),
                                "Auction — Danh sách phiên đấu giá");

                    } catch (IOException e) {
                        showConnectionError("Không thể tải màn hình danh sách: " + e.getMessage());
                    } catch (Exception e) {
                        showConnectionError("Lỗi xử lý phản hồi: " + e.getMessage());
                    }
                } else {
                    messageLabel.setStyle("-fx-text-fill: #e74c3c;");
                    messageLabel.setText(res.getMessage() != null ? res.getMessage() : "Đăng nhập thất bại!");
                }
            });
        }).start();
    }

    /**
     * Deserializes a User JSON string into the correct concrete subclass
     * (Bidder / Seller / Admin) based on the "role" field, because User is abstract.
     */
    private User deserializeUser(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload người dùng rỗng");
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String role = obj.has("role") ? obj.get("role").getAsString() : "BIDDER";

        return switch (role) {
            case "SELLER" -> gson.fromJson(json, Seller.class);
            case "ADMIN"  -> gson.fromJson(json, Admin.class);
            default       -> gson.fromJson(json, Bidder.class);
        };
    }

    private void showConnectionError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi kết nối");
        alert.setHeaderText("Không thể kết nối đến máy chủ");
        alert.setContentText(message);
        alert.showAndWait();
    }

    @FXML
    private void switchToRegister(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/register.fxml"),
                "Đăng ký tài khoản");
    }

    @FXML
    private void switchToHome(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/home.fxml"),
                "Online Auction System");
    }

    // ─── Inner DTO ────────────────────────────────────────────────────────────
    private static class LoginPayload {
        String username;
        String password;
        LoginPayload(String u, String p) { this.username = u; this.password = p; }
    }
}

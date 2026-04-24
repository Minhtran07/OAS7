package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.event.ActionEvent;
import javafx.scene.control.TextFormatter;
import com.auction.client.util.SceneUtil;

import java.io.IOException;
import java.util.regex.Pattern;

public class ControllerRegister {

    // Email regex: basic RFC-style check
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    @FXML private TextField     usernameField;
    @FXML private TextField     fullnameField;
    @FXML private TextField     emailField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private ComboBox<String> roleComboBox;
    @FXML private TextField     storeNameField;
    @FXML private Label         messageLabel;
    @FXML private Button        registerButton;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        roleComboBox.setItems(FXCollections.observableArrayList("BIDDER", "SELLER"));

        // Dùng TextFormatter để chặn ký tự non-ASCII ngay tại nguồn
        for (TextField field : new TextField[]{usernameField, emailField, storeNameField,
                                               passwordField, confirmPasswordField}) {
            field.setTextFormatter(new TextFormatter<>(change -> {
                if (change.getControlNewText().matches("[\\x00-\\x7F]*")) return change;
                return null;
            }));
        }
        // Hiện ô storeName chỉ khi chọn SELLER
        roleComboBox.valueProperty().addListener((obs, oldVal, newVal) ->
                storeNameField.setVisible("SELLER".equals(newVal)));
    }

    @FXML
    private void handleRegister(ActionEvent event) {
        String username = usernameField.getText().trim();
        String fullname = fullnameField.getText().trim();
        String email    = emailField.getText().trim();
        String password = passwordField.getText();          // don't trim passwords
        String confirm  = confirmPasswordField.getText();
        String role     = roleComboBox.getValue();

        // ── Validate ────────────────────────────────────────────────────────
        if (username.isEmpty() || fullname.isEmpty() || email.isEmpty()
                || password.isEmpty() || role == null) {
            setError("Vui lòng điền đầy đủ thông tin!");
            return;
        }
        if (username.length() < 4) {
            setError("Tên đăng nhập phải có ít nhất 4 ký tự!");
            return;
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            setError("Địa chỉ email không hợp lệ!");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            setError("Mật khẩu phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự!");
            return;
        }
        if (!password.equals(confirm)) {
            setError("Mật khẩu xác nhận không khớp!");
            return;
        }

        // ── Build payload ────────────────────────────────────────────────────
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("fullname", fullname);
        payload.addProperty("email", email);
        payload.addProperty("role", role);
        if ("SELLER".equals(role)) {
            String storeName = storeNameField.getText().trim();
            payload.addProperty("storeName",
                    storeName.isEmpty() ? "Cửa hàng của " + fullname : storeName);
        }

        // ── Send off JavaFX thread ───────────────────────────────────────────
        if (registerButton != null) registerButton.setDisable(true);
        messageLabel.setStyle("-fx-text-fill: #2980b9;");
        messageLabel.setText("Đang đăng ký...");

        final String payloadStr = payload.toString();
        final ActionEvent savedEvent = event;

        new Thread(() -> {
            Response response;
            try {
                response = ServerConnection.getInstance().sendRequest("REGISTER", payloadStr);
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (registerButton != null) registerButton.setDisable(false);
                    showConnectionError("Không thể kết nối đến máy chủ:\n" + ex.getMessage());
                });
                return;
            }

            final Response res = response;
            Platform.runLater(() -> {
                if (registerButton != null) registerButton.setDisable(false);

                if ("SUCCESS".equals(res.getStatus())) {
                    messageLabel.setStyle("-fx-text-fill: #27ae60;");
                    messageLabel.setText("Đăng ký thành công! Đang chuyển về trang đăng nhập...");

                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> {
                            try { goToLogin(savedEvent); } catch (IOException ignored) {}
                        });
                    }).start();

                } else {
                    setError(res.getMessage() != null ? res.getMessage() : "Đăng ký thất bại!");
                }
            });
        }).start();
    }

    @FXML
    private void switchToLogin(ActionEvent event) throws IOException {
        goToLogin(event);
    }

    private void goToLogin(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/login.fxml"),
                "Đăng nhập");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setError(String msg) {
        messageLabel.setStyle("-fx-text-fill: #e74c3c;");
        messageLabel.setText(msg);
    }

    private void showConnectionError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi kết nối");
        alert.setHeaderText("Không thể kết nối đến máy chủ");
        alert.setContentText(message);
        alert.showAndWait();
    }
}

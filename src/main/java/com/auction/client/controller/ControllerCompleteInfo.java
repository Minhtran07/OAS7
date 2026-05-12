package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

/**
 * Form Hoàn thiện thông tin sau khi thắng phiên.
 * Mở dưới dạng modal popup từ ControllerBidding.
 */
public class ControllerCompleteInfo {

    @FXML private Label       headerLabel;
    @FXML private TextField   fullNameField;
    @FXML private TextField   phoneField;
    @FXML private TextArea    addressField;
    @FXML private RadioButton codRadio;
    @FXML private RadioButton bankRadio;
    @FXML private ToggleGroup paymentGroup;
    @FXML private VBox        bankBox;
    @FXML private TextField   bankAccountField;
    @FXML private Label       messageLabel;
    @FXML private Button      submitButton;

    private final Gson gson = new Gson();
    private int    auctionId;
    private double finalPrice;
    private Stage  stage;
    private Runnable onCompleted;

    @FXML
    public void initialize() {
        // Hiện/ẩn ô số tài khoản theo selection
        paymentGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            boolean bank = sel == bankRadio;
            bankBox.setVisible(bank);
            bankBox.setManaged(bank);
        });

        // Prefill từ user session
        if (UserSession.getInstance().isLoggedIn()) {
            String fullname = UserSession.getInstance().getCurrentUser().getFullname();
            if (fullname != null && !fullname.isBlank()) fullNameField.setText(fullname);
        }
    }

    public static void openAsPopup(Stage owner, int auctionId, double finalPrice,
                                   Runnable onCompleted) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                MainClient.class.getResource("/client/fxml/complete_info.fxml"));
        Parent root = loader.load();
        ControllerCompleteInfo c = loader.getController();
        c.auctionId = auctionId;
        c.finalPrice = finalPrice;
        c.onCompleted = onCompleted;
        c.headerLabel.setText(String.format("Phiên #%d — Giá thắng: %,.0f VNĐ", auctionId, finalPrice));

        Stage s = new Stage();
        s.initOwner(owner);
        s.initModality(Modality.WINDOW_MODAL);
        s.initStyle(StageStyle.DECORATED);
        s.setTitle("Hoàn thiện thông tin");
        s.setScene(new Scene(root));
        c.stage = s;
        s.show();

        // Sau khi mở: tự load thông tin đã có (nếu PAID) để hiển thị
        new Thread(c::tryLoadExistingInfo).start();
    }

    private void tryLoadExistingInfo() {
        JsonObject p = new JsonObject();
        p.addProperty("auctionId", auctionId);
        Response r = ServerConnection.getInstance()
                .sendRequest("GET_AUCTION_INFO_STATUS", p.toString());
        if (!"SUCCESS".equals(r.getStatus()) || r.getPayload() == null) return;
        try {
            JsonObject obj = gson.fromJson(r.getPayload(), JsonObject.class);
            if (!obj.has("bidderInfo") || obj.get("bidderInfo").isJsonNull()) return;
            JsonObject info = obj.getAsJsonObject("bidderInfo");
            String fullName = info.has("fullName") && !info.get("fullName").isJsonNull()
                    ? info.get("fullName").getAsString() : "";
            String phone   = info.has("phone") && !info.get("phone").isJsonNull()
                    ? info.get("phone").getAsString() : "";
            String address = info.has("address") && !info.get("address").isJsonNull()
                    ? info.get("address").getAsString() : "";
            String method  = info.has("paymentMethod") && !info.get("paymentMethod").isJsonNull()
                    ? info.get("paymentMethod").getAsString() : "COD";
            String bank    = info.has("bankAccount") && !info.get("bankAccount").isJsonNull()
                    ? info.get("bankAccount").getAsString() : "";

            String status = obj.has("status") ? obj.get("status").getAsString() : "FINISHED";
            boolean isPaid = "PAID".equalsIgnoreCase(status);

            Platform.runLater(() -> {
                fullNameField.setText(fullName);
                phoneField.setText(phone);
                addressField.setText(address);
                if ("BANK".equalsIgnoreCase(method)) {
                    bankRadio.setSelected(true);
                    bankAccountField.setText(bank);
                } else {
                    codRadio.setSelected(true);
                }
                if (isPaid) {
                    submitButton.setText("Đã hoàn thiện");
                    submitButton.setDisable(true);
                    showMessage("Thông tin đã được hoàn thiện trước đó.", true);
                }
            });
        } catch (Exception ignore) {}
    }

    @FXML
    private void handleSubmit() {
        String fullName = fullNameField.getText() == null ? "" : fullNameField.getText().trim();
        String phone    = phoneField.getText()    == null ? "" : phoneField.getText().trim();
        String address  = addressField.getText()  == null ? "" : addressField.getText().trim();
        String method   = bankRadio.isSelected() ? "BANK" : "COD";
        String bankAcc  = bankAccountField.getText() == null ? "" : bankAccountField.getText().trim();

        if (fullName.isEmpty()) { showMessage("Vui lòng nhập họ tên!", false); return; }
        if (phone.isEmpty())    { showMessage("Vui lòng nhập số điện thoại!", false); return; }
        if (address.isEmpty())  { showMessage("Vui lòng nhập địa chỉ nhận hàng!", false); return; }
        if ("BANK".equals(method) && bankAcc.isEmpty()) {
            showMessage("Vui lòng nhập số tài khoản ngân hàng!", false); return;
        }
        if (!UserSession.getInstance().isLoggedIn()) {
            showMessage("Phiên đăng nhập hết hạn — vui lòng đăng nhập lại.", false); return;
        }
        int bidderId = UserSession.getInstance().getCurrentUser().getId();

        JsonObject p = new JsonObject();
        p.addProperty("auctionId", auctionId);
        p.addProperty("bidderId", bidderId);
        p.addProperty("fullName", fullName);
        p.addProperty("phone", phone);
        p.addProperty("address", address);
        p.addProperty("paymentMethod", method);
        p.addProperty("bankAccount", bankAcc);

        submitButton.setDisable(true);
        showMessage("Đang gửi...", true);

        new Thread(() -> {
            Response r = ServerConnection.getInstance()
                    .sendRequest("COMPLETE_AUCTION_INFO", p.toString());
            Platform.runLater(() -> {
                submitButton.setDisable(false);
                if ("SUCCESS".equals(r.getStatus())) {
                    showMessage("✅ Hoàn thiện thông tin thành công! Cảm ơn bạn.", true);
                    if (onCompleted != null) onCompleted.run();
                    // Tự đóng sau 1s
                    new Thread(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
                        Platform.runLater(() -> { if (stage != null) stage.close(); });
                    }).start();
                } else {
                    showMessage("❌ " + (r.getMessage() != null ? r.getMessage() : "Thất bại"), false);
                }
            });
        }).start();
    }

    @FXML
    private void handleClose() {
        if (stage != null) stage.close();
    }

    private void showMessage(String msg, boolean success) {
        messageLabel.setText(msg);
        messageLabel.setStyle(success
                ? "-fx-text-fill: #16a34a; -fx-font-size: 12px;"
                : "-fx-text-fill: #dc2626; -fx-font-size: 12px;");
    }
}

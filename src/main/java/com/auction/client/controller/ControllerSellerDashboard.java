package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.ServerConnection;
import com.auction.client.session.UserSession;
import com.auction.client.util.MoneyField;
import com.auction.client.util.SceneUtil;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller cho màn hình Seller Dashboard.
 * Chức năng: xem / thêm / sửa / xóa item; tạo phiên đấu giá.
 */
public class ControllerSellerDashboard {

    // ─── Top bar ─────────────────────────────────────────────────────────────
    @FXML private Label welcomeLabel;

    // ─── Item table (Tab 1) ──────────────────────────────────────────────────
    @FXML private Label                 statusLabel;
    @FXML private TableView<ItemRow>    itemTable;
    @FXML private TableColumn<ItemRow, String> colId;
    @FXML private TableColumn<ItemRow, String> colName;
    @FXML private TableColumn<ItemRow, String> colCategory;
    @FXML private TableColumn<ItemRow, String> colPrice;
    @FXML private TableColumn<ItemRow, String> colActions;

    // ─── Create auction (Tab 2) ──────────────────────────────────────────────
    @FXML private TableView<ItemRow>    auctionItemTable;
    @FXML private TableColumn<ItemRow, String> aColId;
    @FXML private TableColumn<ItemRow, String> aColName;
    @FXML private TableColumn<ItemRow, String> aColCat;
    @FXML private TableColumn<ItemRow, String> aColPrice;
    @FXML private Label     auctionItemNameLabel;
    @FXML private TextField auctionDurationField;
    @FXML private Button    createAuctionButton;
    @FXML private Label     auctionMessageLabel;
    private int selectedAuctionItemId = -1;

    // ─── Item form ────────────────────────────────────────────────────────────
    @FXML private Label         formTitleLabel;
    @FXML private TextField     nameField;
    @FXML private ComboBox<String> categoryComboBox;
    @FXML private TextField     priceField;
    @FXML private TextArea      descField;

    // Extra fields (thay đổi theo category)
    @FXML private VBox      extraBox;
    @FXML private VBox      extra1Box;       // wrapper VBox bao extra1Label + extra1Field
    @FXML private VBox      extra2Box;       // wrapper VBox bao extra2Label + extra2Field
    @FXML private VBox      extraIntBox;     // wrapper VBox bao extraIntLabel + extraIntField
    @FXML private Label     extra1Label;
    @FXML private TextField extra1Field;
    @FXML private Label     extra2Label;
    @FXML private TextField extra2Field;
    @FXML private Label     extraIntLabel;
    @FXML private TextField extraIntField;

    @FXML private Label  formMessageLabel;
    @FXML private Button saveButton;

    // ─── State ───────────────────────────────────────────────────────────────
    private final Gson gson = new Gson();
    private final ObservableList<ItemRow> tableData = FXCollections.observableArrayList();
    private int editingItemId = -1; // -1 = mode thêm mới, >0 = mode sửa

    // ─── Initialize ──────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Welcome label
        if (UserSession.getInstance().isLoggedIn()) {
            welcomeLabel.setText(UserSession.getInstance().getCurrentUser().getFullname());
        }

        // Format tiền có dấu phẩy
        MoneyField.attach(priceField);

        // Category combo
        categoryComboBox.setItems(FXCollections.observableArrayList("ART", "ELECTRONICS", "VEHICLE"));
        categoryComboBox.setValue("ART");
        updateExtraFields("ART");
        categoryComboBox.valueProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) updateExtraFields(newVal);
        });

        // Table columns
        colId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().id)));
        // Name + badge trạng thái (IN_AUCTION / SOLD)
        colName.setCellValueFactory(c -> {
            ItemRow r = c.getValue();
            String label = r.name;
            if ("IN_AUCTION".equals(r.status))  label += "  🔨 Đang đấu giá";
            else if ("SOLD".equals(r.status))   label += "  ✅ Đã bán";
            else if ("CLOSED".equals(r.status)) label += "  ⛔ Đã đóng";
            return new SimpleStringProperty(label);
        });
        colCategory.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().category));
        colPrice.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%,.0f VNĐ", c.getValue().startingPrice)));

        // Actions column: Sửa + Xóa buttons — disable khi item đang đấu giá hoặc đã bán
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button btnEdit   = new Button("✏");
            private final Button btnDelete = new Button("🗑");
            {
                btnEdit.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-cursor: hand; -fx-font-size: 11px;");
                btnDelete.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-cursor: hand; -fx-font-size: 11px;");

                btnEdit.setOnAction(e -> {
                    ItemRow row = getTableView().getItems().get(getIndex());
                    loadItemIntoForm(row);
                });
                btnDelete.setOnAction(e -> {
                    ItemRow row = getTableView().getItems().get(getIndex());
                    handleDeleteItem(row);
                });
            }

            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    ItemRow row = getTableView().getItems().get(getIndex());
                    boolean locked = row.status != null && !"OPEN".equals(row.status);
                    btnEdit.setDisable(locked);
                    btnDelete.setDisable(locked);
                    if (locked) {
                        btnEdit.setStyle("-fx-background-color: #bdc3c7; -fx-text-fill: white; -fx-font-size: 11px;");
                        btnDelete.setStyle("-fx-background-color: #bdc3c7; -fx-text-fill: white; -fx-font-size: 11px;");
                    } else {
                        btnEdit.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white; -fx-cursor: hand; -fx-font-size: 11px;");
                        btnDelete.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-cursor: hand; -fx-font-size: 11px;");
                    }
                    javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(4, btnEdit, btnDelete);
                    setGraphic(box);
                }
            }
        });

        itemTable.setItems(tableData);

        // ─── Bảng Tab 2: chọn sản phẩm để tạo phiên đấu giá ────────────────
        aColId.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().id)));
        aColName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name));
        aColCat.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().category));
        aColPrice.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%,.0f VNĐ", c.getValue().startingPrice)));
        // CHỈ hiển thị item có status OPEN (chưa đấu giá, chưa bán) — tránh tạo trùng phiên
        FilteredList<ItemRow> availableItems = new FilteredList<>(
                tableData, r -> r.status == null || "OPEN".equals(r.status));
        auctionItemTable.setItems(availableItems);

        auctionItemTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                selectedAuctionItemId = sel.id;
                auctionItemNameLabel.setText(sel.name + "  (ID: " + sel.id
                        + " — Giá khởi điểm: " + String.format("%,.0f VNĐ", sel.startingPrice) + ")");
                auctionItemNameLabel.setStyle(
                        "-fx-font-size: 13px; -fx-text-fill: #1a1a2e; -fx-font-weight: bold;");
            }
        });

        loadMyItems();
    }

    // ─── Load items ──────────────────────────────────────────────────────────

    @FXML
    private void handleRefresh() {
        loadMyItems();
    }

    private void loadMyItems() {
        statusLabel.setText("Đang tải...");
        tableData.clear();

        int sellerId = UserSession.getInstance().getCurrentUser().getId();
        JsonObject payload = new JsonObject();
        payload.addProperty("sellerId", sellerId);

        new Thread(() -> {
            Response response = ServerConnection.getInstance()
                    .sendRequest("GET_MY_ITEMS", payload.toString());
            Platform.runLater(() -> {
                if ("SUCCESS".equals(response.getStatus()) && response.getPayload() != null) {
                    List<ItemRow> items = parseItems(response.getPayload());
                    tableData.addAll(items);
                    long openCount = items.stream()
                            .filter(r -> r.status == null || "OPEN".equals(r.status))
                            .count();
                    statusLabel.setText("Tổng: " + items.size() + " sản phẩm (sẵn sàng đấu giá: "
                            + openCount + ")");
                } else {
                    statusLabel.setText("Lỗi: " + response.getMessage());
                }
            });
        }).start();
    }

    private List<ItemRow> parseItems(String json) {
        List<ItemRow> list = new ArrayList<>();
        try {
            JsonArray arr = gson.fromJson(json, JsonArray.class);
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                ItemRow r = new ItemRow();
                r.id           = obj.get("id").getAsInt();
                r.name         = obj.get("name").getAsString();
                r.category     = obj.get("category").getAsString();
                r.startingPrice = obj.get("startingPrice").getAsDouble();
                r.description  = obj.has("description") ? obj.get("description").getAsString() : "";
                r.artist       = obj.has("artist") && !obj.get("artist").isJsonNull()
                                     ? obj.get("artist").getAsString() : "";
                r.material     = obj.has("material") && !obj.get("material").isJsonNull()
                                     ? obj.get("material").getAsString() : "";
                r.brand        = obj.has("brand") && !obj.get("brand").isJsonNull()
                                     ? obj.get("brand").getAsString() : "";
                r.warrantyPeriod = obj.has("warrantyPeriod") ? obj.get("warrantyPeriod").getAsInt() : 0;
                r.year         = obj.has("year") ? obj.get("year").getAsInt() : 0;
                r.status       = (obj.has("status") && !obj.get("status").isJsonNull())
                                     ? obj.get("status").getAsString() : "OPEN";
                list.add(r);
            }
        } catch (Exception e) {
            statusLabel.setText("Lỗi parse dữ liệu.");
        }
        return list;
    }

    // ─── Form — thêm / sửa ───────────────────────────────────────────────────

    private void loadItemIntoForm(ItemRow row) {
        editingItemId = row.id;
        formTitleLabel.setText("✏ Sửa sản phẩm #" + row.id);
        saveButton.setText("💾 Cập nhật");

        nameField.setText(row.name);
        descField.setText(row.description);
        priceField.setText(String.format("%,.0f", row.startingPrice).replace(".", ","));

        String cat = row.category != null ? row.category : "ART";
        categoryComboBox.setValue(cat);
        updateExtraFields(cat);

        switch (cat) {
            case "ART" -> {
                extra1Field.setText(row.artist);
                extra2Field.setText(row.material);
            }
            case "ELECTRONICS" -> {
                extra1Field.setText(row.brand);
                extraIntField.setText(String.valueOf(row.warrantyPeriod));
            }
            case "VEHICLE" -> {
                extraIntField.setText(row.year > 0 ? String.valueOf(row.year) : "");
            }
        }

        formMessageLabel.setText("Đang chỉnh sửa item #" + row.id + ". Nhấn 'Cập nhật' để lưu.");
        formMessageLabel.setStyle("-fx-text-fill: #2980b9;");
    }

    @FXML
    private void handleClearForm() {
        editingItemId = -1;
        formTitleLabel.setText("➕ Thêm sản phẩm mới");
        saveButton.setText("💾 Lưu sản phẩm");
        nameField.clear();
        descField.clear();
        priceField.clear();
        extra1Field.clear();
        extra2Field.clear();
        extraIntField.clear();
        categoryComboBox.setValue("ART");
        formMessageLabel.setText("");
    }

    @FXML
    private void handleSaveItem() {
        // Validate
        String name = nameField.getText().trim();
        String category = categoryComboBox.getValue();
        double price = MoneyField.getValue(priceField);

        if (name.isEmpty()) { setFormError("Vui lòng nhập tên sản phẩm!"); return; }
        if (price <= 0)     { setFormError("Vui lòng nhập giá khởi điểm hợp lệ!"); return; }

        int sellerId = UserSession.getInstance().getCurrentUser().getId();
        String desc = descField.getText().trim();
        String extra1 = extra1Field.getText().trim();
        String extra2 = extra2Field.getText().trim();
        int extraInt = 0;
        String extraIntStr = extraIntField.getText().trim();
        if (!extraIntStr.isEmpty()) {
            try { extraInt = Integer.parseInt(extraIntStr); } catch (NumberFormatException e) {
                setFormError("Giá trị số (" + extraIntLabel.getText() + ") không hợp lệ!"); return;
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("sellerId", sellerId);
        payload.addProperty("name", name);
        payload.addProperty("description", desc);
        payload.addProperty("startingPrice", price);
        payload.addProperty("category", category);
        payload.addProperty("extra1", extra1);
        payload.addProperty("extra2", extra2);
        payload.addProperty("extraInt", extraInt);

        if (saveButton != null) saveButton.setDisable(true);

        if (editingItemId > 0) {
            // UPDATE
            payload.addProperty("itemId", editingItemId);
            new Thread(() -> {
                Response resp = ServerConnection.getInstance()
                        .sendRequest("UPDATE_ITEM", payload.toString());
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    if ("SUCCESS".equals(resp.getStatus())) {
                        setFormSuccess("✅ Cập nhật thành công!");
                        handleClearForm();
                        loadMyItems();
                    } else {
                        setFormError("❌ " + resp.getMessage());
                    }
                });
            }).start();
        } else {
            // ADD
            new Thread(() -> {
                Response resp = ServerConnection.getInstance()
                        .sendRequest("ADD_ITEM", payload.toString());
                Platform.runLater(() -> {
                    saveButton.setDisable(false);
                    if ("SUCCESS".equals(resp.getStatus())) {
                        setFormSuccess("✅ Thêm sản phẩm thành công!");
                        handleClearForm();
                        loadMyItems();
                    } else {
                        setFormError("❌ " + resp.getMessage());
                    }
                });
            }).start();
        }
    }

    // ─── Delete item ─────────────────────────────────────────────────────────

    private void handleDeleteItem(ItemRow row) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Xác nhận xóa");
        confirm.setHeaderText("Xóa sản phẩm: " + row.name);
        confirm.setContentText("Hành động này không thể hoàn tác. Tiếp tục?");

        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                int sellerId = UserSession.getInstance().getCurrentUser().getId();
                JsonObject payload = new JsonObject();
                payload.addProperty("itemId", row.id);
                payload.addProperty("sellerId", sellerId);

                new Thread(() -> {
                    Response resp = ServerConnection.getInstance()
                            .sendRequest("DELETE_ITEM", payload.toString());
                    Platform.runLater(() -> {
                        if ("SUCCESS".equals(resp.getStatus())) {
                            statusLabel.setText("✅ Đã xóa sản phẩm #" + row.id);
                            loadMyItems();
                        } else {
                            statusLabel.setText("❌ " + resp.getMessage());
                        }
                    });
                }).start();
            }
        });
    }

    // ─── Create auction ──────────────────────────────────────────────────────

    // Lưu reference stage để dùng sau khi tạo phiên thành công
    private Stage currentStage;

    @FXML
    private void handleCreateAuction() {
        // Kiểm tra đã chọn sản phẩm chưa
        if (selectedAuctionItemId < 0) {
            setAuctionMsg("Vui lòng chọn sản phẩm từ bảng trước!", false);
            return;
        }

        // Đọc số giờ
        String durationStr = auctionDurationField.getText().trim();
        if (durationStr.isEmpty()) {
            setAuctionMsg("Vui lòng nhập thời gian đấu giá (số giờ)!", false);
            return;
        }
        int durationHours;
        try {
            durationHours = Integer.parseInt(durationStr);
            if (durationHours <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            setAuctionMsg("Thời gian đấu giá phải là số giờ nguyên dương!", false);
            return;
        }

        // Lấy giá khởi điểm từ item đã chọn
        int itemId = selectedAuctionItemId;
        double startingPrice = tableData.stream()
                .filter(r -> r.id == itemId)
                .findFirst()
                .map(r -> r.startingPrice)
                .orElse(0.0);

        if (startingPrice <= 0) {
            setAuctionMsg("Không tìm thấy sản phẩm trong danh sách!", false);
            return;
        }

        // Tự tạo thời gian bắt đầu = ngay bây giờ, kết thúc = bây giờ + số giờ
        LocalDateTime startTime = LocalDateTime.now();
        LocalDateTime endTime   = startTime.plusHours(durationHours);

        JsonObject payload = new JsonObject();
        payload.addProperty("itemId", itemId);
        payload.addProperty("startingPrice", startingPrice);
        payload.addProperty("startTime", startTime.toString());
        payload.addProperty("endTime", endTime.toString());

        if (createAuctionButton != null) {
            currentStage = (Stage) createAuctionButton.getScene().getWindow();
            createAuctionButton.setDisable(true);
        }

        new Thread(() -> {
            Response resp = ServerConnection.getInstance()
                    .sendRequest("CREATE_AUCTION", payload.toString());
            Platform.runLater(() -> {
                if (createAuctionButton != null) createAuctionButton.setDisable(false);
                if ("SUCCESS".equals(resp.getStatus())) {
                    setAuctionMsg("✅ Tạo phiên thành công! Đang chuyển sang danh sách đấu giá...", true);
                    auctionDurationField.clear();
                    selectedAuctionItemId = -1;
                    auctionItemNameLabel.setText("Chưa chọn sản phẩm — click vào dòng trong bảng");
                    auctionItemTable.getSelectionModel().clearSelection();

                    // Chuyển sang auction list sau 1.5 giây
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        Platform.runLater(() -> {
                            try {
                                SceneUtil.navigate(currentStage,
                                        MainClient.class.getResource("/client/fxml/auction_list.fxml"),
                                        "Auction — Danh sách phiên đấu giá");
                            } catch (IOException ignored) {}
                        });
                    }).start();
                } else {
                    setAuctionMsg("❌ " + resp.getMessage(), false);
                }
            });
        }).start();
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    @FXML
    private void handleBack(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/auction_list.fxml"),
                "Auction — Danh sách phiên đấu giá");
    }

    @FXML
    private void handleLogout(ActionEvent event) throws IOException {
        UserSession.getInstance().logout();
        ServerConnection.getInstance().disconnect();
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/home.fxml"),
                "Online Auction System");
    }

    // ─── Extra-field logic ────────────────────────────────────────────────────

    private void updateExtraFields(String category) {
        // Clear nội dung tất cả field khi đổi category (tránh giữ giá trị cũ sai ngữ cảnh)
        extra1Field.clear();
        extra2Field.clear();
        extraIntField.clear();

        switch (category) {
            case "ART" -> {
                extra1Label.setText("Nghệ sĩ (Artist):");
                extra1Field.setPromptText("Tên nghệ sĩ");
                extra2Label.setText("Chất liệu (Material):");
                extra2Field.setPromptText("VD: Sơn dầu");

                showBox(extra1Box,   true);
                showBox(extra2Box,   true);
                showBox(extraIntBox, false);
            }
            case "ELECTRONICS" -> {
                extra1Label.setText("Thương hiệu (Brand):");
                extra1Field.setPromptText("VD: Samsung, Apple");
                extraIntLabel.setText("Bảo hành (tháng):");
                extraIntField.setPromptText("VD: 12");

                showBox(extra1Box,   true);
                showBox(extra2Box,   false);
                showBox(extraIntBox, true);
            }
            case "VEHICLE" -> {
                extra1Label.setText("Mô tả thêm:");
                extra1Field.setPromptText("Màu, model...");
                extraIntLabel.setText("Năm sản xuất:");
                extraIntField.setPromptText("VD: 2020");

                showBox(extra1Box,   true);
                showBox(extra2Box,   false);
                showBox(extraIntBox, true);
            }
        }
    }

    /**
     * Ẩn/hiện một VBox wrapper đúng cách: đồng bộ visible + managed
     * để field ẩn KHÔNG chiếm không gian layout (tránh bug "nhảy text" do
     * field ẩn vẫn còn giữ value và chồng chéo).
     */
    private void showBox(VBox box, boolean show) {
        if (box == null) return;
        box.setVisible(show);
        box.setManaged(show);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void setFormError(String msg) {
        formMessageLabel.setStyle("-fx-text-fill: #e74c3c;");
        formMessageLabel.setText(msg);
    }

    private void setFormSuccess(String msg) {
        formMessageLabel.setStyle("-fx-text-fill: #27ae60;");
        formMessageLabel.setText(msg);
    }

    private void setAuctionMsg(String msg, boolean success) {
        auctionMessageLabel.setStyle(success
                ? "-fx-text-fill: #27ae60;" : "-fx-text-fill: #e74c3c;");
        auctionMessageLabel.setText(msg);
    }

    // ─── Inner DTO ────────────────────────────────────────────────────────────

    public static class ItemRow {
        public int    id;
        public String name;
        public String description;
        public String category;
        public double startingPrice;
        public String artist;
        public String material;
        public String brand;
        public int    warrantyPeriod;
        public int    year;
        public String status;   // OPEN | IN_AUCTION | SOLD | CLOSED
    }
}

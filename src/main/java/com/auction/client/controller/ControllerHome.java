package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.util.SceneUtil;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.io.IOException;

public class ControllerHome {

    @FXML
    private void switchToLogin(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/login.fxml"),
                "Đăng nhập");
    }

    /** Hiện dialog hướng dẫn sử dụng hệ thống. */
    @FXML
    private void showGuide(ActionEvent event) {
        String content = """
                📖 HƯỚNG DẪN SỬ DỤNG BIDNOW

                ① Đăng ký tài khoản
                   • Chọn vai trò: Người mua (Bidder) hoặc Người bán (Seller).
                   • Bidder cần nạp số dư ban đầu để tham gia đấu giá.

                ② Đăng nhập
                   • Nhập tên đăng nhập và mật khẩu đã đăng ký.

                ③ Người bán (Seller)
                   • Đăng sản phẩm mới: chọn danh mục → nhập mô tả, ảnh, giá khởi điểm.
                   • Tạo phiên đấu giá: chọn thời điểm bắt đầu và kết thúc.
                   • Theo dõi danh sách phiên đang chạy / đã kết thúc.

                ④ Người mua (Bidder)
                   • Duyệt danh sách phiên đang RUNNING.
                   • Vào phiên cụ thể để xem giá hiện tại và thông tin sản phẩm.
                   • Đặt giá thủ công, hoặc bật Auto-bid với giá trần.

                ⑤ Khi phiên kết thúc
                   • Người trả giá cao nhất sẽ thắng và bị trừ số dư.
                   • Người bán nhận được thông báo người thắng.

                💡 Lưu ý: Mọi thay đổi giá được cập nhật theo thời gian thực
                       cho tất cả người đang xem phiên.""";
        showInfoDialog("Hướng dẫn sử dụng", content);
    }

    /** Hiện dialog "Cách mua" — hướng dẫn nhanh cho người mua. */
    @FXML
    private void showHowToBuy(ActionEvent event) {
        String content = """
                🛒 CÁCH MUA TRÊN BIDNOW

                Bước 1 — Đăng ký tài khoản Bidder và đăng nhập.

                Bước 2 — Nạp số dư vào ví. Đây là số tiền tối đa
                         bạn có thể đặt cho các phiên đấu giá.

                Bước 3 — Vào trang chính, chọn phiên đấu giá đang RUNNING
                         (đang chạy) mà bạn quan tâm.

                Bước 4 — Xem thông tin sản phẩm, giá hiện tại,
                         và thời gian còn lại.

                Bước 5 — Nhập số tiền (lớn hơn giá hiện tại) → bấm Đặt giá.
                         Hoặc bật Auto-bid để hệ thống tự đấu giúp bạn
                         đến mức giá trần đã đặt.

                Bước 6 — Khi phiên kết thúc, nếu bạn là người trả cao nhất
                         → bạn thắng phiên, số tiền sẽ tự động bị trừ
                         khỏi số dư.

                ⚠️  Lưu ý:
                   • Không thể đặt giá nhỏ hơn hoặc bằng giá hiện tại.
                   • Số tiền đặt phải nhỏ hơn hoặc bằng số dư hiện có.
                   • Mọi thao tác đặt giá đều minh bạch, lưu lại lịch sử.""";
        showInfoDialog("Cách mua", content);
    }

    /** Helper: hiện Alert dạng INFORMATION với nội dung dài, có scroll nếu cần. */
    private void showInfoDialog(String title, String content) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);

        // Dùng Label thay cho contentText mặc định để xuống dòng đẹp + cho phép resize
        Label body = new Label(content);
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 13px; -fx-font-family: 'Arial';");
        body.setMinHeight(Region.USE_PREF_SIZE);
        alert.getDialogPane().setContent(body);
        alert.getDialogPane().setPrefWidth(520);
        alert.setResizable(true);
        alert.showAndWait();
    }
}

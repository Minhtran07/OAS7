package com.auction.client.util;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * Tiện ích chuyển scene mà GIỮ NGUYÊN kích thước cửa sổ hiện tại.
 * Không bao giờ set cứng width/height khi chuyển màn hình.
 */
public class SceneUtil {

    /**
     * Chuyển sang scene mới, giữ nguyên kích thước + trạng thái maximized của stage.
     */
    public static void navigate(Stage stage, URL fxmlUrl, String title) throws IOException {
        // Lưu kích thước hiện tại
        double w = stage.getWidth();
        double h = stage.getHeight();
        boolean maximized = stage.isMaximized();

        Parent root = FXMLLoader.load(fxmlUrl);
        stage.setScene(new Scene(root, w, h));
        stage.setTitle(title);
        if (maximized) stage.setMaximized(true);
        stage.show();
    }

    /**
     * Overload dùng FXMLLoader để lấy controller sau khi load.
     */
    public static FXMLLoader navigateWithLoader(Stage stage, URL fxmlUrl, String title) throws IOException {
        double w = stage.getWidth();
        double h = stage.getHeight();
        boolean maximized = stage.isMaximized();

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        stage.setScene(new Scene(root, w, h));
        stage.setTitle(title);
        if (maximized) stage.setMaximized(true);
        stage.show();
        return loader;
    }
}

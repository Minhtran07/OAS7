package com.auction.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainClient extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainClient.class);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        // Kết nối đến server khi app khởi động
        try {
            ServerConnection.getInstance().connect();
            logger.info("Đã kết nối đến server.");
        } catch (Exception e) {
            logger.warn("Không thể kết nối đến server khi khởi động: {}", e.getMessage());
            // Vẫn cho phép mở app, sẽ báo lỗi khi thao tác cần server
        }

        FXMLLoader fxmlLoader = new FXMLLoader(MainClient.class.getResource("/client/fxml/home.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 650, 550);

        stage.setTitle("Online Auction System");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(700);
        stage.setMinHeight(500);
        stage.show();

        // Ngắt kết nối khi đóng app
        stage.setOnCloseRequest(e -> {
            ServerConnection.getInstance().disconnect();
            logger.info("Đã ngắt kết nối với server.");
        });
    }
}

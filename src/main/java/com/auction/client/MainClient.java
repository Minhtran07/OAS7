package com.auction.client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainClient extends Application {
    public static void main(String[] args){
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception{
        FXMLLoader fxmlLoader = new FXMLLoader(MainClient.class.getResource("/client/fxml/home.fxml"));
        Scene scene = new Scene(fxmlLoader.load());

        stage.setTitle("Auction Client");
        stage.setScene(scene);
        stage.show();
    }
}

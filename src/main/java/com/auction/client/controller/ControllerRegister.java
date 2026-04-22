package com.auction.client.controller;

import com.auction.client.MainClient;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javafx.event.ActionEvent;
import java.io.IOException;

public class ControllerRegister {

    @FXML
    private void switchToLogin(ActionEvent event) throws IOException {
        Parent root = FXMLLoader.load(
                MainClient.class.getResource("/client/fxml/login.fxml")
        );
        Stage stage = (Stage) ((Node)event.getSource()).getScene().getWindow();
        stage.setScene(new Scene(root));
        stage.show();

        stage.setResizable(false);
    }
}

package com.auction.client.controller;

import com.auction.client.MainClient;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javafx.event.ActionEvent;

import java.io.IOException;

public class ControllerLogin {

    public void switchToRegister(ActionEvent event) throws IOException {
        Parent root = FXMLLoader.load(
                MainClient.class.getResource("/client/fxml/register.fxml")
        );
        Stage stage = (Stage) ((Node)event.getSource()).getScene().getWindow();
        stage.setScene(new Scene(root));
        stage.show();
    }
}
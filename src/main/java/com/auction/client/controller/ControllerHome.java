package com.auction.client.controller;

import com.auction.client.MainClient;
import com.auction.client.util.SceneUtil;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.event.ActionEvent;

import java.io.IOException;

public class ControllerHome {

    @FXML
    private void switchToLogin(ActionEvent event) throws IOException {
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        SceneUtil.navigate(stage,
                MainClient.class.getResource("/client/fxml/login.fxml"),
                "Đăng nhập");
    }
}

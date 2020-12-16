package com.ftp;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.File;

public class ClientGUI extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("resources/client.fxml"));
        stage.setTitle("Client");
        stage.setScene(new Scene(root, 600, 400));
        stage.getScene().getStylesheets().add(getClass().getResource("resources/client.css").toExternalForm());
        stage.setMinWidth(400);
        stage.setMinHeight(300);
        stage.show();
    }

    public static void main(String[] args) {
        // Create base dir if it doesn't exist
        File bd = new File(ClientController.BASE_DIR);
        if (!bd.exists()) {
            System.out.println("Base directory '" + ClientController.BASE_DIR + "' does not exist");
            if (bd.mkdir()) {
                System.out.println("Directory created");
            } else {
                System.out.println("Could not create directory. Errors will probably occur from now on");
            }
        }

        System.out.println("Launching GUI");
        launch(args);
    }
}

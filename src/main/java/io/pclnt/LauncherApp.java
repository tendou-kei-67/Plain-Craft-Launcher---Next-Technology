package io.pclnt;

import io.pclnt.ui.LauncherController;
import java.io.IOException;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class LauncherApp extends Application {
    public static final String NAME = "PCL-NT";
    public static final String FULL_NAME = "Plain Craft Launcher - Next Technology";

    @Override
    public void start(Stage stage) throws IOException {
        var loader = new FXMLLoader(LauncherApp.class.getResource("/io/pclnt/ui/launcher.fxml"));
        var scene = new Scene(loader.load(), 900, 550, Color.TRANSPARENT);
        if (Font.getFamilies().contains("Microsoft YaHei UI")) {
            scene.getRoot().getStyleClass().add("yahei-ui");
        }
        scene.getStylesheets().add(
                LauncherApp.class.getResource("/io/pclnt/ui/launcher.css").toExternalForm());

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(NAME + " (" + FULL_NAME + ")");
        stage.getIcons().addAll(
                new Image(LauncherApp.class.getResource("/io/pclnt/ui/pcl-icon-32.png").toExternalForm()),
                new Image(LauncherApp.class.getResource("/io/pclnt/ui/pcl-icon-48.png").toExternalForm()));
        stage.setMinWidth(810);
        stage.setMinHeight(470);
        stage.setScene(scene);
        LauncherController controller = loader.getController();
        controller.showWindow(stage, getHostServices());
    }

    public static void main(String[] args) {
        launch(args);
    }
}

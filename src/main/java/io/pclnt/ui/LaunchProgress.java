package io.pclnt.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ObservableNumberValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

final class LaunchProgress extends HBox {
    LaunchProgress(ObservableNumberValue progress) {
        setAccessibleText("启动进度");
        getStyleClass().add("pcl-launch-progress");
        setMinHeight(4);
        setPrefHeight(4);
        setMaxHeight(4);
        VBox.setMargin(this, new Insets(3, 10, 18, 10));
        var finished = new Region();
        finished.getStyleClass().add("launch-progress-finished");
        var rest = new Region();
        rest.getStyleClass().add("launch-progress-rest");
        setHgrow(rest, Priority.ALWAYS);
        var displayed = new SimpleDoubleProperty(Math.max(0, progress.doubleValue()));
        finished.prefWidthProperty().bind(widthProperty().multiply(displayed));
        finished.maxWidthProperty().bind(finished.prefWidthProperty());
        getChildren().addAll(finished, rest);
        progress.addListener(new javafx.beans.value.ChangeListener<Number>() {
            private Timeline motion;
            @Override public void changed(javafx.beans.value.ObservableValue<? extends Number> value, Number previous, Number next) {
                if (motion != null) motion.stop();
                motion = UiMotion.track(displayed, Math.clamp(next.doubleValue(), 0, 1), 0, 260, UiMotion.OUT_MIDDLE);
                motion.play();
            }
        });
    }

    static Optional<Node> trivia() {
        try (var input = LaunchProgress.class.getResourceAsStream("launch-trivia.txt")) {
            if (input == null) return Optional.empty();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                var tips = reader.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
                if (tips.isEmpty()) return Optional.empty();
                var text = LauncherController.label(tips.get(ThreadLocalRandom.current().nextInt(tips.size())), "launch-trivia-text");
                text.setMaxHeight(54);
                var card = new VBox(5, LauncherController.label("你知道吗", "version-caption"), text);
                card.setAlignment(Pos.TOP_CENTER);
                card.getStyleClass().add("launch-trivia");
                card.setOpacity(0);
                UiMotion.track(card.opacityProperty(), 1, 0, 100, javafx.animation.Interpolator.LINEAR).play();
                return Optional.of(card);
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}

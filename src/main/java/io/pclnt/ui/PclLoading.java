package io.pclnt.ui;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

/** The original pickaxe strike, including the two particles from MyLoading. */
final class PclLoading extends VBox {
    private final Label message = new Label();

    PclLoading(String text) {
        setAlignment(Pos.CENTER);
        setSpacing(10);
        getStyleClass().add("pcl-loading");
        var axe = new Label();
        axe.getStyleClass().add("pcl-loading-axe");
        PclArtwork.setIcon(axe, "loading-pickaxe", 35, 35);
        axe.setManaged(false);
        axe.resizeRelocate(10, 6, 35, 35);
        // MyLoading.xaml puts the axis near the handle end, in the path's local coordinates.
        var swing = new Rotate(55, 30, 30);
        axe.getTransforms().add(swing);
        var left = particle(7, -45);
        var right = particle(14, 45);
        var floor = new Rectangle(0, 45, 25, 2);
        floor.getStyleClass().add("pcl-loading-piece");
        var artwork = new Pane(axe, left, right, floor);
        artwork.setMinSize(60, 47);
        artwork.setPrefSize(60, 47);
        artwork.setMaxSize(60, 47);
        message.setText(text);
        message.setWrapText(true);
        message.getStyleClass().add("pcl-loading-text");
        getChildren().addAll(artwork, message);
        var motion = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(swing.angleProperty(), 55),
                        new KeyValue(left.opacityProperty(), 0), new KeyValue(right.opacityProperty(), 0),
                        new KeyValue(left.translateXProperty(), 0), new KeyValue(right.translateXProperty(), 0),
                        new KeyValue(left.translateYProperty(), 0), new KeyValue(right.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(250), new KeyValue(swing.angleProperty(), 55)),
                new KeyFrame(Duration.millis(600), new KeyValue(swing.angleProperty(), -20, UiMotion.BACK_IN_WEAK),
                        new KeyValue(left.opacityProperty(), 1, Interpolator.DISCRETE),
                        new KeyValue(right.opacityProperty(), 1, Interpolator.DISCRETE),
                        new KeyValue(left.translateXProperty(), 0),
                        new KeyValue(right.translateXProperty(), 0), new KeyValue(left.translateYProperty(), 0),
                        new KeyValue(right.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(650), new KeyValue(left.opacityProperty(), 1),
                        new KeyValue(right.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(750), new KeyValue(left.opacityProperty(), 0, Interpolator.LINEAR),
                        new KeyValue(right.opacityProperty(), 0, Interpolator.LINEAR)),
                new KeyFrame(Duration.millis(780), new KeyValue(left.translateXProperty(), -5, UiMotion.OUT_MIDDLE),
                        new KeyValue(right.translateXProperty(), 5, UiMotion.OUT_MIDDLE),
                        new KeyValue(left.translateYProperty(), -6, UiMotion.OUT_MIDDLE),
                        new KeyValue(right.translateYProperty(), -6, UiMotion.OUT_MIDDLE)),
                new KeyFrame(Duration.millis(1500), new KeyValue(swing.angleProperty(), 55,
                        UiMotion.additive(2.0 / 3, 900, 900, UiMotion.OUT_MIDDLE, UiMotion.ELASTIC_OUT_WEAK))));
        motion.setCycleCount(Animation.INDEFINITE);
        Runnable update = () -> {
            if (getScene() != null && isVisible()) motion.play();
            else motion.stop();
        };
        sceneProperty().addListener(ignored -> update.run());
        visibleProperty().addListener(ignored -> update.run());
    }

    private static Polygon particle(double x, double angle) {
        var piece = new Polygon(0, 0, 3, 0, 1.5, 5);
        piece.getStyleClass().add("pcl-loading-piece");
        piece.setLayoutX(x);
        piece.setLayoutY(41);
        piece.setRotate(angle);
        return piece;
    }
}

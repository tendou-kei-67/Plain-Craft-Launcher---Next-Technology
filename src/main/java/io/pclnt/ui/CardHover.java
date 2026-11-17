package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

final class CardHover {
    private CardHover() {}

    static void install(Pane card, Label title) {
        MaterialBackdrop.install(card);
        var base = ThemeMode.CardColors.of(null);
        var tint = new Rectangle();
        tint.getStyleClass().add("pcl-card-tint");
        tint.setManaged(false);
        tint.setMouseTransparent(true);
        tint.widthProperty().bind(card.widthProperty());
        tint.heightProperty().bind(card.heightProperty());
        tint.setArcWidth(10);
        tint.setArcHeight(10);
        tint.setOpacity(0);
        card.getChildren().add(1, tint);
        var shadow = new DropShadow(3, base.ink().deriveColor(0, 1, 1, .07));
        shadow.setOffsetY(1);
        card.setEffect(shadow);
        if (title != null) title.setTextFill(base.ink());
        var motion = new Timeline();
        var frame = new javafx.beans.property.SimpleObjectProperty<Node>();
        Runnable update = () -> {
            var colors = ThemeMode.CardColors.of(frame.get());
            tint.setFill(colors.surface());
            boolean over = card.isHover();
            var shade = (over ? colors.shadow() : colors.ink()).deriveColor(0, 1, 1, over ? .4 : .07);
            motion.stop();
            var values = new java.util.ArrayList<KeyValue>();
            values.add(new KeyValue(tint.opacityProperty(), over ? 1 : 0, Interpolator.LINEAR));
            values.add(new KeyValue(shadow.colorProperty(), shade, Interpolator.LINEAR));
            if (title != null) values.add(new KeyValue(title.textFillProperty(),
                    over ? colors.highlight() : colors.ink(), Interpolator.LINEAR));
            motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(90), values.toArray(KeyValue[]::new)));
            motion.play();
        };
        InvalidationListener themeChanged = ignored -> update.run();
        card.sceneProperty().addListener((observable, previous, scene) -> {
            if (frame.get() != null) frame.get().styleProperty().removeListener(themeChanged);
            frame.set(scene == null ? null : scene.lookup("#frame"));
            if (frame.get() != null) frame.get().styleProperty().addListener(themeChanged);
            update.run();
        });
        card.hoverProperty().addListener(ignored -> update.run());
    }
}

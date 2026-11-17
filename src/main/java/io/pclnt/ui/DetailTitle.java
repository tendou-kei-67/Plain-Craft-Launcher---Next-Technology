package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

final class DetailTitle {
    private final Node brand, navigation;
    private final HBox inner;
    private final Label text = new Label();
    private final SimpleBooleanProperty mainNavigationVisible = new SimpleBooleanProperty(true);
    private Runnable back;
    private ParallelTransition motion;

    DetailTitle(StackPane host, Node brand, Node navigation) {
        this.brand = brand;
        this.navigation = navigation;
        var button = TaskMonitor.iconButton("返回上一页", "lucide/arrow-left", 19, () -> {
            if (back != null) back.run();
        });
        button.getStyleClass().add("detail-title-back");
        text.getStyleClass().add("detail-title-text");
        text.setMinWidth(0);
        text.setMouseTransparent(true);
        inner = new HBox(7, button, text);
        inner.setPadding(new Insets(0, 140, 0, 12));
        inner.setAlignment(Pos.CENTER_LEFT);
        inner.setPickOnBounds(false);
        inner.setVisible(false);
        inner.setOpacity(0);
        inner.setTranslateX(-16);
        StackPane.setAlignment(inner, Pos.CENTER_LEFT);
        host.getChildren().add(0, inner);
    }

    void show(String title, Runnable action) {
        text.setText(title);
        if (back != null) {
            back = action;
            return;
        }
        back = action;
        change(true);
    }

    void clear() {
        if (back == null) return;
        back = null;
        change(false);
    }

    ReadOnlyBooleanProperty mainNavigationVisibleProperty() { return mainNavigationVisible; }

    private void change(boolean detail) {
        if (motion != null) motion.stop();
        if (detail) inner.setVisible(true);
        else {
            brand.setVisible(true);
            mainNavigationVisible.set(true);
        }
        navigation.setMouseTransparent(detail);
        inner.setMouseTransparent(!detail);
        motion = new ParallelTransition(
                UiMotion.track(brand.opacityProperty(), detail ? 0 : 1, detail ? 0 : 200, 150, Interpolator.LINEAR),
                UiMotion.track(navigation.opacityProperty(), detail ? 0 : 1, detail ? 0 : 200, 150, Interpolator.LINEAR),
                UiMotion.track(inner.opacityProperty(), detail ? 1 : 0, detail ? 200 : 0, 150, Interpolator.LINEAR),
                UiMotion.track(inner.translateXProperty(), detail ? 0 : -18, detail ? 200 : 0,
                        detail ? 350 : 150, detail ? UiMotion.OUT_STRONG : UiMotion.IN_WEAK));
        motion.setOnFinished(event -> {
            inner.setVisible(detail);
            brand.setVisible(!detail);
            mainNavigationVisible.set(!detail);
        });
        motion.play();
    }
}

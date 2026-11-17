package io.pclnt.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

final class Notifications {
    enum Kind {
        INFO("提示", 37, 155, 252, 10, 142, 252),
        SUCCESS("成功", 33, 177, 33, 29, 160, 29),
        ERROR("错误", 255, 53, 11, 255, 43, 0);

        final String title;
        final Color top;
        final Color bottom;

        Kind(String title, int r, int g, int b, int r2, int g2, int b2) {
            this.title = title;
            top = Color.rgb(r, g, b, 215 / 255.0);
            bottom = Color.rgb(r2, g2, b2, 215 / 255.0);
        }
    }

    private static final Interpolator DRAWER_EASE = Interpolator.SPLINE(.25, .1, .25, 1);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final CornerRadii TOAST_RADIUS = new CornerRadii(0, 6, 6, 0, false);
    private static final int MAX_TOASTS = 20;
    private static final int MAX_HISTORY = 100;

    private final StackPane overlay = new StackPane();
    private final StackPane floatingLayer = new StackPane();
    private final StackPane drawerLayer = new StackPane();
    private final VBox toasts = new VBox();
    private final VBox history = new VBox(8);
    private final Map<String, Toast> active = new HashMap<>();
    private final DoubleProperty progress = new SimpleDoubleProperty();
    private final Button openButton;
    private final Button closeButton = new Button();
    private final Label empty = LauncherController.label("暂无通知", "notification-muted");
    private Timeline drawerMotion;
    private boolean opening;

    Notifications(BorderPane frame, Button openButton) {
        this.openButton = openButton;
        openButton.setGraphic(bell());
        openButton.setTooltip(new Tooltip("通知中心"));
        openButton.setOnAction(event -> setOpen(true));
        closeButton.setAccessibleText("关闭通知中心");
        closeButton.getStyleClass().add("notification-close");
        PclArtwork.setIcon(closeButton, "close", 13);
        UiMotion.installIconPress(closeButton);
        closeButton.setOnAction(event -> setOpen(false));

        var title = LauncherController.label("通知中心", "notification-title");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(5, title, spacer, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("notification-header");
        var scroll = new ScrollPane(history);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(true);
        scroll.setAccessibleText("通知列表");
        scroll.getStyleClass().add("notification-scroll");
        history.getChildren().add(empty);
        var drawer = new BorderPane(scroll, header, null, null, null);
        drawer.setId("notificationDrawer");
        drawer.getStyleClass().add("notification-drawer");
        var width = frame.widthProperty().divide(4);
        drawer.minWidthProperty().bind(width);
        drawer.prefWidthProperty().bind(width);
        drawer.maxWidthProperty().bind(width);
        drawer.translateXProperty().bind(width.multiply(progress.negate().add(1)));
        StackPane.setAlignment(drawer, Pos.CENTER_RIGHT);

        var scrim = new Region();
        scrim.setId("notificationScrim");
        scrim.getStyleClass().add("notification-scrim");
        scrim.opacityProperty().bind(progress.multiply(.35));
        scrim.setOnMouseClicked(event -> setOpen(false));
        drawerLayer.getChildren().addAll(scrim, drawer);
        drawerLayer.setVisible(false);
        toasts.setId("notificationToasts");
        toasts.setFillWidth(false);
        toasts.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toasts.setMouseTransparent(true);
        StackPane.setAlignment(toasts, Pos.BOTTOM_LEFT);
        StackPane.setMargin(toasts, new Insets(0, 20, 20, 0));
        overlay.setManaged(false);
        overlay.setPickOnBounds(false);
        // The scrim and drawer must stay in front of the floating task button.
        floatingLayer.setPickOnBounds(false);
        overlay.getChildren().addAll(floatingLayer, toasts, drawerLayer);
        frame.getChildren().add(overlay);
        frame.widthProperty().addListener((observable, old, value) -> overlay.resize(frame.getWidth(), frame.getHeight()));
        frame.heightProperty().addListener((observable, old, value) -> overlay.resize(frame.getWidth(), frame.getHeight()));
        overlay.resize(frame.getWidth(), frame.getHeight());
        frame.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!drawerLayer.isVisible()) {
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE) {
                setOpen(false);
                event.consume();
            } else if (event.getCode() == KeyCode.TAB) {
                if (frame.getScene().getFocusOwner() == closeButton) scroll.requestFocus();
                else closeButton.requestFocus();
                event.consume();
            }
        });
    }

    StackPane floatingLayer() { return floatingLayer; }

    void post(String message, Kind kind) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> post(message, kind));
            return;
        }
        String text = message.replaceAll("\\R+", " ").strip();
        if (text.isEmpty()) {
            return;
        }
        addToHistory(text, kind);
        if (toasts.getChildren().size() >= MAX_TOASTS) {
            return;
        }
        var existing = active.get(text);
        if (existing != null) {
            if (!existing.entering) {
                existing.shake(kind);
            }
            return;
        }
        var toast = new Toast(text, kind);
        active.put(text, toast);
        toasts.getChildren().add(toast.label);
        toast.start();
    }

    private void addToHistory(String text, Kind kind) {
        history.getChildren().remove(empty);
        var meta = LauncherController.label(kind.title + " · " + TIME.format(LocalTime.now()), "notification-muted");
        var message = new Label(text);
        message.setWrapText(true);
        var entry = new VBox(4, meta, message);
        entry.getStyleClass().add("notification-entry");
        history.getChildren().addFirst(entry);
        if (history.getChildren().size() > MAX_HISTORY) {
            history.getChildren().removeLast();
        }
    }

    private void setOpen(boolean open) {
        if (open == opening) {
            return;
        }
        opening = open;
        if (drawerMotion != null) {
            drawerMotion.stop();
        }
        if (open) {
            drawerLayer.setVisible(true);
            closeButton.requestFocus();
        }
        int millis = (int) Math.max(1, Math.round(300 * Math.abs((open ? 1 : 0) - progress.get())));
        drawerMotion = UiMotion.track(progress, open ? 1 : 0, 0, millis, DRAWER_EASE);
        drawerMotion.setOnFinished(event -> {
            if (!opening) {
                drawerLayer.setVisible(false);
                openButton.requestFocus();
            }
        });
        drawerMotion.play();
    }

    private static SVGPath bell() {
        var path = new SVGPath();
        path.setContent("M 4 10 V 7 A 4 4 0 0 1 12 7 V 10 L 14 12 V 13 H 2 V 12 Z M 6.5 15 Q 8 17 9.5 15");
        path.setFill(Color.TRANSPARENT);
        path.setStroke(Color.WHITE);
        path.setStrokeWidth(1.4);
        path.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        path.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        return path;
    }

    private final class Toast {
        final String text;
        final Label label;
        final DoubleProperty height = new SimpleDoubleProperty();
        final DoubleProperty tint = new SimpleDoubleProperty(.3);
        Kind kind;
        boolean entering = true;
        ParallelTransition shake;
        PauseTransition lifetime;

        Toast(String text, Kind kind) {
            this.text = text;
            this.kind = kind;
            label = new Label(text);
            label.getStyleClass().add("notification-toast");
            label.setAlignment(Pos.CENTER_LEFT);
            label.setMinWidth(0);
            label.maxWidthProperty().bind(overlay.widthProperty().subtract(20));
            label.setOpacity(0);
            label.setTranslateX(-70);
            label.minHeightProperty().bind(height);
            label.prefHeightProperty().bind(height);
            label.maxHeightProperty().bind(height);
            var clip = new Rectangle();
            clip.widthProperty().bind(label.widthProperty());
            clip.heightProperty().bind(height);
            label.setClip(clip);
            label.backgroundProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() ->
                    new Background(new BackgroundFill(new LinearGradient(0, 0, 0, 1, true,
                            javafx.scene.paint.CycleMethod.NO_CYCLE,
                            new Stop(0, Color.WHITE.interpolate(this.kind.top, tint.get())),
                            new Stop(1, Color.WHITE.interpolate(this.kind.bottom, tint.get()))),
                            TOAST_RADIUS, Insets.EMPTY)), tint));
        }

        void start() {
            boolean alone = toasts.getChildren().size() == 1;
            height.set(alone ? 26 : 0);
            var motion = new ParallelTransition(
                    UiMotion.track(label.translateXProperty(), -20, 0, 400, UiMotion.TOAST_ENTER),
                    UiMotion.track(label.opacityProperty(), 1, 0, 100, Interpolator.LINEAR),
                    UiMotion.track(tint, 1, 100, 250, Interpolator.LINEAR),
                    UiMotion.track(height, 26, 0, alone ? 1 : 150, UiMotion.OUT_MIDDLE));
            motion.setOnFinished(event -> entering = false);
            motion.play();
            waitThenExit();
        }

        void shake(Kind newKind) {
            if (shake != null) {
                shake.stop();
            }
            kind = newKind;
            tint.set(.3);
            shake = new ParallelTransition(new Timeline(
                    new KeyFrame(Duration.millis(50), new KeyValue(label.translateXProperty(), -12, UiMotion.OUT_MIDDLE)),
                    new KeyFrame(Duration.millis(100), new KeyValue(label.translateXProperty(), -20, UiMotion.IN_MIDDLE)),
                    new KeyFrame(Duration.millis(150), new KeyValue(label.translateXProperty(), -12, UiMotion.OUT_MIDDLE)),
                    new KeyFrame(Duration.millis(200), new KeyValue(label.translateXProperty(), -20, UiMotion.IN_MIDDLE))),
                    UiMotion.track(tint, 1, 0, 250, Interpolator.LINEAR));
            shake.play();
            waitThenExit();
        }

        void waitThenExit() {
            if (lifetime != null) {
                lifetime.stop();
            }
            int hold = 800 + Math.clamp(text.length(), 5, 23) * 180;
            lifetime = new PauseTransition(Duration.millis(hold));
            lifetime.setOnFinished(event -> {
                active.remove(text, this);
                var exit = new ParallelTransition(
                        UiMotion.track(label.translateXProperty(), -70, 0, 200, UiMotion.IN_MIDDLE),
                        UiMotion.track(label.opacityProperty(), 0, 0, 150, Interpolator.LINEAR),
                        UiMotion.track(height, 0, 200, 100, UiMotion.OUT_MIDDLE));
                exit.setOnFinished(done -> toasts.getChildren().remove(label));
                exit.play();
            });
            lifetime.play();
        }
    }
}

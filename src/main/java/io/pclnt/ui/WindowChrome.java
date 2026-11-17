package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

final class WindowChrome {
    private static final double EDGE = 18;
    private static final double CORNER = 26;

    private final Stage stage;
    private final Region frame;
    private ParallelTransition motion;
    private boolean closing;
    private Cursor resizing;
    private boolean moving;
    private double startX;
    private double startY;
    private double windowX;
    private double windowY;
    private double windowWidth;
    private double windowHeight;

    WindowChrome(Stage stage, Region frame, Region titleBar) {
        this.stage = stage;
        this.frame = frame;
        var clip = new Rectangle();
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        frame.setClip(clip);

        var scene = stage.getScene();
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> scene.setCursor(edgeAt(event)));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.isPrimaryButtonDown()) {
                var edge = edgeAt(event);
                if (edge != Cursor.DEFAULT) {
                    rememberBounds(event);
                    resizing = edge;
                    event.consume();
                }
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::drag);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizing != null) {
                event.consume();
            }
            resizing = null;
            moving = false;
            scene.setCursor(edgeAt(event));
        });
        titleBar.setOnMousePressed(event -> {
            if (event.isPrimaryButtonDown() && isTitleBackground(event, titleBar)) {
                rememberBounds(event);
                moving = true;
                event.consume();
            }
        });
        stage.setOnCloseRequest(event -> {
            event.consume();
            close();
        });
    }

    void open() {
        frame.setOpacity(0);
        frame.setTranslateY(60);
        frame.setRotate(-4);
        stage.show();
        motion = new ParallelTransition(
                UiMotion.track(frame.opacityProperty(), 1, 100, 250, Interpolator.LINEAR),
                UiMotion.track(frame.translateYProperty(), 0, 100, 600, UiMotion.BACK_WEAK),
                UiMotion.track(frame.rotateProperty(), 0, 100, 500, UiMotion.BACK_WEAK));
        motion.play();
    }

    void minimize() {
        stage.setIconified(true);
    }

    void close() {
        if (closing) {
            return;
        }
        closing = true;
        motion.stop();
        frame.setMouseTransparent(true);
        motion = new ParallelTransition(
                new PauseTransition(Duration.millis(230)),
                UiMotion.track(frame.opacityProperty(), 0, 40, 140, UiMotion.OUT_WEAK),
                UiMotion.track(frame.scaleXProperty(), 0.88, 0, 180, Interpolator.LINEAR),
                UiMotion.track(frame.scaleYProperty(), 0.88, 0, 180, Interpolator.LINEAR),
                UiMotion.track(frame.translateYProperty(), 20, 0, 180, UiMotion.OUT_WEAK),
                UiMotion.track(frame.rotateProperty(), 0.6, 0, 180, UiMotion.IN_OUT_WEAK));
        motion.setOnFinished(event -> stage.hide());
        motion.play();
    }

    private static boolean isTitleBackground(MouseEvent event, Node titleBar) {
        for (Node node = event.getPickResult().getIntersectedNode(); node != null && node != titleBar;
                node = node.getParent()) {
            if (node instanceof ButtonBase) {
                return false;
            }
        }
        return true;
    }

    private void rememberBounds(MouseEvent event) {
        startX = event.getScreenX();
        startY = event.getScreenY();
        windowX = stage.getX();
        windowY = stage.getY();
        windowWidth = stage.getWidth();
        windowHeight = stage.getHeight();
    }

    private void drag(MouseEvent event) {
        double dx = event.getScreenX() - startX;
        double dy = event.getScreenY() - startY;
        if (resizing != null) {
            resize(dx, dy);
            event.consume();
        } else if (moving) {
            stage.setX(windowX + dx);
            stage.setY(windowY + dy);
            event.consume();
        }
    }

    private void resize(double dx, double dy) {
        boolean west = resizing == Cursor.W_RESIZE || resizing == Cursor.NW_RESIZE
                || resizing == Cursor.SW_RESIZE;
        boolean east = resizing == Cursor.E_RESIZE || resizing == Cursor.NE_RESIZE
                || resizing == Cursor.SE_RESIZE;
        boolean north = resizing == Cursor.N_RESIZE || resizing == Cursor.NW_RESIZE
                || resizing == Cursor.NE_RESIZE;
        boolean south = resizing == Cursor.S_RESIZE || resizing == Cursor.SW_RESIZE
                || resizing == Cursor.SE_RESIZE;

        if (west || east) {
            double width = Math.max(stage.getMinWidth(), windowWidth + (west ? -dx : dx));
            stage.setWidth(width);
            if (west) {
                stage.setX(windowX + windowWidth - width);
            }
        }
        if (north || south) {
            double height = Math.max(stage.getMinHeight(), windowHeight + (north ? -dy : dy));
            stage.setHeight(height);
            if (north) {
                stage.setY(windowY + windowHeight - height);
            }
        }
    }

    private Cursor edgeAt(MouseEvent event) {
        if (!stage.isResizable()) return Cursor.DEFAULT;
        double x = event.getSceneX();
        double y = event.getSceneY();
        double right = stage.getScene().getWidth() - x;
        double bottom = stage.getScene().getHeight() - y;
        if (x < CORNER && y < CORNER && (x < EDGE || y < EDGE)) return Cursor.NW_RESIZE;
        if (right < CORNER && y < CORNER && (right < EDGE || y < EDGE)) return Cursor.NE_RESIZE;
        if (x < CORNER && bottom < CORNER && (x < EDGE || bottom < EDGE)) return Cursor.SW_RESIZE;
        if (right < CORNER && bottom < CORNER && (right < EDGE || bottom < EDGE)) return Cursor.SE_RESIZE;
        if (x < EDGE) return Cursor.W_RESIZE;
        if (right < EDGE) return Cursor.E_RESIZE;
        if (y < EDGE) return Cursor.N_RESIZE;
        if (bottom < EDGE) return Cursor.S_RESIZE;
        return Cursor.DEFAULT;
    }
}

package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.Transition;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.skin.SliderSkin;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.util.Duration;

/** Original MyCheckBox/MyRadioBox/MySlider paint and timing, over accessible JavaFX controls. */
final class AppearanceControlMotion {
    private static final Color INK = Color.rgb(52, 61, 74), SELECTED = Color.rgb(11, 91, 203),
            ACTIVE = Color.rgb(19, 112, 243), DISABLED = Color.rgb(204, 204, 204),
            HALF_WHITE = Color.rgb(255, 255, 255, .5), OVER = Color.rgb(222, 236, 253, 190.0 / 255),
            SLIDER_IDLE = Color.rgb(122, 168, 225);

    private AppearanceControlMotion() { }

    static void install(CheckBox check) { install(check, check.selectedProperty(), false); }
    static void install(RadioButton radio) { install(radio, radio.selectedProperty(), true); }

    private static void install(ButtonBase control, ObservableBooleanValue selected, boolean radio) {
        var glyph = radio ? new Circle(4.5) : checkMark();
        Shape border;
        if (radio) {
            border = new Circle(8.45);
        } else {
            var square = new Rectangle(16.9, 16.9);
            square.setArcWidth(6);
            square.setArcHeight(6);
            border = square;
        }
        border.setStrokeWidth(1.1);
        var ink = new SimpleObjectProperty<>(selected.get() ? SELECTED : INK);
        var text = new SimpleObjectProperty<>(INK);
        var fill = new SimpleObjectProperty<>(HALF_WHITE);
        border.strokeProperty().bind(ink);
        border.fillProperty().bind(fill);
        glyph.fillProperty().bind(ink);
        control.textFillProperty().bind(text);
        glyph.setScaleX(selected.get() ? 1 : 0);
        glyph.setScaleY(selected.get() ? 1 : 0);
        if (radio) {
            glyph.setOpacity(selected.get() ? 1 : 0);
        }
        var slot = new StackPane(border, glyph);
        slot.setMinSize(18, 18);
        slot.setPrefSize(18, 18);
        slot.setMaxSize(18, 18);
        slot.setMouseTransparent(true);
        control.getStyleClass().add("pcl-choice");
        control.setGraphic(slot);

        var paint = new Timeline();
        var dark = ThemeMode.observeDark(control);
        Runnable recolor = () -> {
            boolean enabled = !control.isDisabled(), hover = control.isHover();
            Color normal = dark.get() ? Color.web("#f2f2f2") : INK;
            Color active = dark.get() ? Color.web("#aed5ff") : ACTIVE;
            Color chosen = dark.get() ? active : SELECTED;
            Color disabled = dark.get() ? Color.web("#b7bec8") : DISABLED;
            Color surface = dark.get() ? Color.web(control.isArmed() ? "#35597d" : "#222222")
                    : control.isArmed() ? OVER : HALF_WHITE;
            paint.stop();
            paint.getKeyFrames().setAll(
                    new KeyFrame(Duration.millis(hover ? 100 : 200),
                            new KeyValue(ink, !enabled ? disabled : hover ? active : selected.get() ? chosen : normal),
                            new KeyValue(text, !enabled ? disabled : hover ? active : normal)),
                    new KeyFrame(Duration.millis(100), new KeyValue(fill, surface)));
            paint.play();
        };
        control.hoverProperty().addListener(observable -> recolor.run());
        control.disabledProperty().addListener(observable -> recolor.run());
        dark.addListener(observable -> recolor.run());
        var shapeMotion = new ParallelTransition();
        control.armedProperty().addListener((obs, old, armed) -> {
            recolor.run();
            if (!armed && control.isHover()) {
                return;
            }
            shapeMotion.stop();
            shapeMotion.getChildren().setAll(
                    UiMotion.track(border.scaleXProperty(), armed ? 16.5 / 18 : 1, 0, armed ? 1000 : 400, UiMotion.OUT_STRONG),
                    UiMotion.track(border.scaleYProperty(), armed ? 16.5 / 18 : 1, 0, armed ? 1000 : 400, UiMotion.OUT_STRONG));
            shapeMotion.play();
        });
        selected.addListener((obs, before, checked) -> {
            recolor.run();
            shapeMotion.stop();
            double initial = border.getScaleX();
            int recoilDelay = radio ? 90 : 105;
            double low = (radio ? 10 : 12) / 18.0;
            if (radio && !checked) {
                shapeMotion.getChildren().setAll(
                        UiMotion.track(border.scaleXProperty(), 1, 0, 150, UiMotion.OUT_MIDDLE),
                        UiMotion.track(border.scaleYProperty(), 1, 0, 150, UiMotion.OUT_MIDDLE));
            } else {
                var borderMotion = new Transition() {
                    { setCycleDuration(Duration.millis(recoilDelay + 300)); setInterpolator(Interpolator.LINEAR); }
                    @Override protected void interpolate(double fraction) {
                        double ms = fraction * (recoilDelay + 300);
                        double scale = initial + (low - initial) * sample(radio ? UiMotion.OUT_WEAK : UiMotion.OUT_MIDDLE, ms, 150)
                                + (1 - low) * sample(UiMotion.BACK_MIDDLE, ms - recoilDelay, 300);
                        border.setScaleX(scale);
                        border.setScaleY(scale);
                    }
                };
                shapeMotion.getChildren().setAll(borderMotion);
            }
            int delay = radio || !checked ? 0 : 105;
            int length = checked ? (radio ? 390 : 300) : (radio ? 150 : 135);
            var easing = checked ? UiMotion.BACK_WEAK : radio ? UiMotion.IN_MIDDLE : UiMotion.IN_WEAK;
            shapeMotion.getChildren().addAll(UiMotion.track(glyph.scaleXProperty(), checked ? 1 : 0, delay, length, easing),
                    UiMotion.track(glyph.scaleYProperty(), checked ? 1 : 0, delay, length, easing));
            if (radio) {
                shapeMotion.getChildren().add(UiMotion.track(glyph.opacityProperty(), checked ? 1 : 0, checked ? 90 : 30,
                        75, Interpolator.LINEAR));
            }
            shapeMotion.play();
        });
    }

    private static SVGPath checkMark() {
        var mark = new SVGPath();
        mark.setContent("M0,6L1.5,4.5 4.5,7.5 10.5,1.5 12,3 4.5,10.5 0,6z");
        return mark;
    }

    private static double sample(Interpolator curve, double elapsed, double duration) {
        return curve.interpolate(0.0, 1.0, Math.clamp(elapsed / duration, 0, 1));
    }

    static Slider slider(int max, int value) {
        var slider = new Slider(0, max, value) {
            @Override protected SliderSkin createDefaultSkin() { return new PolishedSliderSkin(this); }
        };
        slider.getStyleClass().add("pcl-slider");
        return slider;
    }

    private static final class PolishedSliderSkin extends SliderSkin {
        private final Canvas canvas = new Canvas();
        private final SimpleDoubleProperty progress;
        private final SimpleDoubleProperty scale = new SimpleDoubleProperty(1);
        private final SimpleObjectProperty<Color> color = new SimpleObjectProperty<>(SLIDER_IDLE);
        private Timeline progressMotion, scaleMotion;
        private final Timeline paint = new Timeline();
        private boolean byKey;

        private PolishedSliderSkin(Slider slider) {
            super(slider);
            progress = new SimpleDoubleProperty(fraction());
            canvas.setMouseTransparent(true);
            getChildren().add(canvas);
            progress.addListener(observable -> draw());
            scale.addListener(observable -> draw());
            color.addListener(observable -> draw());
            slider.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                byKey = true;
                javafx.application.Platform.runLater(() -> byKey = false);
            });
            slider.valueProperty().addListener(observable -> {
                if (progressMotion != null) {
                    progressMotion.stop();
                }
                double delta = Math.abs(progress.get() - fraction());
                int duration = (int) ((1 - Math.pow(1 - delta, 3)) * 300 + (byKey ? 100 : 0));
                progressMotion = UiMotion.track(progress, fraction(), 0, duration,
                        duration > 50 ? UiMotion.OUT_MIDDLE : Interpolator.LINEAR);
                progressMotion.play();
            });
            slider.hoverProperty().addListener(observable -> recolor());
            slider.disabledProperty().addListener(observable -> recolor());
            slider.pressedProperty().addListener((observable, old, pressed) -> {
                if (scaleMotion != null) {
                    scaleMotion.stop();
                }
                scaleMotion = UiMotion.track(scale, pressed ? 1.3 : 1, 0, pressed ? 40 : 200, UiMotion.OUT_MIDDLE);
                scaleMotion.play();
                recolor();
            });
        }

        private double fraction() {
            var slider = getSkinnable();
            return (slider.getValue() - slider.getMin()) / (slider.getMax() - slider.getMin());
        }

        private void recolor() {
            var slider = getSkinnable();
            boolean active = slider.isHover() || slider.isPressed();
            paint.stop();
            paint.getKeyFrames().setAll(new KeyFrame(Duration.millis(slider.isDisabled() ? 200 : active ? 40 : 100),
                    new KeyValue(color, slider.isDisabled() ? DISABLED : active ? ACTIVE : SLIDER_IDLE)));
            paint.play();
        }

        @Override protected void layoutChildren(double x, double y, double width, double height) {
            super.layoutChildren(x, y, width, height);
            canvas.relocate(x, y);
            canvas.setWidth(width);
            canvas.setHeight(height);
            draw();
        }

        private void draw() {
            double width = canvas.getWidth(), height = canvas.getHeight();
            if (width < 10 || height < 10) {
                return;
            }
            double x = 5 + progress.get() * (width - 10), mid = height / 2;
            var ctx = canvas.getGraphicsContext2D();
            ctx.clearRect(0, 0, width, height);
            ctx.setFill(SLIDER_IDLE.deriveColor(0, 1, 1, .3));
            ctx.fillRect(x, mid - .5, width - x - 1, 1);
            ctx.setFill(color.get());
            ctx.fillRect(1, mid - 1, x - 1, 2);
            double radius = 4 * scale.get();
            ctx.fillOval(x - radius, mid - radius, 2 * radius, 2 * radius);
        }
    }
}

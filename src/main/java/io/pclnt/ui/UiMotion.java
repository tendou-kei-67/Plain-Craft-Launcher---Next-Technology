package io.pclnt.ui;

import java.util.function.DoubleUnaryOperator;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.util.Duration;

final class UiMotion {
    static final Interpolator IN_WEAK = curve(t -> t * t);
    static final Interpolator IN_MIDDLE = curve(t -> Math.pow(t, 3));
    static final Interpolator OUT_WEAK = fluentOut(2);
    static final Interpolator OUT_MIDDLE = fluentOut(3);
    static final Interpolator OUT_STRONG = fluentOut(4);
    static final Interpolator OUT_EXTRA_STRONG = fluentOut(5);
    static final Interpolator BACK_WEAK = backOut(2);
    static final Interpolator BACK_IN_WEAK = curve(t -> t * t * Math.cos(1.5 * Math.PI * (1 - t)));
    static final Interpolator ELASTIC_OUT_WEAK = curve(t -> 1 - Math.pow(1 - t, 1.25)
            * Math.cos(2.5 * Math.PI * Math.pow(t, 1.5)));
    static final Interpolator BACK_MIDDLE = backOut(3);
    static final Interpolator TOAST_ENTER = additive(.4, 200, 400, OUT_MIDDLE, ELASTIC_OUT_WEAK);
    static final Interpolator IN_OUT_WEAK = curve(t -> t < 0.5
            ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t));

    private static final Color ACCENT = Color.web("#087bea");
    private static final Color LIGHT_ACCENT = Color.web("#9acbff");
    private static final CornerRadii PILL_RADIUS = new CornerRadii(13.5);

    private UiMotion() {
    }

    private static Interpolator fluentOut(int power) {
        return curve(t -> 1 - Math.pow(1 - t, power));
    }

    private static Interpolator backOut(int power) {
        return curve(t -> 1 - Math.pow(1 - t, 3 - power * 0.5) * Math.cos(1.5 * Math.PI * t));
    }

    private static Interpolator curve(DoubleUnaryOperator function) {
        return new Interpolator() {
            @Override
            protected double curve(double fraction) {
                return function.applyAsDouble(fraction);
            }
        };
    }

    // WPF adds two simultaneous offsets with independent durations and easing curves.
    static Interpolator additive(double firstWeight, int firstMillis, int totalMillis,
                                 Interpolator first, Interpolator second) {
        return curve(t -> firstWeight * first.interpolate(0.0, 1.0, Math.min(1, t * totalMillis / firstMillis))
                + (1 - firstWeight) * second.interpolate(0.0, 1.0, t));
    }

    static Timeline track(DoubleProperty property, double target, int delay, int millis, Interpolator easing) {
        return new Timeline(
                new KeyFrame(Duration.millis(delay), new KeyValue(property, property.get())),
                new KeyFrame(Duration.millis(delay + millis), new KeyValue(property, target, easing)));
    }

    static void revealActions(Node row, Node actions, int enterDelay, int enterTime, int exitTime) {
        actions.setOpacity(0);
        actions.setMouseTransparent(true);
        var fade = new SimpleObjectProperty<Timeline>();
        Runnable update = () -> {
            boolean visible = row.isHover() || row.isFocusWithin();
            actions.setMouseTransparent(!visible);
            if (fade.get() != null) fade.get().stop();
            var motion = track(actions.opacityProperty(), visible ? 1 : 0, visible ? enterDelay : 0,
                    visible ? enterTime : exitTime, Interpolator.LINEAR);
            fade.set(motion);
            motion.play();
        };
        row.hoverProperty().addListener(ignored -> update.run());
        row.focusWithinProperty().addListener(ignored -> update.run());
    }

    static void installButtons(Node node, ObservableBooleanValue dark) {
        if (node instanceof ToggleButton button) {
            boolean login = button.getStyleClass().contains("login-pill");
            if ((login || button.getStyleClass().contains("title-pill"))
                    && button.getProperties().putIfAbsent(PillMotion.class, true) == null) {
                new PillMotion(button, login, dark);
            }
        } else if (node instanceof Button button && button.getStyleClass().contains("outline-button")
                && !button.isDisabled()) {
            installPress(button);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            installButtons(scroll.getContent(), dark);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> installButtons(child, dark));
        }
    }

    static void installPress(Button button) {
        if (button.getProperties().putIfAbsent("pclOutlinePress", true) != null) {
            return;
        }
        var animation = new ParallelTransition();
        var pressed = additive(0.045 / 0.055, 80, 700, OUT_EXTRA_STRONG, OUT_MIDDLE);
        button.armedProperty().addListener((observable, old, armed) -> {
            if (button.getStyleClass().contains("pcl-icon-text-button")) return;
            animation.stop();
            boolean pointerLeft = !armed && button.isPressed() && !button.isHover();
            if (button.getStyleClass().contains("resource-project")) {
                var easing = OUT_MIDDLE;
                double scale = armed ? .996 : 1;
                animation.getChildren().setAll(
                        track(button.scaleXProperty(), scale, 0, 144, easing),
                        track(button.scaleYProperty(), scale, 0, 144, easing));
                animation.play();
                return;
            }
            int delay = armed || pointerLeft ? 0 : 10;
            int millis = armed ? 700 : pointerLeft ? 800 : 300;
            var easing = armed ? pressed : pointerLeft ? OUT_STRONG : OUT_MIDDLE;
            double scale = armed ? 0.945 : 1;
            animation.getChildren().setAll(
                    track(button.scaleXProperty(), scale, delay, millis, easing),
                    track(button.scaleYProperty(), scale, delay, millis, easing));
            animation.playFromStart();
        });
    }

    static void installIconPress(Button button) {
        if (button.getProperties().putIfAbsent("pclIconPress", true) != null) return;
        var motion = new ParallelTransition();
        button.armedProperty().addListener((observable, old, armed) -> {
            motion.stop();
            var artwork = button.getGraphic();
            if (artwork == null || artwork.scaleXProperty().isBound() || artwork.scaleYProperty().isBound()) return;
            double start = artwork.getScaleX();
            boolean pointerLeft = !armed && button.isPressed() && !button.isHover();
            var easing = armed ? OUT_STRONG : pointerLeft ? OUT_MIDDLE : new Interpolator() {
                @Override protected double curve(double t) {
                    return (1.05 - start) * BACK_WEAK.interpolate(0.0, 1.0, t)
                            - .05 * OUT_STRONG.interpolate(0.0, 1.0, t);
                }
                @Override public double interpolate(double from, double to, double t) {
                    return start + curve(t);
                }
            };
            motion.getChildren().setAll(
                    track(artwork.scaleXProperty(), armed ? .8 : 1, 0, armed ? 400 : 250, easing),
                    track(artwork.scaleYProperty(), armed ? .8 : 1, 0, armed ? 400 : 250, easing));
            motion.play();
        });
    }

    private static final class PillMotion {
        private final ToggleButton button;
        private final boolean login;
        private final ObservableBooleanValue dark;
        private final SimpleObjectProperty<Color> fill = new SimpleObjectProperty<>();
        private final SimpleObjectProperty<Color> text = new SimpleObjectProperty<>();
        private Timeline transition;

        private PillMotion(ToggleButton button, boolean login, ObservableBooleanValue dark) {
            this.button = button;
            this.login = login;
            this.dark = dark;
            refresh(0);
            button.backgroundProperty().bind(Bindings.createObjectBinding(
                    () -> new Background(new BackgroundFill(fill.get(), PILL_RADIUS, Insets.EMPTY)), fill));
            button.textFillProperty().bind(text);
            button.selectedProperty().addListener((observable, old, selected) -> refresh(120));
            button.hoverProperty().addListener((observable, old, hover) -> refresh(hover ? 90 : 150));
            button.armedProperty().addListener((observable, old, armed) -> refresh(armed ? (login ? 90 : 60) : 90));
            if (login) {
                // Observe theme changes only for the permanent login controls.
                dark.addListener((observable, old, darkMode) -> refresh(120));
            }
        }

        private void refresh(int millis) {
            if (transition != null) {
                transition.stop();
            }
            var foreground = button.isSelected()
                    ? (login ? Color.WHITE : Color.web("#0562c0"))
                    : (login ? (dark.get() ? LIGHT_ACCENT : ACCENT) : Color.WHITE);
            var background = background();
            if (millis == 0) {
                fill.set(background);
                text.set(foreground);
                return;
            }
            transition = new Timeline(new KeyFrame(Duration.millis(millis),
                    new KeyValue(fill, background), new KeyValue(text, foreground)));
            transition.play();
        }

        private Color background() {
            if (button.isSelected()) {
                return login ? (dark.get() ? Color.web("#065cae") : ACCENT) : Color.WHITE;
            }
            if (button.isArmed()) {
                return login ? Color.web(dark.get() ? "#35597d" : "#d6e9fc") : Color.rgb(0, 0, 0, .20);
            }
            if (button.isHover()) {
                return login ? Color.web(dark.get() ? "#294664" : "#e9f3ff") : Color.rgb(255, 255, 255, .10);
            }
            return Color.TRANSPARENT;
        }
    }
}

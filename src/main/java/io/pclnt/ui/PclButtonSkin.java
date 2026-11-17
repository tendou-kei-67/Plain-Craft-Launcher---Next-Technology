package io.pclnt.ui;

import java.util.List;
import javafx.animation.Interpolator;
import javafx.animation.Transition;
import javafx.beans.property.ObjectProperty;
import javafx.css.StyleableProperty;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.skin.ButtonSkin;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.util.Duration;

/** Animates the paints selected by CSS, including the current light or dark palette. */
public final class PclButtonSkin extends ButtonSkin {
    private final boolean icon;

    public PclButtonSkin(Button button) {
        super(button);
        var styles = button.getStyleClass();
        icon = styles.contains("pcl-action-icon") || styles.contains("pcl-side-icon")
                || styles.contains("window-button") || styles.contains("notification-close");
        if (icon) UiMotion.installIconPress(button);
        else if (!styles.contains("pcl-card-toggle")) UiMotion.installPress(button);
        var background = new PaintMotion<>(button, button.backgroundProperty(), PclButtonSkin::background, icon);
        var border = new PaintMotion<>(button, button.borderProperty(), PclButtonSkin::border, icon);
        var ink = new PaintMotion<>(button, button.textFillProperty(), PclButtonSkin::paint, icon);
        registerChangeListener(button.backgroundProperty(), ignored -> background.changed());
        registerChangeListener(button.borderProperty(), ignored -> border.changed());
        registerChangeListener(button.textFillProperty(), ignored -> ink.changed());
    }

    static void animateStandalone(ButtonBase button) {
        var background = new PaintMotion<>(button, button.backgroundProperty(), PclButtonSkin::background, false);
        var border = new PaintMotion<>(button, button.borderProperty(), PclButtonSkin::border, false);
        var ink = new PaintMotion<>(button, button.textFillProperty(), PclButtonSkin::paint, false);
        button.backgroundProperty().addListener(ignored -> background.changed());
        button.borderProperty().addListener(ignored -> border.changed());
        button.textFillProperty().addListener(ignored -> ink.changed());
    }

    @Override protected void layoutChildren(double x, double y, double width, double height) {
        super.layoutChildren(x, y, width, height);
        var button = getSkinnable();
        var graphic = button.getGraphic();
        if (!icon || graphic == null) return;
        if (graphic.isResizable()) graphic.autosize();
        var bounds = graphic.getLayoutBounds();
        graphic.relocate((button.getWidth() - bounds.getWidth()) / 2,
                (button.getHeight() - bounds.getHeight()) / 2);
    }

    private static Background background(Background before, Background after, double t) {
        if (before != null && !before.getImages().isEmpty() || after != null && !after.getImages().isEmpty()) return after;
        var first = before == null ? List.<BackgroundFill>of() : before.getFills();
        var last = after == null ? List.<BackgroundFill>of() : after.getFills();
        var fills = new BackgroundFill[Math.max(first.size(), last.size())];
        for (int i = 0; i < fills.length; i++) {
            var start = i < first.size() ? first.get(i) : null;
            var end = i < last.size() ? last.get(i) : null;
            var shape = end == null ? start : end;
            fills[i] = new BackgroundFill(paint(start == null ? null : start.getFill(),
                    end == null ? null : end.getFill(), t), shape.getRadii(), shape.getInsets());
        }
        return new Background(fills);
    }

    private static Border border(Border before, Border after, double t) {
        if (before == null || after == null || !before.getImages().isEmpty() || !after.getImages().isEmpty()
                || before.getStrokes().size() != after.getStrokes().size()) return after;
        var strokes = new BorderStroke[after.getStrokes().size()];
        for (int i = 0; i < strokes.length; i++) {
            var start = before.getStrokes().get(i);
            var end = after.getStrokes().get(i);
            strokes[i] = new BorderStroke(paint(start.getTopStroke(), end.getTopStroke(), t),
                    paint(start.getRightStroke(), end.getRightStroke(), t),
                    paint(start.getBottomStroke(), end.getBottomStroke(), t),
                    paint(start.getLeftStroke(), end.getLeftStroke(), t),
                    end.getTopStyle(), end.getRightStyle(), end.getBottomStyle(), end.getLeftStyle(),
                    end.getRadii(), end.getWidths(), end.getInsets());
        }
        return new Border(strokes);
    }

    private static Paint paint(Paint before, Paint after, double t) {
        if (before == null && after instanceof Color color) before = clear(color);
        if (after == null && before instanceof Color color) after = clear(color);
        if (before instanceof Color start && after instanceof Color end) {
            if (start.getOpacity() == 0) start = clear(end);
            if (end.getOpacity() == 0) end = clear(start);
            return start.interpolate(end, t);
        }
        return after;
    }

    private static Color clear(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
    }

    @FunctionalInterface private interface Blend<T> {
        T at(T first, T last, double fraction);
    }

    private static final class PaintMotion<T> {
        private final ButtonBase button;
        private final ObjectProperty<T> property;
        private final StyleableProperty<T> css;
        private final Blend<T> blend;
        private final boolean icon;
        private T rendered;
        private T target;
        private Transition animation;
        private boolean painting;

        @SuppressWarnings("unchecked")
        PaintMotion(ButtonBase button, ObjectProperty<T> property, Blend<T> blend, boolean icon) {
            this.button = button;
            this.property = property;
            this.css = (StyleableProperty<T>) property;
            this.blend = blend;
            this.icon = icon;
            target = rendered = property.get();
        }

        void changed() {
            if (painting) return;
            var next = property.get();
            if (java.util.Objects.equals(next, target)) return;
            if (animation != null) animation.stop();
            target = next;
            if (property.isBound() || button.getScene() == null) {
                rendered = next;
                return;
            }
            T start = rendered;
            boolean project = button.getStyleClass().contains("resource-project");
            boolean iconText = button.getStyleClass().contains("pcl-icon-text-button");
            int millis = iconText && button.isArmed() ? 70
                    : button.isHover() ? (icon || project ? 120 : 100) : (project ? 180 : icon || iconText ? 150 : 200);
            animation = new Transition() {
                {
                    setCycleDuration(Duration.millis(millis));
                    setInterpolator(Interpolator.LINEAR);
                }
                @Override protected void interpolate(double fraction) {
                    if (property.isBound()) return;
                    rendered = fraction >= 1 ? next : blend.at(start, next, fraction);
                    painting = true;
                    try {
                        css.applyStyle(css.getStyleOrigin(), rendered);
                    } finally {
                        painting = false;
                    }
                }
            };
            animation.play();
        }
    }
}

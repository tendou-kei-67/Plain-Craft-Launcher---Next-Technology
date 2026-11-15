package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/** JavaFX counterpart of the WPF MyCard control. */
final class PclCard extends VBox {
    static final double HEADER_HEIGHT = 40;
    private final Body viewport;
    private final Group arrow;
    private final Label title;
    private final BorderPane header;
    private Button toggle;
    private final boolean collapsible;
    private boolean expanded;
    private Timeline heightMotion;
    private Timeline arrowMotion;
    private double collapsedArrowAngle;

    static PclCard fixed(String title, Node... items) {
        return fixedBody(title, new VBox(10, items));
    }

    static PclCard fixedBody(String title, Region body) {
        return new PclCard(title, body, false, true);
    }

    static PclCard foldable(String title, Region body, boolean expanded) {
        return new PclCard(title, body, true, expanded);
    }

    private PclCard(String text, Region body, boolean collapsible, boolean expanded) {
        this.collapsible = collapsible;
        this.expanded = expanded;
        getStyleClass().add("pcl-card");
        setFillWidth(true);
        title = LauncherController.label(text, "card-title");
        title.setWrapText(false);
        var ink = new SimpleObjectProperty<>(Color.rgb(52, 61, 74));
        title.textFillProperty().bind(ink);
        var dark = new javafx.beans.property.SimpleBooleanProperty();
        var themeState = (javafx.collections.SetChangeListener<javafx.css.PseudoClass>) change -> {
            if (ThemeMode.DARK.equals(change.getElementAdded()) || ThemeMode.DARK.equals(change.getElementRemoved())) {
                dark.set(change.wasAdded());
            }
        };
        header = new BorderPane();
        header.setLeft(title);
        BorderPane.setAlignment(title, Pos.CENTER_LEFT);
        header.getStyleClass().add("pcl-card-heading");
        if (collapsible) {
            var path = new SVGPath();
            // PageOtherHelp uses the default MyCard arrow, not SwapLogoRight.
            path.setContent("M2,4 l-2,2 10,10 10,-10 -2,-2 -8,8 -8,-8 z");
            path.getTransforms().add(new Scale(0.5, 0.5));
            path.fillProperty().bind(title.textFillProperty());
            arrow = new Group(path);
            arrow.setRotate(expanded ? 180 : 0);
            header.setRight(arrow);
            BorderPane.setAlignment(arrow, Pos.CENTER_RIGHT);
            toggle = new Button(null, header) {
                @Override public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
                    return attribute == AccessibleAttribute.EXPANDED ? PclCard.this.expanded
                            : super.queryAccessibleAttribute(attribute, parameters);
                }
            };
            toggle.setAccessibleText(text);
            toggle.setAccessibleRole(AccessibleRole.TITLED_PANE);
            toggle.getStyleClass().add("pcl-card-toggle");
            toggle.setMaxWidth(Double.MAX_VALUE);
            toggle.setOnAction(event -> setExpanded(!this.expanded));
            toggle.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (this.expanded && toggle.sceneToLocal(event.getSceneX(), event.getSceneY()).getY() > 34) {
                    event.consume();
                }
            });
            toggle.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
                if (this.expanded && toggle.sceneToLocal(event.getSceneX(), event.getSceneY()).getY() > 34) {
                    toggle.disarm();
                    event.consume();
                }
            });
            getChildren().add(toggle);
        } else {
            arrow = null;
            if (!text.isBlank()) getChildren().add(header);
        }
        body.getStyleClass().add("pcl-card-body");
        viewport = new Body(body);
        body.setVisible(expanded);
        body.setMouseTransparent(!expanded);
        getChildren().add(viewport);
        var shadow = new DropShadow(3, Color.rgb(52, 61, 74, .07));
        shadow.setOffsetY(1);
        setEffect(shadow);
        var hover = new Timeline();
        Runnable refreshInk = () -> {
            boolean over = isHover();
            hover.stop();
            hover.getKeyFrames().setAll(new KeyFrame(Duration.millis(90),
                    new KeyValue(shadow.colorProperty(), over ? Color.rgb(72, 144, 245, .40)
                            : Color.rgb(52, 61, 74, .07), Interpolator.LINEAR),
                    new KeyValue(ink, dark.get() ? (over ? Color.web("#a3c6ff") : Color.web("#f2f2f2"))
                            : (over ? Color.rgb(11, 91, 203) : Color.rgb(52, 61, 74)), Interpolator.LINEAR)));
            hover.play();
        };
        // A page can be attached to the launcher after its cards have been constructed.
        sceneProperty().addListener((observable, previous, scene) -> {
            Node oldOwner = previous == null ? null : previous.lookup("#frame");
            Node owner = scene == null ? null : scene.lookup("#frame");
            if (oldOwner != null) {
                oldOwner.getPseudoClassStates().removeListener(themeState);
            }
            if (owner != null) {
                owner.getPseudoClassStates().addListener(themeState);
            }
            dark.set(owner != null && owner.getPseudoClassStates().contains(ThemeMode.DARK));
        });
        dark.addListener(ignored -> refreshInk.run());
        hoverProperty().addListener(ignored -> refreshInk.run());
        MaterialBackdrop.install(this);
    }

    String title() { return title.getText(); }

    Label titleLabel() { return title; }

    void setHeaderContent(Node content) {
        header.setCenter(content);
        BorderPane.setAlignment(content, Pos.CENTER_LEFT);
    }

    void setHeading(String text) {
        title.setText(text);
        if (toggle != null) toggle.setAccessibleText(text);
    }

    void pointRightWhenCollapsed() {
        collapsedArrowAngle = 270;
        if (!expanded) {
            arrow.setRotate(collapsedArrowAngle);
        }
    }

    void onExpand(Runnable action) {
        onToggle(next -> {
            if (next) action.run();
        });
    }

    void onToggle(java.util.function.Consumer<Boolean> action) {
        toggle.setOnAction(event -> {
            boolean next = !expanded;
            action.accept(next);
            setExpanded(next);
        });
    }

    boolean isExpanded() { return expanded; }

    void setExpanded(boolean value) {
        if (!collapsible || expanded == value) {
            return;
        }
        expanded = value;
        toggle.notifyAccessibleAttributeChanged(AccessibleAttribute.EXPANDED);
        double from = viewport.animatedHeight.get() >= 0 ? viewport.animatedHeight.get() : viewport.getHeight();
        if (heightMotion != null) {
            heightMotion.stop();
        }
        if (arrowMotion != null) {
            arrowMotion.stop();
        }
        viewport.body.setVisible(true);
        viewport.body.setMouseTransparent(!value);
        double target = value ? viewport.body.prefHeight(viewport.getWidth()) : 0;
        viewport.animatedHeight.set(from);
        if (getScene() == null || getWidth() == 0 || Math.abs(target - from) < 1) {
            settle();
            arrow.setRotate(value ? 180 : collapsedArrowAngle);
            return;
        }
        heightMotion = heightTrack(viewport.animatedHeight, from, target);
        heightMotion.setOnFinished(event -> settle());
        heightMotion.play();
        arrowMotion = UiMotion.track(arrow.rotateProperty(), value ? 180 : collapsedArrowAngle, 0, 250,
                UiMotion.OUT_EXTRA_STRONG);
        arrowMotion.play();
    }

    private void settle() {
        viewport.animatedHeight.set(-1);
        viewport.body.setVisible(expanded);
    }

    private static Timeline heightTrack(SimpleDoubleProperty property, double from, double to) {
        double delta = to - from;
        double distance = Math.abs(delta);
        if (distance <= 800) {
            return UiMotion.track(property, to, 0, 150, UiMotion.OUT_EXTRA_STRONG);
        }
        // MyCard.StartHeightAnimation splits long moves into a linear and a decelerating leg.
        double easeLength, speed;
        int easeMillis;
        if (delta < 0) {
            easeLength = 200;
            easeMillis = 150;
            speed = (distance - easeLength) / .1;
        } else if (distance > 3000) {
            speed = 5000;
            easeLength = distance - speed * .3;
            easeMillis = 400;
        } else {
            easeLength = 150;
            easeMillis = 200;
            speed = 4000;
        }
        double linearMillis = (distance - easeLength) / speed * 1000;
        double alpha = Math.max(0, speed * easeMillis / 1000 / easeLength - 1);
        var deceleration = new Interpolator() {
            @Override protected double curve(double t) { return (alpha + 1) * t / (1 + alpha * t); }
        };
        return new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(property, from)),
                new KeyFrame(Duration.millis(linearMillis),
                        new KeyValue(property, to - Math.copySign(easeLength, delta), Interpolator.LINEAR)),
                new KeyFrame(Duration.millis(linearMillis + easeMillis), new KeyValue(property, to, deceleration)));
    }

    private final class Body extends Region {
        private final Region body;
        private final SimpleDoubleProperty animatedHeight = new SimpleDoubleProperty(-1);

        private Body(Region body) {
            this.body = body;
            getChildren().add(body);
            var clip = new Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            setClip(clip);
            animatedHeight.addListener(observable -> requestLayout());
        }

        @Override protected double computePrefWidth(double height) { return body.prefWidth(-1); }
        @Override protected double computeMinWidth(double height) { return 0; }
        @Override protected double computeMinHeight(double width) { return computePrefHeight(width); }
        @Override protected double computePrefHeight(double width) {
            return animatedHeight.get() >= 0 ? animatedHeight.get() : expanded ? body.prefHeight(width) : 0;
        }
        @Override public javafx.geometry.Orientation getContentBias() { return javafx.geometry.Orientation.HORIZONTAL; }
        @Override protected void layoutChildren() { body.resizeRelocate(0, 0, getWidth(), body.prefHeight(getWidth())); }
    }
}

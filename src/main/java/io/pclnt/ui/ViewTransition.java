package io.pclnt.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

final class ViewTransition {
    enum Type { MAIN, CONTENT, LOGIN }

    private final StackPane host;
    private final Type type;
    private final Region sidebarBackground;
    private Node requested;
    private Node displayed;
    private ParallelTransition motion;

    ViewTransition(StackPane host, Type type) {
        this.host = host;
        this.type = type;
        host.getProperties().put(ViewTransition.class, this);
        var clip = new Rectangle();
        clip.widthProperty().bind(host.widthProperty());
        clip.heightProperty().bind(host.heightProperty());
        host.setClip(clip);

        sidebarBackground = type == Type.MAIN ? new Region() : null;
        if (sidebarBackground != null) {
            sidebarBackground.getStyleClass().add("sidebar-background");
            sidebarBackground.setMouseTransparent(true);
            sidebarBackground.setMinWidth(0);
            sidebarBackground.setMaxWidth(Region.USE_PREF_SIZE);
            sidebarBackground.setPrefWidth(0);
            sidebarBackground.visibleProperty().bind(sidebarBackground.prefWidthProperty().greaterThan(0));
            StackPane.setAlignment(sidebarBackground, Pos.CENTER_LEFT);
            host.getChildren().add(sidebarBackground);
        }
    }

    StackPane host() {
        return host;
    }

    void show(Node next) {
        if (requested == next) {
            return;
        }
        requested = next;
        if (motion != null) {
            motion.stop();
        }
        if (displayed == null) {
            display(next);
            if (sidebarBackground != null) {
                sidebarBackground.setPrefWidth(sidebarWidth(next));
            }
        } else if (displayed == next) {
            enter(next, false);
        } else {
            exit(displayed, next);
        }
    }

    private void exit(Node outgoing, Node next) {
        outgoing.setMouseTransparent(true);
        var animation = new ParallelTransition(new PauseTransition(Duration.millis(switch (type) {
            case MAIN -> 110;
            case CONTENT -> 130;
            case LOGIN -> 100;
        })));
        if (type == Type.LOGIN) {
            animation.getChildren().add(UiMotion.track(outgoing.opacityProperty(), 0, 0, 100, UiMotion.OUT_MIDDLE));
        } else {
            if (type == Type.MAIN) {
                settleChild(outgoing);
                animateSidebar(animation, sidebar(outgoing), false, false);
            }
            animateContent(animation, content(outgoing), false, false);
            animateScrollbar(animation, outgoing, false, false);
        }
        animation.setOnFinished(event -> {
            restore(outgoing);
            host.getChildren().remove(outgoing);
            display(next);
            next.applyCss();
            enter(next, true);
        });
        motion = animation;
        motion.play();
    }

    private void display(Node next) {
        host.getChildren().add(next);
        displayed = next;
    }

    private void enter(Node next, boolean newPage) {
        next.setMouseTransparent(false);
        var animation = new ParallelTransition();
        if (type == Type.LOGIN) {
            if (newPage) {
                next.setOpacity(0);
            }
            animation.getChildren().add(
                    UiMotion.track(next.opacityProperty(), 1, newPage ? 20 : 0, 100, UiMotion.IN_MIDDLE));
        } else {
            if (sidebarBackground != null) {
                double width = sidebarWidth(next);
                animation.getChildren().add(UiMotion.track(sidebarBackground.prefWidthProperty(), width, 0, 180,
                        width == 0 ? UiMotion.OUT_MIDDLE : UiMotion.OUT_EXTRA_STRONG));
                var panes = new ParallelTransition();
                animateSidebar(panes, sidebar(next), true, newPage);
                animateContent(panes, content(next), true, newPage);
                animateScrollbar(panes, next, true, newPage);
                panes.setDelay(Duration.millis(newPage ? 30 : 0));
                if (!panes.getChildren().isEmpty()) {
                    animation.getChildren().add(panes);
                }
            } else {
                animateContent(animation, content(next), true, newPage);
                animateScrollbar(animation, next, true, newPage);
                if (newPage) {
                    animation.setDelay(Duration.millis(30));
                }
            }
        }
        motion = animation;
        motion.play();
    }

    private static void animateSidebar(ParallelTransition animation, Node sidebar, boolean entering, boolean fresh) {
        if (sidebar == null) {
            return;
        }
        if (sidebar instanceof VBox items) {
            int delay = 0;
            var visible = items.getChildren().stream().filter(Node::isManaged).toList();
            int count = visible.size();
            for (int i = 0; i < count; i++) {
                var item = visible.get(i);
                if (fresh) {
                    item.setOpacity(0);
                    item.setTranslateX(-25);
                }
                if (entering) {
                    addMotion(animation, item, item.translateXProperty(), delay, 100, 0, 300, UiMotion.OUT_WEAK,
                            UiMotion.additive(5.0 / 25, 200, 300, UiMotion.OUT_MIDDLE, UiMotion.BACK_WEAK), true);
                    delay += Math.max(15 - i, 7) * 2;
                } else {
                    addMotion(animation, item, item.translateXProperty(), 70 * i / count, 50, -6, 50,
                            Interpolator.LINEAR, Interpolator.LINEAR, false);
                }
            }
        } else {
            if (fresh) {
                sidebar.setOpacity(0);
                sidebar.setScaleX(0.96);
                sidebar.setScaleY(0.96);
            }
            var easing = entering ? UiMotion.BACK_WEAK : UiMotion.IN_WEAK;
            double scale = entering ? 1 : 0.95;
            animation.getChildren().addAll(
                    UiMotion.track(sidebar.opacityProperty(), entering ? 1 : 0, entering ? 0 : 30,
                            entering ? 100 : 80, Interpolator.LINEAR),
                    UiMotion.track(sidebar.scaleXProperty(), scale, 0, entering ? 400 : 110, easing),
                    UiMotion.track(sidebar.scaleYProperty(), scale, 0, entering ? 400 : 110, easing));
        }
    }

    private static void animateContent(ParallelTransition animation, List<Node> items, boolean entering, boolean fresh) {
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (fresh) {
                item.setOpacity(0);
                item.setTranslateY(-16);
            }
            if (entering) {
                addMotion(animation, item, item.translateYProperty(), i * 25, 100, 0, 350, UiMotion.OUT_WEAK,
                        UiMotion.additive(5.0 / 16, 250, 350, UiMotion.OUT_MIDDLE, UiMotion.BACK_MIDDLE), true);
            } else {
                addMotion(animation, item, item.translateYProperty(), i * 15, 70, -6, 70,
                        Interpolator.LINEAR, Interpolator.LINEAR, false);
            }
        }
    }

    private static void addMotion(ParallelTransition animation, Node item, DoubleProperty position,
                                  int delay, int fade,
                                  double destination, int duration, Interpolator fadeEase,
                                  Interpolator moveEase, boolean entering) {
        animation.getChildren().addAll(
                UiMotion.track(item.opacityProperty(), entering ? 1 : 0, delay, fade, fadeEase),
                UiMotion.track(position, destination, delay, duration, moveEase));
    }

    private static Node sidebar(Node view) {
        return view instanceof BorderPane pane ? pane.getLeft() : null;
    }

    private static double sidebarWidth(Node view) {
        return sidebar(view) instanceof Region region && region.isManaged() ? region.prefWidth(-1) : 0;
    }

    private static List<Node> content(Node view) {
        var right = right(view);
        if (right instanceof ScrollPane scroll && scroll.getContent() instanceof VBox items) {
            var controls = new ArrayList<Node>();
            collectCards(items, controls);
            return controls;
        }
        return right instanceof StackPane ? List.of(right) : List.of();
    }

    private static void collectCards(Node node, List<Node> out) {
        if (node instanceof VBox box && !(node instanceof PclCard)) {
            box.getChildren().forEach(child -> collectCards(child, out));
        } else if (node.isManaged()) {
            out.add(node);
        }
    }

    private static Node right(Node view) {
        var right = view instanceof BorderPane pane ? pane.getCenter() : view;
        if (right != null && right.getProperties().get(ViewTransition.class) instanceof ViewTransition child) {
            return child.displayed;
        }
        return right;
    }

    private static void settleChild(Node view) {
        if (!(view instanceof BorderPane pane) || pane.getCenter() == null
                || !(pane.getCenter().getProperties().get(ViewTransition.class) instanceof ViewTransition child)
                || child.motion == null || child.displayed == null) {
            return;
        }
        child.motion.stop();
        child.restore(child.displayed);
        if (child.requested != child.displayed) {
            child.host.getChildren().remove(child.displayed);
            child.display(child.requested);
            child.displayed.applyCss();
        }
    }

    private static ScrollBar scrollbar(Node view) {
        return right(view) instanceof ScrollPane scroll
                && scroll.lookup(".scroll-bar:vertical") instanceof ScrollBar bar ? bar : null;
    }

    private static void animateScrollbar(ParallelTransition animation, Node view, boolean enter, boolean fresh) {
        var bar = scrollbar(view);
        if (bar == null || !bar.isVisible()) {
            return;
        }
        if (fresh) {
            bar.setTranslateX(10);
        }
        animation.getChildren().add(UiMotion.track(bar.translateXProperty(), enter ? 0 : 10,
                0, enter ? 350 : 90, enter ? UiMotion.OUT_MIDDLE : UiMotion.IN_MIDDLE));
    }

    private void restore(Node view) {
        view.setMouseTransparent(false);
        if (type == Type.LOGIN) {
            view.setOpacity(1);
            return;
        }
        content(view).forEach(ViewTransition::reset);
        var scrollbar = scrollbar(view);
        if (scrollbar != null) {
            scrollbar.setTranslateX(0);
        }
        var left = sidebar(view);
        if (left instanceof VBox items) {
            items.getChildren().forEach(ViewTransition::reset);
        } else if (left != null) {
            reset(left);
        }
    }

    private static void reset(Node node) {
        node.setOpacity(1);
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1);
        node.setScaleY(1);
    }
}

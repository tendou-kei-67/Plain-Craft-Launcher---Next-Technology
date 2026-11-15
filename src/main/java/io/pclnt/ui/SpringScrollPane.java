package io.pclnt.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;

/** Scrolls in viewport pixels with an underdamped spring instead of a duration. */
final class SpringScrollPane extends ScrollPane {
    private static final double MASS = 1, STIFFNESS = 120, DAMPING = 18;
    private static final double DECAY = DAMPING / (2 * MASS);
    private static final double FREQUENCY = Math.sqrt(STIFFNESS / MASS - DECAY * DECAY);
    private double position, target, velocity;
    private long lastFrame, lastGesture;
    private boolean active, gesture, writing;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override public void handle(long now) { tick(now); }
    };

    SpringScrollPane(Node content) {
        super(content);
        setPannable(false);
        addEventFilter(ScrollEvent.ANY, this::onScroll);
        vvalueProperty().addListener(observable -> {
            if (!writing) {
                stop();
            }
        });
        sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) {
                stop();
            }
        });
    }

    private void onScroll(ScrollEvent event) {
        double extent = extent();
        if (extent <= 0 || event.isShiftDown()) {
            return;
        }
        if (event.getEventType() == ScrollEvent.SCROLL_STARTED) {
            double carry = velocity;
            stop();
            position = offset(extent);
            velocity = carry;
            lastGesture = System.nanoTime();
            gesture = true;
        } else if (event.getEventType() == ScrollEvent.SCROLL_FINISHED && gesture) {
            gesture = false;
            double idle = (System.nanoTime() - lastGesture) * 1e-9;
            velocity *= Math.exp(-Math.max(0, idle - .025) * DECAY);
            target = clamp(position + velocity / (2 * DECAY), extent);
            start();
            event.consume();
        } else if (event.getEventType() == ScrollEvent.SCROLL && event.getDeltaY() != 0) {
            if (!event.isInertia()) {
                if (gesture) {
                    long now = System.nanoTime();
                    double next = clamp(position - event.getDeltaY(), extent);
                    double instantaneous = (next - position) / Math.max(.008, (now - lastGesture) * 1e-9);
                    velocity = .65 * instantaneous + .35 * velocity;
                    position = next;
                    lastGesture = now;
                    write(extent);
                } else {
                    if (!active) {
                        position = target = offset(extent);
                    }
                    // Discrete wheels expose no release event. Keep the current spring velocity across ticks.
                    target = clamp(target - event.getDeltaY(), extent);
                    start();
                }
            }
            event.consume();
        }
    }

    private void tick(long now) {
        double extent = extent();
        if (extent <= 0) {
            stop();
            return;
        }
        double elapsed = lastFrame == 0 ? 0 : (now - lastFrame) * 1e-9;
        lastFrame = now;
        target = clamp(target, extent);
        double x = position - target;
        double b = (velocity + DECAY * x) / FREQUENCY;
        double cos = Math.cos(FREQUENCY * elapsed), sin = Math.sin(FREQUENCY * elapsed);
        double e = Math.exp(-DECAY * elapsed);
        double displacement = x * cos + b * sin;
        position = target + e * displacement;
        velocity = e * (FREQUENCY * (-x * sin + b * cos) - DECAY * displacement);
        if (position < 0 || position > extent) {
            position = clamp(position, extent);
            velocity = 0;
        }
        if (Math.abs(position - target) < .1 && Math.abs(velocity) < 1) {
            position = target;
            write(extent);
            stop();
        } else {
            write(extent);
        }
    }

    private double extent() {
        return Math.max(0, getContent().getLayoutBounds().getHeight() - getViewportBounds().getHeight());
    }

    private double offset(double extent) {
        return extent * (getVvalue() - getVmin()) / (getVmax() - getVmin());
    }

    private void write(double extent) {
        writing = true;
        setVvalue(getVmin() + (getVmax() - getVmin()) * position / extent);
        writing = false;
    }

    private void start() {
        if (!active) {
            active = true;
            lastFrame = 0;
            timer.start();
        }
    }

    private void stop() {
        timer.stop();
        active = false;
        gesture = false;
        velocity = 0;
    }

    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(max, value));
    }
}

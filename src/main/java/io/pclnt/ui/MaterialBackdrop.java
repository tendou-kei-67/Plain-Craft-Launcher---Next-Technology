package io.pclnt.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.SnapshotParameters;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.transform.NonInvertibleTransformException;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/** Captures the launcher background once, so all cards can display the same blurred surface. */
final class MaterialBackdrop {
    private record Frame(WritableImage image, double width, double height, double scale) {}

    private final ReadOnlyObjectWrapper<Frame> frame = new ReadOnlyObjectWrapper<>();
    private final AppearanceOptions options;
    private final StackPane host;
    private final StackPane background;
    private final Timeline videoFrames;
    private boolean queued;
    private boolean pending;
    private boolean videoPlaying;
    private long revision;

    static void install(Pane card) {
        var view = new ImageView();
        view.setMouseTransparent(true);
        view.setOpacity(.42);
        view.setSmooth(true);
        var clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        var layer = new Group(view);
        layer.setManaged(false);
        layer.setMouseTransparent(true);
        layer.setClip(clip);
        view.setVisible(false);
        view.setId("cardMaterial");
        card.getChildren().addFirst(layer);
        var source = new javafx.beans.property.SimpleObjectProperty<Frame>();
        var owner = new javafx.beans.property.SimpleObjectProperty<MaterialBackdrop>();
        Runnable position = () -> {
            var backdrop = owner.get();
            if (source.get() == null || backdrop == null || card.getScene() == null
                    || card.getWidth() < 1 || card.getHeight() < 1) {
                view.setVisible(false);
                return;
            }
            try {
                // Map the shared surface into the card's coordinates before applying its rounded clip.
                var transform = card.getLocalToSceneTransform().createInverse()
                        .createConcatenation(backdrop.host.getLocalToSceneTransform());
                view.getTransforms().setAll(transform);
                view.setVisible(true);
            } catch (NonInvertibleTransformException ignored) {
                view.setVisible(false);
            }
        };
        var relocate = (javafx.beans.InvalidationListener) ignored -> position.run();
        card.widthProperty().addListener(relocate);
        card.heightProperty().addListener(relocate);
        card.localToSceneTransformProperty().addListener(relocate);
        source.addListener((observable, old, next) -> {
            view.setImage(next == null ? null : next.image());
            // Snapshot dimensions round up to pixel boundaries. Keep that padding at the edge.
            view.setFitWidth(next == null ? 0 : next.image().getWidth() / next.scale());
            view.setFitHeight(next == null ? 0 : next.image().getHeight() / next.scale());
            position.run();
        });
        card.sceneProperty().addListener((observable, old, scene) -> {
            source.unbind();
            owner.set(null);
            source.set(null);
            if (scene != null) {
                for (Parent parent = card.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent.getProperties().get(MaterialBackdrop.class) instanceof MaterialBackdrop backdrop) {
                        owner.set(backdrop);
                        source.bind(backdrop.frame);
                        break;
                    }
                }
            }
        });
    }

    MaterialBackdrop(AppearanceOptions options, StackPane host, StackPane background) {
        this.options = options;
        this.host = host;
        this.background = background;
        host.getProperties().put(MaterialBackdrop.class, this);
        videoFrames = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            queueCapture();
        }));
        videoFrames.setCycleCount(Timeline.INDEFINITE);
        options.materialEnabled.addListener(ignored -> update());
        options.materialRadius.addListener(ignored -> update());
        options.materialSampling.addListener(ignored -> invalidate());
        options.materialMethod.addListener(ignored -> invalidate());
        host.backgroundProperty().addListener(ignored -> {
            frame.set(null);
            invalidate();
        });
    }

    void setVideoPlaying(boolean value) {
        videoPlaying = value;
        update();
    }

    void invalidate() {
        var previous = frame.get();
        if (previous != null && (previous.width() != host.getWidth() || previous.height() != host.getHeight())) {
            frame.set(null);
        }
        revision++;
        if (queued) {
            pending = true;
            return;
        }
        queueCapture();
    }

    private void queueCapture() {
        if (queued || !active() || host.getScene() == null || host.getWidth() < 1 || host.getHeight() < 1) return;
        queued = true;
        Platform.runLater(this::capture);
    }

    private void update() {
        if (active()) {
            invalidate();
        } else {
            revision++;
            frame.set(null);
        }
        if (active() && videoPlaying) {
            videoFrames.play();
        } else {
            videoFrames.stop();
        }
    }

    private boolean active() {
        return options.materialEnabled.get() && options.materialRadius.get() > 0;
    }

    private void capture() {
        double width = host.getWidth();
        double height = host.getHeight();
        if (!active() || host.getScene() == null || width < 1 || height < 1) {
            finish();
            return;
        }
        pending = false;
        long capturedRevision = revision;
        double sample = Math.max(.1, options.materialSampling.get() / 100.0);
        double radius = options.materialRadius.get() * sample;
        boolean gaussian = options.materialMethod.get() == 0;
        var snapshot = new SnapshotParameters();
        var fill = host.getBackground();
        snapshot.setFill(fill == null || fill.getFills().isEmpty() ? Color.TRANSPARENT
                : fill.getFills().getFirst().getFill());
        snapshot.setTransform(new Scale(sample, sample));
        snapshot.setViewport(new javafx.geometry.Rectangle2D(0, 0, width * sample, height * sample));
        background.snapshot(result -> {
            var sampled = result.getImage();
            int columns = (int) sampled.getWidth();
            int rows = (int) sampled.getHeight();
            var format = PixelFormat.getIntArgbPreInstance();
            var pixels = new int[columns * rows];
            sampled.getPixelReader().getPixels(0, 0, columns, rows, format, pixels, 0, columns);
            var blur = new javafx.concurrent.Task<int[]>() {
                @Override protected int[] call() {
                    return blurPixels(pixels, columns, rows, radius, gaussian);
                }
            };
            blur.setOnSucceeded(event -> {
                try {
                    if (active() && host.getScene() != null && revision == capturedRevision) {
                        sampled.getPixelWriter().setPixels(0, 0, columns, rows, format, blur.getValue(), 0, columns);
                        frame.set(new Frame(sampled, width, height, sample));
                    }
                } finally {
                    finish();
                }
            });
            blur.setOnFailed(event -> finish());
            Thread.startVirtualThread(blur);
            return null;
        }, snapshot, null);
    }

    private void finish() {
        queued = false;
        if (pending) {
            pending = false;
            queueCapture();
        }
    }

    static int[] blurPixels(int[] pixels, int width, int height, double radius, boolean gaussian) {
        int span = (int) Math.round(gaussian ? radius / 3 : radius);
        if (span == 0) return pixels;
        var scratch = new int[pixels.length];
        // Three box passes approximate a Gaussian without re-reading each pixel for the full kernel.
        int passes = gaussian ? 3 : 1;
        for (int pass = 0; pass < passes; pass++) {
            blurLines(pixels, scratch, width, height, 1, width, span);
            blurLines(scratch, pixels, height, width, width, 1, span);
        }
        return pixels;
    }

    private static void blurLines(int[] input, int[] output, int length, int count, int step, int stride, int radius) {
        int diameter = 2 * radius + 1;
        for (int line = 0; line < count; line++) {
            int start = line * stride;
            long sum = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                sum += channels(input[start + Math.clamp(offset, 0, length - 1) * step]);
            }
            for (int offset = 0; offset < length; offset++) {
                output[start + offset * step] = (int) ((sum >>> 48) / diameter) << 24
                        | (int) ((sum >>> 32 & 65535) / diameter) << 16
                        | (int) ((sum >>> 16 & 65535) / diameter) << 8
                        | (int) ((sum & 65535) / diameter);
                sum += channels(input[start + Math.min(length - 1, offset + radius + 1) * step])
                        - channels(input[start + Math.max(0, offset - radius) * step]);
            }
        }
    }

    // Each lane holds the sum of one premultiplied channel for the supported blur radii.
    private static long channels(int argb) {
        return (long) (argb >>> 24) << 48 | (long) (argb >>> 16 & 255) << 32
                | (long) (argb >>> 8 & 255) << 16 | argb & 255L;
    }
}

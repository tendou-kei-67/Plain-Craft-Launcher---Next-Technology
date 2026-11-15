package io.pclnt.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.binding.BooleanBinding;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/** Applies the personalization values to the live window. */
final class WindowAppearance {
    private static final PseudoClass PHOTO = PseudoClass.getPseudoClass("photo-background");
    private static final PseudoClass PLAIN = PseudoClass.getPseudoClass("plain-background");
    private final AppearanceOptions options;
    private final Label brand;
    private final HBox navigation;
    private final StackPane page;
    private final Canvas picture = new Canvas();
    private final MediaView video = new MediaView();
    private final StackPane background = new StackPane(picture, video);
    private final MaterialBackdrop material;
    private final ReadOnlyBooleanWrapper gaming = new ReadOnlyBooleanWrapper();
    private final BooleanBinding refreshBlocked;
    private final Notifications notifications;
    private final javafx.scene.Node defaultBrand;
    private final ReadOnlyIntegerWrapper pictureCount = new ReadOnlyIntegerWrapper();
    private Image current;
    private MediaPlayer player;
    private boolean minimized;
    private Path videoPath;

    WindowAppearance(AppearanceOptions options, Label brand, HBox navigation, StackPane page,
                     Notifications notifications) {
        this.options = options;
        this.brand = brand;
        this.navigation = navigation;
        this.page = page;
        this.notifications = notifications;
        material = new MaterialBackdrop(options, page, background);
        refreshBlocked = gaming.and(options.autoPauseVideo);
        defaultBrand = brand.getGraphic();
        video.setId("backgroundVideo");
        video.setManaged(false);
        video.setVisible(false);
        background.setMouseTransparent(true);
        background.setManaged(false);
        var clip = new Rectangle();
        clip.widthProperty().bind(page.widthProperty());
        clip.heightProperty().bind(page.heightProperty());
        background.setClip(clip);
        page.getChildren().addFirst(background);
        page.widthProperty().addListener(ignored -> draw());
        page.heightProperty().addListener(ignored -> draw());
        options.imageFit.addListener(ignored -> draw());
        options.imageBlur.addListener(ignored -> draw());
        options.imageOpacity.addListener(ignored -> draw());
        options.colorfulBackground.addListener(ignored -> draw());
        options.autoPauseVideo.addListener(ignored -> updatePlayback());
        options.logoType.addListener(ignored -> refreshLogo());
        options.logoText.addListener(ignored -> refreshLogo());
        options.logoLeft.addListener(ignored -> refreshLogo());
        refreshLogo();
    }

    ReadOnlyIntegerProperty pictureCount() { return pictureCount.getReadOnlyProperty(); }
    BooleanBinding refreshBlocked() { return refreshBlocked; }

    void watch(Stage stage) {
        minimized = stage.isIconified();
        stage.iconifiedProperty().addListener((observable, old, iconified) -> {
            minimized = iconified;
            updatePlayback();
        });
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> stopVideo());
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> {
            if (player == null && videoPath != null && Files.isRegularFile(videoPath)) {
                startVideo(videoPath);
            }
        });
    }

    void setGaming(boolean active) {
        gaming.set(active);
        updatePlayback();
    }

    String refreshPictures() throws IOException {
        var candidates = new ArrayList<Path>();
        Path folder = options.pictures();
        if (Files.isDirectory(folder)) {
            try (var files = Files.walk(folder)) {
                files.filter(Files::isRegularFile).filter(path -> isPicture(path) || isVideo(path))
                        .forEach(candidates::add);
            }
        }
        pictureCount.set(candidates.size());
        Collections.shuffle(candidates);
        current = null;
        videoPath = null;
        stopVideo();
        String name = "";
        for (var candidate : candidates) {
            if (isVideo(candidate)) {
                if (startVideo(candidate)) {
                    name = candidate.getFileName().toString();
                    break;
                }
                continue;
            }
            var image = loadImage(candidate);
            if (image != null) {
                current = image;
                name = candidate.getFileName().toString();
                break;
            }
        }
        draw();
        return name;
    }

    void clearPictures() throws IOException {
        videoPath = null;
        stopVideo();
        Path folder = options.pictures();
        if (Files.isDirectory(folder)) {
            // Never follow symbolic links when clearing this application-owned directory.
            try (var files = Files.walk(folder)) {
                var targets = files.filter(path -> !path.equals(folder)).sorted(java.util.Comparator.reverseOrder()).toList();
                for (var target : targets) {
                    Files.delete(target);
                }
            }
        }
        refreshPictures();
    }

    void refreshLogo() {
        int type = options.logoType.get();
        brand.setVisible(type != 0);
        brand.setText(type == 1 ? "-NT" : type == 2 ? options.logoText.get() : "");
        brand.pseudoClassStateChanged(PseudoClass.getPseudoClass("custom-text"), type == 2);
        brand.setAccessibleText(type == 2 ? options.logoText.get() : "PCL-NT");
        if (type == 1) {
            brand.setGraphic(defaultBrand);
        } else if (type == 3) {
            var image = loadImage(options.logo());
            if (image == null) {
                brand.setGraphic(null);
            } else {
                var logo = new ImageView(image);
                logo.setFitHeight(34);
                logo.setFitWidth(116);
                logo.setPreserveRatio(true);
                brand.setGraphic(logo);
            }
        } else {
            brand.setGraphic(null);
        }
        boolean left = type == 0 && options.logoLeft.get();
        StackPane.setAlignment(navigation, left ? Pos.CENTER_LEFT : Pos.CENTER);
        navigation.setTranslateX(left ? 0 : -3);
    }

    private void draw() {
        material.invalidate();
        double width = page.getWidth(), height = page.getHeight();
        picture.setWidth(width);
        picture.setHeight(height);
        background.resizeRelocate(0, 0, width, height);
        boolean enabled = current != null;
        page.pseudoClassStateChanged(PHOTO, enabled || video.isVisible());
        page.pseudoClassStateChanged(PLAIN, !options.colorfulBackground.get());
        var canvas = picture.getGraphicsContext2D();
        canvas.clearRect(0, 0, width, height);
        double opacity = options.imageOpacity.get() / 1000.0;
        int blur = options.imageBlur.get();
        video.setOpacity(opacity);
        video.setEffect(blur == 0 ? null : new GaussianBlur(blur + 1));
        if (video.isVisible() && player != null) {
            double sourceWidth = player.getMedia().getWidth();
            double sourceHeight = player.getMedia().getHeight();
            double scale = Math.max(width / sourceWidth, height / sourceHeight);
            video.setFitWidth(sourceWidth * scale);
            video.setFitHeight(sourceHeight * scale);
            video.setX((width - sourceWidth * scale) / 2);
            video.setY((height - sourceHeight * scale) / 2);
        }
        if (!enabled || width <= 0 || height <= 0) {
            return;
        }
        picture.setOpacity(opacity);
        picture.setEffect(blur == 0 ? null : new GaussianBlur(blur + 1));
        double iw = current.getWidth(), ih = current.getHeight();
        int fit = options.imageFit.get();
        if (fit == 0) {
            fit = iw < width / 2 && ih < height / 2 ? 4 : 2;
        }
        if (fit == 3) {
            canvas.drawImage(current, 0, 0, width, height);
        } else if (fit == 4) {
            canvas.setFill(new javafx.scene.paint.ImagePattern(current, 0, 0, iw, ih, false));
            canvas.fillRect(0, 0, width, height);
        } else {
            double scale = fit == 1 ? 1 : Math.max(width / iw, height / ih);
            double x = switch (fit) {
                case 5, 7, 9 -> 0;
                case 6, 8, 10 -> width - iw * scale;
                default -> (width - iw * scale) / 2;
            };
            double y = switch (fit) {
                case 5, 6, 11 -> 0;
                case 7, 8, 12 -> height - ih * scale;
                default -> (height - ih * scale) / 2;
            };
            canvas.drawImage(current, x, y, iw * scale, ih * scale);
        }
    }

    private boolean startVideo(Path path) {
        try {
            var media = new Media(path.toUri().toASCIIString());
            var next = new MediaPlayer(media);
            player = next;
            videoPath = path;
            next.setMute(true);
            next.setCycleCount(MediaPlayer.INDEFINITE);
            next.setOnError(() -> videoFailed(path, next));
            next.setOnPlaying(() -> material.setVideoPlaying(true));
            next.setOnPaused(() -> material.setVideoPlaying(false));
            media.setOnError(() -> videoFailed(path, next));
            next.setOnReady(() -> {
                if (player != next) {
                    return;
                }
                if (media.getWidth() <= 0 || media.getHeight() <= 0) {
                    videoFailed(path, next);
                    return;
                }
                video.setVisible(true);
                draw();
                updatePlayback();
            });
            video.setMediaPlayer(next);
            return true;
        } catch (MediaException e) {
            notifications.post("无法播放背景视频：" + path.getFileName(), Notifications.Kind.ERROR);
            return false;
        }
    }

    private void videoFailed(Path path, MediaPlayer failed) {
        if (player == failed) {
            videoPath = null;
            stopVideo();
            draw();
            notifications.post("无法播放背景视频：" + path.getFileName(), Notifications.Kind.ERROR);
        }
    }

    private void stopVideo() {
        if (player != null) {
            var previous = player;
            player = null;
            material.setVideoPlaying(false);
            video.setVisible(false);
            video.setMediaPlayer(null);
            previous.dispose();
        }
    }

    private void updatePlayback() {
        if (player != null && video.isVisible()) {
            if (minimized || refreshBlocked.get()) {
                player.pause();
            } else {
                player.play();
            }
        }
    }

    static Image loadImage(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        var image = new Image(path.toUri().toString(), false);
        return image.isError() ? null : image;
    }

    private static boolean isPicture(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.matches(".+\\.(png|jpe?g|gif|bmp|webp)$");
    }

    private static boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".m4v");
    }
}

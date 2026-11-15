package io.pclnt.ui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.VanillaDownloadPage.button;

final class SkinAvatarTool {
    private final Parent owner;
    private final Notifications notifications;
    private final ComboBox<String> size = new ComboBox<>();
    private final ImageView preview = new ImageView();
    private final Button select = button("选择皮肤", this::choose);
    private BufferedImage skin;
    private BufferedImage avatar;

    SkinAvatarTool(Parent owner, Notifications notifications) {
        this.owner = owner;
        this.notifications = notifications;
    }

    PclCard card() {
        size.getItems().setAll("64x64", "96x96", "128x128");
        size.getSelectionModel().selectFirst();
        size.getStyleClass().add("account-input");
        size.setOnAction(event -> refresh());
        preview.setFitWidth(80);
        preview.setFitHeight(80);
        preview.setSmooth(false);
        preview.setPreserveRatio(true);
        preview.visibleProperty().bind(preview.imageProperty().isNotNull());
        preview.managedProperty().bind(preview.visibleProperty());

        var save = button("保存头像", this::save);
        save.disableProperty().bind(preview.imageProperty().isNull());
        select.setPrefWidth(140);
        save.setPrefWidth(140);
        var actions = new HBox(10, select, save);
        actions.setAlignment(Pos.CENTER);
        var body = new VBox(10, SettingsPage.field("头像大小", size), actions, preview);
        body.setAlignment(Pos.TOP_CENTER);
        return PclCard.foldable("皮肤头像生成器", body, false);
    }

    private void choose() {
        var file = picker("选择皮肤文件").showOpenDialog(owner.getScene().getWindow());
        if (file == null) return;
        skin = null;
        refresh();
        size.setDisable(true);
        select.setDisable(true);
        var task = new Task<BufferedImage>() {
            @Override protected BufferedImage call() throws IOException {
                return readSkin(file.toPath());
            }
        };
        task.setOnSucceeded(event -> {
            skin = task.getValue();
            size.setDisable(false);
            select.setDisable(false);
            refresh();
            notifications.post("头像生成成功。", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            size.setDisable(false);
            select.setDisable(false);
            notifications.post("生成头像失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void refresh() {
        if (skin == null) {
            avatar = null;
            preview.setImage(null);
            return;
        }
        int width = 64 + 32 * size.getSelectionModel().getSelectedIndex();
        avatar = render(skin, width);
        var image = new WritableImage(width, width);
        image.getPixelWriter().setPixels(0, 0, width, width, PixelFormat.getIntArgbInstance(),
                avatar.getRGB(0, 0, width, width, null, 0, width), 0, width);
        preview.setImage(image);
    }

    private void save() {
        var rendered = avatar;
        if (rendered == null) return;
        var chooser = picker("保存头像");
        chooser.setInitialFileName("Head.png");
        var file = chooser.showSaveDialog(owner.getScene().getWindow());
        if (file == null) return;
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                try (var output = Files.newOutputStream(file.toPath())) {
                    if (!ImageIO.write(rendered, "png", output)) throw new IOException("无法编码 PNG 图片");
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> notifications.post("头像保存成功。", Notifications.Kind.SUCCESS));
        task.setOnFailed(event -> notifications.post("保存头像失败：" + VanillaDownloadPage.message(task.getException()),
                Notifications.Kind.ERROR));
        Thread.startVirtualThread(task);
    }

    private static FileChooser picker(String title) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        return chooser;
    }

    static BufferedImage readSkin(Path path) throws IOException {
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = Files.newInputStream(path);
             var image = new javax.imageio.stream.MemoryCacheImageInputStream(input)) {
            reader.setInput(image, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 64 || width > 4096 || width % 64 != 0 || height != width && height != width / 2) {
                throw new IOException("皮肤图片应为 64×64 或 64×32 的整数倍。");
            }
            var parameters = reader.getDefaultReadParam();
            // Decode only the rows that contain the face and its outer layer.
            parameters.setSourceRegion(new java.awt.Rectangle(0, 0, width, width / 4));
            return reader.read(0, parameters);
        } finally {
            reader.dispose();
        }
    }

    static BufferedImage render(BufferedImage skin, int size) {
        int scale = skin.getWidth() / 64;
        var result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int offset = size / 16;
        int faceSize = size - 2 * offset;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int overlay = skin.getRGB(40 * scale + x * 8 * scale / size, 8 * scale + y * 8 * scale / size);
                if ((overlay >>> 24) != 0) {
                    result.setRGB(x, y, overlay);
                } else if (x >= offset && x < size - offset && y >= offset && y < size - offset) {
                    result.setRGB(x, y, skin.getRGB(8 * scale + (x - offset) * 8 * scale / faceSize,
                            8 * scale + (y - offset) * 8 * scale / faceSize));
                }
            }
        }
        return result;
    }
}

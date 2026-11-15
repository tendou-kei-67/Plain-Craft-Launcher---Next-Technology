package io.pclnt.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import javax.imageio.ImageIO;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class AchievementTool {
    private final Parent owner;
    private final Notifications notifications;
    private final HttpClient client;
    private final TextField icon = field("物品 ID");
    private final TextField title = field("成就名称");
    private final TextField firstLine = field("第一行");
    private final TextField secondLine = field("第二行（可选）");
    private final ImageView preview = new ImageView();
    private final VBox body = new VBox(10);
    private final PclLoading loading = new PclLoading("正在生成成就图片……");
    private final javafx.beans.property.BooleanProperty busy = new javafx.beans.property.SimpleBooleanProperty();
    private Form lastForm;
    private Picture lastPicture;

    private record Picture(byte[] bytes, boolean local) {}
    private record Form(String icon, String title, String first, String second) {
        URI address() {
            String path = icon + "/" + segment(title) + "/" + segment(first);
            if (!second.isEmpty()) path += "/" + segment(second);
            return URI.create("https://minecraft-api.com/api/achivements/" + path);
        }

        private static String segment(String text) {
            // This service uses two dots for each space in its path segments.
            return URLEncoder.encode(text.replace(" ", ".."), StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    AchievementTool(Parent owner, Notifications notifications, HttpClient client) {
        this.owner = owner;
        this.notifications = notifications;
        this.client = client;
        icon.setText("grass_block");
        var form = new GridPane(14, 10);
        var inputs = java.util.List.of(icon, title, firstLine, secondLine);
        var labels = java.util.List.of("物品 ID", "成就名称", "第一行", "第二行");
        for (int row = 0; row < inputs.size(); row++) {
            form.addRow(row, label(labels.get(row), "card-text"), inputs.get(row));
            GridPane.setHgrow(inputs.get(row), Priority.ALWAYS);
        }
        var show = button("预览", () -> generate(false));
        var save = button("保存图片", () -> generate(true));
        var disabled = Bindings.createBooleanBinding(() -> busy.get() || !icon.getText().strip().matches("[a-z0-9_]+")
                || title.getText().isBlank() || firstLine.getText().isBlank(),
                busy, icon.textProperty(), title.textProperty(), firstLine.textProperty());
        show.disableProperty().bind(disabled);
        save.disableProperty().bind(disabled);
        show.setMinWidth(140);
        save.setMinWidth(140);
        preview.setFitWidth(320);
        preview.setFitHeight(64);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        var previewBox = new HBox(preview);
        previewBox.setAlignment(Pos.CENTER);
        SettingsPage.bindVisible(previewBox, preview.imageProperty().isNotNull());
        SettingsPage.bindVisible(loading, busy);
        body.getChildren().addAll(form, new HBox(20, show, save), loading, previewBox);
    }

    PclCard card() { return PclCard.foldable("成就图片生成", body, false); }

    private void generate(boolean save) {
        var form = new Form(icon.getText().strip(), title.getText().strip(), firstLine.getText().strip(), secondLine.getText().strip());
        if (form.equals(lastForm) && lastPicture != null) {
            show(form, lastPicture, save);
            return;
        }
        busy.set(true);
        var task = new Task<Picture>() {
            @Override protected Picture call() throws Exception {
                try {
                    var reply = client.send(HttpRequest.newBuilder(form.address()).timeout(Duration.ofSeconds(8)).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    try (var stream = reply.body()) {
                        if (reply.statusCode() == 200) {
                            byte[] data = stream.readNBytes(2_000_001);
                            if (data.length <= 2_000_000 && data.length >= 8 && data[0] == (byte) 137
                                    && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return new Picture(data, false);
                        }
                    }
                } catch (IOException ignored) {
                    // The local renderer can still export an image when the original service is unavailable.
                }
                return new Picture(renderLocal(form), true);
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            var picture = task.getValue();
            if (picture.local()) notifications.post("成就服务暂不可用，已使用本地样式生成。", Notifications.Kind.INFO);
            lastForm = form;
            lastPicture = picture;
            show(form, picture, save);
        });
        task.setOnFailed(event -> {
            busy.set(false);
            notifications.post("生成成就图片失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void show(Form form, Picture picture, boolean save) {
        preview.setImage(new Image(new ByteArrayInputStream(picture.bytes())));
        if (!save || owner.getScene() == null) return;
        var picker = new FileChooser();
        picker.setTitle("保存成就图片");
        picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        picker.setInitialFileName(form.title().replaceAll("[<>:\"/\\\\|?*]", "_") + ".png");
        var target = picker.showSaveDialog(owner.getScene().getWindow());
        if (target == null) return;
        try {
            Files.write(target.toPath(), picture.bytes());
            notifications.post("已保存成就图片", Notifications.Kind.SUCCESS);
        } catch (IOException failure) {
            notifications.post("保存失败：" + VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
        }
    }

    private static byte[] renderLocal(Form form) throws IOException {
        var bitmap = new BufferedImage(320, 64, BufferedImage.TYPE_INT_ARGB);
        var canvas = bitmap.createGraphics();
        try {
            canvas.setColor(new Color(0x151515));
            canvas.fillRoundRect(0, 0, 320, 64, 6, 6);
            canvas.setColor(new Color(0x575757));
            canvas.drawRoundRect(1, 1, 317, 61, 6, 6);
            canvas.setColor(new Color(0x232323));
            canvas.fillRect(8, 12, 40, 40);
            String asset = switch (form.icon()) {
                case "1", "stone", "cobblestone" -> "Cobblestone";
                case "14", "41", "gold_block" -> "GoldBlock";
                case "152", "redstone_block" -> "RedstoneBlock";
                default -> "Grass";
            };
            try (var stream = AchievementTool.class.getResourceAsStream("help/" + asset + ".png")) {
                if (stream != null) {
                    var iconImage = ImageIO.read(stream);
                    if (iconImage != null) canvas.drawImage(iconImage, 12, 16, 32, 32, null);
                }
            }
            canvas.setClip(55, 0, 258, 64);
            canvas.setColor(new Color(0xffdf55));
            canvas.setFont(new Font(Font.DIALOG, Font.BOLD, 15));
            canvas.drawString(form.title(), 55, 20);
            canvas.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
            canvas.setColor(Color.WHITE);
            canvas.drawString(form.first(), 55, 38);
            canvas.drawString(form.second(), 55, 53);
        } finally {
            canvas.dispose();
        }
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(bitmap, "png", buffer);
        return buffer.toByteArray();
    }

    private static TextField field(String prompt) {
        var field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("account-input");
        field.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText().length() <= 50 ? change : null));
        return field;
    }
}

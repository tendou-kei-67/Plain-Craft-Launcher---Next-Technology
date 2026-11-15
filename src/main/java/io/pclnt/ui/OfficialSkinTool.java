package io.pclnt.ui;

import io.pclnt.minecraft.OfficialSkin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class OfficialSkinTool {
    private final Parent owner;
    private final Notifications notifications;
    private final OfficialSkin skins;
    private final TextField player = new TextField();
    private final ImageView preview = new ImageView();
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private final SimpleBooleanProperty fetching = new SimpleBooleanProperty();
    private final PclCard card;
    private OfficialSkin.Download cached;
    private long cacheTime;

    OfficialSkinTool(Parent owner, Notifications notifications, OfficialSkin skins) {
        this.owner = owner;
        this.notifications = notifications;
        this.skins = skins;
        player.setPromptText("输入正版玩家名");
        player.setAccessibleText("正版玩家名");
        player.getStyleClass().add("account-input");
        player.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText().length() <= 50 ? change : null));
        var input = new HBox(25, label("正版玩家名", "card-text"), player);
        input.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(player, Priority.ALWAYS);
        var save = button("保存皮肤", this::download);
        save.setMinWidth(140);
        save.disableProperty().bind(Bindings.createBooleanBinding(
                () -> busy.get() || !OfficialSkin.validName(player.getText().strip()), busy, player.textProperty()));
        player.disableProperty().bind(busy);
        player.setOnAction(event -> {
            if (!save.isDisabled()) download();
        });
        preview.setFitWidth(96);
        preview.setFitHeight(96);
        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        SettingsPage.bindVisible(preview, preview.imageProperty().isNotNull());
        var loading = new PclLoading("正在获取皮肤……");
        SettingsPage.bindVisible(loading, fetching);
        card = PclCard.fixedBody("下载正版玩家的皮肤", new VBox(14, input, save, loading, preview));
    }

    PclCard card() { return card; }

    private void download() {
        String name = player.getText().strip();
        if (busy.get() || !OfficialSkin.validName(name)) return;
        if (cached != null && cached.player().equalsIgnoreCase(name)
                && System.nanoTime() - cacheTime < TimeUnit.MINUTES.toNanos(2)) {
            offerSave(cached);
            return;
        }
        preview.setImage(null);
        busy.set(true);
        fetching.set(true);
        var task = new Task<OfficialSkin.Download>() {
            @Override protected OfficialSkin.Download call() throws IOException, InterruptedException {
                return skins.download(name);
            }
        };
        task.setOnSucceeded(event -> {
            fetching.set(false);
            busy.set(false);
            var image = new Image(new ByteArrayInputStream(task.getValue().png()));
            if (image.isError()) {
                notifications.post("Mojang 返回的皮肤图片无法读取", Notifications.Kind.ERROR);
                return;
            }
            preview.setImage(image);
            cached = task.getValue();
            cacheTime = System.nanoTime();
            offerSave(cached);
        });
        task.setOnFailed(event -> {
            fetching.set(false);
            busy.set(false);
            notifications.post("获取正版皮肤失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void offerSave(OfficialSkin.Download download) {
        if (card.getScene() == null || owner.getScene() == null) return;
        var picker = new FileChooser();
        picker.setTitle("保存皮肤");
        picker.setInitialFileName(download.player() + ".png");
        picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤图片文件 (*.png)", "*.png"));
        var destination = picker.showSaveDialog(owner.getScene().getWindow());
        if (destination == null) return;
        busy.set(true);
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                save(download.png(), destination.toPath());
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            busy.set(false);
            notifications.post("玩家 " + download.player() + " 的皮肤已保存！", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            busy.set(false);
            notifications.post("保存皮肤失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private static void save(byte[] bytes, Path path) throws IOException {
        var target = path.toAbsolutePath();
        var temp = Files.createTempFile(target.getParent(), ".pcl-nt-skin-", ".part");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

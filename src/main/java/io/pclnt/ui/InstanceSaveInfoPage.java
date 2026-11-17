package io.pclnt.ui;

import io.pclnt.minecraft.WorldSave;
import io.pclnt.minecraft.WorldDatapacks;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.TaskMonitor.iconButton;

final class InstanceSaveInfoPage {
    private static final String BEFORE_SAVE = "修改设置前请确保该存档未在游戏中打开，否则会导致设置无效";
    private static final DateTimeFormatter PLAYED = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault());
    private static final List<String> DIFFICULTIES = List.of("和平", "简单", "普通", "困难");

    private final Path world;
    private final HostServices links;
    private final Notifications notifications;
    private final BooleanSupplier busy;
    private final Consumer<Boolean> writing;
    private final SimpleBooleanProperty updating = new SimpleBooleanProperty();
    private final SimpleBooleanProperty datapacksSupported = new SimpleBooleanProperty();
    private final VBox cards = new VBox(15);
    private Task<WorldSave.Info> load;

    InstanceSaveInfoPage(Path world, HostServices links, Notifications notifications, BooleanSupplier busy,
                         Consumer<Boolean> writing) {
        this.world = world;
        this.links = links;
        this.notifications = notifications;
        this.busy = busy;
        this.writing = writing;
    }

    Node view(Runnable back, Runnable openFolder) {
        return view(back, openFolder, null);
    }

    Node view(Runnable back, Runnable openFolder, Runnable openDatapacks) {
        var heading = new HBox(8, iconButton("返回存档列表", "install-back", 15, back),
                label(world.getFileName().toString(), "card-title"),
                iconButton("打开存档文件夹", "lucide/folder-open", 16, openFolder),
                iconButton("刷新存档信息", "help-refresh", 14, this::refresh));
        heading.setAlignment(Pos.CENTER_LEFT);
        if (openDatapacks != null) {
            var datapacks = VanillaDownloadPage.button("数据包", openDatapacks);
            PclArtwork.setIcon(datapacks, "lucide/file-archive", 15);
            SettingsPage.bindVisible(datapacks, datapacksSupported);
            datapacks.disableProperty().bind(updating);
            heading.getChildren().add(datapacks);
        }
        refresh();
        return content(heading, cards);
    }

    private void refresh() {
        if (updating.get()) return;
        if (load != null) load.cancel();
        datapacksSupported.set(false);
        cards.getChildren().setAll(PclCard.fixed("存档详细信息", new PclLoading("正在读取存档信息……")));
        var task = new Task<WorldSave.Info>() {
            @Override protected WorldSave.Info call() throws Exception { return WorldSave.read(world); }
        };
        load = task;
        task.setOnSucceeded(event -> {
            if (load == task) showInfo(task.getValue());
        });
        task.setOnFailed(event -> {
            if (load == task) cards.getChildren().setAll(PclCard.fixed("存档详细信息",
                    label("获取存档信息失败：" + VanillaDownloadPage.message(task.getException()), "card-text")));
        });
        Thread.startVirtualThread(task);
    }

    private void showInfo(WorldSave.Info info) {
        datapacksSupported.set(info.dataVersion() != null && info.dataVersion() >= WorldDatapacks.FIRST_DATA_VERSION);
        cards.getChildren().clear();
        if (info.recoveredFromBackup()) {
            cards.getChildren().add(label("无法读取 level.dat，当前显示 level.dat_old 中的备份信息。", "card-text"));
        }
        if (info.version() == null) {
            var hint = info.difficulty() != null ? "1.9 以下的版本无法获取存档版本"
                    : info.commands() != null ? "1.8 以下的版本无法获取存档版本和游戏难度"
                    : "1.3 以下的版本无法获取存档版本、游戏难度和是否允许作弊";
            cards.getChildren().add(label(hint, "card-text"));
        }
        var grid = grid();
        if (info.version() != null) {
            row(grid, "存档版本", info.version() + (info.dataVersion() == null ? "" : " (" + info.dataVersion() + ")"));
        }
        row(grid, "存档名称", info.name() == null ? world.getFileName().toString() : info.name());
        if (info.seed() == null) {
            row(grid, "种子", "获取失败");
        } else {
            String seed = info.seed().toString();
            var copy = new Hyperlink(seed);
            copy.setPadding(javafx.geometry.Insets.EMPTY);
            copy.setTooltip(new Tooltip("复制种子"));
            copy.setOnAction(event -> copySeed(seed));
            var external = iconButton("跳转到 Chunkbase", "lucide/external-link", 15, () -> openChunkbase(seed, info.version()));
            var actions = new HBox(6, copy, external);
            actions.setAlignment(Pos.CENTER_LEFT);
            row(grid, "种子", actions);
        }
        row(grid, "最后一次游玩", info.lastPlayed() == null ? "获取失败" : PLAYED.format(info.lastPlayed()));
        if (info.spawn() != null) {
            var spawn = info.spawn();
            row(grid, "出生点 (X/Y/Z)", spawn.x() + " / " + spawn.y() + " / " + spawn.z());
        }
        row(grid, "游戏模式", info.hardcore() ? "极限模式" : switch (info.gameMode()) {
            case 1 -> "创造模式";
            case 2 -> "冒险模式";
            case 3 -> "旁观模式";
            default -> "生存模式";
        });
        row(grid, "游戏时长", duration(info.playSeconds()));
        cards.getChildren().add(PclCard.fixed("存档详细信息", grid));
        showSettings(info);
    }

    private void showSettings(WorldSave.Info info) {
        if (info.commands() == null && info.difficulty() == null) return;
        var settings = grid();
        settings.disableProperty().bind(updating);
        if (info.commands() != null) {
            var combo = combo("是否允许作弊", List.of("不允许", "允许"), info.commands() ? 1 : 0);
            combo.setOnAction(event -> save(new WorldSave.Settings(combo.getSelectionModel().getSelectedIndex() == 1,
                    null, null), "作弊设置"));
            row(settings, "是否允许作弊", combo);
        }
        if (info.difficulty() != null) {
            var combo = combo("游戏难度", DIFFICULTIES, info.difficulty());
            combo.setOnAction(event -> save(new WorldSave.Settings(null, combo.getSelectionModel().getSelectedIndex(),
                    null), "难度设置"));
            var options = new HBox(10, combo);
            options.setAlignment(Pos.CENTER_LEFT);
            if (!info.hardcore() && info.difficultyLocked() != null) {
                var lock = new CheckBox("锁定难度");
                lock.setSelected(info.difficultyLocked());
                lock.setTooltip(new Tooltip("锁定当前难度设置，锁定后无法在游戏中更改游戏难度"));
                lock.setOnAction(event -> save(new WorldSave.Settings(null, null, lock.isSelected()), "难度设置"));
                options.getChildren().add(lock);
            }
            row(settings, "游戏难度", options);
        }
        cards.getChildren().add(PclCard.fixed("存档设置", settings));
    }

    private void save(WorldSave.Settings settings, String name) {
        if (updating.get()) return;
        if (busy.getAsBoolean()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            refresh();
            return;
        }
        updating.set(true);
        writing.accept(true);
        var task = new Task<Void>() {
            @Override protected Void call() throws Exception {
                WorldSave.update(world, settings);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            finish();
            notifications.post(name + "修改成功", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            finish();
            notifications.post(name + "修改失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void finish() {
        writing.accept(false);
        updating.set(false);
        refresh();
    }

    private static GridPane grid() {
        var grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(8);
        return grid;
    }

    private static void row(GridPane grid, String title, String value) {
        var text = label(value, "card-text");
        text.setWrapText(true);
        row(grid, title, text);
    }

    private static void row(GridPane grid, String title, Node value) {
        grid.addRow(grid.getRowCount(), label(title, "card-text"), value);
    }

    private static ComboBox<String> combo(String title, List<String> choices, int index) {
        var combo = new ComboBox<String>();
        combo.getStyleClass().add("account-input");
        combo.setPrefWidth(100);
        combo.setAccessibleText(title);
        combo.setTooltip(new Tooltip(BEFORE_SAVE));
        combo.getItems().setAll(choices);
        combo.getSelectionModel().select(index);
        return combo;
    }

    private void copySeed(String seed) {
        try {
            var content = new ClipboardContent();
            content.putString(seed);
            if (!Clipboard.getSystemClipboard().setContent(content)) throw new IllegalStateException();
            notifications.post("已复制种子", Notifications.Kind.SUCCESS);
        } catch (RuntimeException error) {
            notifications.post("复制到剪贴板失败", Notifications.Kind.ERROR);
        }
    }

    private void openChunkbase(String seed, String version) {
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+){1,2}")) {
            notifications.post("当前存档版本无法在 Chunkbase 中打开。", Notifications.Kind.INFO);
            return;
        }
        var pieces = version.split("\\.");
        var platform = version.startsWith("1.21") ? version.replace('.', '_') : pieces[0] + "_" + pieces[1];
        links.showDocument("https://www.chunkbase.com/apps/seed-map#seed=" + seed + "&platform=java_" + platform
                + "&dimension=overworld");
    }

    private static String duration(long time) {
        long days = time / 86_400, hours = time / 3_600 % 24, mins = time / 60 % 60, seconds = time % 60;
        var value = new StringBuilder();
        if (days != 0) value.append(days).append(" 天 ");
        if (hours != 0) value.append(hours).append(" 小时 ");
        if (mins != 0) value.append(mins).append(" 分钟 ");
        if (days == 0 || hours == 0 || mins == 0) {
            if (seconds != 0 || value.isEmpty()) value.append(seconds).append(" 秒");
        }
        return value.toString().strip();
    }
}

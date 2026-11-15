package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class InstanceResourcesPage {
    private final Path instance;
    private final Path directory;
    private final Kind kind;
    private final Parent owner;
    private final HostServices links;
    private final Notifications notifications;
    private final BooleanSupplier instanceBusy;
    private final Consumer<Kind> browse;
    private final Consumer<Boolean> resourceWrite;
    private final TextField search = new TextField();
    private final VBox rows = new VBox(3);
    private final ComboBox<String> filter = new ComboBox<>();
    private final SimpleBooleanProperty working = new SimpleBooleanProperty();
    private List<Path> files = List.of();

    InstanceResourcesPage(Path instance, Kind kind, Parent owner, HostServices links, Notifications notifications,
                          BooleanSupplier instanceBusy, Consumer<Kind> browse, Consumer<Boolean> resourceWrite) {
        this.instance = instance;
        this.kind = kind;
        this.owner = owner;
        this.links = links;
        this.notifications = notifications;
        this.instanceBusy = instanceBusy;
        this.browse = browse;
        this.resourceWrite = resourceWrite;
        directory = instance.resolve(switch (kind) {
            case MOD -> "mods";
            case RESOURCEPACK -> "resourcepacks";
            case SHADER -> "shaderpacks";
            default -> throw new IllegalArgumentException("Unsupported instance resource");
        });
    }

    Node view() {
        search.setPromptText("搜索资源名称或文件名");
        search.getStyleClass().addAll("account-input", "help-search");
        search.textProperty().addListener(ignored -> render());
        filter.getItems().setAll("全部", "已启用", "已禁用");
        filter.getSelectionModel().selectFirst();
        filter.getStyleClass().add("account-input");
        filter.valueProperty().addListener(ignored -> render());
        var add = button("从文件安装", this::importFile);
        add.disableProperty().bind(working);
        var actions = new FlowPane(10, 8, button("打开文件夹", this::openFolder), add,
                button("下载新资源", () -> browse.accept(kind)), button("刷新", this::refresh));
        var card = kind == Kind.MOD ? PclCard.fixed(kind.toString(), filter, rows) : PclCard.fixed(kind.toString(), rows);
        refresh();
        return content(search, PclCard.fixed("管理", actions), card);
    }

    private void refresh() {
        if (working.get()) return;
        try {
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    files = entries.filter(this::isResource)
                            .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                            .toList();
                }
            } else {
                files = List.of();
            }
            render();
        } catch (IOException error) {
            files = List.of();
            rows.getChildren().setAll(label("读取资源失败：" + VanillaDownloadPage.message(error), "card-text"));
        }
    }

    private boolean isResource(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return kind != Kind.MOD && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        }
        var name = filename(path).toLowerCase(Locale.ROOT);
        if (kind == Kind.MOD) return rawName(name).matches(".+\\.(jar|zip|litemod)");
        return name.endsWith(".zip");
    }

    private void render() {
        var term = search.getText().strip().toLowerCase(Locale.ROOT);
        int mode = filter.getSelectionModel().getSelectedIndex();
        rows.getChildren().clear();
        for (var file : files) {
            var name = filename(file);
            boolean disabled = kind == Kind.MOD && !rawName(name).equals(name);
            if (!name.toLowerCase(Locale.ROOT).contains(term)
                    || (kind == Kind.MOD && (mode == 1 && disabled || mode == 2 && !disabled))) continue;
            var title = label(kind == Kind.MOD ? rawName(name) : name, "card-text");
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            var row = new HBox(10, title);
            row.setAlignment(Pos.CENTER_LEFT);
            if (kind == Kind.MOD) {
                var toggle = button(disabled ? "启用" : "禁用", () -> toggle(file, disabled));
                toggle.setAccessibleText(toggle.getText() + " " + rawName(name));
                toggle.disableProperty().bind(working);
                row.getChildren().addAll(label(disabled ? "已禁用" : "已启用", "help-row-description"), toggle);
            }
            rows.getChildren().add(row);
        }
        if (rows.getChildren().isEmpty()) rows.getChildren().add(label(
                files.isEmpty() ? "暂无" + kind : "没有匹配的资源", "card-text"));
    }

    private void toggle(Path file, boolean disabled) {
        if (busy()) return;
        String name = filename(file);
        Path target = directory.resolve(disabled ? rawName(name) : name + ".disabled");
        try {
            if (!disabled && Files.exists(directory.resolve(name + ".old"), LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(name + ".old");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("资源文件已移动或删除");
            Files.move(file, target);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            notifications.post("已有同名资源：" + error.getFile(), Notifications.Kind.ERROR);
        } catch (IOException error) {
            notifications.post("切换状态失败：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
        }
        refresh();
    }

    private void importFile() {
        if (busy()) return;
        var chooser = new FileChooser();
        chooser.setTitle("选择要安装的" + kind);
        chooser.getExtensionFilters().add(kind == Kind.MOD
                ? new FileChooser.ExtensionFilter("模组文件", "*.jar", "*.zip", "*.litemod")
                : new FileChooser.ExtensionFilter("ZIP 文件", "*.zip"));
        var selected = chooser.showOpenDialog(owner.getScene().getWindow());
        if (selected == null || busy()) return;
        Path source = selected.toPath();
        if (!isResource(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            notifications.post("所选文件不属于当前资源类型。", Notifications.Kind.ERROR);
            return;
        }
        working.set(true);
        resourceWrite.accept(true);
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                ensureDirectory();
                Path target = directory.resolve(source.getFileName());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("已有同名资源");
                Path temporary = Files.createTempFile(directory, ".pcl-nt-", ".part");
                try {
                    Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, target);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            working.set(false);
            resourceWrite.accept(false);
            refresh();
            notifications.post("已安装 " + filename(source), Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            working.set(false);
            resourceWrite.accept(false);
            refresh();
            notifications.post("安装资源失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private boolean busy() {
        if (!working.get() && !instanceBusy.getAsBoolean()) return false;
        notifications.post("请等当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
        return true;
    }

    private void openFolder() {
        try {
            ensureDirectory();
            links.showDocument(directory.toUri().toASCIIString());
        } catch (IOException error) {
            notifications.post("无法打开文件夹：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
        }
    }

    private void ensureDirectory() throws IOException {
        if (!Files.isDirectory(instance)) throw new IOException("实例目录已移动或删除");
        Files.createDirectories(directory);
    }

    private static String filename(Path file) { return file.getFileName().toString(); }

    private static String rawName(String name) {
        int suffix = name.toLowerCase(Locale.ROOT).endsWith(".disabled") ? ".disabled".length()
                : name.toLowerCase(Locale.ROOT).endsWith(".old") ? ".old".length() : 0;
        return name.substring(0, name.length() - suffix);
    }
}

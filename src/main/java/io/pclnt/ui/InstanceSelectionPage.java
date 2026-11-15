package io.pclnt.ui;

import io.pclnt.minecraft.VanillaInstaller;
import io.pclnt.minecraft.VanillaInstaller.Instance;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import static io.pclnt.ui.LauncherController.*;

final class InstanceSelectionPage {
    private enum Category {
        FAVORITES("收藏夹"), MODIFIED("可安装模组的实例"), REGULAR("常规实例"),
        APRIL_FOOLS("愚人节版本"), UNREADABLE("错误实例");

        private final String label;
        Category(String label) { this.label = label; }
    }

    private record Entry(Instance instance, String block, String details, Category category) {}

    private VanillaInstaller installer;
    private final AppearanceOptions options;
    private Instance selected;
    private final Consumer<Instance> select;
    private final Consumer<Instance> settings;
    private final Consumer<Instance> removed;
    private final BooleanSupplier busy;
    private final Consumer<Boolean> writing;
    private final HostServices links;
    private final Notifications notifications;
    private final Parent owner;
    private final Runnable download;
    private final Parent view;
    private final VBox groups = new VBox(15);
    private final TextField filter = new TextField();
    private List<Entry> entries = List.of();
    private Task<List<Entry>> loading;

    InstanceSelectionPage(VanillaInstaller installer, AppearanceOptions options, Instance selected,
                          Consumer<Instance> select, Consumer<Instance> settings, Consumer<Instance> removed,
                          BooleanSupplier busy, Consumer<Boolean> writing, HostServices links,
                          Notifications notifications, Parent owner, Runnable back, Runnable download, Runnable importPack,
                          Function<Path, Instance> activateFolder, Supplier<VanillaInstaller> activeInstaller) {
        this.installer = installer;
        this.options = options;
        this.selected = selected;
        this.select = select;
        this.settings = settings;
        this.removed = removed;
        this.busy = busy;
        this.writing = writing;
        this.links = links;
        this.notifications = notifications;
        this.owner = owner;
        this.download = download;
        filter.setPromptText("搜索版本");
        filter.getStyleClass().addAll("account-input", "help-search");
        var navigation = new GameFolderSidebar(options, owner, notifications, links, busy, writing, path -> {
            var current = activateFolder.apply(path);
            this.installer = activeInstaller.get();
            this.selected = current;
            reload();
        }, importPack, activeInstaller);
        var returnButton = VanillaDownloadPage.button("返回启动", back);
        var header = new HBox(10, returnButton, filter);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filter, Priority.ALWAYS);
        view = section(navigation.view(), content(header, groups));
        filter.textProperty().addListener((observable, old, value) -> {
            if (loading == null) render();
        });
        reload();
    }

    Parent view() { return view; }

    private void reload() {
        if (loading != null) loading.cancel();
        groups.getChildren().setAll(PclCard.fixed("版本选择", label("正在加载版本列表……", "card-text")));
        var source = installer;
        var task = new Task<List<Entry>>() {
            @Override protected List<Entry> call() throws IOException {
                return source.instances().stream().map(instance -> entry(source, instance)).toList();
            }
        };
        loading = task;
        task.setOnSucceeded(event -> {
            if (loading != task) return;
            loading = null;
            entries = task.getValue();
            render();
        });
        task.setOnFailed(event -> {
            if (loading != task) return;
            loading = null;
            entries = List.of();
            groups.getChildren().setAll(PclCard.fixed("读取版本失败",
                    label(VanillaDownloadPage.message(task.getException()), "card-text")));
        });
        Thread.startVirtualThread(task);
    }

    private Entry entry(VanillaInstaller source, Instance instance) {
        try {
            var info = source.describe(instance);
            var block = PclArtwork.instanceBlock(info);
            boolean modded = info.loaders().stream().anyMatch(loader -> !loader.equals("OptiFine"));
            var category = modded ? Category.MODIFIED : block.equals("GoldBlock") ? Category.APRIL_FOOLS : Category.REGULAR;
            var details = info.gameVersion() + (info.loaders().isEmpty() ? "" : " · " + String.join(" / ", info.loaders()));
            return new Entry(instance, block, details, category);
        } catch (IOException | com.google.gson.JsonParseException failure) {
            return new Entry(instance, "RedstoneBlock", VanillaDownloadPage.message(failure), Category.UNREADABLE);
        }
    }

    private void render() {
        groups.getChildren().clear();
        if (entries.isEmpty()) {
            groups.getChildren().add(PclCard.fixed("暂无游戏实例", VanillaDownloadPage.button("下载游戏", download)));
            return;
        }
        var byCategory = new EnumMap<Category, VBox>(Category.class);
        var query = filter.getText().strip().toLowerCase(Locale.ROOT);
        for (var entry : entries) {
            var id = entry.instance().id();
            var custom = options.description(id);
            var description = custom.isBlank() ? entry.details() : custom;
            if (!(id + " " + entry.details() + " " + custom).toLowerCase(Locale.ROOT).contains(query)) continue;
            var category = options.favorite(id) && entry.category() != Category.UNREADABLE ? Category.FAVORITES : entry.category();
            var row = VanillaDownloadPage.row(id, description, entry.block(), () -> select.accept(entry.instance()));
            if (entry.instance().equals(selected)) {
                row.getStyleClass().add("instance-selected");
                row.setAccessibleHelp("当前启动版本");
            }
            boolean unreadable = entry.category() == Category.UNREADABLE;
            row.setDisable(unreadable);
            boolean starred = options.favorite(id);
            String favoriteText = starred ? "取消收藏" : "加入收藏夹";
            String favoriteIcon = starred ? "instance-star-filled" : "instance-star";
            Runnable favoriteAction = () -> {
                options.favorite(id, !starred);
                render();
            };
            var favorite = new MenuItem(favoriteText, PclArtwork.menuIcon(favoriteIcon, 14));
            favorite.setOnAction(event -> favoriteAction.run());
            Runnable openFolder = () -> openFolder(entry.instance());
            Runnable openSettings = () -> settings.accept(entry.instance());
            var keyboardMenu = new ContextMenu(favorite);
            row.setContextMenu(keyboardMenu);
            var actions = new HBox(2, action(favoriteText, favoriteIcon, favoriteAction),
                    action("打开文件夹", "instance-open", openFolder),
                    action("删除", "instance-delete", () -> trash(entry.instance())));
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.getStyleClass().add("instance-row-actions");
            if (!unreadable) actions.getChildren().add(action("设置", "instance-settings", openSettings));
            var line = new HBox(4, row, actions);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("instance-row");
            HBox.setHgrow(row, Priority.ALWAYS);
            row.setMinWidth(0);
            line.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
                if (!event.isKeyboardTrigger()) {
                    event.consume();
                    if (unreadable) openFolder.run();
                    else openSettings.run();
                }
            });
            // Keep hover visible when focus moves from the item to its sibling buttons.
            var showActions = line.hoverProperty().or(row.focusedProperty());
            for (var action : actions.getChildren()) {
                showActions = showActions.or(action.focusedProperty());
            }
            actions.opacityProperty().bind(javafx.beans.binding.Bindings.when(showActions).then(1.0).otherwise(0.0));
            actions.mouseTransparentProperty().bind(showActions.not());
            byCategory.computeIfAbsent(category, key -> new VBox(2)).getChildren().add(line);
        }
        byCategory.forEach((category, rows) -> groups.getChildren().add(PclCard.foldable(
                category == Category.FAVORITES ? category.label : category.label + "（" + rows.getChildren().size() + "）",
                rows, true)));
        if (byCategory.isEmpty()) groups.getChildren().add(PclCard.fixed("找不到匹配的版本"));
    }

    private static Button action(String text, String icon, Runnable run) {
        var button = new Button();
        button.getStyleClass().add("instance-row-action");
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
        PclArtwork.setIcon(button, icon, 14);
        UiMotion.installPress(button);
        button.setOnAction(event -> run.run());
        return button;
    }

    private void openFolder(Instance instance) {
        if (Files.isDirectory(instance.directory())) {
            links.showDocument(instance.directory().toUri().toASCIIString());
        } else {
            notifications.post("实例文件夹已移动或删除。", Notifications.Kind.ERROR);
        }
    }

    private void trash(Instance instance) {
        if (busy.getAsBoolean()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        PclDialogs.openMessage(owner, "删除实例",
                "将「" + instance.id() + "」的实例文件夹移入回收站？文件夹内的存档和设置也会一起移动。",
                true, result -> {
                    if (result == ButtonType.OK) moveInstanceToTrash(instance);
                }, ButtonType.OK, ButtonType.CANCEL);
    }

    private void moveInstanceToTrash(Instance instance) {
        if (busy.getAsBoolean()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                var directory = instance.directory().toAbsolutePath().normalize();
                var versions = installer.root().resolve("versions").toAbsolutePath().normalize();
                if (!versions.equals(directory.getParent()) || Files.isSymbolicLink(versions)
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("实例目录不存在或不是普通文件夹");
                }
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                        || !Desktop.getDesktop().moveToTrash(directory.toFile())) {
                    throw new IOException("系统无法将实例移入回收站");
                }
                return null;
            }
        };
        writing.accept(true);
        task.setOnSucceeded(event -> {
            if (instance.equals(selected)) selected = null;
            removed.accept(instance);
            writing.accept(false);
            reload();
            notifications.post("已将「" + instance.id() + "」移入回收站。", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            writing.accept(false);
            notifications.post("删除失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }
}

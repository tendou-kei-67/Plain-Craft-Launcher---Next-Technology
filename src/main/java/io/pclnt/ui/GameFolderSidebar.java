package io.pclnt.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.pclnt.minecraft.VanillaInstaller;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

final class GameFolderSidebar {
    private final AppearanceOptions options;
    private final GameFolders folders;
    private final Parent owner;
    private final Notifications notifications;
    private final HostServices links;
    private final BooleanSupplier busy;
    private final Consumer<Boolean> writing;
    private final Consumer<Path> activate;
    private final Runnable importPack;
    private final Supplier<VanillaInstaller> activeInstaller;
    private final javafx.beans.value.ObservableStringValue bedrockAddress;
    private final Runnable showBedrock;
    private final VBox rows = LauncherController.sidebar();
    private final ScrollPane view = new SpringScrollPane(rows);
    private ToggleGroup group;

    GameFolderSidebar(AppearanceOptions options, Parent owner, Notifications notifications, HostServices links,
                      BooleanSupplier busy, Consumer<Boolean> writing, Consumer<Path> activate, Runnable importPack,
                      Supplier<VanillaInstaller> activeInstaller,
                      javafx.beans.value.ObservableStringValue bedrockAddress, Runnable showBedrock) {
        this.options = options;
        this.folders = options.gameFolders;
        this.owner = owner;
        this.notifications = notifications;
        this.links = links;
        this.busy = busy;
        this.writing = writing;
        this.activate = activate;
        this.importPack = importPack;
        this.activeInstaller = activeInstaller;
        this.bedrockAddress = bedrockAddress;
        this.showBedrock = showBedrock;
        rows.getStyleClass().addAll("pcl-sidebar", "game-folder-list");
        view.getStyleClass().addAll("section-scroll", "game-folder-sidebar");
        view.setFitToWidth(true);
        view.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        view.setMinWidth(250);
        view.setPrefWidth(250);
        rebuild();
    }

    ScrollPane view() { return view; }

    private void rebuild() {
        group = new ToggleGroup();
        var entries = folders.entries();
        rows.getChildren().setAll(heading("文件夹列表"));
        for (var folder : entries) {
            var row = SidebarItem.folder(folder.name(), folder.path().toString(), group,
                    () -> switchTo(folder.path()), menu(folder, entries.size() == 1));
            rows.getChildren().add(row);
            row.toggle().setSelected(folder.path().equals(folders.active()));
        }
        rows.getChildren().add(SidebarItem.folder("基岩版目录", bedrockAddress, group, showBedrock, null));
        rows.getChildren().add(heading("添加或导入"));
        if (!Files.isDirectory(folders.defaultRoot())) {
            addAction("新建 .minecraft 文件夹", "folder-create",
                    "在 PCL-NT 数据目录下创建新的 .minecraft 文件夹", this::create);
        }
        addAction("添加已有文件夹", "folder-add", "将一个已有的 Minecraft 文件夹添加到列表", this::add);
        addAction("导入整合包", "instance-import", "在当前选择的 Minecraft 文件夹下安装整合包", importPack);
    }

    private void addAction(String title, String icon, String hint, Runnable action) {
        var row = SidebarItem.action(title, icon, 16, action);
        row.getStyleClass().add("pcl-folder-action");
        Tooltip.install(row, new Tooltip(hint));
        rows.getChildren().add(row);
    }

    private static Parent heading(String text) {
        var title = LauncherController.label(text, "game-folder-heading");
        VBox.setMargin(title, new Insets(18, 5, 4, 13));
        return title;
    }

    private void switchTo(Path path) {
        if (!allow("切换") || !available(path)) {
            rebuild();
            return;
        }
        activate.accept(path);
    }

    private ContextMenu menu(GameFolders.Folder folder, boolean only) {
        var path = folder.path();
        var menu = new ContextMenu();
        if (folder.renamed()) menu.getItems().add(item("复原名称", "folder-rename", () -> {
            folders.forget(path);
            rebuild();
            post("文件夹名称已复原！");
        }));
        menu.getItems().addAll(
                item("重命名", "folder-rename", () -> name("输入新名称", null, folder.name(), value -> {
                    if (value.equals(folder.name())) return;
                    folders.name(path, value);
                    rebuild();
                    post("文件夹名称已更新为 " + value + "！");
                })),
                item("打开", "folder-open", () -> {
                    if (available(path)) links.showDocument(path.toUri().toASCIIString());
                }),
                item("刷新", "folder-refresh", () -> {
                    if (path.equals(folders.active()) && allow("刷新") && available(path)) activate.accept(path);
                    rebuild();
                }));
        if (!folder.builtin()) {
            menu.getItems().add(item("移出列表", "folder-remove", () -> remove(folder)));
        }
        boolean clear = only && path.equals(folders.defaultRoot());
        // The launcher data directory itself may also contain a versions directory.
        if (!path.equals(folders.base())) menu.getItems().add(item(clear ? "清空" : "删除", "folder-delete",
                () -> trash(folder, clear)));
        return menu;
    }

    private static MenuItem item(String title, String icon, Runnable action) {
        var item = new MenuItem(title, PclArtwork.menuIcon(icon, 14));
        item.setOnAction(event -> action.run());
        return item;
    }

    private void add() {
        if (!allow("添加")) return;
        var chooser = new DirectoryChooser();
        chooser.setTitle("选择 Minecraft 文件夹");
        if (Files.isDirectory(folders.active())) chooser.setInitialDirectory(folders.active().toFile());
        var selected = chooser.showDialog(owner.getScene().getWindow());
        if (selected == null) return;
        final Path path;
        try {
            path = folders.registeredPath(GameFolders.locateExisting(selected.toPath()));
        } catch (IOException e) {
            error("添加文件夹失败：" + VanillaDownloadPage.message(e));
            return;
        }
        var existing = folders.entries().stream().filter(folder -> folder.path().equals(path)).findFirst();
        name("输入显示名称", "输入该文件夹在左边栏列表中显示的名称。",
                existing.map(GameFolders.Folder::name).orElseGet(() -> GameFolders.suggestedName(path)), value -> {
                    if (!allow("添加") || !available(path)) return;
                    boolean same = existing.map(folder -> folder.name().equals(value)).orElse(false);
                    if (!same) folders.name(path, value);
                    activate.accept(path);
                    if (existing.isEmpty()) detectSharedMods(activeInstaller.get());
                    rebuild();
                    if (same) notifications.post("此文件夹已在列表中！", Notifications.Kind.INFO);
                    else post(existing.isPresent() ? "文件夹名称已更新为 " + value + "！" : "文件夹 " + value + " 已添加！");
                });
    }

    private void detectSharedMods(VanillaInstaller source) {
        var task = new Task<java.util.List<String>>() {
            @Override protected java.util.List<String> call() throws IOException {
                return GameFolders.sharedModpackVersions(source);
            }
        };
        task.setOnSucceeded(e -> {
            if (folders.entries().stream().anyMatch(entry -> entry.path().equals(source.root()))) {
                options.inheritSharedMods(source.root(), task.getValue());
            }
        });
        Thread.startVirtualThread(task);
    }

    private void create() {
        if (!allow("创建")) return;
        var path = folders.defaultRoot();
        try {
            GameFolders.createRoot(path);
            activate.accept(path);
            rebuild();
            post("新建 .minecraft 文件夹成功！");
        } catch (IOException e) {
            error("创建文件夹失败：" + VanillaDownloadPage.message(e));
        }
    }

    private void name(String title, String message, String initial, Consumer<String> accept) {
        PclDialogs.input(owner, title, message, initial, name -> GameFolders.validName(name.strip()))
                .map(String::strip).ifPresent(accept);
    }

    private void remove(GameFolders.Folder folder) {
        if (!allow("移除")) return;
        var delete = new ButtonType("删除", ButtonBar.ButtonData.OTHER);
        var keep = new ButtonType("保留", ButtonBar.ButtonData.OK_DONE);
        PclDialogs.openMessage(owner, "配置文件清理",
                "是否需要清理 PCL-NT 在该文件夹中的配置？这包括各个版本的独立设置，对游戏本身没有影响。",
                false, result -> {
            if ((result == delete || result == keep) && allow("移除")) {
                if (result == delete) options.clearFolderSettings(folder.path());
                forget(folder.path());
                post("文件夹 " + folder.name() + " 已从列表中移除！");
            }
        }, delete, keep, ButtonType.CANCEL);
    }

    private void trash(GameFolders.Folder folder, boolean clear) {
        String verb = clear ? "清空" : "删除";
        if (!allow(verb) || !available(folder.path())) return;
        var proceed = new ButtonType("确认", ButtonBar.ButtonData.OTHER);
        PclDialogs.openMessage(owner, "警告", "你确定要" + verb + "这个文件夹吗？\n目标文件夹：" + folder.path(),
                true, result -> {
                    if (result == proceed) confirmTrash(folder, clear, verb);
                }, proceed, ButtonType.CANCEL);
    }

    private void confirmTrash(GameFolders.Folder folder, boolean clear, String verb) {
        var confirm = new ButtonType("移入回收站", ButtonBar.ButtonData.OTHER);
        PclDialogs.openMessage(owner, verb + "确认", "目标文件夹：" + folder.path()
                + "\n\n游戏存档、游戏版本及其中的其他文件会一并移入回收站。",
                true, result -> {
            if (result != confirm || !allow(verb)) return;
            var otherPaths = folders.entries().stream().map(GameFolders.Folder::path)
                    .filter(path -> !path.equals(folder.path())).toList();
            var task = new Task<Void>() {
                @Override protected Void call() throws IOException {
                    recycle(folder.path(), otherPaths);
                    return null;
                }
            };
            writing.accept(true);
            task.setOnSucceeded(e -> {
                try {
                    if (clear) GameFolders.createRoot(folder.path());
                    post("已" + verb + "文件夹 " + folder.name() + "！");
                } catch (IOException failure) {
                    error("已移入回收站，但无法重新创建文件夹：" + VanillaDownloadPage.message(failure));
                } finally {
                    options.clearFolderSettings(folder.path());
                    writing.accept(false);
                    forget(folder.path());
                }
            });
            task.setOnFailed(e -> {
                writing.accept(false);
                error(verb + "失败：" + VanillaDownloadPage.message(task.getException()));
            });
            Thread.startVirtualThread(task);
        }, confirm, ButtonType.CANCEL);
    }

    private void recycle(Path target, java.util.List<Path> otherPaths) throws IOException {
        var real = target.toRealPath();
        var data = Files.exists(folders.base()) ? folders.base().toRealPath() : folders.base();
        if (real.getParent() == null || !real.equals(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                || data.startsWith(real)) throw new IOException("无法删除此目录");
        for (var other : otherPaths) {
            if (other.startsWith(target)) throw new IOException("目录内包含另一个已登记的游戏文件夹");
            Path otherReal;
            try {
                otherReal = other.toRealPath();
            } catch (IOException ignored) {
                // Another game's drive may be offline or inaccessible.
                continue;
            }
            if (otherReal.startsWith(real)) throw new IOException("目录内包含另一个已登记的游戏文件夹");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                || !Desktop.getDesktop().moveToTrash(target.toFile())) {
            throw new IOException("系统无法将此文件夹移入回收站");
        }
    }

    private void forget(Path path) {
        folders.forget(path);
        if (path.equals(folders.active())) activate.accept(folders.fallback());
        rebuild();
    }


    private boolean allow(String verb) {
        if (!busy.getAsBoolean()) return true;
        notifications.post("请在其他任务完成后，再" + verb + "游戏文件夹！", Notifications.Kind.INFO);
        return false;
    }

    private boolean available(Path path) {
        if (Files.isDirectory(path) && Files.isReadable(path)) return true;
        error("无法访问 Minecraft 文件夹：" + path);
        return false;
    }

    private void error(String message) { notifications.post(message, Notifications.Kind.ERROR); }
    private void post(String message) { notifications.post(message, Notifications.Kind.SUCCESS); }
}

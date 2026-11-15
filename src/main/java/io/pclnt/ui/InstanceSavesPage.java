package io.pclnt.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.TaskMonitor.iconButton;
import static io.pclnt.ui.VanillaDownloadPage.button;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class InstanceSavesPage {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/M/d").withZone(ZoneId.systemDefault());
    private static final String COPY_PREFIX = ".pcl-nt-save-copy-";
    private record Save(Path directory, FileTime created, FileTime modified) {
        String name() { return directory.getFileName().toString(); }
        String dates() {
            return "创建时间：" + DATE.format(created.toInstant()) + "，最后修改时间：" + DATE.format(modified.toInstant());
        }
    }
    private record Snapshot(List<Save> saves, boolean quickPlay) {}

    private final Path instance;
    private final Path directory;
    private final HostServices links;
    private final Notifications notifications;
    private final BooleanSupplier instanceBusy;
    private final Consumer<Boolean> resourceWrite;
    private final Callable<Boolean> supportsQuickPlay;
    private final Consumer<String> quickPlay;
    private final Consumer<Node> show;
    private final TextField search = new TextField();
    private final VBox rows = new VBox(3);
    private final PclCard list = PclCard.fixed("存档列表", rows);
    private final SimpleBooleanProperty working = new SimpleBooleanProperty();
    private List<Save> saves = List.of();
    private boolean quickPlayEnabled;
    private Comparator<Save> order = Comparator.comparing(Save::name, String.CASE_INSENSITIVE_ORDER);
    private Task<Snapshot> scan;
    private Node view;

    InstanceSavesPage(Path instance, Path gameDirectory, HostServices links, Notifications notifications,
                      BooleanSupplier instanceBusy, Consumer<Boolean> resourceWrite, Callable<Boolean> supportsQuickPlay,
                      Consumer<String> quickPlay, Consumer<Node> show) {
        this.instance = instance;
        directory = gameDirectory.resolve("saves");
        this.links = links;
        this.notifications = notifications;
        this.instanceBusy = instanceBusy;
        this.resourceWrite = resourceWrite;
        this.supportsQuickPlay = supportsQuickPlay;
        this.quickPlay = quickPlay;
        this.show = show;
    }

    Node view(Runnable download) {
        search.setPromptText("搜索存档名称");
        search.setAccessibleText("搜索存档名称");
        search.getStyleClass().addAll("account-input", "help-search");
        search.textProperty().addListener(ignored -> render());
        var sort = new MenuButton("排序：文件名");
        sort.getStyleClass().add("account-input");
        PclArtwork.setIcon(sort, "lucide/arrow-up-down", 14);
        sort.getItems().addAll(sortItem(sort, "文件名", order),
                sortItem(sort, "创建时间", Comparator.comparing(Save::created).reversed().thenComparing(order)),
                sortItem(sort, "修改时间", Comparator.comparing(Save::modified).reversed().thenComparing(order)));
        var controls = new HBox(6, sort);
        controls.setAlignment(Pos.CENTER_RIGHT);
        list.setHeaderContent(controls);
        var paste = button("粘贴剪贴板文件", this::paste);
        paste.disableProperty().bind(working);
        var actions = new FlowPane(10, 8, button("打开存档文件夹", this::openRoot), button("下载新资源", download), paste);
        view = content(search, PclCard.fixed("快捷操作", actions), list);
        reload();
        return view;
    }

    void reload() {
        if (working.get()) return;
        if (scan != null) scan.cancel();
        if (saves.isEmpty()) rows.getChildren().setAll(new PclLoading("正在刷新……"));
        var task = new Task<Snapshot>() {
            @Override protected Snapshot call() throws Exception {
                boolean supported;
                try {
                    supported = supportsQuickPlay.call();
                } catch (IOException failure) {
                    supported = false;
                }
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return new Snapshot(List.of(), supported);
                try (var paths = Files.list(directory)) {
                    var snapshot = new ArrayList<Save>();
                    for (var iterator = paths.iterator(); iterator.hasNext() && !isCancelled();) {
                        Path path = iterator.next();
                        if (path.getFileName().toString().startsWith(COPY_PREFIX)) continue;
                        try {
                            var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                            if (attributes.isDirectory()) snapshot.add(new Save(path, attributes.creationTime(), attributes.lastModifiedTime()));
                        } catch (java.nio.file.NoSuchFileException ignored) {
                            // The game or file manager removed this folder during the scan.
                        }
                    }
                    return new Snapshot(snapshot, supported);
                }
            }
        };
        scan = task;
        task.setOnSucceeded(event -> {
            if (scan != task) return;
            var snapshot = task.getValue();
            saves = snapshot.saves();
            quickPlayEnabled = snapshot.quickPlay();
            render();
        });
        task.setOnFailed(event -> {
            if (scan != task) return;
            rows.getChildren().setAll(label("读取存档列表失败：" + VanillaDownloadPage.message(task.getException()), "card-text"));
        });
        Thread.startVirtualThread(task);
    }

    private MenuItem sortItem(MenuButton menu, String text, Comparator<Save> comparator) {
        var item = new MenuItem(text);
        item.setOnAction(event -> {
            order = comparator;
            menu.setText("排序：" + text);
            render();
        });
        return item;
    }

    private void render() {
        var term = search.getText().strip().toLowerCase(Locale.ROOT);
        var filtered = saves.stream().filter(save -> save.name().toLowerCase(Locale.ROOT).contains(term)).sorted(order).toList();
        list.setHeading((term.isEmpty() ? "存档列表" : "搜索结果") + " (" + filtered.size() + ")");
        if (filtered.isEmpty()) {
            rows.getChildren().setAll(label(saves.isEmpty() ? "暂无存档文件" : "没有匹配的存档", "card-text"));
            return;
        }
        rows.getChildren().setAll(filtered.stream().map(this::row).toList());
    }

    private Node row(Save save) {
        Path source = save.directory().resolve("icon.png");
        var icon = PclArtwork.image("resource-icons/NoIcon.png", 32);
        if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            var image = new Image(source.toUri().toASCIIString(), 32, 32, true, true, true);
            image.progressProperty().addListener((ignored, old, progress) -> {
                if (progress.doubleValue() == 1 && !image.isError()) icon.setImage(image);
            });
            if (image.getProgress() == 1 && !image.isError()) icon.setImage(image);
        }
        var clip = new Rectangle(32, 32);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        icon.setClip(clip);
        var caption = new VBox(2, label(save.name(), "version-row-title"), label(save.dates(), "help-row-description"));
        var graphic = new HBox(8, icon, caption);
        graphic.setAlignment(Pos.CENTER_LEFT);
        var details = new Button(null, graphic);
        details.getStyleClass().add("help-row");
        details.setAccessibleText(save.name() + " · 详情");
        details.setAlignment(Pos.CENTER_LEFT);
        details.setMaxWidth(Double.MAX_VALUE);
        details.setOnAction(event -> details(save));
        HBox.setHgrow(details, Priority.ALWAYS);
        var row = new HBox(2, details,
                iconButton("打开文件位置", "lucide/folder-open", 16, () -> open(save.directory())),
                iconButton("删除", "lucide/trash-2", 16, () -> trash(save)),
                iconButton("复制存档", "lucide/copy", 16, () -> copy(save)),
                iconButton("详情", "lucide/info", 16, () -> details(save)));
        row.setAlignment(Pos.CENTER_LEFT);
        row.disableProperty().bind(working);
        if (quickPlayEnabled && Files.isRegularFile(save.directory().resolve("level.dat"), NOFOLLOW_LINKS)) {
            row.getChildren().add(iconButton("快捷启动", "lucide/play", 16, () -> quickPlay.accept(save.name())));
        }
        return row;
    }

    private void details(Save save) {
        if (!Files.isDirectory(save.directory(), NOFOLLOW_LINKS)) {
            report("存档文件夹不存在");
            reload();
            return;
        }
        var details = new InstanceSaveInfoPage(save.directory(), links, notifications, instanceBusy, resourceWrite);
        show.accept(details.view(() -> {
            reload();
            show.accept(view);
        }, () -> open(save.directory())));
    }

    private void openRoot() {
        try {
            ensureDirectory();
            open(directory);
        } catch (IOException failure) {
            report(VanillaDownloadPage.message(failure));
        }
    }

    private void open(Path path) {
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) links.showDocument(path.toUri().toASCIIString());
        else report("存档文件夹不存在");
    }

    private void copy(Save save) {
        if (!Files.isDirectory(save.directory(), NOFOLLOW_LINKS)) {
            report("存档文件夹不存在");
            return;
        }
        try {
            var data = new ClipboardContent();
            data.putFiles(List.of(save.directory().toFile()));
            if (!Clipboard.getSystemClipboard().setContent(data)) throw new IllegalStateException("剪贴板不可用");
            notifications.post("已复制存档文件夹到剪贴板！请在删除原存档前完成粘贴。", Notifications.Kind.INFO);
        } catch (RuntimeException failure) {
            report("复制失败：" + VanillaDownloadPage.message(failure));
        }
    }

    private void paste() {
        var clipboard = Clipboard.getSystemClipboard();
        var folders = clipboard.getFiles().stream().map(java.io.File::toPath).toList();
        if (folders.isEmpty()) {
            report("剪贴板中没有可粘贴的存档文件夹");
            return;
        }
        write(() -> {
            ensureDirectory();
            int copied = 0;
            var failures = new ArrayList<String>();
            for (Path source : folders) {
                try {
                    pasteFolder(source);
                    copied++;
                } catch (IOException | UncheckedIOException failure) {
                    failures.add(VanillaDownloadPage.message(failure));
                }
            }
            if (!failures.isEmpty()) return "已粘贴 " + copied + " 个文件夹。 " + String.join("；", failures);
            return "已粘贴 " + copied + " 个文件夹";
        });
    }

    private void pasteFolder(Path source) throws IOException {
        if (!Files.isDirectory(source, NOFOLLOW_LINKS) || source.getFileName() == null)
            throw new IOException("来源不是存档文件夹：" + source);
        Path target = directory.resolve(source.getFileName());
        if (Files.exists(target, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException("发现同名文件夹：" + source.getFileName());
        if (directory.toRealPath().startsWith(source.toRealPath())) throw new IOException("不能复制存档目录的上级文件夹");
        Path staging = Files.createTempDirectory(directory, COPY_PREFIX);
        try {
            try (var paths = Files.walk(source)) {
                for (var iterator = paths.iterator(); iterator.hasNext();) {
                    Path path = iterator.next();
                    if (path.equals(source)) continue;
                    var copy = staging.resolve(source.relativize(path));
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectory(copy);
                    else if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.copy(path, copy, NOFOLLOW_LINKS);
                    else throw new IOException("存档包含无法复制的文件：" + path.getFileName());
                }
            }
            Files.move(staging, target);
        } finally {
            if (Files.isDirectory(staging, NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private void trash(Save save) {
        write(() -> {
            var path = save.directory();
            if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                    || !Desktop.getDesktop().moveToTrash(path.toFile())) throw new IOException("无法将存档移到回收站");
            return "已将存档移至回收站！";
        });
    }

    private void ensureDirectory() throws IOException {
        if (!Files.isDirectory(instance)) throw new IOException("实例目录已移动或删除");
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("存档目录不可用");
    }

    private void write(Callable<String> operation) {
        if (working.get() || instanceBusy.getAsBoolean()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        working.set(true);
        resourceWrite.accept(true);
        var task = new Task<String>() {
            @Override protected String call() throws Exception { return operation.call(); }
        };
        task.setOnSucceeded(event -> {
            finishWrite();
            notifications.post(task.getValue(), Notifications.Kind.INFO);
        });
        task.setOnFailed(event -> {
            finishWrite();
            report(VanillaDownloadPage.message(task.getException()));
        });
        Thread.startVirtualThread(task);
    }

    private void finishWrite() {
        working.set(false);
        resourceWrite.accept(false);
        reload();
    }

    private void report(String message) {
        notifications.post(message, Notifications.Kind.ERROR);
    }
}

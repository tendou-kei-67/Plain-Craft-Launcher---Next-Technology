package io.pclnt.ui;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class InstanceScreenshotsPage {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp", "webp", "tiff");
    private static final int ROW_WIDTH = 214, THUMBNAIL_WIDTH = 200, THUMBNAIL_HEIGHT = 120;
    private record Screenshot(Path file, FileTime created, FileTime modified) {}
    private record Gallery(Path directory, List<Screenshot> files) {}

    private final Path directory;
    private final HostServices links;
    private final Notifications notifications;
    private final BorderPane view = new BorderPane();
    // An empty list represents the quick-actions row. All other rows are virtualized by ListView.
    private final ListView<List<Screenshot>> list = new ListView<>();
    private final LinkedHashMap<Screenshot, Image> thumbnails = new LinkedHashMap<>(16, .75f, true);
    private Gallery gallery;
    private Task<Gallery> scan;
    private int columns = 1;

    InstanceScreenshotsPage(Path gameDirectory, HostServices links, Notifications notifications) {
        this.directory = gameDirectory.resolve("screenshots").toAbsolutePath().normalize();
        this.links = links;
        this.notifications = notifications;
        list.getStyleClass().add("instance-screenshot-list");
        list.setAccessibleText("截图列表");
        list.setCellFactory(ignored -> new GalleryCell());
        list.widthProperty().addListener((observable, old, width) -> {
            int count = Math.max(1, (width.intValue() - 50) / ROW_WIDTH);
            if (count == columns) return;
            columns = count;
            if (gallery != null && !gallery.files().isEmpty()) updateRows();
        });
        view.sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null && scan != null) scan.cancel();
        });
        reload();
    }

    Node view() { return view; }

    void reload() {
        if (scan != null) scan.cancel();
        view.setCenter(center(PclCard.fixedBody("", new PclLoading("正在加载截图……"))));
        var task = new Task<Gallery>() {
            @Override protected Gallery call() throws IOException {
                if (Files.notExists(directory, NOFOLLOW_LINKS)) return new Gallery(null, List.of());
                if (!Files.isDirectory(directory)) throw new IOException("截图路径不是文件夹");
                Path actual = directory.toRealPath();
                var files = new ArrayList<Screenshot>();
                try (var children = Files.list(actual)) {
                    for (var child : (Iterable<Path>) children::iterator) {
                        if (isCancelled()) break;
                        if (!supported(child)) continue;
                        try {
                            var stat = Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW_LINKS);
                            if (stat.isRegularFile()) files.add(new Screenshot(child, stat.creationTime(), stat.lastModifiedTime()));
                        } catch (java.nio.file.NoSuchFileException ignored) {
                            // A screenshot can be removed while its metadata is being enumerated.
                        }
                    }
                }
                files.sort(Comparator.comparing(Screenshot::created).reversed()
                        .thenComparing(Screenshot::file, Comparator.reverseOrder()));
                return new Gallery(actual, List.copyOf(files));
            }
        };
        scan = task;
        task.setOnSucceeded(event -> {
            if (scan != task) return;
            scan = null;
            gallery = task.getValue();
            thumbnails.keySet().retainAll(gallery.files());
            showGallery();
        });
        task.setOnFailed(event -> {
            if (scan != task) return;
            scan = null;
            view.setCenter(center(PclCard.fixed("无法读取截图", label(VanillaDownloadPage.message(task.getException()),
                    "card-text"), button("重试", this::reload))));
        });
        Thread.startVirtualThread(task);
    }

    private static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    private void showGallery() {
        if (gallery.files().isEmpty()) {
            list.getItems().clear();
            view.setCenter(center(PclCard.fixed("暂时没有截图文件",
                    label("在游戏内按下截图键（默认为 F2）后，可在此处查看保存的截图", "card-text"), folderButton())));
        } else {
            view.setCenter(list);
            updateRows();
        }
    }

    private void updateRows() {
        var rows = new ArrayList<List<Screenshot>>();
        rows.add(List.of());
        var files = gallery.files();
        for (int start = 0; start < files.size(); start += columns) {
            rows.add(files.subList(start, Math.min(start + columns, files.size())));
        }
        list.getItems().setAll(rows);
    }

    private Button folderButton() {
        var open = button("打开截图文件夹", this::openFolder);
        open.setMinWidth(140);
        open.getStyleClass().add("accent-button");
        return open;
    }

    private void openFolder() {
        var task = new Task<Path>() {
            @Override protected Path call() throws IOException {
                if (!Files.isDirectory(directory.getParent())) throw new IOException("游戏目录已移走或删除");
                return Files.createDirectories(directory);
            }
        };
        task.setOnSucceeded(event -> links.showDocument(task.getValue().toUri().toASCIIString()));
        task.setOnFailed(event -> error("打开截图文件夹失败", task.getException()));
        Thread.startVirtualThread(task);
    }

    private static StackPane center(Region content) {
        content.setMaxSize(420, Region.USE_PREF_SIZE);
        var center = new StackPane(content);
        center.getStyleClass().add("instance-screenshot-empty");
        return center;
    }

    private final class GalleryCell extends ListCell<List<Screenshot>> {
        private final List<Task<?>> pending = new ArrayList<>();

        @Override protected void updateItem(List<Screenshot> files, boolean empty) {
            super.updateItem(files, empty);
            pending.forEach(task -> task.cancel());
            pending.clear();
            setText(null);
            setGraphic(null);
            if (empty || files == null) return;
            if (files.isEmpty()) {
                var actions = PclCard.fixed("快捷操作", folderButton());
                actions.prefWidthProperty().bind(list.widthProperty().subtract(66));
                setGraphic(actions);
                return;
            }
            var row = new HBox(14);
            for (var file : files) row.getChildren().add(tile(file, pending));
            setGraphic(row);
        }
    }

    private Node tile(Screenshot screenshot, List<Task<?>> pending) {
        var name = screenshot.file().getFileName().toString();
        var preview = new ImageView();
        preview.setFitWidth(THUMBNAIL_WIDTH);
        preview.setFitHeight(THUMBNAIL_HEIGHT);
        preview.setPreserveRatio(true);
        preview.setSmooth(true);
        var fallback = label(name, "notification-muted");
        var art = new StackPane(fallback, preview);
        art.setMinSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        art.setMaxSize(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        var opener = new Button(null, art);
        opener.getStyleClass().add("instance-screenshot-preview");
        opener.setAccessibleText("查看截图 " + name);
        opener.setTooltip(new Tooltip(name));
        opener.setOnAction(event -> open(screenshot, false));
        if (thumbnails.containsKey(screenshot)) {
            preview.setImage(thumbnails.get(screenshot));
            fallback.setVisible(false);
        } else {
            Path expected = gallery.directory();
            var work = new Task<BufferedImage>() {
                @Override protected BufferedImage call() throws IOException {
                    return decode(existing(screenshot, expected), true);
                }
            };
            work.setOnSucceeded(event -> {
                var image = fxImage(work.getValue());
                thumbnails.put(screenshot, image);
                if (thumbnails.size() > 128) thumbnails.pollFirstEntry();
                preview.setImage(image);
                fallback.setVisible(false);
            });
            pending.add(work);
            Thread.startVirtualThread(work);
        }
        var actions = new HBox(4,
                TaskMonitor.iconButton("打开文件位置", "lucide/folder-open", 17, () -> open(screenshot, true)),
                TaskMonitor.iconButton("删除截图", "lucide/trash-2", 17, () -> trash(screenshot)),
                TaskMonitor.iconButton("复制截图", "lucide/copy", 17, () -> copy(screenshot)));
        actions.setAlignment(Pos.CENTER);
        var body = new VBox(5, opener, actions);
        body.getStyleClass().add("instance-screenshot-card");
        var card = PclCard.fixedBody("", body);
        card.setMinWidth(THUMBNAIL_WIDTH);
        card.setMaxWidth(THUMBNAIL_WIDTH);
        return card;
    }

    private Path existing(Screenshot screenshot, Path expected) throws IOException {
        Path file = screenshot.file();
        if (expected == null || !expected.equals(directory.toRealPath()) || !file.getParent().equals(expected)
                || !Files.isRegularFile(file, NOFOLLOW_LINKS) || !file.toRealPath().equals(file)) {
            throw new IOException("截图文件不存在或已移走");
        }
        return file;
    }

    private void open(Screenshot screenshot, boolean reveal) {
        Path expected = gallery.directory();
        var work = new Task<Path>() {
            @Override protected Path call() throws IOException {
                var file = existing(screenshot, expected);
                if (Desktop.isDesktopSupported()) {
                    var desktop = Desktop.getDesktop();
                    if (reveal && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                        desktop.browseFileDirectory(file.toFile());
                        return null;
                    }
                    if (!reveal && desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(file.toFile());
                        return null;
                    }
                }
                return reveal ? file.getParent() : file;
            }
        };
        work.setOnSucceeded(event -> {
            if (work.getValue() != null) links.showDocument(work.getValue().toUri().toASCIIString());
        });
        work.setOnFailed(event -> error("打开截图失败", work.getException()));
        Thread.startVirtualThread(work);
    }

    private void trash(Screenshot screenshot) {
        Path expected = gallery.directory();
        var work = new Task<Void>() {
            @Override protected Void call() throws IOException {
                var file = existing(screenshot, expected);
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                        || !Desktop.getDesktop().moveToTrash(file.toFile())) {
                    throw new IOException("系统无法将截图移入回收站");
                }
                return null;
            }
        };
        work.setOnSucceeded(event -> {
            if (gallery != null) {
                gallery = new Gallery(gallery.directory(), gallery.files().stream()
                        .filter(file -> !file.equals(screenshot)).toList());
                thumbnails.remove(screenshot);
                showGallery();
            }
            notifications.post("已将截图移至回收站！", Notifications.Kind.INFO);
        });
        work.setOnFailed(event -> error("删除截图失败", work.getException()));
        Thread.startVirtualThread(work);
    }

    private void copy(Screenshot screenshot) {
        Path expected = gallery.directory();
        var work = new Task<BufferedImage>() {
            @Override protected BufferedImage call() throws IOException {
                return decode(existing(screenshot, expected), false);
            }
        };
        work.setOnSucceeded(event -> {
            if (copyImage(work.getValue())) {
                notifications.post("已复制截图到剪贴板！", Notifications.Kind.INFO);
            } else {
                notifications.post("截图复制失败！", Notifications.Kind.ERROR);
            }
        });
        work.setOnFailed(event -> error("截图复制失败", work.getException()));
        Thread.startVirtualThread(work);
    }

    private static boolean copyImage(BufferedImage image) {
        try {
            var content = new ClipboardContent();
            content.putImage(fxImage(image));
            var clipboard = Clipboard.getSystemClipboard();
            if (clipboard.setContent(content) && clipboard.hasImage()) return true;
        } catch (IllegalStateException | SecurityException ignored) {
            // Try the AWT system clipboard if JavaFX cannot open it.
        }
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
                @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                    return image;
                }
            }, null);
            return clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor);
        } catch (IllegalStateException | java.awt.HeadlessException | SecurityException ignored) {
            return false;
        }
    }

    private static BufferedImage decode(Path file, boolean thumbnail) throws IOException {
        try (var source = Files.newInputStream(file);
             var input = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("当前 Java 环境无法解码该图片格式");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if ((long) w * h > 40_000_000) throw new IOException("图片尺寸过大");
                var parameters = reader.getDefaultReadParam();
                if (thumbnail) {
                    int scale = Math.max(1, Math.max(w / THUMBNAIL_WIDTH, h / THUMBNAIL_HEIGHT));
                    parameters.setSourceSubsampling(scale, scale, 0, 0);
                }
                return reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Image fxImage(BufferedImage data) {
        int w = data.getWidth(), h = data.getHeight();
        var image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(),
                data.getRGB(0, 0, w, h, null, 0, w), 0, w);
        return image;
    }

    private void error(String title, Throwable error) {
        notifications.post(title + "：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
    }
}

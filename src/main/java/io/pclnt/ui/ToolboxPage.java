package io.pclnt.ui;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.LauncherController.section;
import static io.pclnt.ui.LauncherController.sidebar;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class ToolboxPage {
    private final AppearanceOptions options;
    private final Notifications notifications;
    private final HostServices links;
    private final Parent owner;
    private final Parent view;
    private final TaskMonitor tasks;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final TextField url = input("下载地址");
    private final TextField folder = input("保存到");
    private final TextField name = input("文件名");
    private final Button start = button("开始下载", this::download);
    private final Button open = button("打开文件夹", this::openFolder);
    private String suggestedName = "";
    private boolean downloading;

    ToolboxPage(AppearanceOptions options, Notifications notifications, HostServices links, Parent owner, TaskMonitor tasks) {
        this.options = options;
        this.notifications = notifications;
        this.links = links;
        this.owner = owner;
        this.tasks = tasks;

        var tools = new FlowPane(10, 10, button("今日人品", this::dailyLuck),
                button("查看启动计数", this::launchCount));
        if (WindowsMemory.supported()) {
            var optimize = new Button("内存优化");
            optimize.getStyleClass().add("outline-button");
            UiMotion.installPress(optimize);
            optimize.setOnAction(event -> optimizeMemory(optimize));
            tools.getChildren().add(optimize);
        }
        tools.getChildren().forEach(node -> ((Button) node).setMinWidth(112));
        var officialSkin = new io.pclnt.minecraft.OfficialSkin(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        var cards = content(PclCard.fixed("百宝箱", tools), downloadCard(),
                new OfficialSkinTool(owner, notifications, officialSkin).card(), new ServerQueryTool().card(),
                new AchievementTool(owner, notifications, client).card(),
                new SkinAvatarTool(owner, notifications).card());
        var group = new ToggleGroup();
        var toolbox = SidebarItem.page("百宝箱", "toolbox", 17, group, () -> {});
        toolbox.toggle().setSelected(true);
        var navigation = sidebar(label("奇妙小工具", "settings-group-title"), toolbox);
        navigation.getStyleClass().add("pcl-sidebar");
        view = section(navigation, cards);
    }

    Parent view() { return view; }

    private void optimizeMemory(Button button) {
        button.setDisable(true);
        button.setText("优化中……");
        var task = new Task<WindowsMemory.Result>() {
            @Override protected WindowsMemory.Result call() throws Exception {
                return WindowsMemory.optimize();
            }
        };
        task.setOnSucceeded(event -> {
            button.setDisable(false);
            button.setText("内存优化");
            var result = task.getValue();
            if (result.processes() == 0) {
                notifications.post("没有可优化的后台进程。", Notifications.Kind.INFO);
            } else {
                notifications.post(String.format(Locale.ROOT, "已优化 %d 个后台进程，工作集减少约 %.0f MB。",
                        result.processes(), result.reducedBytes() / 1048576.0), Notifications.Kind.SUCCESS);
            }
        });
        task.setOnFailed(event -> {
            button.setDisable(false);
            button.setText("内存优化");
            notifications.post(VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private Parent downloadCard() {
        folder.setText(options.toolDownloadFolder.get().isBlank()
                ? options.directory.resolve("MyDownload").toString() : options.toolDownloadFolder.get());
        folder.textProperty().addListener((observable, old, value) -> {
            options.toolDownloadFolder.set(value);
            updateButtons();
        });
        url.textProperty().addListener((observable, old, value) -> {
            var source = downloadUri();
            if (name.getText().isBlank() || name.getText().equals(suggestedName)) {
                String path = source == null ? "" : source.getPath();
                String suggested = path.substring(path.lastIndexOf('/') + 1);
                suggestedName = validFileName(suggested) ? suggested : "";
                name.setText(suggestedName);
            }
            updateButtons();
        });
        name.textProperty().addListener(ignored -> updateButtons());

        var fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(10);
        fields.addRow(0, label("下载地址", "card-text"), url);
        fields.addRow(1, label("保存到", "card-text"), folder, button("选择", this::chooseFolder));
        fields.addRow(2, label("文件名", "card-text"), name);
        GridPane.setColumnSpan(url, 2);
        GridPane.setColumnSpan(name, 2);
        for (var input : new TextField[]{url, folder, name}) {
            GridPane.setHgrow(input, Priority.ALWAYS);
        }
        start.getStyleClass().add("accent-button");
        start.setPrefWidth(140);
        open.setPrefWidth(140);
        var actions = new HBox(15, start, open);
        actions.setAlignment(Pos.CENTER);
        updateButtons();
        return PclCard.fixed("下载自定义文件",
                label("输入文件地址、保存位置和文件名即可下载。部分网站可能拒绝直接下载。", "card-text"),
                fields, actions);
    }

    private void updateButtons() {
        var destination = folderPath();
        start.setDisable(downloading || downloadUri() == null || destination == null || !validFileName(name.getText().strip()));
        open.setDisable(destination == null || !Files.isDirectory(destination));
    }

    private void chooseFolder() {
        var chooser = new DirectoryChooser();
        chooser.setTitle("选择文件保存位置");
        var current = folderPath();
        if (current != null && Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        var selected = chooser.showDialog(owner.getScene().getWindow());
        if (selected != null) {
            folder.setText(selected.getAbsolutePath());
        }
    }

    private void openFolder() {
        var directory = folderPath();
        if (directory != null && Files.isDirectory(directory)) {
            links.showDocument(directory.toUri().toString());
        }
    }

    private URI downloadUri() {
        try {
            var source = URI.create(url.getText().strip());
            var scheme = source.getScheme();
            return source.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    ? source : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Path folderPath() {
        try {
            var path = Path.of(folder.getText().strip());
            return path.isAbsolute() ? path.normalize() : null;
        } catch (java.nio.file.InvalidPathException ignored) {
            return null;
        }
    }

    private static boolean validFileName(String value) {
        return !value.isBlank() && !value.endsWith(".")
                && !value.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")
                && value.codePoints().noneMatch(code -> Character.isISOControl(code) || "<>:\"/\\|?*".indexOf(code) >= 0);
    }

    private void download() {
        var source = downloadUri();
        var directory = folderPath();
        var filename = name.getText().strip();
        if (downloading || source == null || directory == null || !validFileName(filename)) {
            return;
        }
        var target = directory.resolve(filename);
        if (Files.exists(target)) {
            notifications.post("目标文件已存在，请使用其他文件名。", Notifications.Kind.ERROR);
            return;
        }
        downloading = true;
        updateButtons();
        var task = new Task<Void>() {
            @Override protected Void call() throws Exception {
                downloadFile(source, target);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            downloading = false;
            updateButtons();
            notifications.post("自定义下载文件：" + filename + " 完成！", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            downloading = false;
            updateButtons();
            notifications.post("下载失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        tasks.start("自定义下载 " + filename, filename, task, true, true);
    }

    private void downloadFile(URI source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".pcl-nt-", ".part");
        try {
            var request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(10)).build();
            var transfer = client.sendAsync(request, io.pclnt.minecraft.TaskIo.download(temporary, target));
            HttpResponse<Path> response;
            try {
                response = transfer.get(10, TimeUnit.MINUTES);
            } finally {
                transfer.cancel(true);
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            Files.move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void dailyLuck() {
        var day = LocalDate.now();
        int hash = 5381;
        for (char character : (day.format(DateTimeFormatter.BASIC_ISO_DATE) + options.directory).toCharArray()) {
            hash = hash * 33 + character;
        }
        int score = new Random(hash & Integer.MAX_VALUE).nextInt(101);
        String rating = switch (score) {
            case 100 -> "100！100！";
            default -> score >= 95 ? "差一点就到100了呢..." : score >= 90 ? "好评如潮！"
                    : score >= 60 ? "还行啦，还行啦" : score >= 40 ? "勉强还行吧..."
                    : score >= 30 ? "呜..." : score >= 10 ? "不会吧！" : "（是百分制哦）";
        };
        PclDialogs.message(owner, "今日人品 - " + day,
                "你今天的人品值是：" + score + (score >= 60 ? "！" : "... ") + rating, score <= 30);
    }

    private void launchCount() {
        var count = String.format(Locale.getDefault(), "%,d", options.launchCount.get());
        PclDialogs.message(owner, "查看启动计数", "PCL-NT 已经为你启动了 " + count + " 次游戏了。", false);
    }

    private static TextField input(String title) {
        var field = new TextField();
        field.getStyleClass().add("account-input");
        field.setAccessibleText(title);
        return field;
    }
}

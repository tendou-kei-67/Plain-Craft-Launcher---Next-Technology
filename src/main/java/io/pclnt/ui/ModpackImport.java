package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources;
import io.pclnt.minecraft.ModpackInstaller;
import io.pclnt.minecraft.VanillaInstaller.Instance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

final class ModpackImport {
    private final Parent owner;
    private final ModpackInstaller installer;
    private final CommunityResources resources;
    private final Notifications notifications;
    private final BooleanSupplier busy;
    private final Consumer<Boolean> writing;
    private final Consumer<Instance> installed;
    private final TaskMonitor tasks;

    ModpackImport(Parent owner, ModpackInstaller installer, CommunityResources resources,
                       Notifications notifications, BooleanSupplier busy, Consumer<Boolean> writing,
                       Consumer<Instance> installed, TaskMonitor tasks) {
        this.owner = owner;
        this.installer = installer;
        this.resources = resources;
        this.notifications = notifications;
        this.busy = busy;
        this.writing = writing;
        this.installed = installed;
        this.tasks = tasks;
    }

    void open() {
        if (!available()) return;
        var chooser = new FileChooser();
        chooser.setTitle("选择整合包压缩文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("整合包文件", "*.mrpack", "*.zip"));
        var selected = chooser.showOpenDialog(owner.getScene().getWindow());
        if (selected != null && available()) prepare(() -> describe(selected.toPath(), null), "");
    }

    void download(CommunityResources.File file) {
        if (!available()) return;
        prepare(() -> {
            var directory = Files.createTempDirectory("pcl-nt-pack-");
            var archive = directory.resolve("download.pack");
            try {
                resources.download(file, archive);
                return describe(archive, directory);
            } catch (Exception failure) {
                clean(archive, directory);
                throw failure;
            }
        }, file.name());
    }

    private record Prepared(Path archive, Path temporary, ModpackInstaller.Pack pack) {}
    private record Options(String name, Set<String> optional) {}

    private Prepared describe(Path archive, Path temporary) throws IOException {
        return new Prepared(archive, temporary, installer.inspect(archive));
    }

    private void prepare(Callable<Prepared> read, String downloadName) {
        writing.accept(true);
        var task = new Task<Prepared>() {
            @Override protected Prepared call() throws Exception { return read.call(); }
        };
        task.setOnSucceeded(event -> {
            Prepared prepared = task.getValue();
            var options = choose(prepared.pack());
            if (options == null) {
                clean(prepared.archive(), prepared.temporary());
                writing.accept(false);
            } else {
                install(prepared, options);
            }
        });
        task.setOnFailed(event -> failed(task.getException()));
        if (downloadName.isEmpty()) Thread.startVirtualThread(task);
        else tasks.start("下载整合包 " + downloadName, downloadName, task, true, true);
    }

    private Options choose(ModpackInstaller.Pack pack) {
        var name = new TextField(pack.name());
        name.setPromptText("版本名称");
        name.getStyleClass().add("account-input");
        var body = new VBox(8, LauncherController.label("版本名称", "card-text"), name);
        var checks = new LinkedHashMap<String, CheckBox>();
        if (!pack.optionalFiles().isEmpty()) {
            var list = new VBox(6);
            for (var file : pack.optionalFiles()) {
                var choice = new CheckBox(file);
                checks.put(file, choice);
                list.getChildren().add(choice);
            }
            var scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(160);
            body.getChildren().addAll(LauncherController.label("可选文件", "card-text"), scroll);
        }
        var invalid = javafx.beans.binding.Bindings.createBooleanBinding(() -> name.getText().isBlank(), name.textProperty());
        if (PclDialogs.waitForForm(owner, "安装整合包", body, invalid, name, ButtonType.OK, ButtonType.CANCEL) != ButtonType.OK) {
            return null;
        }
        var optional = new java.util.HashSet<String>();
        checks.forEach((file, check) -> { if (check.isSelected()) optional.add(file); });
        return new Options(name.getText().strip(), Set.copyOf(optional));
    }

    private void install(Prepared prepared, Options options) {
        var task = new Task<Instance>() {
            @Override protected Instance call() throws Exception {
                try {
                    return installer.install(prepared.archive(), options.name(), options.optional(), this::updateProgress);
                } finally {
                    clean(prepared.archive(), prepared.temporary());
                }
            }
        };
        notifications.post("正在安装整合包 " + options.name() + "……", Notifications.Kind.INFO);
        task.setOnSucceeded(event -> {
            writing.accept(false);
            installed.accept(task.getValue());
            notifications.post("已安装整合包 " + options.name(), Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> failed(task.getException()));
        tasks.start("安装整合包 " + options.name(), prepared.archive().getFileName().toString(), task, false, true);
    }

    private boolean available() {
        if (!busy.getAsBoolean()) return true;
        notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
        return false;
    }

    private void failed(Throwable error) {
        writing.accept(false);
        notifications.post("整合包安装失败：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
    }

    private static void clean(Path archive, Path temporary) {
        if (temporary == null) return;
        try {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(temporary);
        } catch (IOException error) {
            System.getLogger(ModpackImport.class.getName()).log(System.Logger.Level.WARNING, "Unable to remove pack download", error);
        }
    }
}

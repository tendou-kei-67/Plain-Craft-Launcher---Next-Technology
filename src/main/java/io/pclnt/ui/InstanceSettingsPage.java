package io.pclnt.ui;

import io.pclnt.minecraft.LaunchOptions;
import io.pclnt.minecraft.ModpackExport;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.VanillaInstaller;
import io.pclnt.minecraft.VanillaInstaller.Instance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.*;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class InstanceSettingsPage {
    private final Instance instance;
    private final AppearanceOptions options;
    private final HostServices links;
    private final Notifications notifications;
    private final Parent owner;
    private final VanillaInstaller installer;
    private final AppearanceOptions.InstanceLaunchSettings launch;
    private final Consumer<Instance> repair;
    private final BooleanSupplier instanceBusy;
    private final Consumer<Kind> browse;
    private final Consumer<Boolean> resourceWrite;
    private final Callable<Boolean> supportsQuickPlay;
    private final Consumer<String> quickPlay;

    InstanceSettingsPage(Instance instance, VanillaInstaller installer, AppearanceOptions options,
                         HostServices links, Notifications notifications, Parent owner, Consumer<Instance> repair,
                         BooleanSupplier instanceBusy, Consumer<Kind> browse, Consumer<Boolean> resourceWrite,
                         Callable<Boolean> supportsQuickPlay, Consumer<String> quickPlay) {
        this.instance = instance;
        this.installer = installer;
        this.options = options;
        this.links = links;
        this.notifications = notifications;
        this.owner = owner;
        this.repair = repair;
        this.instanceBusy = instanceBusy;
        this.browse = browse;
        this.resourceWrite = resourceWrite;
        this.supportsQuickPlay = supportsQuickPlay;
        this.quickPlay = quickPlay;
        launch = options.instanceLaunchSettings(instance.id());
    }

    Parent view(Runnable back, Runnable selectInstance) {
        var details = new ViewTransition(new StackPane(), ViewTransition.Type.CONTENT);
        var group = new ToggleGroup();
        var settingsView = settings();
        var overview = SidebarItem.page("概览", "about", 16, group, () -> details.show(overview()));
        var settings = SidebarItem.page("设置", "settings", 16, group, () -> details.show(settingsView),
                "初始化本页设置", "setup-reset", 13, this::reset);
        var export = SidebarItem.page("导出", "download-modpack", 16, group, () -> details.show(exportPack()));
        var savesPage = new java.util.concurrent.atomic.AtomicReference<InstanceSavesPage>();
        var saves = SidebarItem.page("存档", "instance-folder", 16, group, () -> {
            var page = new InstanceSavesPage(instance.directory(), gameDirectory(), links, notifications,
                    instanceBusy, resourceWrite, supportsQuickPlay, quickPlay, details::show);
            savesPage.set(page);
            details.show(page.view(() -> browse.accept(Kind.WORLD)));
        }, "刷新存档", "help-refresh", 13, () -> {
            if (savesPage.get() != null) savesPage.get().reload();
        });
        var screenshots = new java.util.concurrent.atomic.AtomicReference<InstanceScreenshotsPage>();
        var gallery = SidebarItem.page("截图", "lucide/image", 17, group, () -> {
            var page = new InstanceScreenshotsPage(gameDirectory(), links, notifications);
            screenshots.set(page);
            details.show(page.view());
        }, "刷新截图", "help-refresh", 13, () -> {
            if (screenshots.get() != null) screenshots.get().reload();
        });
        var sidebar = sidebar(label("游戏本体", "download-resource-heading"), overview, settings, export,
                label("游戏资源", "download-resource-heading"),
                saves,
                gallery,
                managedResource("模组", "download-mod", Kind.MOD, details, group),
                managedResource("资源包", "download-resourcepack", Kind.RESOURCEPACK, details, group),
                managedResource("光影包", "download-shader", Kind.SHADER, details, group),
                SidebarItem.action("版本选择", "download-vanilla", 16, selectInstance),
                SidebarItem.action("返回启动", "play", 14, back));
        sidebar.getStyleClass().add("pcl-sidebar");
        overview.toggle().fire();
        return section(sidebar, details.host());
    }

    private Node exportPack() {
        var name = new TextField(instance.id());
        var version = new TextField("1.0.0");
        name.getStyleClass().add("account-input");
        version.getStyleClass().add("account-input");
        var fields = new VBox(8, SettingsPage.field("整合包名称", name), SettingsPage.field("整合包版本", version));
        var choices = new VBox(7);
        var selected = EnumSet.of(ModpackExport.Content.MODS, ModpackExport.Content.MOD_SETTINGS,
                ModpackExport.Content.RESOURCE_PACKS, ModpackExport.Content.SHADERS);
        for (var content : ModpackExport.Content.values()) {
            var check = new CheckBox(content.toString());
            check.setSelected(selected.contains(content));
            check.selectedProperty().addListener((observable, old, value) -> {
                if (value) selected.add(content);
                else selected.remove(content);
            });
            AppearanceControlMotion.install(check);
            choices.getChildren().add(check);
        }
        var export = button("导出整合包", () -> exportPack(name.getText().strip(), version.getText().strip(), selected));
        export.getStyleClass().add("accent-button");
        export.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> name.getText().isBlank() || version.getText().isBlank(), name.textProperty(), version.textProperty()));
        return content(PclCard.fixed("整合包信息", fields),
                PclCard.fixed("导出内容", choices, label("导出的 Modrinth 整合包不会包含 OptiFine。", "help-hint")),
                export);
    }

    private void exportPack(String name, String version, EnumSet<ModpackExport.Content> selected) {
        if (instanceBusy.getAsBoolean()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        var chooser = new FileChooser();
        chooser.setTitle("保存整合包");
        try {
            chooser.setInitialFileName(io.pclnt.minecraft.ResourceDestination.suggestedName(Kind.MODPACK, name + ".mrpack"));
        } catch (IOException ignored) {
            chooser.setInitialFileName("modpack.mrpack");
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modrinth 整合包", "*.mrpack"));
        var file = chooser.showSaveDialog(owner.getScene().getWindow());
        if (file == null || instanceBusy.getAsBoolean()) return;
        var included = EnumSet.copyOf(selected);
        var gameDirectory = gameDirectory();
        resourceWrite.accept(true);
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException, InterruptedException {
                new ModpackExport(installer).write(instance, gameDirectory, name, version, included, file.toPath());
                return null;
            }
        };
        notifications.post("正在导出整合包……", Notifications.Kind.INFO);
        task.setOnSucceeded(event -> {
            resourceWrite.accept(false);
            notifications.post("已导出 " + file.getName(), Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            resourceWrite.accept(false);
            notifications.post("导出失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private SidebarItem managedResource(String name, String icon, Kind kind, ViewTransition details, ToggleGroup group) {
        return SidebarItem.page(name, icon, 16, group, () -> details.show(
                new InstanceResourcesPage(gameDirectory(), kind, owner, links, notifications, instanceBusy,
                        browse, resourceWrite).view()));
    }

    private Node overview() {
        Node info;
        try {
            var metadata = installer.describe(instance);
            var block = PclArtwork.block(PclArtwork.instanceBlock(metadata), 32);
            String loaders = metadata.loaders().isEmpty() ? "" : " · " + String.join(" / ", metadata.loaders());
            info = new HBox(10, block, new VBox(3, label(instance.id(), "card-title"),
                    label("Minecraft " + metadata.gameVersion() + loaders, "help-row-description")));
        } catch (IOException | com.google.gson.JsonParseException error) {
            info = label("无法读取实例信息：" + VanillaDownloadPage.message(error), "card-text");
        }
        var description = new javafx.scene.control.TextField(options.description(instance.id()));
        description.setPromptText("默认描述");
        description.getStyleClass().add("account-input");
        description.textProperty().addListener((observable, old, text) -> options.description(instance.id(), text));
        var favorite = new javafx.scene.control.CheckBox("加入收藏夹");
        favorite.setSelected(options.favorite(instance.id()));
        favorite.selectedProperty().addListener((observable, old, value) -> options.favorite(instance.id(), value));
        AppearanceControlMotion.install(favorite);
        var shortcuts = new FlowPane(10, 8, openButton("打开版本文件夹", instance.directory()),
                openButton("打开存档文件夹", gameDirectory().resolve("saves")),
                openButton("打开模组文件夹", gameDirectory().resolve("mods")));
        return content(PclCard.fixed("实例信息", info), PclCard.fixed("个性化",
                SettingsPage.field("实例描述", description), favorite), PclCard.fixed("快捷方式", shortcuts),
                PclCard.fixed("管理", button("补全文件", () -> repair.accept(instance))));
    }

    private Node settings() {
        var java = SettingsPage.text(launch.javaPath(), "跟随全局设置");
        var browse = button("选择 Java", () -> {
            var chooser = new FileChooser();
            chooser.setTitle("选择 Java 可执行文件");
            var selected = chooser.showOpenDialog(owner.getScene().getWindow());
            if (selected != null) launch.javaPath().set(selected.getAbsolutePath());
        });
        var group = new ToggleGroup();
        var choices = new HBox(14);
        var titles = List.of("自动配置", "自定义", "跟随全局设置");
        for (int index : new int[]{2, 0, 1}) {
            var radio = new RadioButton(titles.get(index));
            radio.setToggleGroup(group);
            radio.setUserData(index);
            radio.setSelected(launch.memoryMode().get() == index);
            AppearanceControlMotion.install(radio);
            choices.getChildren().add(radio);
        }
        group.selectedToggleProperty().addListener((observable, old, selected) -> {
            if (selected != null) launch.memoryMode().set((int) selected.getUserData());
        });
        var inherited = (RadioButton) choices.getChildren().getFirst();
        // Reset keeps the displayed setting and the persisted override in sync.
        launch.memoryMode().addListener((observable, old, value) -> {
            if (value.intValue() == 2) inherited.setSelected(true);
        });
        var memory = SettingsPage.slider("最大内存（-Xmx）", launch.memory(), 49, 1,
                value -> String.format(Locale.ROOT, "%.1f GB", LaunchOptions.customGigabytes(value)));
        memory.disableProperty().bind(launch.memoryMode().isNotEqualTo(1));
        var initial = SettingsPage.initialMemorySlider(launch.initialMemory(), launch.memory());
        var customInitial = javafx.beans.binding.Bindings.createBooleanBinding(() -> SettingsPage.hasCustomInitialMemory(
                launch.jvmArguments().get().isBlank() ? options.launchJvmArgs.get() : launch.jvmArguments().get()),
                launch.jvmArguments(), options.launchJvmArgs);
        initial.disableProperty().bind(launch.memoryMode().isNotEqualTo(1).or(options.launchLockMemory).or(customInitial));
        var jvm = SettingsPage.text(launch.jvmArguments(), "跟随全局设置");
        var args = SettingsPage.text(launch.gameArguments(), "跟随全局设置");
        return content(PclCard.fixed("启动选项", SettingsPage.field("实例隔离", isolationChoice()),
                        SettingsPage.windowTitleField(launch.title(), launch.defaultTitle()),
                        SettingsPage.versionInfoField(launch.info(), "跟随全局设置"),
                        SettingsPage.field("Java", java, browse)),
                PclCard.fixed("内存分配", choices, memory, initial,
                        SettingsPage.instanceMemoryOptimization(launch.memoryOptimization())),
                PclCard.foldable("高级选项", new VBox(10, SettingsPage.field("Java 虚拟机参数", jvm),
                        SettingsPage.field("游戏参数", args),
                        SettingsPage.preLaunchField(launch.preLaunchCommand(), launch.preLaunchWait())), false));
    }

    private Path gameDirectory() {
        return installer.gameDirectory(instance, launch.isolated().get());
    }

    private ComboBox<String> isolationChoice() {
        var choice = new ComboBox<String>();
        choice.setId("instanceIsolation");
        choice.getStyleClass().add("account-input");
        choice.getItems().addAll("启用", "禁用");
        choice.getSelectionModel().select(launch.isolated().get() ? 0 : 1);
        choice.setOnAction(event -> {
            int selection = choice.getSelectionModel().getSelectedIndex();
            boolean isolated = selection == 0;
            if (selection < 0 || isolated == launch.isolated().get()) return;
            if (instanceBusy.getAsBoolean()) {
                choice.getSelectionModel().select(launch.isolated().get() ? 0 : 1);
                notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
                return;
            }
            choice.setDisable(true);
            PclDialogs.openMessage(owner, "实例隔离",
                    "更改实例隔离后，游戏会从新的目录读取存档、模组和设置。原有文件留在原目录，可手动迁移或恢复原设置。",
                    false, result -> {
                if (result == ButtonType.OK && !instanceBusy.getAsBoolean()) {
                    launch.isolated().set(isolated);
                }
                choice.getSelectionModel().select(launch.isolated().get() ? 0 : 1);
                choice.setDisable(false);
            }, ButtonType.OK, ButtonType.CANCEL);
        });
        return choice;
    }

    private Node openButton(String name, Path path) {
        return button(name, () -> {
            try {
                if (!Files.isDirectory(instance.directory())) throw new IOException("实例目录已移动或删除");
                Files.createDirectories(path);
                links.showDocument(path.toUri().toASCIIString());
            } catch (IOException error) {
                notifications.post("无法打开文件夹：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
            }
        });
    }

    private void reset() {
        if (PclDialogs.confirm(owner, "初始化确认", "是否要初始化该实例的独立设置？")) {
            launch.reset();
        }
    }
}

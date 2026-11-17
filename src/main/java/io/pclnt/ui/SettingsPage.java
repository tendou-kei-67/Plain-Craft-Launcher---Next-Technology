package io.pclnt.ui;

import javafx.beans.binding.Bindings;

import io.pclnt.minecraft.LaunchOptions;
import io.pclnt.minecraft.VanillaInstaller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.IntFunction;
import javafx.application.HostServices;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.AppearanceOptions.*;
import static io.pclnt.ui.LauncherController.*;

final class SettingsPage {
    private record Choice(int id, String name) {
        @Override public String toString() { return name; }
    }

    private static final List<Choice> FIT = List.of(
            new Choice(0, "自动"), new Choice(4, "平铺"), new Choice(1, "居中"), new Choice(3, "拉伸"),
            new Choice(5, "左上 (保持长宽比)"), new Choice(9, "左中 (保持长宽比)"), new Choice(7, "左下 (保持长宽比)"),
            new Choice(11, "中上 (保持长宽比)"), new Choice(2, "居中 (保持长宽比)"), new Choice(12, "中下 (保持长宽比)"),
            new Choice(6, "右上 (保持长宽比)"), new Choice(10, "右中 (保持长宽比)"), new Choice(8, "右下 (保持长宽比)"));
    private static final List<Choice> COLOR_MODES = List.of(
            new Choice(0, "浅色"), new Choice(1, "深色"), new Choice(2, "跟随系统"), new Choice(3, "跟随时间"));
    private static final List<Choice> THEME_COLORS = List.of(
            new Choice(0, "天空蓝"), new Choice(1, "龙猫蓝"), new Choice(2, "死机蓝"));

    private final AppearanceOptions settings;
    private final WindowAppearance appearance;
    private final CustomHome home;
    private final HostServices links;
    private final Parent owner;
    private final Runnable openHome;
    private final Runnable openVersionSettings;
    private final java.util.function.Supplier<VanillaInstaller.Instance> instance;
    private final ViewTransition details = new ViewTransition(new StackPane(), ViewTransition.Type.CONTENT);

    SettingsPage(AppearanceOptions settings, WindowAppearance appearance, CustomHome home,
                 HostServices links, Parent owner, Runnable openHome, Runnable openVersionSettings,
                 java.util.function.Supplier<VanillaInstaller.Instance> instance) {
        this.settings = settings;
        this.appearance = appearance;
        this.home = home;
        this.links = links;
        this.owner = owner;
        this.openHome = openHome;
        this.openVersionSettings = openVersionSettings;
        this.instance = instance;
    }

    Parent view() {
        var group = new ToggleGroup();
        var launchPage = launchSettings();
        var launch = SidebarItem.page("启动", "setup-launch", 16, group, () -> details.show(launchPage));
        var link = SidebarItem.page("联机", "setup-link", 15.4, group, () -> details.show(new Region()));
        var personal = personalization();
        var ui = SidebarItem.page("个性化", "setup-ui", 18, group, () -> details.show(personal),
                "初始化本页设置", "setup-reset", 13.5, () -> {
                    if (confirm("初始化本页设置", "是否将个性化设置恢复为默认值？")) {
                        settings.reset();
                        notify("已初始化个性化设置");
                    }
                });
        var rates = List.of(new Choice(0, "byte/s（字节）"), new Choice(1, "bit/s（比特）"));
        var taskSettings = content(PclCard.fixed("任务管理",
                taskSetting("下载速度单位", combo(settings.downloadSpeedUnit, rates)),
                taskSetting("磁盘使用量单位", combo(settings.diskSpeedUnit, rates)),
                taskSetting("任务管理悬浮球", combo(settings.taskFloatMode, List.of(
                        new Choice(0, "有任务时显示"), new Choice(1, "始终显示"), new Choice(2, "隐藏"))))));
        var system = SidebarItem.page("其他", "setup-system", 16, group, () -> details.show(taskSettings));
        var themePage = content(materialCard());
        var theme = SidebarItem.page("主题", "settings", 16, group, () -> details.show(themePage));
        var sidebar = sidebar(launch, link, ui, system, theme);
        sidebar.getStyleClass().addAll("pcl-sidebar", "settings-sidebar");
        bindVisible(launch, settings.visible(SETUP_LAUNCH));
        bindVisible(link, settings.visible(SETUP_LINK));
        bindVisible(ui, settings.visible(SETUP_UI));
        bindVisible(system, settings.visible(SETUP_OTHER));
        bindVisible(theme, settings.visible(SETUP_UI));
        for (var candidate : List.of(ui, launch, link, system)) {
            if (candidate.isVisible()) {
                candidate.toggle().fire();
                break;
            }
        }
        return section(sidebar, details.host());
    }

    private static HBox taskSetting(String title, Node input) {
        var caption = label(title, "card-text");
        caption.setMinWidth(136);
        input.setAccessibleText(title);
        var row = new HBox(10, caption, input);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private Node launchSettings() {
        var isolation = combo(settings.defaultIsolation, List.of(new Choice(0, "关闭"),
                new Choice(1, "隔离可安装模组的实例"), new Choice(2, "隔离非正式版"),
                new Choice(3, "隔离可安装模组的实例与非正式版"), new Choice(4, "隔离所有实例")));
        isolation.setId("defaultInstanceIsolation");
        isolation.setTooltip(new javafx.scene.control.Tooltip("默认策略只会对今后新安装的实例生效。已有实例可在版本设置中调整。"));
        isolation.setOnAction(event -> {
            var choice = isolation.getValue();
            if (choice != null && choice.id() != settings.defaultIsolation.get()) {
                settings.defaultIsolation.set(choice.id());
                notify("默认策略只会对今后新安装的实例生效。已有实例可在版本设置中调整。");
            }
        });
        var window = combo(settings.launchWindow, List.of(new Choice(0, "全屏"), new Choice(1, "默认"),
                new Choice(2, "与启动器尺寸一致"), new Choice(3, "自定义尺寸"), new Choice(4, "最大化")));
        var width = dimension(settings.launchWidth, "宽度");
        var height = dimension(settings.launchHeight, "高度");
        var dimensions = new HBox(4, width, new Label("×"), height);
        dimensions.setAlignment(Pos.CENTER_LEFT);
        bindVisible(dimensions, settings.launchWindow.isEqualTo(3));
        var visibility = combo(settings.launchVisibility, List.of(new Choice(0, "游戏启动后立即关闭"),
                new Choice(2, "游戏启动后隐藏，游戏退出后自动关闭"),
                new Choice(3, "游戏启动后隐藏，游戏退出后重新打开"),
                new Choice(4, "游戏启动后最小化"), new Choice(5, "游戏启动后仍保持不变")));
        var priority = combo(settings.launchPriority, List.of(
                new Choice(4, "实时（使游戏以最高优先级运行，谨慎使用，可能严重影响系统运行）"),
                new Choice(3, "极高（谨慎使用，可能严重影响其他程序运行）"),
                new Choice(0, "高（优先保证游戏运行，游戏性能更佳，但可能造成其他程序卡顿）"),
                new Choice(1, "中（平衡）"),
                new Choice(2, "低（优先保证其他程序运行，但可能让游戏卡顿，适合挂机时使用）")));
        priority.setAccessibleText("进程优先级");
        var authentication = combo(settings.microsoftAuthType, List.of(
                new Choice(0, "Web 账户管理器（暂时强制设备代码流）"), new Choice(1, "设备代码流")));
        authentication.setAccessibleText("正版验证方式");
        authentication.setDisable(true);
        var authRow = field("正版验证方式", authentication);
        javafx.scene.control.Tooltip.install(authRow, new javafx.scene.control.Tooltip("与 PCL-CE 一致，目前固定使用设备代码流验证。"));
        var ip = combo(settings.launchIpStack, List.of(new Choice(0, "IPv4 优先"),
                new Choice(1, "Java 默认"), new Choice(2, "IPv6 优先")));
        ip.setAccessibleText("IP 协议偏好");
        ip.setTooltip(new javafx.scene.control.Tooltip("通过 Java 虚拟机参数选择 Minecraft 的 IP 协议偏好。"));
        var arguments = PclCard.fixed("启动选项", field("默认实例隔离", isolation),
                windowTitleField(settings.launchTitle, null),
                versionInfoField(settings.launchInfo, ""),
                field("启动器可见性", visibility), field("进程优先级", priority),
                field("窗口大小", window, dimensions), authRow, field("IP 协议偏好", ip));

        var allocation = radio(settings.launchMemoryMode, List.of(new Choice(0, "自动配置"), new Choice(1, "自定义")));
        var custom = slider("最大内存（-Xmx）", settings.launchMemory, 49, 1, value -> gb(LaunchOptions.customGigabytes(value)));
        custom.disableProperty().bind(settings.launchMemoryMode.isNotEqualTo(1));
        var initial = initialMemorySlider(settings.launchInitialMemory, settings.launchMemory);
        initial.disableProperty().bind(settings.launchMemoryMode.isNotEqualTo(1).or(settings.launchLockMemory)
                .or(Bindings.createBooleanBinding(() -> hasCustomInitialMemory(settings.launchJvmArgs.get()),
                        settings.launchJvmArgs)));
        var memoryBar = new HBox(0);
        var used = new Region();
        used.getStyleClass().add("launch-ram-used");
        var game = new Region();
        game.getStyleClass().add("launch-ram-game");
        var free = new Region();
        free.getStyleClass().add("launch-ram-free");
        HBox.setHgrow(free, Priority.ALWAYS);
        memoryBar.getChildren().addAll(used, game, free);
        memoryBar.setMinHeight(12);
        var memoryLabels = new Label();
        memoryLabels.getStyleClass().add("card-text");
        Runnable update = () -> {
            double total = Math.max(1, LaunchOptions.totalMemory());
            double inUse = Math.clamp(total - LaunchOptions.availableMemory(), 0, total);
            var current = instance.get();
            var directory = current == null ? null : current.directory();
            if (current != null && !settings.isolated(current.id())) {
                directory = directory.getParent().getParent();
            }
            int heap = new LaunchOptions(settings.launchMemoryMode.get(), settings.launchMemory.get(), 4, "", "", "",
                    1, 854, 480).heapMegabytes(current, directory);
            used.setPrefWidth(memoryBar.getWidth() * inUse / total);
            game.setPrefWidth(memoryBar.getWidth() * Math.min(1 - inUse / total, heap * 1048576.0 / total));
            memoryLabels.setText("已使用内存  " + gb(inUse / 1073741824) + " / " + gb(total / 1073741824)
                    + "      游戏分配  " + gb(heap / 1024.0));
        };
        memoryBar.widthProperty().addListener(ignored -> update.run());
        var timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                event -> update.run()));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        memoryBar.sceneProperty().addListener((observable, old, scene) -> {
            if (scene == null) timer.stop();
            else {
                update.run();
                timer.play();
            }
        });
        var optimizeMemory = check("启动游戏前进行内存优化", settings.optimizeMemoryBeforeLaunch);
        optimizeMemory.setId("optimizeMemoryBeforeLaunch");
        optimizeMemory.setDisable(!WindowsMemory.supported());
        optimizeMemory.setTooltip(memoryOptimizationTooltip());
        var memory = PclCard.fixed("内存分配", allocation, custom, initial, optimizeMemory, memoryBar, memoryLabels);

        var collector = combo(settings.launchCollector, List.of(new Choice(0, "尽量使用 ZGC"),
                new Choice(1, "尽量使用分代 ZGC"), new Choice(2, "标准 G1GC"), new Choice(4, "调优 G1GC"),
                new Choice(3, "不指定（可自定义）")));
        var advanced = PclCard.foldable("高级选项", new VBox(10,
                field("Java 虚拟机参数", text(settings.launchJvmArgs, "默认")),
                field("游戏参数", text(settings.launchGameArgs, "")),
                JavaPicker.field("Java 列表", "自动选择", settings.launchJava, settings.gameFolders::active),
                preLaunchField(settings.preLaunchCommand, settings.preLaunchWait),
                field("内存管理", collector), check("锁定内存分配（-Xms = -Xmx）", settings.launchLockMemory),
                label("版本独立设置中还有更多高级选项可供调整。", "help-hint")), false);
        var version = button("版本独立设置", openVersionSettings);
        version.setDisable(instance.get() == null);
        return content(arguments, memory, advanced, version);
    }

    private static String gb(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f GB", value);
    }

    static HBox initialMemorySlider(IntegerProperty initial, IntegerProperty maximum) {
        var row = slider("初始内存（-Xms）", initial, 49, 1,
                value -> initialMemoryLabel(value, maximum.get()));
        var readout = (Label) row.getChildren().getLast();
        readout.textProperty().bind(Bindings.createStringBinding(
                () -> initialMemoryLabel(initial.get(), maximum.get()), initial, maximum));
        return row;
    }

    private static String initialMemoryLabel(int value, int maximum) {
        return value == 0 ? "默认" : gb(LaunchOptions.customGigabytes(Math.min(value, maximum)));
    }

    static boolean hasCustomInitialMemory(String arguments) {
        return arguments != null && arguments.matches("(?s).*(?:^|[\\s\"'])-Xms[0-9].*");
    }

    static TextField text(javafx.beans.property.StringProperty value, String hint) {
        var field = new TextField();
        field.setPromptText(hint);
        field.getStyleClass().add("account-input");
        field.textProperty().bindBidirectional(value);
        return field;
    }

    private static TextField dimension(IntegerProperty value, String caption) {
        var field = new TextField(Integer.toString(value.get()));
        field.getStyleClass().add("account-input");
        field.setAccessibleText(caption);
        field.setPrefWidth(65);
        field.setTextFormatter(new javafx.scene.control.TextFormatter<>(change ->
                change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
        field.textProperty().addListener((observable, old, next) -> {
            if (!next.isEmpty() && Integer.parseInt(next) > 0) value.set(Integer.parseInt(next));
        });
        field.focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) field.setText(Integer.toString(value.get()));
        });
        return field;
    }
    private Node personalization() {
        var basic = PclCard.fixed("基础",
                slider("不透明度", settings.windowOpacity, 600, 10, value -> Math.round(40 + value * .1) + "%"),
                themeRow(),
                check("打开启动器时显示 PCL 图标", settings.startupLogo),
                check("锁定启动器大小", settings.lockWindowSize),
                check("在游戏启动时显示“你知道吗”", settings.showLaunchingHint));
        var background = background();
        var title = title();
        var homepage = homepage();
        var hidden = hidden();
        var scroll = content(basic, background, title, homepage, hidden);
        scroll.getStyleClass().addAll("about-scroll", "personalization");
        return scroll;
    }

    private PclCard materialCard() {
        var enabled = check("高级材质", settings.materialEnabled);
        enabled.setId("materialEnabled");
        enabled.setTooltip(new javafx.scene.control.Tooltip("启用后，卡片等半透明区域会模糊背后的画面。"));
        var radius = slider("模糊半径", settings.materialRadius, 40, 10, value -> value + " 像素");
        var method = combo(settings.materialMethod, List.of(new Choice(0, "高斯模糊"), new Choice(1, "方框模糊")));
        method.setId("materialMethod");
        method.setTooltip(new javafx.scene.control.Tooltip("高斯模糊使用加权采样，方框模糊使用均值采样。"));
        var sampling = slider("采样率", settings.materialSampling, 100, 5, value -> value + "%");
        HBox.setHgrow(sampling, Priority.ALWAYS);
        var methodAndSampling = field("模糊方法", method, sampling);
        var controls = new VBox(10, radius, methodAndSampling);
        bindVisible(controls, settings.materialEnabled);
        return PclCard.fixed("高级材质", enabled, controls);
    }

    private HBox themeRow() {
        var mode = combo(settings.colorMode, COLOR_MODES);
        mode.setId("themeColorMode");
        var light = combo(settings.lightColor, THEME_COLORS);
        light.setId("themeLightColor");
        var dark = combo(settings.darkColor, THEME_COLORS);
        dark.setId("themeDarkColor");
        var row = field("主题", mode);
        for (var group : List.of(field("浅色配色", light), field("深色配色", dark))) {
            var caption = (Label) group.getChildren().getFirst();
            caption.setMinWidth(64);
            caption.setAlignment(Pos.CENTER);
            HBox.setHgrow(group, Priority.ALWAYS);
            row.getChildren().add(group);
        }
        return row;
    }

    private PclCard background() {
        var sizing = field("内容自适应方式", combo(settings.imageFit, FIT));
        var opacity = slider("不透明度", settings.imageOpacity, 1000, 10, value -> Math.round(value * .1) + "%");
        var blur = slider("背景模糊", settings.imageBlur, 40, 1, String::valueOf);
        var clear = button("清空背景内容", () -> {
            if (confirm("警告", "即将删除背景内容文件夹中的所有文件。\n此操作不可撤销，是否确定？")) {
                attempt(() -> {
                    appearance.clearPictures();
                    notify("背景内容已清空！");
                });
            }
        });
        var hasPicture = appearance.pictureCount().greaterThan(0);
        for (var item : List.of(sizing, opacity, blur, clear)) {
            bindVisible(item, hasPicture);
        }
        var pause = check("游戏启动后暂停视频背景，游戏退出后重新播放", settings.autoPauseVideo);
        pause.setTooltip(new javafx.scene.control.Tooltip("若启用此项设置，游戏时将无法刷新背景"));
        bindVisible(pause, hasPicture);
        var refresh = button("刷新背景内容", () -> attempt(() -> {
            String name = appearance.refreshPictures();
            notify(name.isBlank() ? "未检测到可用背景内容！" : "背景内容已刷新：" + name);
        }));
        refresh.setTooltip(new javafx.scene.control.Tooltip("随机加载文件夹内的一张背景图片或视频\n视频推荐使用 H.264 编码"));
        refresh.disableProperty().bind(appearance.refreshBlocked());
        var open = button("打开文件夹", () -> openFolder(settings.pictures()));
        open.setTooltip(new javafx.scene.control.Tooltip("将背景图片和视频放到该文件夹。启动器每次打开时会随机加载一个。"));
        for (var item : List.of(open, refresh, clear)) {
            item.setMinSize(140, 35);
        }
        var card = PclCard.fixed("背景图片/视频", sizing, opacity, blur, pause,
                check("叠加彩色背景", settings.colorfulBackground),
                new FlowPane(20, 6, open, refresh, clear));
        card.titleLabel().textProperty().bind(appearance.pictureCount().map(count ->
                count.intValue() == 0 ? "背景图片/视频" : "背景图片/视频（" + count + " 张）"));
        return card;
    }

    private PclCard title() {
        var type = new ToggleGroup();
        var choices = new FlowPane(12, 6);
        String[] labels = {"无", "默认", "文本", "图片"};
        for (int i = 0; i < labels.length; i++) {
            final int id = i;
            var radio = new RadioButton(labels[i]);
            AppearanceControlMotion.install(radio);
            radio.setToggleGroup(type);
            radio.setUserData(id);
            radio.setOnAction(event -> {
                if (id == 3 && WindowAppearance.loadImage(settings.logo()) == null && !chooseLogo()) {
                    type.selectToggle(type.getToggles().get(settings.logoType.get()));
                    return;
                }
                settings.logoType.set(id);
            });
            choices.getChildren().add(radio);
        }
        type.selectToggle(type.getToggles().get(settings.logoType.get()));
        settings.logoType.addListener((observable, old, id) -> type.selectToggle(type.getToggles().get(id.intValue())));
        var left = check("标题栏居左", settings.logoLeft);
        bindVisible(left, settings.logoType.isEqualTo(0));
        var name = new TextField();
        name.getStyleClass().add("account-input");
        name.setAccessibleText("标题栏文本");
        name.textProperty().bindBidirectional(settings.logoText);
        var text = field("标题栏文本", name);
        bindVisible(text, settings.logoType.isEqualTo(2));
        var images = flow(button("更改图片", this::chooseLogo),
                button("清空图片", () -> attempt(() -> {
                    Files.deleteIfExists(settings.logo());
                    settings.logoType.set(1);
                    notify("标题栏图片已清空！");
                })));
        bindVisible(images, settings.logoType.isEqualTo(3));
        return PclCard.fixed("标题栏", choices, left, text, images);
    }

    private boolean chooseLogo() {
        var chooser = new FileChooser();
        chooser.setTitle("选择图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("常用图片文件",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        var selected = chooser.showOpenDialog(owner.getScene().getWindow());
        if (selected == null) {
            return false;
        }
        if (WindowAppearance.loadImage(selected.toPath()) == null) {
            notify("无法读取这张图片，请尝试 PNG 或 JPEG 格式。");
            return false;
        }
        try {
            Files.createDirectories(settings.directory);
            Files.copy(selected.toPath(), settings.logo(), StandardCopyOption.REPLACE_EXISTING);
            appearance.refreshLogo();
            return true;
        } catch (IOException exception) {
            notify("设置标题栏图片失败：" + exception.getMessage());
            return false;
        }
    }

    private PclCard homepage() {
        var choices = radio(settings.homeType, List.of(new Choice(0, "空白"), new Choice(3, "预设"),
                new Choice(1, "读取本地文件"), new Choice(2, "联网更新")));
        var refresh = button("刷新主页", () -> {
            home.refresh();
            openHome.run();
        });
        var local = new VBox(8, label("从启动器数据目录下的 Custom.xaml 读取主页内容。", "card-text"),
                flow(button("生成教学文件", () -> attempt(() -> {
                    if (!Files.exists(settings.customHome()) || confirm("覆盖确认", "继续生成会覆盖现有的 Custom.xaml。")) {
                        home.writeTutorial();
                        openFolder(settings.directory);
                    }
                })), button("查看教程", () -> notify("生成教学文件后，用文本编辑器打开 Custom.xaml。\n"
                        + "保存修改后，点击「刷新主页」查看效果。")), button("打开主页文件夹", () -> openFolder(settings.directory))));
        var address = new TextField();
        address.getStyleClass().add("account-input");
        address.setAccessibleText("下载地址");
        address.setPromptText("https://");
        address.textProperty().bindBidirectional(settings.homeUrl);
        var online = new VBox(8, label("从指定网址联网获取主页内容。", "card-text"), field("下载地址", address));
        var presets = field("主页预设", combo(settings.homePreset,
                CustomHome.PRESETS.stream().map(preset -> new Choice(preset.id(), preset.title())).toList()));
        var caution = label("请谨慎使用陌生人提供的主页。", "help-hint");
        bindVisible(local, settings.homeType.isEqualTo(1));
        bindVisible(online, settings.homeType.isEqualTo(2));
        bindVisible(presets, settings.homeType.isEqualTo(3));
        bindVisible(caution, settings.homeType.isEqualTo(1).or(settings.homeType.isEqualTo(2)));
        bindVisible(refresh, settings.homeType.isNotEqualTo(0));
        return PclCard.fixed("主页", choices, caution, local, online, presets, refresh);
    }

    private PclCard hidden() {
        var status = new Label();
        status.setWrapText(true);
        status.textProperty().bind(settings.forceShow.map(show -> show
                ? "功能隐藏已暂时关闭，按 F12 重新启用。"
                : "你可以隐藏不需要的页面或关闭特定功能。在任意界面按 F12 可以暂时显示被隐藏的功能。"));
        var card = PclCard.fixed("功能隐藏", status,
                hiddenGroup("主页面", List.of("下载", "联机", "设置", "更多"), List.of(DOWNLOAD, LINK, SETTINGS, MORE)),
                hiddenGroup("设置 子页面", List.of("启动", "联机", "个性化", "其他"), SETTINGS_ROWS),
                hiddenGroup("更多 子页面", List.of("帮助", "关于与鸣谢", "百宝箱", "反馈", "新功能投票"), MORE_ROWS),
                hiddenGroup("特定功能", List.of("邮箱隐藏", "版本管理", "Mod 更新", "功能隐藏", "版本修改"),
                        List.of(EMAIL, VERSION, MOD_UPDATE, HIDE, MODIFY)));
        bindVisible(card, settings.visible(HIDE));
        return card;
    }

    private Node hiddenGroup(String title, List<String> names, List<String> keys) {
        var checks = new FlowPane(16, 6);
        for (int i = 0; i < names.size(); i++) {
            var key = keys.get(i);
            var option = check(names.get(i), settings.hiddenFlag(key));
            if (List.of(HIDE, SETTINGS, SETUP_UI).contains(key)) {
                option.setOnAction(event -> {
                    if (option.isSelected()) {
                        notify("按 F12 可以暂时关闭功能隐藏设置。");
                    }
                });
            }
            checks.getChildren().add(option);
        }
        return new VBox(6, label(title, "settings-group-title"), checks);
    }

    private static FlowPane radio(IntegerProperty value, List<Choice> choices) {
        var group = new ToggleGroup();
        var flow = new FlowPane(12, 6);
        for (var choice : choices) {
            var option = new RadioButton(choice.name());
            AppearanceControlMotion.install(option);
            option.setToggleGroup(group);
            option.setSelected(value.get() == choice.id());
            option.setOnAction(event -> value.set(choice.id()));
            value.addListener((observable, old, next) -> {
                if (next.intValue() == choice.id()) {
                    option.setSelected(true);
                }
            });
            flow.getChildren().add(option);
        }
        return flow;
    }

    private static ComboBox<Choice> combo(IntegerProperty value, List<Choice> choices) {
        var box = new ComboBox<Choice>();
        box.getItems().setAll(choices);
        box.getStyleClass().add("account-input");
        Runnable select = () -> box.getSelectionModel().select(choices.stream()
                .filter(choice -> choice.id() == value.get()).findFirst().orElse(choices.getFirst()));
        select.run();
        value.addListener(observable -> select.run());
        box.setOnAction(event -> {
            if (box.getValue() != null) {
                value.set(box.getValue().id());
            }
        });
        return box;
    }

    static Node instanceMemoryOptimization(IntegerProperty value) {
        var modes = combo(value, List.of(new Choice(0, "跟随全局设置"), new Choice(1, "开启"), new Choice(2, "关闭")));
        modes.setId("instanceMemoryOptimization");
        modes.setDisable(!WindowsMemory.supported());
        modes.setTooltip(memoryOptimizationTooltip());
        return field("启动游戏前进行内存优化", modes);
    }

    private static javafx.scene.control.Tooltip memoryOptimizationTooltip() {
        return new javafx.scene.control.Tooltip(WindowsMemory.supported()
                ? "启动时尝试减少后台进程的工作集，可能增加启动耗时。"
                : "启动前内存优化目前仅支持 Windows。");
    }

    private static CheckBox check(String text, BooleanProperty value) {
        var box = new CheckBox(text);
        box.selectedProperty().bindBidirectional(value);
        AppearanceControlMotion.install(box);
        return box;
    }

    static VBox preLaunchField(javafx.beans.property.StringProperty command, BooleanProperty wait) {
        var input = text(command, "");
        input.setTooltip(new javafx.scene.control.Tooltip(
                "在 .minecraft 目录执行，先运行全局命令，再运行实例命令。Windows 使用 cmd，macOS 和 Linux 使用 sh。\n"
                        + "路径标记：{path}、{minecraft}、{verpath}、{verindie}、{java}。路径请使用双引号。\n"
                        + "其他标记：{user}、{login}、{uuid}、{name}、{version}、{date}、{time}。"));
        var waitControl = check("等待命令执行完成后再继续启动", wait);
        bindVisible(waitControl, command.isNotEmpty());
        var waitRow = new HBox(waitControl);
        waitRow.setPadding(new javafx.geometry.Insets(0, 0, 0, 88));
        bindVisible(waitRow, waitControl.visibleProperty());
        return new VBox(4, field("启动前执行命令", input), waitRow);
    }

    static HBox windowTitleField(javafx.beans.property.StringProperty title, BooleanProperty defaultTitle) {
        var input = text(title, defaultTitle == null ? "默认" : "跟随全局设置");
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        input.setTooltip(new javafx.scene.control.Tooltip(windows
                ? "支持 {user}、{login}、{name}、{version}、{date} 和 {time}。"
                : "游戏窗口标题目前仅支持 Windows。"));
        if (defaultTitle == null) {
            input.setDisable(!windows);
            return field("游戏窗口标题", input);
        }
        var keep = check("默认窗口标题", defaultTitle);
        input.disableProperty().bind(defaultTitle.or(new javafx.beans.property.SimpleBooleanProperty(!windows)));
        keep.setDisable(!windows);
        return field("游戏窗口标题", input, keep);
    }

    static HBox versionInfoField(javafx.beans.property.StringProperty info, String prompt) {
        var input = text(info, prompt);
        input.setTooltip(new javafx.scene.control.Tooltip("显示在游戏主界面与 F3 调试界面。"));
        return field("自定义信息", input);
    }

    static HBox slider(String name, IntegerProperty value, int max, int step, IntFunction<String> format) {
        var slider = AppearanceControlMotion.slider(max, value.get());
        slider.setAccessibleText(name);
        slider.setBlockIncrement(step);
        slider.valueProperty().addListener((obs, old, next) -> value.set((int) Math.round(next.doubleValue())));
        value.addListener((obs, old, next) -> slider.setValue(next.doubleValue()));
        var readout = new Label();
        readout.setMinWidth(36);
        readout.textProperty().bind(value.map(number -> format.apply(number.intValue())));
        return field(name, slider, readout);
    }

    static HBox field(String name, Region control, Node... suffix) {
        var title = label(name, "settings-field-label");
        title.setMinWidth(80);
        title.setLabelFor(control);
        HBox.setHgrow(control, Priority.ALWAYS);
        control.setMaxWidth(Double.MAX_VALUE);
        var row = new HBox(8, title, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(suffix);
        return row;
    }

    private static FlowPane flow(Node... items) {
        return new FlowPane(8, 6, items);
    }

    private static Button button(String title, Runnable run) {
        var button = new Button(title);
        button.getStyleClass().add("outline-button");
        UiMotion.installPress(button);
        button.setOnAction(event -> run.run());
        return button;
    }

    static void bindVisible(Node item, ObservableBooleanValue show) {
        item.visibleProperty().bind(show);
        item.managedProperty().bind(item.visibleProperty());
    }

    private void openFolder(Path folder) {
        attempt(() -> {
            Files.createDirectories(folder);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } else {
                links.showDocument(folder.toUri().toString());
            }
        });
    }

    private void attempt(IoAction action) {
        try {
            action.run();
        } catch (IOException exception) {
            notify("操作失败：" + exception.getMessage());
        }
    }

    private boolean confirm(String title, String message) {
        return PclDialogs.confirm(owner, title, message);
    }

    private void notify(String message) {
        PclDialogs.message(owner, "PCL-NT", message, false);
    }

    private interface IoAction { void run() throws IOException; }
}

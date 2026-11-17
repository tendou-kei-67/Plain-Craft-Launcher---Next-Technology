package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources;
import io.pclnt.minecraft.GameLaunch;
import io.pclnt.minecraft.VanillaInstaller;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import javafx.application.HostServices;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class LauncherController {
    @FXML private BorderPane frame;
    @FXML private StackPane titleBar;
    private DetailTitle detailTitle;
    @FXML private HBox titleNavigation;
    @FXML private Label brand;
    @FXML private StackPane pageHost;
    @FXML private BorderPane launchPane;
    @FXML private BorderPane launchSidebar;
    @FXML private StackPane loginHost;
    @FXML private ToggleGroup navigation;
    @FXML private ToggleButton launchTab;
    @FXML private ToggleButton downloadTab;
    @FXML private ToggleButton multiplayerTab;
    @FXML private ToggleButton serverTab;
    @FXML private ToggleButton toolsTab;
    @FXML private ToggleButton settingsTab;
    @FXML private ToggleButton moreTab;

    @FXML private Button notificationButton;
    @FXML private Button minimizeButton;
    @FXML private Button closeButton;
    @FXML private Button playButton;
    @FXML private Button versionSettingsButton;
    @FXML private Label versionCaption;
    @FXML private HBox versionButtons;

    private enum Page { LAUNCH, DOWNLOAD, TASKS, MULTIPLAYER, SERVER, TOOLS, SETTINGS, MORE, INSTANCES, INSTANCE_SETTINGS }

    private ViewTransition pages;
    private WindowChrome window;
    private HostServices links;
    private Page page;
    private final SimpleBooleanProperty darkTheme = new SimpleBooleanProperty();
    private final SimpleBooleanProperty taskPageOpen = new SimpleBooleanProperty();
    private ThemeMode themeMode;
    private Runnable openJavaDownloads;
    private final java.util.EnumMap<CommunityResources.Kind, ToggleButton> resourceTabs =
            new java.util.EnumMap<>(CommunityResources.Kind.class);
    private final java.util.EnumMap<CommunityResources.Kind, CommunityResourcePage> resourcePages =
            new java.util.EnumMap<>(CommunityResources.Kind.class);
    private Node downloadsView;
    private final AppearanceOptions appearanceOptions = new AppearanceOptions();
    private WindowAppearance appearance;
    private CustomHome customHome;
    private Node settingsView;
    private ToolboxPage toolbox;
    private Notifications notifications;
    private VanillaInstaller vanillaInstaller;
    private VanillaDownloadPage vanillaDownloads;
    private CommunityResources communityResources;
    private GameLaunch gameLaunch;
    private HttpClient minecraftClient;
    private ResourceFavorites resourceFavorites;
    private AccountPane accounts;
    private VanillaInstaller.Instance selectedInstance;
    private boolean gameRunning;
    private boolean repairing;
    private boolean writingResource;
    private int communityTransfers;
    private ModpackImport modpacks;
    private TaskMonitor tasks;

    @FXML
    private void initialize() {
        pages = new ViewTransition(pageHost, ViewTransition.Type.MAIN);
        installArtwork();
        themeMode = new ThemeMode(appearanceOptions, frame, darkTheme);
        notifications = new Notifications(frame, notificationButton);
        tasks = new TaskMonitor(appearanceOptions);
        minecraftClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        communityResources = new CommunityResources(minecraftClient,
                System.getenv().getOrDefault("PCLNT_CURSEFORGE_API_KEY", ""));
        resourceFavorites = new ResourceFavorites(appearanceOptions.directory, notifications);
        bindGameFolder(appearanceOptions.gameFolders.active());
        appearance = new WindowAppearance(appearanceOptions, brand, titleNavigation, pageHost, notifications);
        var profiles = new PlayerProfiles(appearanceOptions.directory, appearanceOptions.offlineNames.get(), notifications);
        accounts = new AccountPane(new ViewTransition(loginHost, ViewTransition.Type.LOGIN), profiles, appearanceOptions, notifications,
                this::updateLaunchButtons, address -> links.showDocument(address.toASCIIString()), () -> gameRunning);
        accounts.show();
        UiMotion.installButtons(frame, darkTheme);
        UiMotion.installButtons(launchPane, darkTheme);
        UiMotion.installPress(playButton);
        UiMotion.installPress(versionSettingsButton);
        restoreInstance();
        showLaunch();
    }

    private void bindGameFolder(Path folder) {
        vanillaInstaller = new VanillaInstaller(minecraftClient, folder);
        vanillaDownloads = new VanillaDownloadPage(minecraftClient, vanillaInstaller, appearanceOptions, notifications, this::selectInstance,
                () -> repairing || writingResource, tasks, this::showInstances);
        modpacks = new ModpackImport(frame, new io.pclnt.minecraft.ModpackInstaller(minecraftClient, vanillaInstaller, communityResources),
                communityResources, notifications, this::instanceBusy, this::setWritingResource, instance -> {
                    appearanceOptions.initializeIsolation(instance.id(), true);
                    chooseInstance(instance);
                }, tasks);
        gameLaunch = new GameLaunch(vanillaInstaller);
        downloadsView = null;
        resourceTabs.clear();
        resourcePages.clear();
        openJavaDownloads = null;
    }

    private VanillaInstaller.Instance activateGameFolder(Path folder) {
        if (!vanillaInstaller.root().equals(folder)) {
            appearanceOptions.gameFolders.select(folder);
            bindGameFolder(folder);
        }
        restoreInstance();
        return selectedInstance;
    }

    private void installArtwork() {
        PclArtwork.setBrand(brand);
        PclArtwork.setIcon(launchTab, "play", 14.4);
        PclArtwork.setIcon(downloadTab, "download", 14.4);
        PclArtwork.setIcon(multiplayerTab, "wifi", 16.8);
        PclArtwork.setGlobeIcon(serverTab);
        PclArtwork.setIcon(toolsTab, "toolbox", 15.5);
        PclArtwork.setIcon(settingsTab, "settings", 17.6);
        PclArtwork.setIcon(moreTab, "more", 14.9);
        PclArtwork.setIcon(minimizeButton, "minimize", 13);
        PclArtwork.setIcon(closeButton, "close", 14.4);
        UiMotion.installIconPress(minimizeButton);
        UiMotion.installIconPress(closeButton);
        UiMotion.installIconPress(notificationButton);
    }

    public void showWindow(Stage stage, HostServices links) {
        this.links = links;
        detailTitle = new DetailTitle(titleBar, brand, titleNavigation);
        themeMode.watch(stage);
        appearance.watch(stage);
        customHome = new CustomHome(appearanceOptions, launchPane, links);
        var root = (StackPane) stage.getScene().getRoot();
        var floating = tasks.floating(notifications.floatingLayer(), this::showTasks, taskPageOpen);
        darkTheme.addListener((ignored, old, dark) -> floating.pseudoClassStateChanged(ThemeMode.DARK, dark));
        floating.pseudoClassStateChanged(ThemeMode.DARK, darkTheme.get());
        root.opacityProperty().bind(appearanceOptions.windowOpacity.multiply(.001).add(.4));
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, event -> appearanceOptions.flush());
        installVisibility(stage);
        try {
            appearance.refreshPictures();
        } catch (java.io.IOException exception) {
            System.getLogger(LauncherController.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not load background pictures", exception);
        }
        customHome.show();
        window = new WindowChrome(stage, frame, titleBar);
        stage.setResizable(!appearanceOptions.lockWindowSize.get());
        appearanceOptions.lockWindowSize.addListener((observable, old, locked) -> stage.setResizable(!locked));
        window.open();
        if (appearanceOptions.startupLogo.get()) {
            var splash = new ImageView(stage.getIcons().getLast());
            splash.setFitWidth(96);
            splash.setFitHeight(96);
            splash.setMouseTransparent(true);
            splash.getStyleClass().add("startup-logo");
            root.getChildren().add(splash);
            var fade = UiMotion.track(splash.opacityProperty(), 0, 0, 400, javafx.animation.Interpolator.LINEAR);
            fade.setOnFinished(event -> root.getChildren().remove(splash));
            fade.play();
        }
    }

    private void installVisibility(Stage stage) {
        SettingsPage.bindVisible(downloadTab, appearanceOptions.visible(AppearanceOptions.DOWNLOAD));
        SettingsPage.bindVisible(multiplayerTab, appearanceOptions.visible(AppearanceOptions.LINK));
        SettingsPage.bindVisible(settingsTab, appearanceOptions.visible(AppearanceOptions.SETTINGS));
        SettingsPage.bindVisible(moreTab, appearanceOptions.visible(AppearanceOptions.MORE));
        var moreVisibility = AppearanceOptions.MORE_ROWS.stream().map(appearanceOptions::visible).toList();
        moreTab.textProperty().bind(Bindings.createStringBinding(() -> {
            if (moreVisibility.stream().filter(value -> value.get()).count() != 1) {
                return "更多";
            }
            if (moreVisibility.get(0).get()) {
                return "帮助";
            }
            return moreVisibility.get(1).get() ? "关于" : "百宝箱";
        }, moreVisibility.toArray(javafx.beans.Observable[]::new)));
        SettingsPage.bindVisible(versionButtons, appearanceOptions.visible(AppearanceOptions.VERSION));
        titleNavigation.visibleProperty().bind(downloadTab.visibleProperty().or(multiplayerTab.visibleProperty())
                .or(settingsTab.visibleProperty()).or(moreTab.visibleProperty()).or(serverTab.visibleProperty())
                .or(toolsTab.visibleProperty()).and(detailTitle.mainNavigationVisibleProperty()));
        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F12) {
                appearanceOptions.forceShow.set(!appearanceOptions.forceShow.get());
                notifications.post(appearanceOptions.forceShow.get() ? "功能隐藏设置已暂时关闭！" : "功能隐藏设置已重新开启！",
                        Notifications.Kind.INFO);
                event.consume();
            }
        });
    }

    @FXML
    private void showLaunch() {
        if (customHome != null) {
            customHome.show();
        }
        select(Page.LAUNCH, launchTab, launchPane);
    }

    @FXML
    private void showDownloads() {
        if (page == Page.DOWNLOAD) {
            navigation.selectToggle(downloadTab);
            return;
        }
        if (downloadsView == null) {
            var details = new ViewTransition(new StackPane(), ViewTransition.Type.CONTENT);
            var group = new ToggleGroup();
            var javaEdition = SidebarItem.page("原版游戏", "download-vanilla", 17, group,
                    () -> {
                        clearDatapackWorld();
                        detailTitle.clear();
                        details.show(vanillaDownloads.view());
                    });
            var bedrockPage = new BedrockDownloadPage(links, notifications, tasks);
            var bedrock = SidebarItem.page("基岩版", "toolbox", 16, group, () -> {
                clearDatapackWorld();
                detailTitle.clear();
                details.show(bedrockPage.view());
            });
            var heading = label("社区资源", "download-resource-heading");
            var navigation = sidebar(javaEdition, bedrock, heading);
            navigation.getStyleClass().add("pcl-sidebar");
            var folderInstaller = vanillaInstaller;
            var folderModpacks = modpacks;
            java.util.function.Consumer<Boolean> transfer = active -> communityTransfers += active ? 1 : -1;
            java.util.function.Function<CommunityResources.Kind, CommunityResourcePage> pageFor = kind ->
                    resourcePages.computeIfAbsent(kind,
                            key -> new CommunityResourcePage(communityResources, notifications, links, key,
                                    folderInstaller, () -> folderInstaller == vanillaInstaller ? selectedInstance : null,
                                    id -> appearanceOptions.isolated(folderInstaller.root(), id),
                                    folderModpacks::download, folderModpacks::open, transfer, tasks, detailTitle, resourceFavorites));
            for (var kind : CommunityResources.Kind.values()) {
                Runnable open = () -> {
                    clearDatapackWorld();
                    var resources = pageFor.apply(kind);
                    resources.showSearch();
                    details.show(resources.view());
                };
                var item = SidebarItem.page(kind.toString(), "download-" + kind.name().toLowerCase(java.util.Locale.ROOT),
                        17, group, open, "刷新", "help-refresh", 13, () -> {
                            var resource = resourcePages.get(kind);
                            resourceTabs.get(kind).fire();
                            if (resource != null) resource.refresh();
                        });
                navigation.getChildren().add(item);
                resourceTabs.put(kind, item.toggle());
            }
            var favorites = new CommunityFavoritesPage(resourceFavorites, communityResources, pageFor, (entry, returnView) -> {
                var resources = pageFor.apply(entry.kind());
                resources.showFavorite(entry, () -> {
                    detailTitle.clear();
                    details.show(returnView);
                });
                details.show(resources.view());
            }, notifications, tasks, transfer);
            var favoritesLink = SidebarItem.page("收藏夹", "lucide/heart", 17, group, () -> {
                clearDatapackWorld();
                detailTitle.clear();
                details.show(favorites.view());
            }, "刷新", "help-refresh", 13, favorites::refresh);
            navigation.getChildren().add(favoritesLink);
            openJavaDownloads = javaEdition.toggle()::fire;
            openJavaDownloads.run();
            downloadsView = section(navigation, details.host());
        }
        select(Page.DOWNLOAD, downloadTab, downloadsView);
    }

    private void showTasks() {
        select(Page.TASKS, toolsTab, tasks.view());
        detailTitle.show("任务管理", this::showTools);
    }

    @FXML
    private void showSettings() {
        if (page == Page.SETTINGS) {
            navigation.selectToggle(settingsTab);
            return;
        }
        if (settingsView == null) {
            settingsView = new SettingsPage(appearanceOptions, appearance, customHome, links, frame, this::showLaunch,
                    this::showInstanceSettings, () -> selectedInstance).view();
        }
        select(Page.SETTINGS, settingsTab, settingsView);
    }

    @FXML
    private void showMore() {
        if (page == Page.MORE) {
            navigation.selectToggle(moreTab);
            return;
        }
        select(Page.MORE, moreTab, new MorePage(links, frame, appearanceOptions, notifications, this::showTools).view());
    }

    @FXML
    private void showTools() {
        if (toolbox == null) {
            toolbox = new ToolboxPage(appearanceOptions, notifications, links, frame, tasks, this::showTasks);
        }
        select(Page.TOOLS, toolsTab, toolbox.view());
    }

    @FXML
    private void showMultiplayer() {
        select(Page.MULTIPLAYER, multiplayerTab, new Region());
    }

    @FXML
    private void showServers() {
        select(Page.SERVER, serverTab, new Region());
    }

    @FXML
    private void showInstances() {
        if (page == Page.INSTANCES) {
            return;
        }
        select(Page.INSTANCES, launchTab, new InstanceSelectionPage(vanillaInstaller, appearanceOptions,
                selectedInstance, this::chooseInstance, this::openInstanceSettings, this::instanceRemoved,
                this::instanceBusy, this::setWritingResource, links, notifications, frame,
                this::openVanillaDownloads, () -> modpacks.open(), this::activateGameFolder,
                () -> vanillaInstaller, tasks).view());
        detailTitle.show("版本选择", this::showLaunch);
    }

    private void chooseInstance(VanillaInstaller.Instance instance) {
        selectInstance(instance);
        showLaunch();
    }

    @FXML
    private void showInstanceSettings() {
        openInstanceSettings(selectedInstance);
    }

    private void openInstanceSettings(VanillaInstaller.Instance instance) {
        if (instance == null || page == Page.INSTANCE_SETTINGS) {
            return;
        }
        Runnable back = page == Page.INSTANCES ? this::showInstances : this::showLaunch;
        String caption = "版本设置 - " + instance.id();
        select(Page.INSTANCE_SETTINGS, launchTab,
                new InstanceSettingsPage(instance, vanillaInstaller, appearanceOptions, links, notifications,
                        frame, this::repairInstance, this::instanceBusy, this::openResourceDownloads, this::setWritingResource,
                        () -> gameLaunch.supportsQuickPlay(instance), world -> playWorld(instance, world), this::openWorldDatapacks,
                        new InstanceResourcesPage.DatapackServices(communityResources,
                                () -> vanillaInstaller.describe(instance).gameVersion(), tasks), (world, project) -> {
                            var settings = pages.current();
                            String gameVersion = "";
                            try {
                                gameVersion = vanillaInstaller.describe(instance).gameVersion();
                            } catch (IOException ignored) {
                                // The project can still open when the instance metadata is unavailable.
                            }
                            openWorldDatapacks(world);
                            var datapacks = resourcePages.get(CommunityResources.Kind.DATAPACK);
                            datapacks.showDatapackProject(project, gameVersion, () -> {
                                datapacks.showSearch();
                                select(Page.INSTANCE_SETTINGS, launchTab, settings);
                                detailTitle.show(caption, back);
                            });
                        })
                        .view(this::showLaunch, this::showInstances));
        detailTitle.show(caption, back);
    }

    private void instanceRemoved(VanillaInstaller.Instance instance) {
        if (instance.equals(selectedInstance)) {
            selectInstance(null);
        }
    }

    private void repairInstance(VanillaInstaller.Instance instance) {
        if (instanceBusy()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException, InterruptedException {
                vanillaInstaller.repair(instance, this::updateProgress);
                return null;
            }
        };
        repairing = true;
        updateLaunchButtons();
        notifications.post("正在补全 " + instance.id() + " 的游戏文件……", Notifications.Kind.INFO);
        task.setOnSucceeded(event -> finishRepair("已补全 " + instance.id() + " 的游戏文件", Notifications.Kind.SUCCESS));
        task.setOnFailed(event -> finishRepair("补全失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR));
        tasks.start("补全 Minecraft " + instance.id(), instance.id(), task, false, true);
    }

    private boolean instanceBusy() {
        return repairing || gameRunning || writingResource || communityTransfers > 0 || vanillaDownloads.isInstalling();
    }

    private void setWritingResource(boolean value) {
        writingResource = value;
        updateLaunchButtons();
    }

    private void openResourceDownloads(CommunityResources.Kind kind) {
        showDownloads();
        resourceTabs.get(kind).fire();
    }

    private void openWorldDatapacks(java.nio.file.Path world) {
        var kind = CommunityResources.Kind.DATAPACK;
        openResourceDownloads(kind);
        resourcePages.get(kind).useDatapackWorld(world);
    }

    private void clearDatapackWorld() {
        var datapacks = resourcePages.get(CommunityResources.Kind.DATAPACK);
        if (datapacks != null) datapacks.useDatapackWorld(null);
    }

    private void finishRepair(String message, Notifications.Kind kind) {
        repairing = false;
        updateLaunchButtons();
        notifications.post(message, kind);
    }

    private void openVanillaDownloads() {
        showDownloads();
        openJavaDownloads.run();
    }

    private void restoreInstance() {
        selectedInstance = null;
        try {
            var installed = vanillaInstaller.installed();
            String id = appearanceOptions.gameFolders.selectedVersion(vanillaInstaller.root());
            selectedInstance = installed.stream().filter(instance -> instance.id().equals(id)).findFirst()
                    .orElse(installed.isEmpty() ? null : installed.getFirst());
        } catch (IOException e) {
            notifications.post("读取已安装版本失败：" + VanillaDownloadPage.message(e), Notifications.Kind.ERROR);
        }
        updateLaunchButtons();
    }

    private void selectInstance(VanillaInstaller.Instance instance) {
        selectedInstance = instance;
        appearanceOptions.gameFolders.selectedVersion(vanillaInstaller.root(), instance == null ? "" : instance.id());
        updateLaunchButtons();
    }

    private void updateLaunchButtons() {
        String caption = selectedInstance == null ? "尚未选择版本" : selectedInstance.id();
        versionCaption.setText(caption);
        playButton.setAccessibleText("启动游戏，" + caption);
        var profile = accounts.selected();
        playButton.setDisable(selectedInstance == null || !accounts.canLaunch(profile) || gameRunning || repairing || writingResource);
        versionSettingsButton.setDisable(selectedInstance == null);
    }

    @FXML
    private void playSelectedProfile() {
        playProfile(selectedInstance, null);
    }

    private void playWorld(VanillaInstaller.Instance instance, String world) {
        if (instanceBusy()) {
            notifications.post("请等待当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
            return;
        }
        showLaunch();
        if (!accounts.canLaunch(accounts.selected())) {
            notifications.post("请先选择可用于启动的账户。", Notifications.Kind.INFO);
            return;
        }
        playProfile(instance, world);
    }

    private void playProfile(VanillaInstaller.Instance instance, String world) {
        var profile = accounts.selected();
        if (instance == null || gameRunning || repairing || writingResource || !accounts.canLaunch(profile)) {
            return;
        }
        var session = accounts.session(profile);
        var microsoftSession = accounts.microsoftSession(profile);
        int windowMode = appearanceOptions.launchWindow.get();
        int width = appearanceOptions.launchWidth.get();
        int height = appearanceOptions.launchHeight.get();
        if (windowMode == 2) {
            width = (int) frame.getWidth();
            height = (int) frame.getHeight();
        } else if (windowMode == 4) {
            var screen = javafx.stage.Screen.getScreensForRectangle(frame.getScene().getWindow().getX(),
                    frame.getScene().getWindow().getY(), frame.getWidth(), frame.getHeight());
            var bounds = (screen.isEmpty() ? javafx.stage.Screen.getPrimary() : screen.getFirst()).getVisualBounds();
            width = (int) bounds.getWidth();
            height = (int) bounds.getHeight();
        }
        var instanceSettings = appearanceOptions.instanceLaunchSettings(instance.id());
        var options = instanceSettings.resolve(appearanceOptions, windowMode, width, height);
        boolean optimizeMemory = WindowsMemory.supported() && instanceSettings.optimizeBeforeLaunch(appearanceOptions);
        int visibility = appearanceOptions.launchVisibility.get();
        var stage = (Stage) frame.getScene().getWindow();
        var task = new Task<Process>() {
            private io.pclnt.minecraft.Yggdrasil.Session validated;
            private io.pclnt.minecraft.MicrosoftLogin.Session validatedMicrosoft;

            @Override protected Process call() throws Exception {
                updateProgress(1, 5);
                updateMessage(optimizeMemory ? "正在优化内存……" : "正在准备启动……");
                if (optimizeMemory) {
                    try {
                        WindowsMemory.optimize();
                    } catch (IOException | RuntimeException failure) {
                        notifications.post("内存优化失败，继续启动游戏：" + VanillaDownloadPage.message(failure),
                                Notifications.Kind.ERROR);
                    }
                }
                updateProgress(2, 5);
                updateMessage("正在验证账户……");
                if (microsoftSession != null) {
                    validatedMicrosoft = accounts.microsoft().forLaunch(microsoftSession);
                    prepareGame();
                    return gameLaunch.microsoft(instance, validatedMicrosoft, options, world);
                }
                if (session != null) {
                    var service = accounts.yggdrasil();
                    validated = service.forLaunch(session);
                    prepareGame();
                    return gameLaunch.thirdParty(instance, service, validated, options, world);
                }
                prepareGame();
                return gameLaunch.offline(instance, profile.name(), profile.uuid(), options, world);
            }

            private void prepareGame() {
                updateProgress(3, 5);
                updateMessage("正在准备游戏文件并创建进程……");
            }

            @Override protected void succeeded() { rememberSession(); }
            @Override protected void failed() {
                rememberSession();
                if (getException() instanceof io.pclnt.minecraft.MicrosoftLogin.ReauthenticationRequiredException) {
                    accounts.expireMicrosoftSession(profile, microsoftSession);
                }
            }

            private void rememberSession() {
                if (validated != null) accounts.session(profile, validated);
                if (validatedMicrosoft != null) accounts.microsoftSession(profile, validatedMicrosoft);
            }
        };
        gameRunning = true;
        var loading = new PclLoading(instance.id());
        var stageLabel = label("正在准备启动……", "card-text");
        stageLabel.textProperty().bind(task.messageProperty());
        var loginMethod = label("登录方式  " + (microsoftSession != null ? "正版登录"
                : session != null ? "第三方登录" : "离线登录"), "version-caption");
        var progressText = label("等待启动", "version-caption");
        var launchProgress = new LaunchProgress(task.progressProperty());
        progressText.textProperty().bind(Bindings.createStringBinding(() -> task.getProgress() < 0 ? "等待启动"
                : String.format(java.util.Locale.ROOT, "启动进度  %.0f%%", task.getProgress() * 100), task.progressProperty()));
        var launching = new VBox(9, loading, label("正在启动游戏", "card-title"), loginMethod, stageLabel,
                launchProgress, progressText);
        if (appearanceOptions.showLaunchingHint.get()) LaunchProgress.trivia().ifPresent(launching.getChildren()::add);
        launching.setAlignment(Pos.CENTER);
        launching.getStyleClass().add("launch-loading");
        launching.setOpacity(0);
        launchSidebar.setCenter(launching);
        launchSidebar.getBottom().setManaged(false);
        launchSidebar.getBottom().setVisible(false);
        UiMotion.track(launching.opacityProperty(), 1, 0, 180, UiMotion.OUT_WEAK).play();
        updateLaunchButtons();
        if (optimizeMemory) {
            notifications.post("正在进行内存优化……", Notifications.Kind.INFO);
        }
        task.setOnSucceeded(event -> {
            launchSidebar.setCenter(loginHost);
            launchSidebar.getBottom().setVisible(true);
            launchSidebar.getBottom().setManaged(true);
            appearance.setGaming(true);
            if (appearanceOptions.launchCount.get() < Integer.MAX_VALUE) {
                appearanceOptions.launchCount.set(appearanceOptions.launchCount.get() + 1);
            }
            notifications.post("已启动 Minecraft " + instance.id(), Notifications.Kind.SUCCESS);
            if (visibility == 0) {
                close();
                return;
            }
            if (visibility == 2 || visibility == 3) {
                // Keep the toolkit alive while the only launcher window is hidden.
                javafx.application.Platform.setImplicitExit(false);
                appearanceOptions.flush();
                stage.hide();
            } else if (visibility == 4) {
                minimize();
            }
            task.getValue().onExit().thenAccept(process -> javafx.application.Platform.runLater(() -> {
                gameRunning = false;
                appearance.setGaming(false);
                updateLaunchButtons();
                if (visibility == 2) {
                    javafx.application.Platform.exit();
                    return;
                }
                if (visibility == 3) {
                    stage.show();
                    javafx.application.Platform.setImplicitExit(true);
                }
                if (process.exitValue() != 0) {
                    notifications.post("游戏异常退出，请查看版本目录下的 logs/pcl-nt-launch.log", Notifications.Kind.ERROR);
                }
            }));
        });
        task.setOnFailed(event -> {
            launchSidebar.setCenter(loginHost);
            launchSidebar.getBottom().setVisible(true);
            launchSidebar.getBottom().setManaged(true);
            gameRunning = false;
            updateLaunchButtons();
            notifications.post("启动失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        tasks.start("启动 Minecraft " + instance.id(), instance.id(), task, false, false);
    }

    @FXML
    private void minimize() {
        window.minimize();
    }

    @FXML
    private void close() {
        window.close();
    }

    private void select(Page target, ToggleButton tab, Node view) {
        navigation.selectToggle(tab);
        if (target == page) {
            return;
        }
        page = target;
        if (target != Page.DOWNLOAD) clearDatapackWorld();
        if (detailTitle != null) detailTitle.clear();
        taskPageOpen.set(target == Page.TASKS);
        if (view != launchPane) {
            UiMotion.installButtons(view, darkTheme);
        }
        pages.show(view);
    }

    static ToggleButton sideTab(String text) {
        var tab = new ToggleButton(text);
        tab.getStyleClass().add("side-tab");
        tab.setMaxWidth(Double.MAX_VALUE);
        return tab;
    }

    static ToggleGroup group(ToggleButton... buttons) {
        var group = new ToggleGroup();
        for (var button : buttons) {
            button.setToggleGroup(group);
        }
        return group;
    }

    static VBox sidebar(Node... items) {
        var sidebar = new VBox(3, items);
        sidebar.getStyleClass().add("section-sidebar");
        return sidebar;
    }

    static BorderPane section(Node sidebar, Node details) {
        var view = new BorderPane(details);
        view.setLeft(sidebar);
        return view;
    }

    static ScrollPane content(Node... children) {
        var contents = new VBox(15, children);
        contents.getStyleClass().add("section-content");
        var scroll = new SpringScrollPane(contents);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("section-scroll");
        return scroll;
    }

    static VBox card(String title, String description) {
        return PclCard.fixed(title, label(description, "card-text"));
    }

    static Label label(String text, String style) {
        var label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}

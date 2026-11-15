package io.pclnt.ui;

import io.pclnt.minecraft.VanillaCatalog;
import io.pclnt.minecraft.VanillaInstaller;
import io.pclnt.minecraft.FabricMeta;
import io.pclnt.minecraft.ForgeInstaller;
import io.pclnt.minecraft.Modrinth;
import io.pclnt.minecraft.OptiFineInstaller;
import java.net.http.HttpClient;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import static io.pclnt.ui.LauncherController.*;

final class VanillaDownloadPage {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private final StackPane view = new StackPane();
    private final HttpClient client;
    private final VanillaInstaller installer;
    private final AppearanceOptions appearance;
    private final FabricMeta fabric;
    private final Modrinth modrinth;
    private final ForgeInstaller forge;
    private final OptiFineInstaller optiFine;
    private final Notifications notifications;
    private final TaskMonitor tasks;
    private final Consumer<VanillaInstaller.Instance> onInstalled;
    private VanillaCatalog catalog;
    private Task<?> job;
    private boolean installing;
    private final java.util.function.BooleanSupplier repairing;

    VanillaDownloadPage(HttpClient client, VanillaInstaller installer, AppearanceOptions appearance, Notifications notifications,
                        Consumer<VanillaInstaller.Instance> onInstalled, java.util.function.BooleanSupplier repairing,
                        TaskMonitor tasks) {
        this.client = client;
        this.installer = installer;
        this.appearance = appearance;
        this.repairing = repairing;
        fabric = new FabricMeta(client);
        modrinth = new Modrinth(client);
        forge = new ForgeInstaller(client, installer);
        optiFine = new OptiFineInstaller(client, installer);
        this.notifications = notifications;
        this.onInstalled = onInstalled;
        this.tasks = tasks;
    }

    Node view() {
        if (view.getChildren().isEmpty()) {
            load();
        }
        return view;
    }

    private void load() {
        var task = new Task<VanillaCatalog>() {
            @Override protected VanillaCatalog call() throws Exception {
                return VanillaCatalog.load(client);
            }
        };
        replace(center(PclCard.fixedBody("", new PclLoading("正在获取版本列表"))));
        task.setOnSucceeded(event -> {
            catalog = task.getValue();
            showCatalog();
        });
        task.setOnFailed(event -> replace(center(PclCard.fixed("版本列表加载失败",
                label(message(task.getException()), "card-text"), button("重试", this::load)))));
        run(task);
    }

    private void showCatalog() {
        var latest = new VBox(3);
        for (String id : java.util.stream.Stream.of(catalog.latestRelease(), catalog.latestSnapshot()).distinct().toList()) {
            catalog.versions().stream().filter(version -> version.id().equals(id)).findFirst()
                    .ifPresent(version -> latest.getChildren().add(row(version)));
        }
        var list = content(PclCard.fixed("最新版本", latest));
        var cards = (VBox) list.getContent();
        for (var category : VanillaCatalog.Category.values()) {
            var matches = catalog.versions().stream().filter(version -> version.category() == category).toList();
            if (matches.isEmpty()) {
                continue;
            }
            var rows = new VBox(2);
            for (var version : matches) {
                rows.getChildren().add(row(version));
            }
            cards.getChildren().add(PclCard.foldable(category.title + "（" + matches.size() + "）", rows, false));
        }
        replace(list);
    }

    private Button row(VanillaCatalog.Version version) {
        return row(version.id(), version.category().title + "  ·  " + DATE.format(version.released()),
                gameBlock(version), () -> showInstall(version));
    }

    private static String gameBlock(VanillaCatalog.Version version) {
        return switch (version.category()) {
            case APRIL_FOOLS -> "GoldBlock";
            case LEGACY -> "CobbleStone";
            case SNAPSHOT -> "CommandBlock";
            case RELEASE -> "Grass";
        };
    }

    private record Installed(VanillaInstaller.Instance instance, boolean newInstance, boolean newBase) {}

    static Button row(String name, String description, String block, Runnable open) {
        var summary = new VBox(2, label(name, "version-row-title"), label(description, "help-row-description"));
        summary.setAlignment(Pos.CENTER_LEFT);
        var graphic = new HBox(10);
        graphic.setAlignment(Pos.CENTER_LEFT);
        graphic.getChildren().addAll(PclArtwork.block(block, 28), summary);
        var row = new Button(null, graphic);
        row.setAccessibleText(name + " " + description);
        row.getStyleClass().add("help-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setPrefHeight(42);
        row.setOnAction(event -> open.run());
        return row;
    }

    private void showInstall(VanillaCatalog.Version version) {
        var status = label("", "card-text");
        var progress = new ProgressBar(0);
        progress.setMaxWidth(Double.MAX_VALUE);
        var install = button("开始下载", () -> {});
        install.getStyleClass().add("accent-button");
        PclArtwork.setIcon(install, "download", 15);
        var back = button("", this::showCatalog);
        PclArtwork.setIcon(back, "install-back", 14);
        back.setAccessibleText("返回版本列表");
        var versionName = label(version.id(), "install-version-name");
        var versionIcon = PclArtwork.block(gameBlock(version), 32);
        var banner = new HBox(12, back, versionIcon, versionName);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("pcl-card", "install-banner");

        var forgeCard = new ComponentCard<ForgeInstaller.Release>("Forge", "Anvil", ForgeInstaller.Release::version,
                () -> forge.releases(ForgeInstaller.Distribution.FORGE, version.id()));
        var neoCard = new ComponentCard<ForgeInstaller.Release>("NeoForge", "NeoForge", ForgeInstaller.Release::version,
                () -> forge.releases(ForgeInstaller.Distribution.NEOFORGE, version.id()));
        var fabricCard = new ComponentCard<FabricMeta.Loader>("Fabric", "Fabric", FabricMeta.Loader::version,
                () -> fabric.loaders(version.id()));
        var apiCard = new ComponentCard<Modrinth.Release>("Fabric API", "Fabric", Modrinth.Release::version,
                () -> modrinth.versions("fabric-api", version.id(), "fabric"));
        var optiFineCard = new ComponentCard<OptiFineInstaller.Release>("OptiFine", "GrassPath",
                OptiFineInstaller.Release::version, () -> optiFine.releases(version.id()));
        forgeCard.filter = candidate -> optiFineCard.selected.get() == null
                || optiFineCard.selected.get().supports(candidate);
        optiFineCard.filter = candidate -> forgeCard.selected.get() == null
                || candidate.supports(forgeCard.selected.get());
        apiCard.needsFabric();
        apiCard.autoSelect = true;
        var hint = label("如果不安装 Fabric API，大多数 Mod 都会无法使用！", "help-hint");
        hint.visibleProperty().bind(fabricCard.selected.isNotNull().and(apiCard.selected.isNull()).and(apiCard.loading.not()));
        hint.managedProperty().bind(hint.visibleProperty());

        forgeCard.selected.addListener((observable, previous, current) -> {
            if (current != null) {
                neoCard.clear();
                fabricCard.clear();
                if (optiFineCard.selected.get() != null && !optiFineCard.selected.get().supports(current)) {
                    optiFineCard.clear();
                }
            }
            optiFineCard.refreshRows();
        });
        neoCard.selected.addListener((observable, previous, current) -> {
            if (current != null) {
                forgeCard.clear();
                fabricCard.clear();
                optiFineCard.clear();
            }
        });
        fabricCard.selected.addListener((observable, previous, current) -> {
            if (current != null) {
                forgeCard.clear();
                neoCard.clear();
                optiFineCard.clear();
                apiCard.card.setDisable(false);
                if (previous == null) {
                    apiCard.request();
                }
            } else {
                apiCard.needsFabric();
            }
        });
        optiFineCard.selected.addListener((observable, previous, current) -> {
            if (current != null) {
                if (forgeCard.selected.get() != null && !current.supports(forgeCard.selected.get())) {
                    forgeCard.clear();
                }
                neoCard.clear();
                fabricCard.clear();
            }
            forgeCard.refreshRows();
        });
        var loaderCards = java.util.List.of(fabricCard, forgeCard, neoCard, optiFineCard);
        javafx.beans.InvalidationListener updateIcon = ignored -> {
            String icon = loaderCards.stream().filter(card -> card.selected.get() != null)
                    .map(card -> card.icon).findFirst().orElse(gameBlock(version));
            versionIcon.setImage(PclArtwork.blockImage(icon));
        };
        loaderCards.forEach(card -> card.selected.addListener(updateIcon));

        var items = new VBox(12, banner, hint, forgeCard.card, neoCard.card, fabricCard.card, apiCard.card, optiFineCard.card);
        var activity = new VBox(5, progress, status);
        activity.setVisible(false);
        activity.managedProperty().bind(activity.visibleProperty());
        var footer = new VBox(7, activity, install);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("install-footer");
        var layout = new javafx.scene.layout.BorderPane(content(items));
        layout.setBottom(footer);
        install.setOnAction(event -> {
            if (repairing.getAsBoolean()) {
                notifications.post("正在补全游戏文件，请稍后再安装。", Notifications.Kind.INFO);
                return;
            }
            if (installing || job != null && job.isRunning()) {
                return;
            }
            var loader = fabricCard.selected.get();
            var api = apiCard.selected.get();
            var forgeRelease = forgeCard.selected.get() == null ? neoCard.selected.get() : forgeCard.selected.get();
            var optiFineRelease = optiFineCard.selected.get();
            int policy = appearance.defaultIsolation.get();
            boolean nonRelease = version.category() != VanillaCatalog.Category.RELEASE;
            boolean modded = loader != null || forgeRelease != null;
            boolean isolate = AppearanceOptions.isolateNewInstance(policy, modded, nonRelease);
            if (modded && !isolate) {
                var proceed = new javafx.scene.control.ButtonType("继续安装", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                if (PclDialogs.waitForMessage(view, "实例隔离",
                        "按当前默认策略，新实例会共享模组文件夹。切换实例时，载入不兼容的模组可能导致游戏崩溃。",
                        true, proceed, javafx.scene.control.ButtonType.CANCEL) != proceed) return;
                if (repairing.getAsBoolean() || installing) return;
            }
            items.setDisable(true);
            install.setDisable(true);
            activity.setVisible(true);
            status.setText("正在下载并校验文件……");
            installing = true;
            var task = new Task<Installed>() {
                @Override protected Installed call() throws Exception {
                    var existing = installer.instances().stream().map(VanillaInstaller.Instance::id)
                            .collect(Collectors.toSet());
                    var instance = installSelected(existing);
                    return new Installed(instance, !existing.contains(instance.id()),
                            !instance.id().equals(version.id()) && !existing.contains(version.id()));
                }

                private VanillaInstaller.Instance installSelected(java.util.Set<String> existing) throws Exception {
                    if (forgeRelease != null && optiFineRelease != null) {
                        return optiFine.installWithForge(version, optiFineRelease, forgeRelease, this::updateProgress);
                    }
                    if (forgeRelease != null) {
                        return forge.install(version, forgeRelease, this::updateProgress);
                    }
                    if (optiFineRelease != null) {
                        return optiFine.install(version, optiFineRelease, this::updateProgress);
                    }
                    return loader == null ? installer.install(version, this::updateProgress)
                            : installer.installFabric(version, loader, api,
                                    instance -> existing.contains(instance.id()) ? appearance.isolated(instance.id()) : isolate,
                                    this::updateProgress);
                }
            };
            progress.progressProperty().bind(task.progressProperty());
            task.setOnSucceeded(done -> {
                installing = false;
                progress.progressProperty().unbind();
                progress.setProgress(1);
                items.setDisable(false);
                install.setDisable(false);
                status.setText("安装完成。已将此版本设为启动版本。");
                var result = task.getValue();
                if (result.newBase()) appearance.initializeIsolation(version.id(),
                        AppearanceOptions.isolateNewInstance(policy, false, nonRelease));
                if (result.newInstance()) appearance.initializeIsolation(result.instance().id(), isolate);
                onInstalled.accept(result.instance());
                notifications.post("已安装 " + result.instance().id(), Notifications.Kind.SUCCESS);
            });
            task.setOnFailed(done -> {
                installing = false;
                progress.progressProperty().unbind();
                progress.setProgress(0);
                items.setDisable(false);
                install.setDisable(false);
                status.setText("安装失败：" + message(task.getException()));
                notifications.post("安装 Minecraft " + version.id() + " 失败", Notifications.Kind.ERROR);
            });
            job = task;
            tasks.start("安装 Minecraft " + version.id(), version.id(), task, false, true);
        });
        replace(layout);
    }

    private static final class ComponentCard<T> {
        final PclCard card;
        final javafx.beans.property.ObjectProperty<T> selected = new javafx.beans.property.SimpleObjectProperty<>();
        final javafx.beans.property.BooleanProperty loading = new javafx.beans.property.SimpleBooleanProperty();
        private final VBox rows = new VBox(2);
        private final Label summary = label("未选择", "help-row-description");
        private final java.util.concurrent.Callable<java.util.List<T>> source;
        private final java.util.function.Function<T, String> name;
        private final String icon;
        private Task<java.util.List<T>> request;
        private java.util.List<T> cached;
        java.util.function.Predicate<T> filter = candidate -> true;
        boolean autoSelect;

        ComponentCard(String title, String icon, java.util.function.Function<T, String> name,
                      java.util.concurrent.Callable<java.util.List<T>> source) {
            this.source = source;
            this.name = name;
            this.icon = icon;
            card = PclCard.foldable(title, rows, false);
            card.pointRightWhenCollapsed();
            var clear = button("", this::clear);
            PclArtwork.setIcon(clear, "install-clear", 10);
            clear.setAccessibleText("取消安装 " + title);
            clear.getStyleClass().add("install-clear");
            clear.visibleProperty().bind(selected.isNotNull());
            clear.managedProperty().bind(clear.visibleProperty());
            var detail = new HBox(7, PclArtwork.block(icon, 18), summary, clear);
            detail.setAlignment(Pos.CENTER_LEFT);
            card.setHeaderContent(detail);
            card.getStyleClass().add("install-component");
            card.onExpand(this::request);
            selected.addListener((observable, old, next) -> summary.setText(next == null ? "未选择" : name.apply(next)));
        }

        void clear() {
            selected.set(null);
        }

        void needsFabric() {
            if (request != null && loading.get()) {
                request.cancel();
            }
            loading.set(false);
            clear();
            summary.setText("需要安装 Fabric");
            card.setExpanded(false);
            card.setDisable(true);
        }

        void request() {
            if (loading.get()) {
                return;
            }
            if (cached != null) {
                display(cached);
                return;
            }
            loading.set(true);
            summary.setText("获取中……");
            rows.getChildren().setAll(label("正在获取版本列表……", "card-text"));
            request = new Task<>() {
                @Override protected java.util.List<T> call() throws Exception {
                    return source.call();
                }
            };
            request.setOnSucceeded(event -> {
                loading.set(false);
                cached = request.getValue();
                display(cached);
            });
            request.setOnFailed(event -> {
                loading.set(false);
                summary.setText("获取失败");
                rows.getChildren().setAll(label(message(request.getException()), "card-text"), button("重试", this::request));
            });
            Thread.startVirtualThread(request);
        }

        private void display(java.util.List<T> versions) {
            versions = versions.stream().filter(filter).toList();
            if (autoSelect && !versions.isEmpty()) {
                autoSelect = false;
                selected.set(versions.getFirst());
            }
            summary.setText(selected.get() != null ? name.apply(selected.get()) : versions.isEmpty() ? "无" : "未选择");
            if (versions.isEmpty()) {
                rows.getChildren().setAll(label("暂无匹配版本。", "card-text"));
                return;
            }
            rows.getChildren().setAll(versions.stream().map(version -> {
                String description = switch (version) {
                    case FabricMeta.Loader loader -> loader.stable() ? "稳定版" : "测试版";
                    case OptiFineInstaller.Release release -> (release.preview() ? "测试版" : "正式版")
                            + (release.forgeVersion().isBlank() ? "，不兼容 Forge" : "，兼容 Forge " + release.forgeVersion());
                    default -> "点击选择";
                };
                return row(name.apply(version), description, icon, () -> {
                    selected.set(version);
                    card.setExpanded(false);
                });
            }).toList());
        }

        void refreshRows() {
            if (cached != null) {
                display(cached);
            }
        }
    }

    private void replace(Node content) {
        view.getChildren().setAll(content);
    }

    private void run(Task<?> task) {
        job = task;
        Thread.startVirtualThread(task);
    }

    boolean isInstalling() {
        return installing;
    }

    private static StackPane center(PclCard card) {
        card.setMaxSize(350, PclCard.USE_PREF_SIZE);
        return new StackPane(card);
    }

    static Button button(String title, Runnable action) {
        var button = new Button(title);
        button.getStyleClass().add("outline-button");
        UiMotion.installPress(button);
        button.setOnAction(event -> action.run());
        return button;
    }

    static String message(Throwable error) {
        String description = error.getLocalizedMessage();
        return description == null || description.isBlank() ? error.getClass().getSimpleName() : description;
    }
}

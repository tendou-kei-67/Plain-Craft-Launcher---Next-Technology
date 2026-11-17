package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources;
import io.pclnt.minecraft.CommunityResources.Filter;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.CommunityResources.Project;
import io.pclnt.minecraft.CommunityResources.Source;
import io.pclnt.minecraft.ResourceDestination;
import io.pclnt.minecraft.VanillaInstaller;
import io.pclnt.minecraft.VanillaInstaller.Instance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class CommunityResourcePage {
    private static final int PAGE_SIZE = 20;
    private static final String ANY_VERSION = "全部 (也可自行输入)";
    private static final Category ALL = new Category("全部", "", "", "item");
    private record Category(String name, String curse, String modrinth, String layout) {
        @Override public String toString() { return name; }
    }
    private record Origin(String label, Source source) {
        @Override public String toString() { return label; }
    }
    private record ProjectFileViews(FileView initial, FileView alternate) {}

    private static final class FileView {
        final Kind kind;
        final ResourceFileVersions versions;
        final java.util.Set<String> expanded = new java.util.HashSet<>();
        String choice;

        FileView(Kind kind, ResourceFileVersions versions) {
            this.kind = kind;
            this.versions = versions;
            this.choice = versions.initialChoice();
        }
    }
    private final CommunityResources resources;
    private final Notifications notifications;
    private final HostServices links;
    private final Kind kind;
    private final VanillaInstaller installer;
    private final Supplier<Instance> selectedInstance;
    private final Predicate<String> isolated;
    private final Consumer<CommunityResources.File> installPack;
    private final Consumer<Boolean> transferActive;
    private final TaskMonitor tasks;
    private final ResourceFavorites favorites;
    private final ViewTransition details = new ViewTransition(new StackPane(), ViewTransition.Type.CONTENT);
    private final ComboBox<Origin> source = new ComboBox<>();
    private final ComboBox<String> loader = new ComboBox<>();
    private final ComboBox<Category> category = new ComboBox<>();
    private final TextField query = field("搜索名称");
    private final ComboBox<String> version = new ComboBox<>();
    private final VBox results = new VBox(15);
    private final java.util.Map<URI, Task<Image>> icons = new LinkedHashMap<>(128, .75f, true);
    private final Node searchView;
    private final DetailTitle titleBar;
    private Task<?> request;
    private boolean opened;
    private boolean searchVisible = true;
    private boolean preparingSave;
    private Path datapackWorld;
    private final Map<Kind, Path> lastFolders = new java.util.EnumMap<>(Kind.class);

    CommunityResourcePage(CommunityResources resources, Notifications notifications, HostServices links, Kind kind,
                          VanillaInstaller installer, Supplier<Instance> selectedInstance, Predicate<String> isolated,
                          Consumer<CommunityResources.File> installPack, Runnable importPack,
                          Consumer<Boolean> transferActive, TaskMonitor tasks, DetailTitle titleBar,
                          ResourceFavorites favorites) {
        this.resources = resources;
        this.notifications = notifications;
        this.links = links;
        this.kind = kind;
        this.installer = installer;
        this.selectedInstance = selectedInstance;
        this.isolated = isolated;
        this.installPack = installPack;
        this.transferActive = transferActive;
        this.tasks = tasks;
        this.titleBar = titleBar;
        this.favorites = favorites;
        var curse = new Origin("CurseForge", Source.CURSEFORGE);
        source.getItems().setAll(kind == Kind.WORLD ? List.of(curse)
                : List.of(new Origin("全部", null), curse, new Origin("Modrinth", Source.MODRINTH)));
        loader.getItems().setAll("任意 Mod 加载器", "Forge", "NeoForge", "Fabric", "LiteLoader");
        category.getItems().setAll(readCategories(kind));
        category.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
                setDisable(!empty && item != null && item.layout().equals("header"));
                setPadding(new javafx.geometry.Insets(3, 7, 3, !empty && item != null && item.layout().equals("nested") ? 18 : 7));
            }
        });
        version.getItems().setAll(ANY_VERSION, "26.2", "26.1", "1.21.11", "1.21.8", "1.21.4", "1.21.1",
                "1.20.1", "1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.7.10");
        version.setEditable(true);
        for (var control : List.of(source, loader, category, version)) {
            control.getStyleClass().add("account-input");
            control.setMaxWidth(Double.MAX_VALUE);
            control.setMinWidth(0);
            control.getSelectionModel().selectFirst();
        }
        source.setDisable(kind == Kind.WORLD);
        source.setAccessibleText("资源来源");
        category.setAccessibleText("类型");
        version.setAccessibleText("游戏版本");
        loader.setAccessibleText("Mod 加载器");
        query.setOnAction(event -> search());
        version.getEditor().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && !version.isShowing()) search();
        });
        var filters = new GridPane(0, 9);
        for (int index = 0; index < 2; index++) {
            filters.getRowConstraints().add(new javafx.scene.layout.RowConstraints(28));
        }
        double labelWidth = 44;
        double gap = 50;
        var leftColumn = new javafx.scene.layout.ColumnConstraints(216, 285, Double.MAX_VALUE,
                Priority.ALWAYS, null, true);
        var rightColumn = new javafx.scene.layout.ColumnConstraints(94, 114, Double.MAX_VALUE,
                Priority.SOMETIMES, null, true);
        // PageResource.xaml assigns 1.3* and 1.2* to the version and loader, versus 1* on the right.
        var available = filters.widthProperty().subtract(labelWidth * 2 + gap);
        leftColumn.prefWidthProperty().bind(available.multiply(2.5 / 3.5));
        rightColumn.prefWidthProperty().bind(available.divide(3.5));
        filters.addRow(0, filterLabel("名称"), query, new javafx.scene.layout.Region(), filterLabel("来源"), source);
        filters.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(labelWidth), leftColumn,
                new javafx.scene.layout.ColumnConstraints(gap), new javafx.scene.layout.ColumnConstraints(labelWidth), rightColumn);
        var versionAndLoader = new HBox(10, version);
        HBox.setHgrow(version, Priority.ALWAYS);
        if (kind == Kind.MOD) {
            loader.setMinWidth(125);
            loader.prefWidthProperty().bind(leftColumn.prefWidthProperty().multiply(1.2 / 2.5).subtract(10));
            versionAndLoader.getChildren().add(loader);
            var showLoader = javafx.beans.binding.Bindings.createBooleanBinding(() -> {
                String text = version.getEditor().getText();
                return !ANY_VERSION.equals(text) && (text.contains(".") || text.contains("w"));
            }, version.getEditor().textProperty());
            SettingsPage.bindVisible(loader, showLoader);
            showLoader.addListener((observable, old, show) -> {
                if (!show) loader.getSelectionModel().selectFirst();
            });
        }
        filters.addRow(1, filterLabel("版本"), versionAndLoader, new javafx.scene.layout.Region(), filterLabel("类型"), category);
        query.setMinWidth(0);
        GridPane.setHgrow(query, Priority.ALWAYS);
        GridPane.setHgrow(versionAndLoader, Priority.ALWAYS);
        category.setDisable(kind == Kind.WORLD);
        var search = button("搜索", this::search);
        search.getStyleClass().add("accent-button");
        var reset = button("重置条件", this::reset);
        var actions = new javafx.scene.layout.FlowPane(20, 8, search, reset);
        if (kind == Kind.MODPACK) actions.getChildren().add(button("安装已有整合包", importPack));
        actions.getChildren().forEach(node -> ((ButtonBase) node).setMinWidth(140));
        var searchForm = new VBox(15, filters, actions);
        searchForm.getStyleClass().add("resource-search-body");
        searchView = content(PclCard.fixedBody("搜索" + kind, searchForm), results);
        details.host().sceneProperty().addListener((observable, old, scene) -> {
            if (scene != null) openSearchIfNeeded();
        });
        details.show(searchView);
    }

    Node view() { return details.host(); }

    void useDatapackWorld(Path world) { datapackWorld = world; }

    void showSearch() {
        searchVisible = true;
        details.show(searchView);
        titleBar.clear();
        if (details.host().getScene() != null) openSearchIfNeeded();
    }

    void showFavorite(ResourceFavorites.Entry entry, Runnable returnTo) {
        searchVisible = false;
        showProject(entry.project(), entry.kind(), new Filter("", "", ""), returnTo);
    }

    void showDatapackProject(Project project, String gameVersion, Runnable returnTo) {
        searchVisible = false;
        showProject(project, Kind.DATAPACK, new Filter("", gameVersion, ""), returnTo);
    }

    private void openSearchIfNeeded() {
        if (!searchVisible || opened) return;
        opened = true;
        search();
    }

    private static Label filterLabel(String text) {
        var caption = label(text, "card-text");
        caption.setWrapText(false);
        caption.setMinWidth(44);
        return caption;
    }

    void refresh() {
        showSearch();
        search();
    }

    private static List<Category> readCategories(Kind kind) {
        var items = new ArrayList<Category>();
        items.add(ALL);
        var stream = CommunityResourcePage.class.getResourceAsStream("community-categories.tsv");
        if (stream == null) return items;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var columns = line.split("\\t", -1);
                if (columns.length != 4 || !columns[0].equals(kind.name())) continue;
                var tags = columns[2].split("/", -1);
                items.add(new Category(columns[1], tags[0], tags.length == 2 ? tags[1] : "", columns[3]));
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return items;
    }

    private void reset() {
        query.clear();
        for (var combo : List.of(source, category, version, loader)) combo.getSelectionModel().selectFirst();
        version.getEditor().setText(ANY_VERSION);
    }

    private void search() {
        String game = version.getEditor().getText().strip();
        var tag = category.getValue();
        if (tag == null || tag.layout().equals("header")) {
            category.getSelectionModel().selectFirst();
            tag = ALL;
        }
        var filter = new Filter(query.getText().strip(), game.equals(ANY_VERSION) ? "" : game,
                loader.getSelectionModel().getSelectedIndex() == 0 ? "" : loader.getValue().toLowerCase(Locale.ROOT),
                tag.curse(), tag.modrinth());
        showProjects(source.getValue().source(), filter, 0);
    }

    private void showProjects(Source selected, Filter filter, int offset) {
        load(results, () -> {
            if (selected == null) return resources.searchAll(kind, filter, offset);
            var page = resources.search(selected, kind, filter, offset);
            return new CommunityResources.SearchPage(page.items(), page.more(), List.of());
        }, page -> renderProjects(selected, filter, offset, page));
    }

    private void renderProjects(Source selected, Filter filter, int offset, CommunityResources.SearchPage page) {
        var projects = new VBox();
        projects.getStyleClass().add("resource-results-body");
        for (var project : page.items()) {
            projects.getChildren().add(projectRow(project, () -> showProject(project, kind, filter, this::showSearch),
                    rowActions(project, kind)));
        }
        if (page.items().isEmpty()) projects.getChildren().add(label("没有找到匹配的项目。", "card-text"));
        for (var warning : page.warnings()) results.getChildren().add(label(warning, "help-hint"));
        var card = PclCard.fixedBody("", projects);
        card.setAccessibleText("搜索结果");
        results.getChildren().add(card);
        pagination(results, offset, page.more(), next -> showProjects(selected, filter, next));
    }

    private void showProject(Project project, Kind resourceKind, Filter filter, Runnable returnTo) {
        var files = new VBox(15);
        var alternate = new javafx.scene.control.Button();
        alternate.getStyleClass().add("resource-kind-switch");
        alternate.setMaxWidth(Double.MAX_VALUE);
        alternate.setWrapText(true);
        PclArtwork.setIcon(alternate, "lucide/arrow-right-left", 15);
        UiMotion.installPress(alternate);
        alternate.setVisible(false);
        alternate.managedProperty().bind(alternate.visibleProperty());
        var versionFilters = new javafx.scene.layout.FlowPane(8, 6);
        versionFilters.getStyleClass().add("resource-version-filters");
        var filterCard = PclCard.fixedBody("", versionFilters);
        filterCard.setAccessibleText("筛选游戏版本");
        SettingsPage.bindVisible(filterCard, javafx.beans.binding.Bindings.isNotEmpty(versionFilters.getChildren()));
        var website = button("转到 " + project.source(), () -> links.showDocument(project.website().toString()));
        website.setDisable(project.website() == null);
        var copy = button("复制名称", () -> {
            var clipboard = new javafx.scene.input.ClipboardContent();
            clipboard.putString(project.localizedTitle().equals(project.title()) ? project.title()
                    : project.localizedTitle() + " (" + project.title() + ")");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard);
        });
        var introActions = new javafx.scene.layout.FlowPane(20, 8, website);
        if (project.wiki() != null) introActions.getChildren().add(button("转到 MC 百科",
                () -> links.showDocument(project.wiki().toString())));
        introActions.getChildren().add(copy);
        introActions.getChildren().stream()
                .forEach(node -> ((ButtonBase) node).setMinWidth(140));
        introActions.getChildren().addAll(translateButton(project, true), favoriteButton(project,
                resourceKind == null ? Kind.MOD : resourceKind, true));
        var summary = projectSummary(project);
        summary.getStyleClass().add("resource-intro-summary");
        var intro = new VBox(8, summary, introActions);
        intro.getStyleClass().add("resource-intro-body");
        var introCard = PclCard.fixedBody("", intro);
        introCard.setAccessibleText(project.localizedTitle());
        var projectView = content(introCard, alternate, filterCard, files);
        titleBar.show(project.localizedTitle(), returnTo);
        details.show(projectView);
        load(files, () -> projectFileViews(resources.projectFiles(project), project.source(), resourceKind, filter), views -> {
            Consumer<Project> openDependency = dependency -> showProject(dependency, null, filter, () -> {
                details.show(projectView);
                titleBar.show(project.localizedTitle(), returnTo);
            });
            displayFileView(project, views.initial(), views.alternate(), alternate, versionFilters, files, openDependency);
        });
    }

    private static ProjectFileViews projectFileViews(List<CommunityResources.File> files, Source source, Kind kind, Filter filter) {
        Kind alternate = null;
        if (source == Source.MODRINTH && (kind == Kind.MOD || kind == Kind.DATAPACK)) {
            var types = java.util.EnumSet.noneOf(Kind.class);
            for (var file : files) {
                Kind declared = ResourceFileVersions.declaredKind(file);
                if (declared != null) types.add(declared);
            }
            if (types.contains(Kind.MOD) && types.contains(Kind.DATAPACK)) {
                alternate = kind == Kind.MOD ? Kind.DATAPACK : Kind.MOD;
            }
        }
        if (alternate == null) {
            return new ProjectFileViews(new FileView(kind,
                    new ResourceFileVersions(files, source, kind, filter.gameVersion(), filter.loader())), null);
        }
        Kind alternateKind = alternate;
        var primaryFiles = files.stream().filter(file -> ResourceFileVersions.declaredKind(file) == kind).toList();
        var alternateFiles = files.stream().filter(file -> ResourceFileVersions.declaredKind(file) == alternateKind).toList();
        return new ProjectFileViews(
                new FileView(kind, new ResourceFileVersions(primaryFiles, source, kind, filter.gameVersion(), filter.loader())),
                new FileView(alternateKind, new ResourceFileVersions(alternateFiles, source, alternateKind, filter.gameVersion(), "")));
    }

    private void displayFileView(Project project, FileView selected, FileView other, javafx.scene.control.Button alternate,
                                 javafx.scene.layout.FlowPane filters, VBox files, Consumer<Project> openDependency) {
        alternate.setVisible(other != null);
        if (other != null) {
            alternate.setText(selected.kind == Kind.MOD
                    ? "以下是该项目的 Mod 版本。点击这里查看其数据包版本。"
                    : "以下是该项目的数据包版本。点击这里查看其 Mod 版本。");
            alternate.setOnAction(event -> displayFileView(project, other, selected, alternate, filters, files, openDependency));
        }
        Consumer<String> showFiles = choice -> {
            selected.choice = choice;
            renderFiles(project, selected.kind, files, selected.versions.cards(choice), selected.expanded, openDependency);
        };
        filters.getChildren().clear();
        if (!selected.versions.choices().isEmpty()) {
            var group = new javafx.scene.control.ToggleGroup();
            filters.getChildren().add(versionFilter("全部", selected.choice.isEmpty(), group, () -> showFiles.accept("")));
            for (var version : selected.versions.choices()) {
                filters.getChildren().add(versionFilter(version, version.equals(selected.choice), group, () -> showFiles.accept(version)));
            }
        }
        showFiles.accept(selected.choice);
    }

    private static javafx.scene.control.RadioButton versionFilter(String name, boolean selected,
                                                                     javafx.scene.control.ToggleGroup group, Runnable open) {
        var choice = new javafx.scene.control.RadioButton(name);
        choice.setToggleGroup(group);
        choice.setSelected(selected);
        AppearanceControlMotion.install(choice);
        choice.setOnAction(event -> open.run());
        return choice;
    }

    private void renderFiles(Project project, Kind resourceKind, VBox files, List<ResourceFileVersions.Card> groups,
                             java.util.Set<String> expanded, Consumer<Project> openDependency) {
        files.getChildren().clear();
        if (groups.isEmpty()) {
            files.getChildren().add(PclCard.fixed("没有符合筛选条件的文件"));
            return;
        }
        for (var group : groups) {
            var rows = new VBox();
            rows.getStyleClass().add("resource-file-list");
            boolean open = group.selectedVersion() || groups.size() == 1 || expanded.contains(group.title());
            if (open) populateFileCard(project, resourceKind, rows, group.files(), openDependency);
            var card = PclCard.foldable(group.title(), rows, open);
            card.onToggle(next -> {
                if (next) {
                    expanded.add(group.title());
                    if (rows.getChildren().isEmpty()) populateFileCard(project, resourceKind, rows, group.files(), openDependency);
                } else {
                    expanded.remove(group.title());
                }
            });
            files.getChildren().add(card);
        }
    }

    private void populateFileCard(Project project, Kind resourceKind, VBox rows, List<CommunityResources.File> files,
                                  Consumer<Project> openDependency) {
        var dependencies = files.stream().flatMap(file -> file.dependencies().stream())
                .filter(id -> !project.id().equals(id)).distinct().toList();
        if (resourceKind != Kind.MODPACK && !dependencies.isEmpty()) {
            var dependencyRows = new VBox(label("正在获取前置资源……", "card-text"));
            var versionHeading = label("版本列表", "resource-dependency-heading");
            VBox.setMargin(versionHeading, new javafx.geometry.Insets(7, 0, 0, 0));
            rows.getChildren().addAll(label("前置资源", "resource-dependency-heading"), dependencyRows, versionHeading);
            var task = new Task<List<Project>>() {
                @Override protected List<Project> call() throws Exception {
                    return resources.dependencies(project.source(), dependencies);
                }
            };
            task.setOnSucceeded(event -> {
                dependencyRows.getChildren().setAll(task.getValue().stream().map(dependency ->
                        projectRow(dependency, () -> openDependency.accept(dependency))).toList());
                if (task.getValue().isEmpty()) dependencyRows.getChildren().add(label("未找到前置资源信息。", "card-text"));
            });
            task.setOnFailed(event -> dependencyRows.getChildren().setAll(label("无法获取前置资源："
                    + VanillaDownloadPage.message(task.getException()), "card-text")));
            Thread.startVirtualThread(task);
        }
        files.forEach(file -> {
            Kind destination = resourceKind == null ? ResourceFileVersions.declaredKind(file) : resourceKind;
            rows.getChildren().add(fileRow(project, file, destination == null ? kind : destination));
        });
    }

    private HBox fileRow(Project project, CommunityResources.File file, Kind resourceKind) {
        String badge = switch (file.releaseType()) {
            case ALPHA -> "Alpha";
            case BETA -> "Beta";
            case RELEASE -> "Release";
        };
        var description = new ArrayList<String>(file.loaders());
        if (!file.title().isBlank() && !file.title().equals(file.name())) description.add(file.title());
        if (!file.dependencies().isEmpty() && resourceKind != Kind.MODPACK) {
            description.add(file.dependencies().size() + " 项前置");
        }
        if (!file.published().equals(java.time.Instant.EPOCH)) {
            description.add("更新于 " + file.published().atZone(java.time.ZoneId.systemDefault()).toLocalDate());
        }
        var entry = row(file.name(), String.join(" · ", description),
                PclArtwork.image("resource-icons/" + badge + ".png", 28), () -> download(file, resourceKind));
        var filePage = resources.filePage(project, file);
        if (file.url() == null) {
            entry.setOnAction(event -> {
                if (filePage != null) links.showDocument(filePage.toString());
            });
            entry.setTooltip(new javafx.scene.control.Tooltip("作者未开放第三方下载，点击前往项目网页"));
        }
        String name = file.name().toLowerCase(Locale.ROOT);
        var actions = new HBox(6, entry);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(entry, Priority.ALWAYS);
        entry.setMinWidth(0);
        if (resourceKind == Kind.MODPACK && file.url() != null && (name.endsWith(".mrpack")
                || project.source() == Source.CURSEFORGE && name.endsWith(".zip"))) {
            entry.setOnAction(event -> installPack.accept(file));
            actions.getChildren().add(button("安装整合包", () -> installPack.accept(file)));
        }
        if (filePage != null) actions.getChildren().add(fileAction("查看更新日志", "lucide/scroll-text",
                () -> links.showDocument(filePage.toString())));
        if (file.url() != null) actions.getChildren().add(fileAction("另存为", "resource-save", () -> download(file, resourceKind)));
        return actions;
    }

    private static javafx.scene.control.Button fileAction(String name, String icon, Runnable action) {
        var button = TaskMonitor.iconButton(name, icon, 14, action);
        button.getStyleClass().add("instance-row-action");
        return button;
    }

    private <T> void load(VBox panel, Callable<T> work, Consumer<T> render) {
        if (request != null) request.cancel();
        panel.getChildren().setAll(PclCard.fixedBody("", new PclLoading("正在加载……")));
        var task = new Task<T>() {
            @Override protected T call() throws Exception { return work.call(); }
        };
        request = task;
        task.setOnSucceeded(event -> {
            if (request != task) return;
            panel.getChildren().clear();
            render.accept(task.getValue());
        });
        task.setOnFailed(event -> panel.getChildren().setAll(PclCard.fixed("加载失败",
                label(VanillaDownloadPage.message(task.getException()), "card-text"),
                button("重试", () -> load(panel, work, render)))));
        Thread.startVirtualThread(task);
    }

    private void download(CommunityResources.File file, Kind resourceKind) {
        if (preparingSave) return;
        preparingSave = true;
        transferActive.accept(true);
        var selected = selectedInstance.get();
        var world = resourceKind == Kind.DATAPACK ? datapackWorld : null;
        var remembered = lastFolders.get(resourceKind);
        var prepare = new Task<Path>() {
            @Override protected Path call() throws Exception {
                if (world != null) return io.pclnt.minecraft.WorldDatapacks.ensureFolder(world);
                if (remembered != null && Files.isDirectory(remembered)) return remembered;
                var folder = ResourceDestination.suggestedFolder(installer, selected, resourceKind, file, isolated);
                Files.createDirectories(folder);
                return folder;
            }
        };
        prepare.setOnSucceeded(event -> {
            destinationReady(file, resourceKind, prepare.getValue(), false, world);
        });
        prepare.setOnFailed(event -> {
            destinationReady(file, resourceKind, null, true, world);
        });
        prepare.setOnCancelled(event -> {
            preparingSave = false;
            transferActive.accept(false);
        });
        Thread.startVirtualThread(prepare);
    }

    private void destinationReady(CommunityResources.File file, Kind resourceKind, Path folder, boolean failed, Path world) {
        boolean started = false;
        try {
            if (details.host().getScene() == null) return;
            if (world != null && !world.equals(datapackWorld)) return;
            if (world != null && failed) {
                notifications.post("无法打开此存档的数据包文件夹。", Notifications.Kind.ERROR);
                return;
            }
            if (failed) notifications.post("无法读取实例目录，请手动选择保存位置。", Notifications.Kind.INFO);
            started = chooseDestination(file, resourceKind, folder, world == null);
        } finally {
            preparingSave = false;
            if (!started) transferActive.accept(false);
        }
    }

    private boolean chooseDestination(CommunityResources.File file, Kind resourceKind, Path folder, boolean remember) {
        var picker = new FileChooser();
        picker.setTitle("选择保存位置");
        if (folder != null && Files.isDirectory(folder)) picker.setInitialDirectory(folder.toFile());
        try {
            picker.setInitialFileName(ResourceDestination.suggestedName(resourceKind, file.name()));
        } catch (java.io.IOException ignored) {
            // The user can choose a valid local name if the source supplies an invalid one.
        }
        var output = picker.showSaveDialog(details.host().getScene().getWindow());
        if (output == null) return false;
        var target = output.toPath().toAbsolutePath().normalize();
        if (remember) lastFolders.put(resourceKind, target.getParent());
        if (Files.exists(target)) {
            notifications.post("目标文件已存在，请选择其他文件名。", Notifications.Kind.ERROR);
            return false;
        }
        var task = new Task<Void>() {
            @Override protected Void call() throws Exception {
                resources.download(file, target);
                return null;
            }
        };
        notifications.post("正在下载 " + file.name(), Notifications.Kind.INFO);
        task.setOnSucceeded(event -> {
            transferActive.accept(false);
            notifications.post("已下载 " + file.name(), Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            transferActive.accept(false);
            notifications.post("下载失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        task.setOnCancelled(event -> transferActive.accept(false));
        tasks.start("下载 " + file.name(), file.name(), task, true, true);
        return true;
    }

    Node projectRow(Project project, Runnable action, Node... actions) {
        var row = button("", action);
        row.setAccessibleText(project.title());
        row.getStyleClass().setAll("button", "help-row", "resource-project");
        row.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setGraphic(projectSummary(project));
        if (actions.length == 0) return row;
        row.getStyleClass().add("resource-project-actions");
        if (actions.length > 2) row.getStyleClass().add("resource-project-favorite-actions");
        var controls = new HBox(2, actions);
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.CENTER_RIGHT);
        StackPane.setMargin(controls, new javafx.geometry.Insets(0, 5, 0, 0));
        var wrapper = new StackPane(row, controls);
        UiMotion.revealActions(wrapper, controls, 18, 42, 72);
        return wrapper;
    }

    private Node[] rowActions(Project project, Kind kind) {
        return new Node[]{translateButton(project, false), favoriteButton(project, kind, false)};
    }

    private javafx.scene.control.Button favoriteButton(Project project, Kind kind, boolean text) {
        var heart = text ? button("收藏", () -> {})
                : TaskMonitor.iconButton("收藏", "lucide/heart", 16, () -> {});
        if (text) heart.getStyleClass().add("pcl-icon-text-button");
        Runnable update = () -> PclArtwork.setIcon(heart,
                favorites.contains(project) ? "lucide/heart-filled" : "lucide/heart", 16);
        javafx.collections.ListChangeListener<ResourceFavorites.Folder> listener = change -> update.run();
        heart.sceneProperty().addListener((observable, previous, scene) -> {
            if (previous != null) favorites.folders().removeListener(listener);
            if (scene != null) {
                favorites.folders().addListener(listener);
                update.run();
            }
        });
        update.run();
        heart.setOnAction(event -> {
            var menu = new javafx.scene.control.ContextMenu();
            for (var folder : favorites.folders()) {
                boolean present = folder.contains(project);
                var action = new javafx.scene.control.MenuItem((present ? "从 " : "添加到 ") + folder.name()
                        + (present ? " 移除" : ""));
                action.setGraphic(menuIcon(present ? "lucide/heart-filled" : "lucide/heart"));
                action.setOnAction(ignored -> {
                    try {
                        favorites.toggle(folder, kind, project);
                        notifications.post((present ? "已取消收藏 " : "已收藏 ") + project.localizedTitle(), Notifications.Kind.SUCCESS);
                    } catch (IOException failure) {
                        notifications.post(VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
                    }
                });
                menu.getItems().add(action);
            }
            menu.show(heart, javafx.geometry.Side.BOTTOM, 0, 1);
        });
        return heart;
    }

    private static Node menuIcon(String graphic) {
        var icon = new Label();
        PclArtwork.setIcon(icon, graphic, 14);
        return icon;
    }

    javafx.scene.control.Button translateButton(Project project, boolean text) {
        var action = text ? button("翻译简介", () -> {})
                : TaskMonitor.iconButton("翻译简介", "lucide/earth", 16, () -> {});
        if (text) {
            action.getStyleClass().add("pcl-icon-text-button");
            PclArtwork.setIcon(action, "lucide/earth", 16);
        }
        action.setDisable(project.summary().isBlank());
        action.setOnAction(event -> {
            action.setDisable(true);
            notifications.post("正在翻译 " + project.localizedTitle() + " 的简介", Notifications.Kind.INFO);
            var translation = new Task<java.util.Optional<String>>() {
                @Override protected java.util.Optional<String> call() throws Exception {
                    return resources.translateDescription(project);
                }
            };
            translation.setOnSucceeded(done -> {
                action.setDisable(false);
                if (action.getScene() == null) return;
                var chinese = translation.getValue();
                PclDialogs.message(action, "简介翻译", chinese.map(summary ->
                        "原文：" + project.summary() + "\n译文：" + summary)
                        .orElse("当前资源的简介暂无译文"), chinese.isEmpty());
            });
            translation.setOnFailed(done -> {
                action.setDisable(false);
                if (action.getScene() != null) PclDialogs.message(action, "简介翻译失败",
                        VanillaDownloadPage.message(translation.getException()), true);
            });
            Thread.startVirtualThread(translation);
        });
        return action;
    }

    private HBox projectSummary(Project project) {
        var icon = PclArtwork.image("resource-icons/NoIcon.png", 50);
        var slot = new StackPane(icon);
        slot.setMinSize(50, 50);
        slot.setPrefSize(50, 50);
        slot.setMaxSize(50, 50);
        var clip = new javafx.scene.shape.Rectangle(50, 50);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        slot.setClip(clip);
        loadIcon(icon, resources.iconSources(project), 0);

        var title = new Label(singleLine(project.localizedTitle()));
        title.getStyleClass().add("resource-title");
        title.setMinWidth(0);
        var titleLine = new HBox(5, title);
        titleLine.getStyleClass().add("resource-title-line");
        titleLine.setAlignment(Pos.CENTER_LEFT);
        if (!project.localizedTitle().equals(project.title())) {
            var original = new Label(singleLine(project.title()));
            original.getStyleClass().add("resource-metric");
            original.setMinWidth(0);
            titleLine.getChildren().add(original);
        }
        var description = new Label(singleLine(project.summary()));
        description.getStyleClass().add("help-row-description");
        description.setTooltip(new javafx.scene.control.Tooltip(project.summary()));
        var line = new HBox(5);
        line.getStyleClass().add("resource-description-line");
        if (!project.categories().isEmpty()) {
            var tags = new HBox(4);
            tags.setAlignment(Pos.CENTER_LEFT);
            tags.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            for (var category : project.categories().stream().limit(2).toList()) {
                var tag = new Label(singleLine(category));
                tag.getStyleClass().add("resource-tag");
                tag.setMaxWidth(100);
                tags.getChildren().add(tag);
            }
            line.getChildren().add(tags);
        }
        line.getChildren().add(description);
        HBox.setHgrow(description, Priority.ALWAYS);
        description.setMinWidth(0);
        line.setMinWidth(0);
        line.setAlignment(Pos.CENTER_LEFT);

        var meta = new HBox(12);
        meta.getStyleClass().add("resource-metadata-line");
        meta.setAlignment(Pos.CENTER_LEFT);
        var versions = project.gameVersions().stream().filter(value -> !value.isBlank()).distinct().toList();
        if (!versions.isEmpty()) {
            String versionsText = versions.size() > 1 ? versions.getFirst() + " – " + versions.getLast() : versions.getFirst();
            meta.getChildren().add(metric(versionsText, "resource-version"));
        }
        if (project.downloads() > 0) {
            var compact = java.text.NumberFormat.getCompactNumberInstance(Locale.SIMPLIFIED_CHINESE,
                    java.text.NumberFormat.Style.SHORT);
            meta.getChildren().add(metric(compact.format(project.downloads()), "download"));
        }
        if (project.updated().length() >= 10) {
            meta.getChildren().add(metric(project.updated().substring(0, 10), "resource-updated"));
        }
        meta.getChildren().add(metric(project.source().toString(), "resource-source"));
        var lines = new VBox(titleLine, line, meta);
        lines.setMinWidth(0);
        HBox.setHgrow(lines, Priority.ALWAYS);
        var box = new HBox(8, slot, lines);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(0);
        return box;
    }

    private static Label metric(String text, String icon) {
        var value = new Label(text);
        value.getStyleClass().add("resource-metric");
        PclArtwork.setIcon(value, icon, 10, 12);
        return value;
    }

    private void loadIcon(ImageView view, List<URI> sources, int index) {
        if (index >= sources.size()) return;
        var uri = sources.get(index);
        var loader = icons.computeIfAbsent(uri, key -> {
            var task = new Task<Image>() {
                @Override protected Image call() throws Exception {
                    return ResourceIconImages.decode(resources.iconBytes(key));
                }
            };
            task.setOnFailed(event -> icons.remove(key, task));
            Thread.startVirtualThread(task);
            return task;
        });
        if (icons.size() > 128) icons.remove(icons.keySet().iterator().next());
        var finished = javafx.concurrent.WorkerStateEvent.WORKER_STATE_SUCCEEDED;
        var failed = javafx.concurrent.WorkerStateEvent.WORKER_STATE_FAILED;
        if (loader.getState() == javafx.concurrent.Worker.State.SUCCEEDED) view.setImage(loader.getValue());
        else if (loader.getState() == javafx.concurrent.Worker.State.FAILED) loadIcon(view, sources, index + 1);
        else {
            javafx.event.EventHandler<javafx.concurrent.WorkerStateEvent> apply = new javafx.event.EventHandler<>() {
                @Override public void handle(javafx.concurrent.WorkerStateEvent event) {
                    loader.removeEventHandler(finished, this);
                    loader.removeEventHandler(failed, this);
                    if (event.getEventType() == finished) view.setImage(loader.getValue());
                    else loadIcon(view, sources, index + 1);
                }
            };
            loader.addEventHandler(finished, apply);
            loader.addEventHandler(failed, apply);
        }
    }

    private static javafx.scene.control.Button row(String title, String summary, Node icon, Runnable action) {
        var row = button("", action);
        row.getStyleClass().setAll("button", "help-row", "resource-file");
        var name = label(singleLine(title), "version-row-title");
        var info = label(singleLine(summary), "help-row-description");
        for (var text : List.of(name, info)) {
            text.setWrapText(false);
            text.setMinWidth(0);
        }
        var labels = new VBox(1, name);
        if (!summary.isBlank()) labels.getChildren().add(info);
        var graphic = new HBox(8, icon, labels);
        graphic.setAlignment(Pos.CENTER_LEFT);
        row.setGraphic(graphic);
        row.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        row.setAccessibleText(title);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static String singleLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static void pagination(VBox panel, int offset, boolean more, java.util.function.IntConsumer open) {
        var previous = TaskMonitor.iconButton("上一页", "resource-page-prev", 13, () -> open.accept(offset - PAGE_SIZE));
        previous.setDisable(offset == 0);
        var first = TaskMonitor.iconButton("首页", "resource-page-first", 13, () -> open.accept(0));
        first.setDisable(offset == 0);
        var next = TaskMonitor.iconButton("下一页", "resource-page-next", 13, () -> open.accept(offset + PAGE_SIZE));
        next.setDisable(!more);
        var paging = new HBox(10, first, previous, new Label("第 " + (offset / PAGE_SIZE + 1) + " 页"), next);
        paging.setAlignment(Pos.CENTER);
        paging.getStyleClass().add("resource-pagination");
        var card = PclCard.fixedBody("", paging);
        card.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        var centered = new StackPane(card);
        panel.getChildren().add(centered);
    }

    private static TextField field(String prompt) {
        var input = new TextField();
        input.setPromptText(prompt);
        input.getStyleClass().add("account-input");
        return input;
    }
}

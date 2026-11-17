package io.pclnt.ui;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.pclnt.minecraft.CommunityResources;
import io.pclnt.minecraft.CommunityResources.File;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.ResourceDestination;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class CommunityFavoritesPage {
    private final ResourceFavorites favorites;
    private final CommunityResources resources;
    private final Function<Kind, CommunityResourcePage> pages;
    private final BiConsumer<ResourceFavorites.Entry, Node> open;
    private final Notifications notifications;
    private final TaskMonitor tasks;
    private final Consumer<Boolean> transfers;
    private final ComboBox<ResourceFavorites.Folder> folder = new ComboBox<>();
    private final TextField query = new TextField();
    private final VBox groups = new VBox(15);
    private final LinkedHashSet<ResourceFavorites.Entry> selected = new LinkedHashSet<>();
    private final HBox actions;
    private final Node view;
    private boolean refreshing;

    CommunityFavoritesPage(ResourceFavorites favorites, CommunityResources resources, Function<Kind, CommunityResourcePage> pages,
                           BiConsumer<ResourceFavorites.Entry, Node> open, Notifications notifications, TaskMonitor tasks,
                           Consumer<Boolean> transfers) {
        this.favorites = favorites;
        this.resources = resources;
        this.pages = pages;
        this.open = open;
        this.notifications = notifications;
        this.tasks = tasks;
        this.transfers = transfers;
        query.setPromptText("搜索收藏夹内容");
        query.getStyleClass().add("account-input");
        query.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(query, Priority.ALWAYS);
        folder.getStyleClass().add("account-input");
        folder.setAccessibleText("选择收藏夹");
        var manage = TaskMonitor.iconButton("管理收藏夹", "instance-settings", 16, () -> {});
        manage.setOnAction(event -> manage(manage));
        var filters = new HBox(9, query, folder, manage);
        filters.setAlignment(Pos.CENTER_LEFT);
        actions = new HBox(10, button("取消选择", () -> { selected.clear(); render(); }),
                button("分享所选资源", () -> share(List.copyOf(selected))),
                button("下载所选资源", this::download));
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new javafx.geometry.Insets(4, 25, 12, 25));
        var pane = new BorderPane(content(PclCard.fixedBody("", filters), groups));
        pane.setBottom(actions);
        view = pane;
        var refresh = (ListChangeListener<ResourceFavorites.Folder>) change -> refresh();
        view.sceneProperty().addListener((observable, previous, scene) -> {
            if (previous != null) favorites.folders().removeListener(refresh);
            if (scene != null) {
                favorites.folders().addListener(refresh);
                refresh();
            }
        });
        query.textProperty().addListener(ignored -> render());
        folder.setOnAction(event -> {
            if (refreshing) return;
            selected.clear();
            render();
        });
        refresh();
    }

    Node view() { return view; }

    void refresh() {
        var active = folder.getValue();
        refreshing = true;
        try {
            folder.getItems().setAll(favorites.folders());
            folder.getSelectionModel().select(active == null ? favorites.folders().getFirst()
                    : favorites.folders().stream().filter(item -> item.id().equals(active.id()))
                            .findFirst().orElse(favorites.folders().getFirst()));
        } finally {
            refreshing = false;
        }
        render();
    }

    private void render() {
        groups.getChildren().clear();
        var active = folder.getValue();
        if (active == null) return;
        String needle = query.getText().strip().toLowerCase(Locale.ROOT);
        var matches = active.entries().stream().filter(entry -> matches(entry, needle)).toList();
        selected.retainAll(active.entries());
        updateActions();
        if (matches.isEmpty()) {
            groups.getChildren().add(PclCard.fixed(needle.isBlank() ? "收藏夹为空" : "搜索结果",
                    label(needle.isBlank() ? "在社区资源列表或详情中点击收藏图标，将项目添加到这里。"
                            : "没有找到匹配的项目。", "card-text")));
        } else if (!needle.isBlank()) {
            groups.getChildren().add(PclCard.foldable("搜索结果 (" + matches.size() + ")", rows(active, matches), true));
        } else {
            for (var kind : Kind.values()) {
                var files = matches.stream().filter(entry -> entry.kind() == kind).toList();
                if (!files.isEmpty()) {
                    String title = kind == Kind.MOD ? "模组" : kind.toString();
                    groups.getChildren().add(PclCard.foldable(title + " (" + files.size() + ")", rows(active, files), true));
                }
            }
        }
    }

    private static boolean matches(ResourceFavorites.Entry entry, String needle) {
        if (needle.isBlank()) return true;
        var project = entry.project();
        return (project.localizedTitle() + " " + project.title() + " " + entry.note())
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private VBox rows(ResourceFavorites.Folder active, List<ResourceFavorites.Entry> list) {
        var rows = new VBox(3);
        for (var entry : list) {
            var project = entry.project();
            var source = pages.apply(entry.kind());
            var note = TaskMonitor.iconButton("编辑备注", "lucide/pencil", 15, () -> PclDialogs.input(view, "编辑备注",
                    "", entry.note(), value -> value.length() <= 300).ifPresent(value ->
                    save(() -> favorites.note(active, entry, value))));
            var remove = TaskMonitor.iconButton("移出收藏夹", "lucide/trash-2", 15,
                    () -> save(() -> favorites.toggle(active, entry.kind(), project)));
            var details = source.projectRow(project, () -> open.accept(entry, view),
                    source.translateButton(project, false), note, remove);
            if (!entry.note().isBlank()) {
                var description = label("备注  " + entry.note(), "help-row-description");
                description.setWrapText(true);
                details = new VBox(details, description);
            }
            var select = new CheckBox();
            select.setAccessibleText("选择 " + project.localizedTitle());
            select.setSelected(selected.contains(entry));
            AppearanceControlMotion.install(select);
            select.selectedProperty().addListener((observable, before, checked) -> {
                if (checked) selected.add(entry);
                else selected.remove(entry);
                updateActions();
            });
            var line = new HBox(5, select, details);
            line.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(details, Priority.ALWAYS);
            rows.getChildren().add(line);
        }
        return rows;
    }

    private void updateActions() {
        actions.setVisible(!selected.isEmpty());
        actions.setManaged(actions.isVisible());
    }

    private void manage(Node anchor) {
        var active = folder.getValue();
        var rename = item("重命名收藏夹", () -> PclDialogs.input(view, "重命名收藏夹", "", active.name(),
                CommunityFavoritesPage::validName).ifPresent(name -> save(() -> favorites.rename(active, name))));
        var remove = item("删除收藏夹", () -> PclDialogs.openMessage(view, "删除收藏夹",
                "确定删除收藏夹 " + active.name() + "？", true, response -> {
                    if (response == ButtonType.OK) save(() -> favorites.delete(active));
                }, ButtonType.OK, ButtonType.CANCEL));
        remove.setDisable(favorites.folders().size() == 1);
        var menu = new ContextMenu(item("新建收藏夹", () -> PclDialogs.input(view, "新建收藏夹", "", "",
                        CommunityFavoritesPage::validName).ifPresent(name -> save(() -> folder.setValue(favorites.create(name, List.of()))))),
                rename, remove, item("分享收藏夹", () -> share(active.entries())), item("导入收藏夹", () -> importFavorites(active)));
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    private static MenuItem item(String title, Runnable run) {
        var item = new MenuItem(title);
        item.setOnAction(event -> run.run());
        return item;
    }

    private static boolean validName(String value) {
        return !value.isBlank() && value.strip().length() <= 64;
    }

    private void share(List<ResourceFavorites.Entry> entries) {
        if (entries.isEmpty()) {
            notifications.post("没有可分享的收藏", Notifications.Kind.INFO);
            return;
        }
        var ids = entries.stream().map(entry -> entry.project().id()).distinct().toList();
        var clipboard = new javafx.scene.input.ClipboardContent();
        clipboard.putString(new Gson().toJson(ids));
        if (javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard)) {
            notifications.post("已复制收藏夹分享码", Notifications.Kind.SUCCESS);
        }
    }

    private void importFavorites(ResourceFavorites.Folder destination) {
        PclDialogs.input(view, "导入收藏夹", "输入收藏夹分享码", "", text -> !shareIds(text).isEmpty()).ifPresent(code -> {
            var mode = PclDialogs.choose(view, "导入收藏夹", "合并到当前收藏夹", List.of("合并到当前收藏夹", "导入为新收藏夹"));
            if (mode.isEmpty()) return;
            String name = mode.get().equals("合并到当前收藏夹") ? null
                    : PclDialogs.input(view, "输入新收藏夹名称", "", "", CommunityFavoritesPage::validName).orElse(null);
            if (mode.get().equals("导入为新收藏夹") && name == null) return;
            var ids = shareIds(code);
            var known = favorites.folders().stream().flatMap(item -> item.entries().stream()).toList();
            var task = new Task<List<ResourceFavorites.Entry>>() {
                @Override protected List<ResourceFavorites.Entry> call() throws InterruptedException {
                    var loaded = new ArrayList<ResourceFavorites.Entry>();
                    for (String id : ids) {
                        var prior = known.stream().filter(entry -> entry.project().id().equals(id)).findFirst();
                        if (prior.isPresent()) {
                            var entry = prior.get();
                            loaded.add(new ResourceFavorites.Entry(entry.kind(), entry.project(), ""));
                            continue;
                        }
                        try {
                            var entry = resources.findProject(id);
                            loaded.add(new ResourceFavorites.Entry(entry.kind(), entry.project(), ""));
                        } catch (IOException | RuntimeException ignored) {
                            // Keep resolving the rest of the shared entries.
                        }
                    }
                    return loaded;
                }
            };
            notifications.post("正在读取收藏夹分享码", Notifications.Kind.INFO);
            task.setOnSucceeded(event -> {
                var entries = task.getValue();
                if (entries.isEmpty()) {
                    notifications.post("分享码里的资源均无法获取", Notifications.Kind.ERROR);
                } else {
                    save(() -> {
                        if (name == null) favorites.merge(destination, entries);
                        else folder.setValue(favorites.create(name, entries));
                        notifications.post("已导入 " + entries.size() + " 项收藏"
                                + (entries.size() < ids.size() ? "，跳过 " + (ids.size() - entries.size()) + " 项" : ""),
                                Notifications.Kind.SUCCESS);
                    });
                }
            });
            Thread.startVirtualThread(task);
        });
    }

    private static List<String> shareIds(String code) {
        if (code.length() > 16384) return List.of();
        try {
            var values = new Gson().fromJson(code, String[].class);
            if (values == null) return List.of();
            return java.util.Arrays.stream(values).filter(value -> value != null && value.matches("[a-zA-Z0-9_-]{1,64}"))
                    .distinct().toList();
        } catch (JsonParseException invalid) {
            return List.of();
        }
    }

    private void download() {
        var projects = List.copyOf(selected);
        if (projects.isEmpty()) return;
        PclDialogs.openMessage(view, "批量下载收藏", "查找这些资源共同支持的游戏版本，并将文件保存至同一目录。",
                true, response -> { if (response == ButtonType.OK) findVersions(projects); }, ButtonType.OK, ButtonType.CANCEL);
    }

    private void findVersions(List<ResourceFavorites.Entry> projects) {
        var task = new Task<Map<ResourceFavorites.Entry, List<File>>>() {
            @Override protected Map<ResourceFavorites.Entry, List<File>> call() throws Exception {
                var files = new LinkedHashMap<ResourceFavorites.Entry, List<File>>();
                for (var entry : projects) {
                    var candidates = resources.projectFiles(entry.project()).stream().filter(file -> file.url() != null)
                            .sorted(Comparator.comparing(File::published).reversed()).toList();
                    if (candidates.isEmpty()) throw new IOException(entry.project().localizedTitle() + " 没有可下载的版本");
                    files.put(entry, candidates);
                }
                return files;
            }
        };
        notifications.post("正在获取资源版本", Notifications.Kind.INFO);
        task.setOnSucceeded(event -> { if (view.getScene() != null) chooseVersion(task.getValue()); });
        task.setOnFailed(event -> notifications.post(VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR));
        Thread.startVirtualThread(task);
    }

    private void chooseVersion(Map<ResourceFavorites.Entry, List<File>> files) {
        var loaderSets = files.entrySet().stream().filter(entry -> entry.getKey().kind() == Kind.MOD)
                .map(entry -> entry.getValue().stream().flatMap(file -> file.loaders().stream()).distinct().toList()).toList();
        String loader = "";
        if (!loaderSets.isEmpty()) {
            var loaders = common(loaderSets);
            if (loaders.isEmpty()) {
                notifications.post("所选模组没有共同支持的加载器", Notifications.Kind.ERROR);
                return;
            }
            var choice = PclDialogs.choose(view, "选择 Mod 加载器", loaders.getFirst(), loaders);
            if (choice.isEmpty()) return;
            loader = choice.get();
        }
        String requested = loader;
        var filtered = new LinkedHashMap<ResourceFavorites.Entry, List<File>>();
        files.forEach((entry, candidates) -> filtered.put(entry, entry.kind() != Kind.MOD ? candidates : candidates.stream()
                .filter(file -> file.loaders().contains(requested)).toList()));
        var versions = common(filtered.values().stream().map(candidates -> candidates.stream()
                .flatMap(file -> file.gameVersions().stream()).distinct().toList()).toList());
        if (versions.isEmpty()) {
            notifications.post("所选资源没有共同支持的游戏版本", Notifications.Kind.ERROR);
            return;
        }
        PclDialogs.choose(view, "选择游戏版本", versions.getFirst(), versions).ifPresent(version -> {
            var picker = new DirectoryChooser();
            picker.setTitle("选择资源保存目录");
            var folder = picker.showDialog(view.getScene().getWindow());
            if (folder == null) return;
            var download = new LinkedHashMap<ResourceFavorites.Entry, File>();
            filtered.forEach((entry, candidates) -> candidates.stream().filter(file -> file.gameVersions().contains(version))
                    .findFirst().ifPresent(file -> download.put(entry, file)));
            runDownloads(download, folder.toPath());
        });
    }

    private static List<String> common(List<List<String>> values) {
        var result = new LinkedHashSet<>(values.getFirst());
        values.forEach(result::retainAll);
        return List.copyOf(result);
    }

    private void runDownloads(Map<ResourceFavorites.Entry, File> queue, Path folder) {
        if (queue.isEmpty()) return;
        var task = new Task<Integer>() {
            @Override protected Integer call() throws Exception {
                int done = 0, failures = 0;
                for (var entry : queue.entrySet()) {
                    if (isCancelled()) break;
                    var file = entry.getValue();
                    updateMessage(file.name());
                    try {
                        Path target = folder.resolve(ResourceDestination.suggestedName(entry.getKey().kind(), file.name()));
                        if (Files.exists(target)) throw new IOException("文件已存在");
                        resources.download(file, target);
                    } catch (IOException failure) {
                        failures++;
                        notifications.post(file.name() + " 下载失败：" + VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
                    }
                    updateProgress(++done, queue.size());
                }
                if (failures == queue.size()) throw new IOException("全部资源下载失败");
                return done - failures;
            }
        };
        transfers.accept(true);
        task.setOnSucceeded(event -> {
            transfers.accept(false);
            notifications.post("已下载 " + task.getValue() + " / " + queue.size() + " 项收藏资源", Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            transfers.accept(false);
            notifications.post(VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        task.setOnCancelled(event -> transfers.accept(false));
        tasks.start("下载收藏资源", queue.size() + " 个文件", task, true, true);
    }

    @FunctionalInterface private interface Edit { void save() throws IOException; }

    private void save(Edit edit) {
        try {
            edit.save();
        } catch (IOException | IllegalArgumentException failure) {
            notifications.post(VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
        }
    }
}

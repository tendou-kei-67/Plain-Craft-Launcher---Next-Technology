package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.CommunityResources;
import io.pclnt.minecraft.WorldDatapacks;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class InstanceResourcesPage {
    record DatapackServices(CommunityResources catalog, Callable<String> gameVersion, TaskMonitor tasks) {}
    private final Path instance;
    private final Path directory;
    private final Kind kind;
    private final Parent owner;
    private final HostServices links;
    private final Notifications notifications;
    private final BooleanSupplier instanceBusy;
    private final Consumer<Kind> browse;
    private final Consumer<Boolean> resourceWrite;
    private final CommunityResources catalog;
    private final Consumer<CommunityResources.Project> openProject;
    private final Callable<String> gameVersion;
    private final TaskMonitor tasks;
    private final TextField search = new TextField();
    private final VBox rows = new VBox(3);
    private final ComboBox<String> filter = new ComboBox<>();
    private final SimpleBooleanProperty working = new SimpleBooleanProperty();
    private final SimpleBooleanProperty exporting = new SimpleBooleanProperty();
    private final Label identificationStatus = label("", "help-row-description");
    private final DatapackSelection datapackSelection;
    private List<Path> files = List.of();
    private Map<Path, WorldDatapacks.Archive> archiveInfo = Map.of();
    private Comparator<WorldDatapacks.Archive> datapackOrder = Comparator.comparing(
            this::datapackTitle, String.CASE_INSENSITIVE_ORDER);
    private Task<List<WorldDatapacks.Archive>> scan;
    private Task<Identification> identification;
    private Map<WorldDatapacks.Archive, CommunityResources.InstalledResource> matches = Map.of();
    private Map<String, CommunityResources.File> updates = Map.of();
    private record Identification(Map<WorldDatapacks.Archive, CommunityResources.InstalledResource> matches,
                                  List<CommunityResources.Source> unavailable, CommunityResources.DatapackUpdates updates,
                                  boolean versionUnavailable) {}

    InstanceResourcesPage(Path instance, Kind kind, Parent owner, HostServices links, Notifications notifications,
                          BooleanSupplier instanceBusy, Consumer<Kind> browse, Consumer<Boolean> resourceWrite) {
        this(instance, kind, owner, links, notifications, instanceBusy, browse, resourceWrite, (DatapackServices) null, null);
    }

    InstanceResourcesPage(Path instance, Kind kind, Parent owner, HostServices links, Notifications notifications,
                          BooleanSupplier instanceBusy, Consumer<Kind> browse, Consumer<Boolean> resourceWrite,
                          CommunityResources catalog, Consumer<CommunityResources.Project> openProject) {
        this(instance, kind, owner, links, notifications, instanceBusy, browse, resourceWrite,
                catalog == null ? null : new DatapackServices(catalog, null, null), openProject);
    }

    InstanceResourcesPage(Path instance, Kind kind, Parent owner, HostServices links, Notifications notifications,
                          BooleanSupplier instanceBusy, Consumer<Kind> browse, Consumer<Boolean> resourceWrite,
                          DatapackServices services, Consumer<CommunityResources.Project> openProject) {
        this.instance = instance;
        this.kind = kind;
        this.owner = owner;
        this.links = links;
        this.notifications = notifications;
        this.instanceBusy = instanceBusy;
        this.browse = browse;
        this.resourceWrite = resourceWrite;
        this.catalog = services == null ? null : services.catalog();
        this.openProject = openProject;
        this.gameVersion = services == null ? null : services.gameVersion();
        this.tasks = services == null ? null : services.tasks();
        datapackSelection = kind == Kind.DATAPACK ? new DatapackSelection() : null;
        directory = instance.resolve(switch (kind) {
            case MOD -> "mods";
            case RESOURCEPACK -> "resourcepacks";
            case SHADER -> "shaderpacks";
            case DATAPACK -> "datapacks";
            default -> throw new IllegalArgumentException("Unsupported instance resource");
        });
    }

    Node view() {
        return view(null);
    }

    Node view(Runnable back) {
        search.setPromptText("搜索资源名称或文件名");
        search.getStyleClass().addAll("account-input", "help-search");
        search.textProperty().addListener(ignored -> render());
        filter.getItems().setAll("全部", "已启用", "已禁用");
        if (datapackSelection != null) filter.getItems().add("不可用");
        filter.getSelectionModel().selectFirst();
        filter.getStyleClass().add("account-input");
        filter.valueProperty().addListener(ignored -> render());
        var add = button("从文件安装", this::importFile);
        add.disableProperty().bind(working);
        var actions = new FlowPane(10, 8, button("打开文件夹", this::openFolder), add,
                button("下载新资源", () -> browse.accept(kind)), button("刷新", this::refresh));
        var card = switchable() ? PclCard.fixed(kind.toString(), filter, rows) : PclCard.fixed(kind.toString(), rows);
        if (datapackSelection != null) {
            var export = button("导出信息", this::exportDatapacks);
            export.disableProperty().bind(working.or(exporting));
            export.setTooltip(new javafx.scene.control.Tooltip("导出详细的数据包信息"));
            actions.getChildren().addAll(datapackSelection.selectAll, export);
            if (catalog != null) {
                SettingsPage.bindVisible(identificationStatus, identificationStatus.textProperty().isNotEmpty());
                actions.getChildren().add(identificationStatus);
            }
            var sort = new MenuButton("排序：资源名称");
            sort.getStyleClass().addAll("account-input", "pcl-icon-text-button");
            PclButtonSkin.animateStandalone(sort);
            PclArtwork.setIcon(sort, "lucide/arrow-up-down", 14);
            sort.getItems().setAll(
                    sortOption(sort, "文件名", Comparator.comparing(WorldDatapacks.Archive::fileName, String.CASE_INSENSITIVE_ORDER)),
                    sortOption(sort, "资源名称", Comparator.comparing(this::datapackTitle, String.CASE_INSENSITIVE_ORDER)),
                    sortOption(sort, "加入时间", Comparator.comparing(WorldDatapacks.Archive::created).reversed()),
                    sortOption(sort, "文件大小", Comparator.comparingLong(WorldDatapacks.Archive::size).reversed()));
            var sortRow = new HBox(sort);
            sortRow.setAlignment(Pos.CENTER_RIGHT);
            card.setHeaderContent(sortRow);
        }
        refresh();
        var management = PclCard.fixed("管理", actions);
        Node page;
        if (back == null) {
            page = content(search, management, card);
        } else {
            var heading = new HBox(8, TaskMonitor.iconButton("返回存档信息", "lucide/arrow-left", 16, back),
                    label(instance.getFileName() + " · 数据包", "card-title"));
            heading.setAlignment(Pos.CENTER_LEFT);
            page = content(heading, search, management, card);
        }
        if (datapackSelection == null) return page;
        var view = new BorderPane(page);
        view.setBottom(datapackSelection.bar);
        BorderPane.setMargin(datapackSelection.bar, new Insets(0, 25, 12, 25));
        view.setOnDragOver(event -> {
            if (!working.get() && event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        view.setOnDragDropped(event -> {
            var files = event.getDragboard().getFiles();
            boolean accepted = !files.isEmpty() && !busy();
            if (accepted) installDatapacks(files.stream().map(java.io.File::toPath).toList());
            event.setDropCompleted(accepted);
            event.consume();
        });
        return view;
    }

    private void refresh() {
        if (working.get()) return;
        if (kind == Kind.DATAPACK) {
            refreshDatapacks();
            return;
        }
        try {
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    files = entries.filter(this::isResource)
                            .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                            .toList();
                }
            } else {
                files = List.of();
            }
            render();
        } catch (IOException error) {
            files = List.of();
            rows.getChildren().setAll(label("读取资源失败：" + VanillaDownloadPage.message(error), "card-text"));
        }
    }

    private void refreshDatapacks() {
        if (scan != null) scan.cancel();
        if (identification != null) {
            identification.cancel();
            identification = null;
        }
        identificationStatus.setText("");
        updates = Map.of();
        var task = new Task<List<WorldDatapacks.Archive>>() {
            @Override protected List<WorldDatapacks.Archive> call() throws IOException { return WorldDatapacks.describe(instance); }
        };
        scan = task;
        task.setOnSucceeded(event -> {
            if (scan != task) return;
            scan = null;
            archiveInfo = task.getValue().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    WorldDatapacks.Archive::path, java.util.function.Function.identity()));
            files = task.getValue().stream().map(WorldDatapacks.Archive::path).toList();
            matches = matches.entrySet().stream().filter(entry -> entry.getKey().equals(archiveInfo.get(entry.getKey().path())))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            render();
            identifyDatapacks(task.getValue());
        });
        task.setOnFailed(event -> {
            if (scan != task) return;
            scan = null;
            files = List.of();
            archiveInfo = Map.of();
            matches = Map.of();
            datapackSelection.showing(files);
            rows.getChildren().setAll(label("读取数据包失败：" + VanillaDownloadPage.message(task.getException()), "card-text"));
        });
        Thread.startVirtualThread(task);
    }

    private void identifyDatapacks(List<WorldDatapacks.Archive> archives) {
        if (catalog == null) return;
        var candidates = archives.stream().filter(archive -> archive.state() != WorldDatapacks.State.UNAVAILABLE)
                .filter(archive -> !matches.containsKey(archive)).toList();
        var known = matches;
        if (candidates.isEmpty() && (known.isEmpty() || gameVersion == null)) return;
        var task = new Task<Identification>() {
            @Override protected Identification call() throws InterruptedException {
                var local = new java.util.LinkedHashMap<WorldDatapacks.Archive, WorldDatapacks.Fingerprint>();
                for (var archive : candidates) {
                    try {
                        local.put(archive, WorldDatapacks.fingerprint(instance, archive, catalog.canIdentifyCurseForge()));
                    } catch (IOException skipped) {
                        // The local list remains usable when a pack changes or becomes unreadable.
                    }
                }
                var identified = catalog.identifyDatapacks(List.copyOf(local.values()));
                var found = new java.util.LinkedHashMap<>(known);
                local.forEach((archive, fingerprint) -> {
                    var match = identified.matches().get(fingerprint.sha1());
                    if (match != null) found.put(archive, match);
                });
                var updates = new CommunityResources.DatapackUpdates(Map.of(), List.of());
                boolean versionUnavailable = false;
                if (!found.isEmpty() && gameVersion != null) {
                    String version = "";
                    try {
                        version = gameVersion.call();
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Exception ignored) {
                        // Identified projects remain useful even if the instance metadata cannot be read.
                    }
                    versionUnavailable = version == null || version.isBlank();
                    if (!versionUnavailable) updates = catalog.datapackUpdates(List.copyOf(found.values()), version);
                }
                return new Identification(Map.copyOf(found), identified.unavailable(), updates, versionUnavailable);
            }
        };
        identification = task;
        identificationStatus.setText("正在识别数据包并检查更新…");
        task.setOnSucceeded(event -> {
            if (identification != task) return;
            identification = null;
            var result = task.getValue();
            matches = result.matches();
            updates = result.updates().candidates();
            identificationStatus.setText(result.versionUnavailable() ? "无法读取实例游戏版本"
                    : !result.unavailable().isEmpty() || !result.updates().unavailable().isEmpty() ? "部分资源来源暂不可用" : "");
            if (!updates.isEmpty() && !filter.getItems().contains("可更新")) filter.getItems().add("可更新");
            else if (updates.isEmpty() && filter.getSelectionModel().getSelectedIndex() != 4) filter.getItems().remove("可更新");
            render();
        });
        task.setOnFailed(event -> {
            if (identification != task) return;
            identification = null;
            identificationStatus.setText("社区项目识别失败");
        });
        Thread.startVirtualThread(task);
    }

    private MenuItem sortOption(MenuButton control, String name, Comparator<WorldDatapacks.Archive> order) {
        var item = new MenuItem(name);
        item.setOnAction(event -> {
            datapackOrder = order;
            control.setText("排序：" + name);
            render();
        });
        return item;
    }

    private boolean switchable() { return kind == Kind.MOD || kind == Kind.DATAPACK; }

    private boolean isResource(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return kind != Kind.MOD && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        }
        var name = filename(path).toLowerCase(Locale.ROOT);
        if (kind == Kind.MOD) return rawName(name).matches(".+\\.(jar|zip|litemod)");
        return name.endsWith(".zip");
    }

    private void render() {
        var term = search.getText().strip().toLowerCase(Locale.ROOT);
        int mode = filter.getSelectionModel().getSelectedIndex();
        var matching = files.stream().filter(file -> filename(file).toLowerCase(Locale.ROOT).contains(term)
                        || matchesTitle(file, term))
                .filter(file -> matchesState(file, mode));
        if (datapackSelection != null) matching = matching.sorted(Comparator.comparing(archiveInfo::get, datapackOrder));
        var visible = matching.toList();
        if (datapackSelection != null) datapackSelection.showing(visible);
        rows.getChildren().clear();
        for (var file : visible) {
            if (datapackSelection != null) {
                rows.getChildren().add(datapackRow(archiveInfo.get(file)));
                continue;
            }
            var name = filename(file);
            String enabledName = kind == Kind.MOD ? rawName(name) : name;
            boolean disabled = !enabledName.equals(name);
            var title = label(enabledName, "card-text");
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            var row = new HBox(10, title);
            row.setAlignment(Pos.CENTER_LEFT);
            if (switchable()) {
                var toggle = button(disabled ? "启用" : "禁用", () -> toggle(file, disabled));
                toggle.setAccessibleText(toggle.getText() + " " + enabledName);
                toggle.disableProperty().bind(working);
                row.getChildren().addAll(label(disabled ? "已禁用" : "已启用", "help-row-description"), toggle);
            }
            rows.getChildren().add(row);
        }
        if (rows.getChildren().isEmpty()) rows.getChildren().add(label(
                files.isEmpty() ? "暂无" + kind : "没有匹配的资源", "card-text"));
    }

    private boolean matchesTitle(Path file, String term) {
        if (datapackSelection == null) return false;
        var match = matches.get(archiveInfo.get(file));
        return match != null && (match.project().localizedTitle().toLowerCase(Locale.ROOT).contains(term)
                || match.project().title().toLowerCase(Locale.ROOT).contains(term));
    }

    private String datapackTitle(WorldDatapacks.Archive archive) {
        var match = matches.get(archive);
        return match == null ? archive.resourceName() : match.project().localizedTitle();
    }

    private boolean matchesState(Path file, int mode) {
        if (!switchable() || mode <= 0) return true;
        if (datapackSelection == null) return disabled(file) == (mode == 2);
        var state = archiveInfo.get(file).state();
        return switch (mode) {
            case 1 -> state == WorldDatapacks.State.ENABLED;
            case 2 -> state == WorldDatapacks.State.DISABLED;
            case 3 -> state == WorldDatapacks.State.UNAVAILABLE;
            default -> updateFor(archiveInfo.get(file)) != null;
        };
    }

    private Node datapackRow(WorldDatapacks.Archive archive) {
        var file = archive.path();
        var state = archive.state();
        String name = archive.fileName();
        var match = matches.get(archive);
        var available = updateFor(archive);
        var selected = datapackSelection.checkbox(file);
        var title = label(match == null ? name : match.project().localizedTitle(), "card-text");
        title.setMinWidth(0);
        String description = match == null || match.version().isBlank() ? archive.metadata().description()
                : name + " · " + match.version();
        if (available != null) description += " · 可更新";
        String caption = switch (state) {
            case ENABLED -> description.isBlank() ? "已启用" : description;
            case DISABLED -> description.isBlank() ? "已禁用" : "已禁用 · " + description;
            case UNAVAILABLE -> "不可用";
        };
        var summary = label(caption, "help-row-description");
        summary.setWrapText(false);
        summary.setMinWidth(0);
        var text = new VBox(2, title, summary);
        text.setMinWidth(0);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        var information = TaskMonitor.iconButton("详情 " + name, "lucide/info", 15, () -> showDatapackInfo(archive));
        var reveal = TaskMonitor.iconButton("打开文件位置 " + name, "lucide/folder-open", 16, () ->
                revealFile(() -> WorldDatapacks.locate(instance, file)));
        var actions = new HBox(2, information, reveal);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (available != null) {
            var update = TaskMonitor.iconButton("更新 " + name, "download", 15, () -> updateDatapacks(List.of(file)));
            update.disableProperty().bind(working);
            actions.getChildren().add(update);
        }
        if (state != WorldDatapacks.State.UNAVAILABLE) {
            boolean disabled = state == WorldDatapacks.State.DISABLED;
            var toggle = TaskMonitor.iconButton((disabled ? "启用 " : "禁用 ") + name,
                    disabled ? "lucide/circle-check" : "lucide/circle-minus", 15, () -> toggle(file, disabled));
            toggle.disableProperty().bind(working);
            actions.getChildren().add(toggle);
        }
        var remove = TaskMonitor.iconButton("移至回收站 " + name, "lucide/trash-2", 14, () ->
                PclDialogs.openMessage(owner, "删除数据包", "将 " + name + " 移至回收站？", true, response -> {
                    if (response == ButtonType.OK) {
                        writeDatapack(() -> { WorldDatapacks.moveToTrash(instance, file); return null; }, "已移至回收站");
                    }
                }, ButtonType.OK, ButtonType.CANCEL));
        remove.disableProperty().bind(working);
        actions.getChildren().add(remove);
        var row = new HBox(8, selected, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("datapack-row");
        row.setMinHeight(42);
        UiMotion.revealActions(row, actions, 35, 85, 72);
        row.setOnMouseClicked(event -> {
            for (Node target = event.getPickResult().getIntersectedNode(); target != null && target != row;
                    target = target.getParent()) {
                if (target instanceof javafx.scene.control.ButtonBase) return;
            }
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) selected.fire();
            else if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) showDatapackInfo(archive);
        });
        return row;
    }

    private void showDatapackInfo(WorldDatapacks.Archive archive) {
        if (archive.error() != null) {
            PclDialogs.message(owner, "无法读取数据包", archive.fileName() + "\n" + archive.error(), true);
            return;
        }
        var match = matches.get(archive);
        if (match != null && openProject != null) {
            openProject.accept(match.project());
            return;
        }
        var details = new StringBuilder();
        if (!archive.metadata().description().isBlank()) details.append(archive.metadata().description()).append("\n\n");
        details.append("文件名  ").append(archive.fileName()).append("\n文件大小  ").append(archive.size()).append(" 字节");
        if (archive.metadata().packFormat() != null) details.append("\n数据包格式  ").append(archive.metadata().packFormat());
        PclDialogs.message(owner, archive.resourceName(), details.toString(), false);
    }

    private boolean disabled(Path file) {
        String name = filename(file);
        return !rawName(name).equals(name);
    }

    private void toggle(Path file, boolean disabled) {
        if (kind == Kind.DATAPACK) {
            writeDatapack(() -> { WorldDatapacks.setEnabled(instance, file, disabled); return null; },
                    disabled ? "已启用数据包" : "已禁用数据包");
            return;
        }
        if (busy()) return;
        String name = filename(file);
        Path target = directory.resolve(disabled ? rawName(name) : name + ".disabled");
        try {
            if (!disabled && Files.exists(directory.resolve(name + ".old"), LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(name + ".old");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("资源文件已移动或删除");
            Files.move(file, target);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            notifications.post("已有同名资源：" + error.getFile(), Notifications.Kind.ERROR);
        } catch (IOException error) {
            notifications.post("切换状态失败：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
        }
        refresh();
    }

    private void importFile() {
        if (busy()) return;
        var chooser = new FileChooser();
        chooser.setTitle("选择要安装的" + kind);
        chooser.getExtensionFilters().add(kind == Kind.MOD
                ? new FileChooser.ExtensionFilter("模组文件", "*.jar", "*.zip", "*.litemod")
                : new FileChooser.ExtensionFilter("ZIP 文件", "*.zip"));
        if (kind == Kind.DATAPACK) {
            var selected = chooser.showOpenMultipleDialog(owner.getScene().getWindow());
            if (selected != null) installDatapacks(selected.stream().map(java.io.File::toPath).toList());
            return;
        }
        var selected = chooser.showOpenDialog(owner.getScene().getWindow());
        if (selected == null || busy()) return;
        Path source = selected.toPath();
        if (!isResource(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            notifications.post("所选文件不属于当前资源类型。", Notifications.Kind.ERROR);
            return;
        }
        working.set(true);
        resourceWrite.accept(true);
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                ensureDirectory();
                Path target = directory.resolve(source.getFileName());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("已有同名资源");
                Path temporary = Files.createTempFile(directory, ".pcl-nt-", ".part");
                try {
                    Files.copy(source, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, target);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            working.set(false);
            resourceWrite.accept(false);
            refresh();
            notifications.post("已安装 " + filename(source), Notifications.Kind.SUCCESS);
        });
        task.setOnFailed(event -> {
            working.set(false);
            resourceWrite.accept(false);
            refresh();
            notifications.post("安装资源失败：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void installDatapacks(List<Path> files) {
        writeDatapackBatch("安装", () -> WorldDatapacks.install(instance, files));
    }

    private void exportDatapacks() {
        var text = new ButtonType("TXT 格式");
        var csv = new ButtonType("CSV 格式");
        var format = PclDialogs.waitForMessage(owner, "选择导出模式", "请选择数据包信息的导出格式。",
                false, text, csv, ButtonType.CANCEL);
        if (format != text && format != csv) return;
        boolean spreadsheet = format == csv;
        String suffix = spreadsheet ? ".csv" : ".txt";
        var chooser = new FileChooser();
        chooser.setTitle("选择保存位置");
        chooser.setInitialFileName(instance.getFileName() + "的数据包信息" + suffix);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.getText(), "*" + suffix));
        var file = chooser.showSaveDialog(owner.getScene().getWindow());
        if (file == null) return;
        exporting.set(true);
        var matched = matches;
        var task = new Task<Void>() {
            @Override protected Void call() throws IOException {
                WorldDatapacks.export(instance, file.toPath(), spreadsheet, matched);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            exporting.set(false);
            notifications.post("已导出 " + file.getName(), Notifications.Kind.SUCCESS);
            revealFile(() -> file.toPath().toAbsolutePath());
        });
        task.setOnFailed(event -> {
            exporting.set(false);
            PclDialogs.message(owner, "导出数据包信息失败", VanillaDownloadPage.message(task.getException()), true);
        });
        Thread.startVirtualThread(task);
    }

    private void revealFile(Callable<Path> lookup) {
        var task = new Task<Path>() {
            @Override protected Path call() throws Exception {
                var path = lookup.call();
                if (Desktop.isDesktopSupported()) {
                    var desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                        desktop.browseFileDirectory(path.toFile());
                        return null;
                    }
                }
                if (com.sun.jna.Platform.isWindows()) {
                    new ProcessBuilder(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "explorer.exe").toString(),
                            "/select," + path).start();
                    return null;
                }
                return path.getParent();
            }
        };
        task.setOnSucceeded(event -> {
            if (task.getValue() != null && links != null) links.showDocument(task.getValue().toUri().toASCIIString());
        });
        task.setOnFailed(event -> notifications.post("打开文件位置失败：" + VanillaDownloadPage.message(task.getException()),
                Notifications.Kind.ERROR));
        Thread.startVirtualThread(task);
    }

    private void writeDatapackBatch(String action, Callable<WorldDatapacks.BatchResult> task) {
        runDatapackTask(task, result -> reportDatapackBatch(action, result));
    }

    private CommunityResources.File updateFor(WorldDatapacks.Archive archive) {
        if (archive == null) return null;
        var installed = matches.get(archive);
        return installed == null ? null : updates.get(installed.sha1());
    }

    private void updateDatapacks(List<Path> selection) {
        var candidates = new java.util.ArrayList<WorldDatapacks.Update>();
        for (var path : selection) {
            var archive = archiveInfo.get(path);
            var update = updateFor(archive);
            if (update != null) candidates.add(new WorldDatapacks.Update(archive, matches.get(archive).sha1(), update));
        }
        if (candidates.isEmpty()) return;
        String files = candidates.stream().limit(8).map(item -> item.archive().fileName() + " → " + item.file().name())
                .collect(java.util.stream.Collectors.joining("\n"));
        PclDialogs.openMessage(owner, "更新数据包", files, false, answer -> {
            if (answer != ButtonType.OK) return;
            var task = new Task<WorldDatapacks.UpdateResult>() {
                @Override protected WorldDatapacks.UpdateResult call() throws Exception {
                    updateProgress(0, candidates.size());
                    return WorldDatapacks.update(instance, candidates, catalog, done -> updateProgress(done, candidates.size()));
                }
            };
            runDatapackTask("更新数据包", task, result -> {
                reportDatapackBatch("更新", result.batch());
                if (!result.backups().isEmpty()) notifications.post("旧数据包备份位于 " + result.backups().getFirst().getParent()
                        + (result.backups().size() > 1 ? " 等位置" : ""), Notifications.Kind.INFO);
            });
        }, ButtonType.OK, ButtonType.CANCEL);
    }

    private void reportDatapackBatch(String action, WorldDatapacks.BatchResult result) {
        datapackSelection.selected.clear();
        datapackSelection.update();
        String summary = "已" + action + " " + result.changed() + " 个数据包";
        if (result.failures().isEmpty()) {
            notifications.post(summary, Notifications.Kind.SUCCESS);
            return;
        }
        summary += "，" + result.failures().size() + " 个失败";
        notifications.post(summary, Notifications.Kind.ERROR);
        if (owner.getScene() != null) {
            String details = result.failures().stream().limit(10).map(failure -> filename(failure.path()) + "："
                    + (failure.cause() instanceof java.nio.file.FileAlreadyExistsException
                    ? "已有同名文件" : VanillaDownloadPage.message(failure.cause())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (result.failures().size() > 10) details += "\n其余 " + (result.failures().size() - 10) + " 个失败";
            PclDialogs.message(owner, "批量" + action + "结果", summary + "\n\n" + details, true);
        }
    }

    private void writeDatapack(Callable<Void> action, String success) {
        runDatapackTask(action, unused -> notifications.post(success, Notifications.Kind.SUCCESS));
    }

    private <T> void runDatapackTask(Callable<T> action, Consumer<T> onSuccess) {
        runDatapackTask(null, new Task<T>() {
            @Override protected T call() throws Exception { return action.call(); }
        }, onSuccess);
    }

    private <T> void runDatapackTask(String title, Task<T> task, Consumer<T> onSuccess) {
        if (busy()) return;
        if (scan != null) {
            scan.cancel();
            scan = null;
        }
        working.set(true);
        resourceWrite.accept(true);
        task.setOnSucceeded(event -> {
            finishDatapackWrite();
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            finishDatapackWrite();
            notifications.post(VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        if (title != null && tasks != null) tasks.start(title + " · " + instance.getFileName(), "数据包", task, false, false);
        else Thread.startVirtualThread(task);
    }

    private void finishDatapackWrite() {
        working.set(false);
        resourceWrite.accept(false);
        refresh();
    }

    private final class DatapackSelection {
        private final LinkedHashSet<Path> selected = new LinkedHashSet<>();
        private List<Path> visibleFiles = List.of();
        private final Label caption = label("", "card-text");
        private final Button selectAll = button("全选", this::selectAll);
        private final Button enable = batchButton("启用", "lucide/circle-check", () -> changeEnabled(true));
        private final Button disable = batchButton("禁用", "lucide/circle-minus", () -> changeEnabled(false));
        private final Button updatePacks = batchButton("更新", "download", () -> updateDatapacks(List.copyOf(selected)));
        private final Button delete = batchButton("删除", "lucide/trash-2", this::delete);
        private final PclCard bar;

        DatapackSelection() {
            var controls = new HBox(8, enable, disable, delete);
            if (gameVersion != null) controls.getChildren().add(2, updatePacks);
            controls.setAlignment(Pos.CENTER);
            caption.setAlignment(Pos.CENTER);
            caption.setMaxWidth(Double.MAX_VALUE);
            bar = PclCard.fixedBody("", new VBox(6, caption, controls));
            bar.getStyleClass().add("datapack-selection");
            bar.managedProperty().bind(bar.visibleProperty());
            working.addListener(ignored -> update());
            update();
        }

        void showing(List<Path> files) {
            visibleFiles = files;
            selected.retainAll(files);
            update();
        }

        CheckBox checkbox(Path file) {
            var box = new CheckBox();
            box.setAccessibleText("选择 " + filename(file));
            box.setSelected(selected.contains(file));
            box.disableProperty().bind(working);
            box.setOnAction(event -> {
                if (box.isSelected()) selected.add(file);
                else selected.remove(file);
                update();
            });
            return box;
        }

        private void selectAll() {
            if (selected.containsAll(visibleFiles)) selected.clear();
            else selected.addAll(visibleFiles);
            render();
        }

        private void update() {
            caption.setText("已选择 " + selected.size() + " 个文件");
            bar.setVisible(!selected.isEmpty());
            selectAll.setText(!visibleFiles.isEmpty() && selected.containsAll(visibleFiles) ? "取消全选" : "全选");
            selectAll.setDisable(working.get() || visibleFiles.isEmpty());
            enable.setDisable(working.get() || targets(true).isEmpty());
            disable.setDisable(working.get() || targets(false).isEmpty());
            updatePacks.setDisable(working.get() || selected.stream().noneMatch(path -> updateFor(archiveInfo.get(path)) != null));
            delete.setDisable(working.get() || selected.isEmpty());
        }

        private List<Path> targets(boolean enabled) {
            var from = enabled ? WorldDatapacks.State.DISABLED : WorldDatapacks.State.ENABLED;
            return selected.stream().filter(file -> archiveInfo.containsKey(file) && archiveInfo.get(file).state() == from).toList();
        }

        private void changeEnabled(boolean enabled) {
            var candidates = targets(enabled);
            if (!candidates.isEmpty()) writeDatapackBatch(enabled ? "启用" : "禁用",
                    () -> WorldDatapacks.setEnabled(instance, candidates, enabled));
        }

        private void delete() {
            var targets = List.copyOf(selected);
            if (!targets.isEmpty() && PclDialogs.confirm(owner, "删除数据包", "将选中的 " + targets.size() + " 个数据包移至回收站？")) {
                writeDatapackBatch("删除", () -> WorldDatapacks.moveToTrash(instance, targets));
            }
        }

        private Button batchButton(String caption, String icon, Runnable action) {
            var control = button(caption, action);
            PclArtwork.setIcon(control, icon, 15);
            return control;
        }
    }

    private boolean busy() {
        if (!working.get() && !instanceBusy.getAsBoolean()) return false;
        notifications.post("请等当前游戏、安装或补全任务结束。", Notifications.Kind.INFO);
        return true;
    }

    private void openFolder() {
        try {
            ensureDirectory();
            links.showDocument(directory.toUri().toASCIIString());
        } catch (IOException error) {
            notifications.post("无法打开文件夹：" + VanillaDownloadPage.message(error), Notifications.Kind.ERROR);
        }
    }

    private void ensureDirectory() throws IOException {
        if (kind == Kind.DATAPACK) {
            WorldDatapacks.ensureFolder(instance);
            return;
        }
        if (!Files.isDirectory(instance)) throw new IOException("实例目录已移动或删除");
        Files.createDirectories(directory);
    }

    private static String filename(Path file) { return file.getFileName().toString(); }

    private static String rawName(String name) {
        int suffix = name.toLowerCase(Locale.ROOT).endsWith(".disabled") ? ".disabled".length()
                : name.toLowerCase(Locale.ROOT).endsWith(".old") ? ".old".length() : 0;
        return name.substring(0, name.length() - suffix);
    }
}

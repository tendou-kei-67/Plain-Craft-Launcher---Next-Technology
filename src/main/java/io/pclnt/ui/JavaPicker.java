package io.pclnt.ui;

import io.pclnt.minecraft.JavaInstallations;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;

final class JavaPicker {
    private record Choice(String path, String caption) {
        @Override public String toString() { return caption; }
    }

    private final ComboBox<Choice> choices = new ComboBox<>();
    private final StringProperty preference;
    private final Supplier<Path> gameRoot;
    private final Choice automatic;
    private List<JavaInstallations.Installation> installed = List.of();
    private boolean applying;
    private boolean scanned;
    private boolean busy;

    static javafx.scene.layout.HBox field(String title, String fallback, StringProperty preference, Supplier<Path> gameRoot) {
        return new JavaPicker(fallback, preference, gameRoot).field(title);
    }

    private JavaPicker(String fallback, StringProperty preference, Supplier<Path> gameRoot) {
        this.preference = preference;
        this.gameRoot = gameRoot;
        automatic = new Choice("", fallback);
        choices.setAccessibleText("Java 列表");
        choices.getStyleClass().add("account-input");
        choices.setMinWidth(0);
        choices.setCellFactory(list -> cell());
        choices.setButtonCell(cell());
        choices.setOnAction(event -> {
            if (!applying && choices.getValue() != null) preference.set(choices.getValue().path());
        });
        preference.addListener(ignored -> render());
        choices.sceneProperty().addListener((observable, previous, scene) -> {
            if (scene != null && !scanned) scan();
        });
        choices.setOnShowing(event -> {
            if (!scanned) scan();
        });
        render();
    }

    private javafx.scene.layout.HBox field(String title) {
        var refresh = TaskMonitor.iconButton("重新扫描 Java", "help-refresh", 14, this::scan);
        var browse = TaskMonitor.iconButton("添加 Java", "lucide/folder-open", 16, this::browse);
        return SettingsPage.field(title, choices, refresh, browse);
    }

    private void scan() {
        if (busy) return;
        Path root = gameRoot.get();
        String selectedPath = preference.get();
        var task = new Task<List<JavaInstallations.Installation>>() {
            @Override protected List<JavaInstallations.Installation> call() {
                var found = new ArrayList<>(JavaInstallations.scan(root));
                if (!selectedPath.isBlank()) {
                    try {
                        var selected = JavaInstallations.inspect(Path.of(selectedPath));
                        if (!found.contains(selected)) found.add(selected);
                    } catch (java.io.IOException | java.nio.file.InvalidPathException ignored) {
                        // Keep the user's saved entry visible even if it is currently unavailable.
                    }
                }
                return List.copyOf(found);
            }
        };
        busy = true;
        task.setOnSucceeded(event -> {
            busy = false;
            scanned = true;
            installed = task.getValue();
            render();
        });
        task.setOnFailed(event -> busy = false);
        Thread.startVirtualThread(task);
    }

    private void browse() {
        var chooser = new FileChooser();
        chooser.setTitle("选择 Java 可执行文件");
        var selected = chooser.showOpenDialog(choices.getScene().getWindow());
        if (selected == null) return;
        var task = new Task<JavaInstallations.Installation>() {
            @Override protected JavaInstallations.Installation call() throws Exception {
                return JavaInstallations.inspect(selected.toPath());
            }
        };
        task.setOnSucceeded(event -> {
            var item = task.getValue();
            if (!installed.contains(item)) {
                var updated = new ArrayList<>(installed);
                updated.add(item);
                installed = List.copyOf(updated);
            }
            preference.set(item.executable().toString());
            render();
        });
        task.setOnFailed(event -> {
            if (choices.getScene() != null) PclDialogs.message(choices, "添加 Java 失败",
                    VanillaDownloadPage.message(task.getException()), true);
        });
        Thread.startVirtualThread(task);
    }

    private void render() {
        applying = true;
        try {
            var items = new ArrayList<Choice>();
            items.add(automatic);
            for (var entry : installed) items.add(new Choice(entry.executable().toString(), entry.toString()));
            String saved = preference.get();
            Choice active = items.stream().filter(item -> item.path().equals(saved)).findFirst().orElse(null);
            if (active == null) {
                active = new Choice(saved, "Java  " + saved);
                items.add(active);
            }
            choices.getItems().setAll(items);
            choices.getSelectionModel().select(active);
        } finally {
            applying = false;
        }
    }

    private static ListCell<Choice> cell() {
        return new ListCell<>() {
            @Override protected void updateItem(Choice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.caption());
                setTooltip(empty || item == null || item.path().isBlank() ? null : new Tooltip(item.path()));
            }
        };
    }
}

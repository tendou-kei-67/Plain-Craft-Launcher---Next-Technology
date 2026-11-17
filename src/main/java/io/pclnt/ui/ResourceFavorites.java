package io.pclnt.ui;

import com.google.gson.Gson;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.CommunityResources.Project;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

final class ResourceFavorites {
    record Entry(Kind kind, Project project, String note) {
        Entry {
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(project);
            note = note == null ? "" : note.strip();
        }
    }

    record Folder(UUID id, String name, List<Entry> entries) {
        Folder {
            java.util.Objects.requireNonNull(id);
            name = name.strip();
            if (name.isBlank() || name.length() > 64) throw new IllegalArgumentException("收藏夹名称应为 1 至 64 个字符");
            entries = List.copyOf(entries);
        }
        @Override public String toString() { return name; }
        boolean contains(Project project) { return entries.stream().anyMatch(entry -> same(entry.project(), project)); }
    }

    private final Gson json = new Gson();
    private final Path file;
    private final ObservableList<Folder> folders = FXCollections.observableArrayList();
    private final ObservableList<Folder> view = FXCollections.unmodifiableObservableList(folders);
    private boolean unreadable;

    ResourceFavorites(Path directory, Notifications notifications) {
        file = directory.resolve("community-favorites.json");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                var saved = json.fromJson(reader, Folder[].class);
                if (saved == null) throw new IOException("收藏夹数据为空");
                folders.addAll(List.of(saved));
            } catch (IOException | RuntimeException failure) {
                unreadable = true;
                notifications.post("无法读取收藏夹，已保留原文件。", Notifications.Kind.ERROR);
            }
        }
        if (folders.isEmpty()) folders.add(new Folder(UUID.randomUUID(), "默认", List.of()));
    }

    ObservableList<Folder> folders() { return view; }

    boolean contains(Project project) { return folders.stream().anyMatch(folder -> folder.contains(project)); }

    void toggle(Folder folder, Kind kind, Project project) throws IOException {
        var current = resolve(folder.id());
        var next = new ArrayList<>(current.entries());
        if (!next.removeIf(item -> same(item.project(), project))) next.add(new Entry(kind, project, ""));
        replace(current, new Folder(current.id(), current.name(), next));
    }

    void note(Folder folder, Entry entry, String text) throws IOException {
        var current = resolve(folder.id());
        var updated = current.entries().stream().map(item -> same(item.project(), entry.project())
                ? new Entry(item.kind(), item.project(), text) : item).toList();
        replace(current, new Folder(current.id(), current.name(), updated));
    }

    Folder create(String name, List<Entry> entries) throws IOException {
        var next = new Folder(UUID.randomUUID(), name, entries);
        var updated = new ArrayList<>(folders);
        updated.add(next);
        save(updated);
        return next;
    }

    void merge(Folder folder, List<Entry> entries) throws IOException {
        var current = resolve(folder.id());
        var updated = new ArrayList<>(current.entries());
        for (var item : entries) {
            if (updated.stream().noneMatch(existing -> same(existing.project(), item.project()))) updated.add(item);
        }
        replace(current, new Folder(current.id(), current.name(), updated));
    }

    void rename(Folder folder, String name) throws IOException {
        var current = resolve(folder.id());
        replace(current, new Folder(current.id(), name, current.entries()));
    }

    void delete(Folder folder) throws IOException {
        if (folders.size() == 1) throw new IOException("请至少保留一个收藏夹");
        save(folders.stream().filter(item -> !item.id().equals(folder.id())).toList());
    }

    private Folder resolve(UUID id) throws IOException {
        return folders.stream().filter(folder -> folder.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("收藏夹已删除"));
    }

    private void replace(Folder current, Folder next) throws IOException {
        var updated = new ArrayList<>(folders);
        updated.set(updated.indexOf(current), next);
        save(updated);
    }

    private void save(List<Folder> updated) throws IOException {
        if (unreadable) throw new IOException("请先恢复收藏夹数据");
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), "community-favorites-", ".tmp");
        try {
            Files.writeString(temporary, json.toJson(updated));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            folders.setAll(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean same(Project first, Project second) {
        return first.source() == second.source() && first.id().equals(second.id());
    }
}

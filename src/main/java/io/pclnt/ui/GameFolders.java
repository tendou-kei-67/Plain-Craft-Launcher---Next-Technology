package io.pclnt.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.pclnt.minecraft.VanillaInstaller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Folder names are overrides; built-in paths can still be rediscovered after a reset. */
final class GameFolders {
    record Folder(Path path, String name, boolean builtin, boolean renamed) {}

    private final Path base;
    private final Path defaultRoot;
    private final Path officialRoot;
    private final Function<String, String> read;
    private final BiConsumer<String, String> write;
    private final LinkedHashMap<Path, String> names = new LinkedHashMap<>();
    private Path active;

    GameFolders(Path base, Function<String, String> read, BiConsumer<String, String> write) {
        this.base = base;
        this.defaultRoot = base.resolve(".minecraft");
        this.officialRoot = officialRoot();
        this.read = read;
        this.write = write;
        try {
            if (JsonParser.parseString(read.apply("LaunchFolders")) instanceof JsonObject object) {
                object.entrySet().forEach(entry -> {
                    try {
                        if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                            names.put(normalize(Path.of(entry.getKey())), entry.getValue().getAsString());
                        }
                    } catch (InvalidPathException ignored) {
                    }
                });
            }
        } catch (com.google.gson.JsonParseException ignored) {
        }
        try {
            String saved = read.apply("LaunchFolderSelect");
            if (!saved.isBlank()) active = normalize(Path.of(saved));
        } catch (InvalidPathException ignored) {
        }
        if (active == null || !Files.isDirectory(active)) active = fallback();
    }

    Path active() { return active; }
    Path defaultRoot() { return defaultRoot; }
    Path base() { return base; }

    void select(Path path) {
        active = normalize(path);
        write.accept("LaunchFolderSelect", active.toString());
    }

    List<Folder> entries() {
        var discovered = new LinkedHashMap<Path, String>();
        if (Files.isDirectory(defaultRoot)) discovered.put(defaultRoot, ".minecraft");
        if (hasVersions(base)) discovered.put(base, "当前文件夹");
        try (var children = Files.list(base)) {
            children.filter(path -> !path.equals(defaultRoot) && hasVersions(path))
                    .sorted().forEach(path -> discovered.put(path, suggestedName(path)));
        } catch (IOException ignored) {
        }
        if (!officialRoot.equals(defaultRoot) && hasVersions(officialRoot)) {
            discovered.putIfAbsent(officialRoot, "官方启动器文件夹");
        }
        var result = new LinkedHashMap<Path, Folder>();
        discovered.forEach((path, name) -> result.put(path,
                new Folder(path, names.getOrDefault(path, name), true, names.containsKey(path))));
        names.forEach((path, name) -> result.putIfAbsent(path, new Folder(path, name, false, false)));
        return List.copyOf(result.values());
    }

    Path fallback() {
        return entries().stream().map(Folder::path).filter(Files::isDirectory).findFirst().orElse(defaultRoot);
    }

    Path registeredPath(Path path) {
        return entries().stream().map(Folder::path).filter(existing -> sameDirectory(existing, path))
                .findFirst().orElse(normalize(path));
    }

    void name(Path path, String name) {
        names.put(path, name);
        saveNames();
    }

    void forget(Path path) {
        names.remove(path);
        saveNames();
    }

    private void saveNames() {
        var json = new JsonObject();
        names.forEach((path, name) -> json.addProperty(path.toString(), name));
        write.accept("LaunchFolders", json.toString());
    }

    String selectedVersion(Path path) { return read.apply(versionKey(path)); }
    void selectedVersion(Path path, String id) { write.accept(versionKey(path), id); }

    private String versionKey(Path path) {
        return path.equals(defaultRoot) ? "LaunchVersion" : "LaunchVersion." + pathKey(path);
    }

    String instancePrefix(Path path) {
        return path.equals(defaultRoot) ? "Instance." : "FolderInstance." + pathKey(path) + ".";
    }

    private static String pathKey(Path path) {
        String value = normalize(path).toString();
        if (java.io.File.separatorChar == '\\') value = value.toLowerCase(Locale.ROOT);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String suggestedName(Path path) {
        Path name = path.getFileName();
        if (name == null) return "";
        if (name.toString().equals(".minecraft") && path.getParent() != null && path.getParent().getFileName() != null) {
            name = path.getParent().getFileName();
        }
        String value = name.toString();
        return value.substring(0, Math.min(30, value.length()));
    }

    static boolean validName(String name) {
        return !name.isBlank() && name.length() <= 30 && !name.contains(">") && !name.contains("|");
    }

    static void createRoot(Path path) throws IOException {
        Files.createDirectories(path.resolve("versions"));
        var profile = new JsonObject();
        profile.addProperty("icon", "Grass");
        profile.addProperty("name", "PCL-NT");
        profile.addProperty("lastVersionId", "latest-release");
        profile.addProperty("type", "latest-release");
        profile.addProperty("lastUsed", java.time.Instant.now().toString());
        var profiles = new JsonObject();
        profiles.add("PCL-NT", profile);
        var json = new JsonObject();
        json.add("profiles", profiles);
        json.addProperty("selectedProfile", "PCL-NT");
        json.addProperty("clientToken", java.util.UUID.randomUUID().toString().replace("-", ""));
        try {
            Files.writeString(path.resolve("launcher_profiles.json"), json.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
        }
    }

    static Path locateExisting(Path selected) throws IOException {
        var path = selected.toRealPath();
        if (!Files.isDirectory(path) || !Files.isReadable(path)) throw new IOException("无法访问该文件夹");
        if (!hasVersions(path)) {
            if (hasVersions(path.resolve(".minecraft"))) {
                path = path.resolve(".minecraft").toRealPath();
            } else {
                try (var children = Files.list(path)) {
                    path = children.filter(GameFolders::hasVersions).sorted().findFirst().orElse(path).toRealPath();
                }
            }
        }
        if (path.getParent() == null) throw new IOException("请选择游戏文件夹，而不是磁盘根目录");
        if (path.toString().contains("!") || path.toString().contains(";")) {
            throw new IOException("Minecraft 文件夹路径中不能含有感叹号或分号");
        }
        if (!Files.isReadable(path) || !Files.isWritable(path)) throw new IOException("没有该文件夹的读写权限");
        return path;
    }

    static boolean sameDirectory(Path first, Path second) {
        if (normalize(first).equals(normalize(second))) return true;
        try { return Files.isSameFile(first, second); }
        catch (IOException ignored) { return false; }
    }

    static List<String> sharedModpackVersions(VanillaInstaller installer) throws IOException {
        if (countFiles(installer.root().resolve("mods"), 3) < 3) return List.of();
        try (var directories = Files.list(installer.root().resolve("versions"))) {
            if (directories.filter(Files::isDirectory).limit(4).count() > 3) return List.of();
        }
        var found = new java.util.ArrayList<String>();
        for (var instance : installer.instances()) {
            try {
                if (installer.describe(instance).loaders().stream().noneMatch(loader -> !loader.equals("OptiFine"))) continue;
            } catch (IOException | com.google.gson.JsonParseException ignored) {
                continue;
            }
            if (countFiles(instance.directory().resolve("mods"), 1) > 0) return List.of();
            found.add(instance.id());
        }
        return List.copyOf(found);
    }

    private static long countFiles(Path directory, int limit) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).limit(limit).count();
        }
    }

    private static Path normalize(Path path) { return path.toAbsolutePath().normalize(); }
    private static boolean hasVersions(Path path) { return Files.isDirectory(path.resolve("versions")); }

    private static Path officialRoot() {
        var home = Path.of(System.getProperty("user.home"));
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return normalize(Path.of(System.getenv().getOrDefault("APPDATA", home.resolve("AppData/Roaming").toString()))
                    .resolve(".minecraft"));
        }
        return normalize(home.resolve(os.contains("mac") ? "Library/Application Support/minecraft" : ".minecraft"));
    }
}

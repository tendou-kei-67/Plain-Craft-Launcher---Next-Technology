package io.pclnt.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ModpackExport {
    public enum Content {
        MODS("模组", "mods"), DISABLED_MODS("已禁用的模组", "mods"),
        MOD_SETTINGS("模组设置", "config", "defaultconfigs", "kubejs", "scripts"),
        RESOURCE_PACKS("资源包", "resourcepacks"), SHADERS("光影包", "shaderpacks"),
        GAME_SETTINGS("游戏本体设置", "options.txt", "optionsof.txt", "optionsshaders.txt"),
        SAVES("存档", "saves"), SCREENSHOTS("截图", "screenshots");

        private final String label;
        private final List<String> paths;

        Content(String label, String... paths) {
            this.label = label;
            this.paths = List.of(paths);
        }

        @Override public String toString() { return label; }
    }

    private final VanillaInstaller installer;

    public ModpackExport(VanillaInstaller installer) {
        this.installer = installer;
    }

    public void write(VanillaInstaller.Instance instance, String name, String version, Set<Content> included, Path output)
            throws IOException, InterruptedException {
        write(instance, instance.directory(), name, version, included, output);
    }

    public void write(VanillaInstaller.Instance instance, Path gameDirectory, String name, String version,
                      Set<Content> included, Path output) throws IOException, InterruptedException {
        if (name.isBlank() || version.isBlank()) throw new IOException("整合包名称和版本号不能为空");
        var root = gameDirectory.toRealPath();
        var target = output.toAbsolutePath().normalize();
        var parent = target.getParent().toRealPath();
        for (var content : included) {
            if (content.paths.stream().anyMatch(path -> parent.startsWith(root.resolve(path))
                    || parent.resolve(target.getFileName()).equals(root.resolve(path)))) {
                throw new IOException("请选择导出内容目录以外的保存位置");
            }
        }
        var index = index(instance, name, version);
        var pending = Files.createTempFile(parent, ".pcl-nt-export-", ".part");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(pending))) {
                zip.putNextEntry(new ZipEntry("modrinth.index.json"));
                zip.write(index.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (var content : Content.values()) {
                    if (!included.contains(content)) continue;
                    for (var path : content.paths) writeFiles(zip, root, root.resolve(path), content);
                }
            }
            VanillaInstaller.replace(pending, parent.resolve(target.getFileName()));
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private JsonObject index(VanillaInstaller.Instance instance, String name, String version) throws IOException {
        var installed = installer.launchVersion(instance);
        var dependencies = new JsonObject();
        String game = installed.client().id();
        dependencies.addProperty("minecraft", game);
        var info = installer.describe(instance);
        var metadata = installed.metadata();
        var arguments = metadata.getAsJsonObject("arguments");
        var gameArguments = arguments == null ? new JsonArray() : VanillaInstaller.array(arguments, "game");
        for (String loader : info.loaders()) {
            switch (loader) {
                case "Fabric" -> dependencies.addProperty("fabric-loader", libraryVersion(metadata, "net.fabricmc:fabric-loader:"));
                case "Forge" -> dependencies.addProperty("forge", loaderVersion(metadata, gameArguments,
                        "--fml.forgeVersion", "net.minecraftforge:forge:", "net.minecraftforge:fmlloader:", game));
                case "NeoForge" -> dependencies.addProperty("neoforge", loaderVersion(metadata, gameArguments,
                        "--fml.neoForgeVersion", "net.neoforged:neoforge:", "net.neoforged:forge:", game));
                case "OptiFine" -> { }
                default -> throw new IOException("暂不支持导出使用 " + loader + " 的实例");
            }
        }
        if (dependencies.size() > 2) throw new IOException("该实例包含多个加载器，无法导出为整合包");
        var index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("name", name);
        index.addProperty("versionId", version);
        index.add("dependencies", dependencies);
        index.add("files", new JsonArray());
        return index;
    }

    private static String loaderVersion(JsonObject metadata, JsonArray arguments, String flag,
                                        String primary, String alternate, String game) throws IOException {
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).isJsonPrimitive() && arguments.get(i + 1).isJsonPrimitive()
                    && flag.equals(arguments.get(i).getAsString())) return arguments.get(i + 1).getAsString();
        }
        String version = findLibraryVersion(metadata, primary);
        if (version.isEmpty()) version = libraryVersion(metadata, alternate);
        return version.startsWith(game + "-") ? version.substring(game.length() + 1) : version;
    }

    private static String libraryVersion(JsonObject metadata, String prefix) throws IOException {
        String version = findLibraryVersion(metadata, prefix);
        if (version.isBlank()) throw new IOException("无法读取加载器版本");
        return version;
    }

    private static String findLibraryVersion(JsonObject metadata, String prefix) {
        for (var entry : VanillaInstaller.array(metadata, "libraries")) {
            String coordinate = VanillaInstaller.string(entry.getAsJsonObject(), "name");
            if (coordinate.startsWith(prefix)) return coordinate.substring(prefix.length()).split(":", 2)[0];
        }
        return "";
    }

    private static void writeFiles(ZipOutputStream zip, Path root, Path directory, Content category)
            throws IOException, InterruptedException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var path = iterator.next();
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var relative = root.relativize(path);
                String basename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (basename.equals("session.lock")) continue;
                if (category == Content.MODS || category == Content.DISABLED_MODS) {
                    if (relative.getNameCount() != 2 || basename.startsWith("optifine")) continue;
                    boolean disabled = basename.matches(".+\\.(jar|zip|litemod)\\.(disabled|old)");
                    if (category == Content.DISABLED_MODS ? !disabled : !basename.matches(".+\\.(jar|zip|litemod)")) continue;
                }
                for (var piece : relative) ResourceDestination.suggestedName(CommunityResources.Kind.MODPACK, piece.toString());
                String name = relative.toString().replace(java.io.File.separatorChar, '/');
                zip.putNextEntry(new ZipEntry("overrides/" + name));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }
}

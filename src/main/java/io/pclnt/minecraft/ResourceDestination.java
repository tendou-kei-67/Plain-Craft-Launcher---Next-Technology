package io.pclnt.minecraft;

import io.pclnt.minecraft.CommunityResources.File;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.VanillaInstaller.Instance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;

public final class ResourceDestination {
    private ResourceDestination() {}

    public static Path suggestedFolder(VanillaInstaller installer, Instance selected, Kind kind, File file)
            throws IOException {
        return suggestedFolder(installer, selected, kind, file, id -> true);
    }

    public static Path suggestedFolder(VanillaInstaller installer, Instance selected, Kind kind, File file,
                                       java.util.function.Predicate<String> isolated) throws IOException {
        if (kind == Kind.MODPACK || kind == Kind.WORLD) return installer.root();
        if (selected != null && suitable(installer, selected, kind, file)) {
            return folder(installer.gameDirectory(selected, isolated.test(selected.id())), kind);
        }
        var candidates = new ArrayList<Path>();
        for (var instance : installer.installed()) {
            if (suitable(installer, instance, kind, file)) {
                candidates.add(folder(installer.gameDirectory(instance, isolated.test(instance.id())), kind));
            }
        }
        return candidates.stream().max(Comparator.comparing(ResourceDestination::modified)).orElse(installer.root());
    }

    public static String suggestedName(Kind kind, String original) throws IOException {
        String name = kind == Kind.MOD ? original.replace('~', '-') : original;
        if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")) {
            throw new IOException("文件名是 Windows 保留名称");
        }
        return VanillaInstaller.fileName(name);
    }

    private static Path folder(Path root, Kind kind) {
        return switch (kind) {
            case MOD -> root.resolve("mods");
            case RESOURCEPACK -> root.resolve("resourcepacks");
            case SHADER -> root.resolve("shaderpacks");
            case DATAPACK -> root;
            default -> throw new IllegalArgumentException("不支持的实例资源类型");
        };
    }

    private static boolean suitable(VanillaInstaller installer, Instance instance, Kind kind, File file) {
        if (kind != Kind.MOD && kind != Kind.DATAPACK) return true;
        try {
            var version = installer.launchVersion(instance);
            if (!file.gameVersions().isEmpty() && !file.gameVersions().contains(version.client().id())) {
                return false;
            }
            if (kind == Kind.DATAPACK) return true;
            for (var library : VanillaInstaller.array(version.metadata(), "libraries")) {
                var coordinate = VanillaInstaller.string(library.getAsJsonObject(), "name");
                String loader = "";
                if (coordinate.startsWith("net.fabricmc:fabric-loader:")) loader = "fabric";
                else if (coordinate.startsWith("net.minecraftforge:forge:")
                        || coordinate.startsWith("net.minecraftforge:fmlloader:")) loader = "forge";
                else if (coordinate.startsWith("net.neoforged:neoforge:")
                        || coordinate.startsWith("net.neoforged.fancymodloader:loader:")) loader = "neoforge";
                else if (coordinate.startsWith("org.quiltmc:quilt-loader:")) loader = "quilt";
                else if (coordinate.startsWith("com.mumfrey:liteloader:")) loader = "liteloader";
                if (!loader.isEmpty() && (file.loaders().isEmpty() || file.loaders().contains(loader))) return true;
            }
        } catch (IOException | com.google.gson.JsonParseException ignored) {
            // An unreadable instance cannot be offered as a compatible destination.
        }
        return false;
    }

    private static FileTime modified(Path folder) {
        try {
            return Files.getLastModifiedTime(folder);
        } catch (IOException ignored) {
            return FileTime.fromMillis(Long.MIN_VALUE);
        }
    }
}

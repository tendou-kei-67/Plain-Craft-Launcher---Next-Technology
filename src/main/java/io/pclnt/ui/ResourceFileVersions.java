package io.pclnt.ui;

import io.pclnt.minecraft.CommunityResources.File;
import io.pclnt.minecraft.CommunityResources.Kind;
import io.pclnt.minecraft.CommunityResources.Source;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Builds PCL's version filters and file cards from a project's published versions. */
final class ResourceFileVersions {
    private static final String SNAPSHOT = "快照版", LEGACY = "远古版", OTHER = "其他";
    private static final Pattern RELEASE = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}(?:\\.[0-9]{1,4})?");
    private static final Pattern WEEKLY_SNAPSHOT = Pattern.compile("[0-9]{2}w[0-9]{2}[a-z].*");
    private static final String PREVIEW = " 预览版";
    private static final Map<String, String> LOADER_NAMES = Map.of(
            "fabric", "Fabric", "forge", "Forge", "neoforge", "NeoForge", "quilt", "Quilt",
            "liteloader", "LiteLoader", "rift", "Rift", "modloader", "ModLoader");
    private static final Comparator<String> VERSION_ORDER = ResourceFileVersions::compareVersions;
    private static final java.util.Set<String> PLUGIN_LOADERS = java.util.Set.of("bukkit", "folia", "paper", "purpur", "spigot");
    record Card(String title, List<File> files, boolean selectedVersion) {}
    private record Group(String version, String loader) {
        String title() { return loader.isEmpty() ? version : loader + " " + version; }
    }

    private final List<File> files;
    private final String targetVersion;
    private final String targetLoader;
    private final boolean splitLoaders;
    private final Kind kind;
    private final Layout layout;
    private final List<String> choices;

    private record Layout(boolean groupByMinor, boolean foldOld) {
        String name(String version) {
            if (version.isBlank()) return OTHER;
            if (WEEKLY_SNAPSHOT.matcher(version).matches()) return SNAPSHOT;
            String base = version.split("-", 2)[0];
            if (!RELEASE.matcher(base).matches()) return LEGACY;
            var parts = base.split("\\.");
            if (foldOld && parts[0].equals("1") && Integer.parseInt(parts[1]) < 20) return LEGACY;
            if (groupByMinor) return parts[0] + "." + parts[1];
            if (!base.equals(version)) return base + PREVIEW;
            return version;
        }
    }

    ResourceFileVersions(List<File> files, Source source, Kind kind, String targetVersion, String targetLoader) {
        this.kind = kind;
        this.files = files.stream().filter(file -> accepts(file, source, kind))
                .sorted(Comparator.comparing(File::published).reversed()).toList();
        this.targetVersion = targetVersion;
        this.targetLoader = targetLoader.toLowerCase(Locale.ROOT);
        this.splitLoaders = (kind == Kind.MOD || kind == null) && this.files.stream().flatMap(file -> loaders(file).stream())
                .distinct().limit(2).count() > 1;
        var versions = this.files.stream().flatMap(file -> versions(file).stream())
                .filter(version -> !version.isBlank()).distinct().toList();
        var layouts = List.of(new Layout(false, false), new Layout(true, false),
                new Layout(false, true), new Layout(true, true));
        Layout chosen = layouts.getLast();
        List<String> labels = List.of();
        for (var candidate : layouts) {
            chosen = candidate;
            labels = versions.stream().map(candidate::name).distinct().sorted(VERSION_ORDER).toList();
            if (labels.size() < 9) break;
        }
        layout = chosen;
        choices = labels.size() < 2 ? List.of() : labels;
    }

    List<String> choices() { return choices; }

    String initialChoice() {
        return !targetVersion.isBlank() && files.stream().anyMatch(file -> versions(file).contains(targetVersion))
                && choices.contains(layout.name(targetVersion)) ? layout.name(targetVersion) : "";
    }

    List<Card> cards(String choice) {
        var exactLayout = new Layout(false, false);
        var groups = new TreeMap<Group, LinkedHashSet<File>>(Comparator.comparing(Group::version, VERSION_ORDER)
                .thenComparing(Group::loader));
        var target = new LinkedHashSet<File>();
        var targetGroup = new Group(targetVersion, loaderName(targetLoader));
        for (var file : files) {
            var versions = versions(file);
            var fileLoaders = loaders(file);
            for (var version : versions) {
                if (!choice.isEmpty() && !choice.equals(layout.name(version))) continue;
                String name = exactLayout.name(version);
                var prefixes = splitLoaders && RELEASE.matcher(name).matches() && !fileLoaders.isEmpty()
                        ? fileLoaders.stream().map(ResourceFileVersions::loaderName).toList() : List.of("");
                for (var prefix : prefixes) {
                    groups.computeIfAbsent(new Group(name, prefix), key -> new LinkedHashSet<>()).add(file);
                }
                if (!targetVersion.isEmpty() && version.equals(targetVersion)
                        && (targetLoader.isEmpty() || fileLoaders.contains(targetLoader))) target.add(file);
            }
        }
        var cards = new ArrayList<Card>();
        if (!target.isEmpty()) {
            cards.add(new Card(targetGroup.title() + "（所选版本）", List.copyOf(target), true));
            var matching = groups.get(targetGroup);
            if (matching != null && target.containsAll(matching)) groups.remove(targetGroup);
        }
        groups.forEach((group, grouped) -> cards.add(new Card(group.title(), List.copyOf(grouped), false)));
        return List.copyOf(cards);
    }

    private List<String> versions(File file) {
        if (file.gameVersions().isEmpty()) return List.of("");
        if (kind != Kind.MODPACK) return file.gameVersions();
        var exact = new Layout(false, false);
        return List.of(file.gameVersions().stream().min(Comparator.comparing(exact::name, VERSION_ORDER)).orElseThrow());
    }

    private static boolean accepts(File file, Source source, Kind kind) {
        if (source != Source.MODRINTH || kind != null && kind != Kind.MOD && kind != Kind.DATAPACK) return true;
        Kind declaredKind = declaredKind(file);
        if (declaredKind != null) return kind == null || kind == declaredKind;
        return file.loaders().stream().noneMatch(PLUGIN_LOADERS::contains);
    }

    static Kind declaredKind(File file) {
        if (!loaders(file).isEmpty()) return Kind.MOD;
        if (file.loaders().contains("datapack")) return Kind.DATAPACK;
        return null;
    }

    private static List<String> loaders(File file) {
        return file.loaders().stream().map(loader -> loader.toLowerCase(Locale.ROOT))
                .filter(LOADER_NAMES::containsKey).distinct().toList();
    }

    private static String loaderName(String key) {
        return LOADER_NAMES.getOrDefault(key, key);
    }

    private static int compareVersions(String left, String right) {
        String leftBase = left.replace(PREVIEW, "");
        String rightBase = right.replace(PREVIEW, "");
        boolean leftRelease = RELEASE.matcher(leftBase).matches();
        boolean rightRelease = RELEASE.matcher(rightBase).matches();
        if (leftRelease != rightRelease) return leftRelease ? -1 : 1;
        if (!leftRelease) return left.compareTo(right);
        var a = leftBase.split("\\.");
        var b = rightBase.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int compared = Integer.compare(Integer.parseInt(b[i]), Integer.parseInt(a[i]));
            if (compared != 0) return compared;
        }
        int compared = Integer.compare(b.length, a.length);
        return compared == 0 ? left.compareTo(right) : compared;
    }
}

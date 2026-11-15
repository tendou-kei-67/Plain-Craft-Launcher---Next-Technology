package io.pclnt.minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The original PCL index maps platform slugs to MC 百科 article numbers. */
final class ResourceWiki {
    private record Key(CommunityResources.Source source, String slug) {}
    record Entry(int id, String title) {
        URI website() { return URI.create("https://www.mcmod.cn/class/" + id + ".html"); }
    }

    private static final Map<Key, Entry> INDEX = load();

    static Entry lookup(CommunityResources.Source source, String slug) {
        return INDEX.get(new Key(source, slug));
    }

    private static Map<Key, Entry> load() {
        var stream = ResourceWiki.class.getResourceAsStream("WikiEntries.txt");
        if (stream == null) return Map.of();
        List<String> lines;
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        } catch (IOException | java.io.UncheckedIOException failure) {
            System.getLogger(ResourceWiki.class.getName()).log(System.Logger.Level.WARNING, "无法读取 MC 百科索引", failure);
            return Map.of();
        }
        var index = new HashMap<Key, Entry>();
        for (int line = 0; line + 1 < lines.size(); line++) {
            for (String row : lines.get(line).split("¨")) {
                var parts = row.split("\\|", -1);
                var slugs = parts[0].split("@", -1);
                String curse = slugs[0].replace("\uFEFF", "");
                String modrinth = slugs.length == 1 ? "" : slugs[1].isEmpty() ? curse : slugs[1];
                String title = parts.length < 2 ? "" : parts[parts.length - 1];
                var entry = new Entry(line + 1, title.replace("*", " (" + (curse.isEmpty() ? modrinth : curse).replace('-', ' ') + ")"));
                if (!curse.isBlank()) index.putIfAbsent(new Key(CommunityResources.Source.CURSEFORGE, curse), entry);
                if (!modrinth.isBlank()) index.putIfAbsent(new Key(CommunityResources.Source.MODRINTH, modrinth), entry);
            }
        }
        return Map.copyOf(index);
    }
}

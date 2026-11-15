package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record VanillaCatalog(String latestRelease, String latestSnapshot, List<Version> versions) {
    private static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final Set<String> FOOLS = Set.of(
            "2point0_blue", "2point0_red", "2point0_purple", "2.0_blue", "2.0_red", "2.0_purple", "2.0",
            "20w14infinite", "20w14∞", "3d shareware v1.34", "1.rv-pre1", "15w14a",
            "22w13oneblockatatime", "23w13a_or_b", "24w14potato", "25w14craftmine", "26w14a");

    public enum Category {
        RELEASE("正式版"), SNAPSHOT("快照版"), LEGACY("远古版"), APRIL_FOOLS("愚人节版");

        public final String title;

        Category(String title) {
            this.title = title;
        }
    }

    public record Version(String id, Category category, OffsetDateTime released, URI metadata, String sha1) {}

    public static VanillaCatalog load(HttpClient client) throws IOException, InterruptedException {
        var catalog = DownloadMirrors.read(client, MANIFEST, body -> {
            var result = parse(JsonParser.parseString(body).getAsJsonObject());
            if (result.versions().stream().noneMatch(version -> version.id().equals(result.latestRelease()))) {
                throw new IOException("版本清单缺少最新正式版");
            }
            return result;
        });
        if (catalog == null) throw new IOException("找不到 Minecraft 版本清单");
        return catalog;
    }

    static VanillaCatalog parse(JsonObject manifest) {
        var latest = manifest.getAsJsonObject("latest");
        var versions = manifest.getAsJsonArray("versions").asList().stream().map(entry -> {
            var object = entry.getAsJsonObject();
            String id = object.get("id").getAsString();
            var released = OffsetDateTime.parse(object.get("releaseTime").getAsString());
            return new Version(id, classify(id, object.get("type").getAsString(), released), released,
                    DownloadMirrors.officialMetadata(URI.create(object.get("url").getAsString())),
                    object.get("sha1").getAsString());
        }).sorted(Comparator.comparing((Version version) -> version.released().toInstant()).reversed()).toList();
        return new VanillaCatalog(latest.get("release").getAsString(), latest.get("snapshot").getAsString(), versions);
    }

    private static Category classify(String id, String type, OffsetDateTime released) {
        String lower = id.toLowerCase(Locale.ROOT);
        if ("special".equals(type)) {
            return Category.APRIL_FOOLS;
        }
        if ("release".equals(type)) {
            return Category.RELEASE;
        }
        if (!"snapshot".equals(type) && !"pending".equals(type)) {
            return Category.LEGACY;
        }
        var date = released.withOffsetSameInstant(ZoneOffset.ofHours(2));
        if (FOOLS.contains(lower) || (date.getMonthValue() == 4 && date.getDayOfMonth() == 1)) {
            return Category.APRIL_FOOLS;
        }
        if (lower.startsWith("1.") && !lower.contains("combat") && !lower.contains("rc")
                && !lower.contains("experimental") && !lower.equals("1.2") && !lower.contains("pre")) {
            return Category.RELEASE;
        }
        return Category.SNAPSHOT;
    }
}

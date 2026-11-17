package io.pclnt.minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Mirror routes used by the original PCL downloaders. */
final class DownloadMirrors {
    private static final String BMCL = "https://bmclapi2.bangbang93.com";
    private static final String MCIM = "https://mod.mcimirror.top";

    private DownloadMirrors() {}

    static boolean isMirror(URI uri) {
        return "bmclapi2.bangbang93.com".equalsIgnoreCase(uri.getHost())
                || "mod.mcimirror.top".equalsIgnoreCase(uri.getHost());
    }

    static URI officialMetadata(URI uri) {
        if (BMCL.equalsIgnoreCase(uri.getScheme() + "://" + uri.getRawAuthority())
                && uri.getRawPath().startsWith("/v1/packages/")) {
            return URI.create("https://piston-meta.mojang.com" + uri.getRawPath());
        }
        return uri;
    }

    static List<URI> sources(URI official) {
        if (!"https".equalsIgnoreCase(official.getScheme()) || official.getHost() == null
                || official.getUserInfo() != null || official.getPort() != -1) {
            return List.of(official);
        }
        String path = official.getRawPath();
        String query = official.getRawQuery() == null ? "" : "?" + official.getRawQuery();
        String mirror = switch (official.getHost().toLowerCase(java.util.Locale.ROOT)) {
            case "piston-meta.mojang.com", "piston-data.mojang.com",
                    "launcher.mojang.com", "launchermeta.mojang.com" -> BMCL + path;
            case "resources.download.minecraft.net" -> BMCL + "/assets" + path;
            case "libraries.minecraft.net", "maven.fabricmc.net", "maven.minecraftforge.net" -> BMCL + "/maven" + path;
            case "maven.neoforged.net" -> path.startsWith("/releases/") ? BMCL + "/maven" + path.substring(9) : null;
            case "meta.fabricmc.net" -> BMCL + "/fabric-meta" + path;
            case "api.modrinth.com" -> MCIM + "/modrinth" + path;
            case "cdn.modrinth.com", "edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net" -> MCIM + path;
            default -> null;
        };
        if (mirror == null) return List.of(official);
        var preferred = URI.create(mirror + query);
        if (official.getHost().equalsIgnoreCase("libraries.minecraft.net")) {
            return List.of(preferred, URI.create(BMCL + "/libraries" + path + query), official);
        }
        // MCIM's project versions can lag behind Modrinth and omit new releases.
        if (official.getHost().equalsIgnoreCase("api.modrinth.com") && path.endsWith("/version")) {
            return List.of(official, preferred);
        }
        // Mojang's old manifests retain the preceding host names for the same content-addressed files.
        if (official.getHost().equalsIgnoreCase("launcher.mojang.com") && path.startsWith("/v1/objects/")) {
            return List.of(preferred, URI.create("https://piston-data.mojang.com" + path + query), official);
        }
        if (official.getHost().equalsIgnoreCase("launchermeta.mojang.com") && path.startsWith("/v1/packages/")) {
            return List.of(preferred, URI.create("https://piston-meta.mojang.com" + path + query), official);
        }
        return List.of(preferred, official);
    }

    @FunctionalInterface
    interface Read<T> {
        T parse(String body) throws IOException;
    }

    static <T> T read(HttpClient client, URI official, Read<T> read) throws IOException, InterruptedException {
        var sources = sources(official);
        IOException failure = null;
        for (var source : sources) {
            try {
                long timeout = source.equals(official) ? 25 : 8;
                var request = HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(timeout))
                        .header("User-Agent", "PCL-NT/0.1 (Minecraft launcher)").build();
                var transfer = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                try {
                    var response = transfer.get(timeout, TimeUnit.SECONDS);
                    if (response.statusCode() == 404 && source.equals(official)) return null;
                    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                    return read.parse(response.body());
                } finally {
                    transfer.cancel(true);
                }
            } catch (IOException | ExecutionException | TimeoutException | RuntimeException error) {
                var next = new IOException("读取 " + source.getHost() + " 失败：" + error.getMessage(), error);
                if (failure != null) next.addSuppressed(failure);
                failure = next;
            }
        }
        throw failure;
    }
}

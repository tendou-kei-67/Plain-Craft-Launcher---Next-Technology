package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class FabricMeta {
    private static final String LOADERS = "https://meta.fabricmc.net/v2/versions/loader/";
    private final HttpClient client;

    public FabricMeta(HttpClient client) {
        this.client = client;
    }

    public record Loader(String version, boolean stable) {
        @Override public String toString() {
            return version + (stable ? "（稳定版）" : "（测试版）");
        }
    }

    public List<Loader> loaders(String game) throws IOException, InterruptedException {
        var loaders = DownloadMirrors.read(client, URI.create(LOADERS + gameSegment(game)),
                body -> JsonParser.parseString(body).getAsJsonArray().asList().stream().map(entry -> {
                    var loader = entry.getAsJsonObject().getAsJsonObject("loader");
                    return new Loader(loader.get("version").getAsString(), loader.get("stable").getAsBoolean());
                }).toList());
        return loaders == null ? List.of() : loaders;
    }

    JsonObject profile(String game, Loader loader) throws IOException, InterruptedException {
        var uri = URI.create(LOADERS + gameSegment(game) + "/" + segment(loader.version()) + "/profile/json");
        var response = DownloadMirrors.read(client, uri, body -> {
            var profile = JsonParser.parseString(body).getAsJsonObject();
            if (!profile.has("libraries") || !profile.has("mainClass") || !profile.has("id")) {
                throw new IOException("Fabric 安装配置不完整");
            }
            return profile;
        });
        if (response == null) {
            throw new IOException("找不到此 Fabric 安装配置");
        }
        return response;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String gameSegment(String value) {
        return segment(value.replace("∞", "infinite"));
    }
}

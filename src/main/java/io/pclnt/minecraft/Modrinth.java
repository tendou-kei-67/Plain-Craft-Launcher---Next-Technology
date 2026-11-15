package io.pclnt.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

public final class Modrinth {
    private static final Gson JSON = new Gson();
    private final HttpClient client;

    public Modrinth(HttpClient client) {
        this.client = client;
    }

    public record File(String name, URI url, String sha1, long size) {}

    public record Release(String version, OffsetDateTime published, File file) {
        @Override public String toString() {
            return version;
        }
    }

    public List<Release> versions(String project, String game, String loader) throws IOException, InterruptedException {
        if (!project.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Modrinth project");
        }
        String endpoint = "https://api.modrinth.com/v2/project/" + project + "/version?include_changelog=false"
                + "&game_versions=" + filter(game) + "&loaders=" + filter(loader);
        var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "PCL-NT/0.1").build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Modrinth 请求失败（HTTP " + response.statusCode() + "）");
        }
        return JsonParser.parseString(response.body()).getAsJsonArray().asList().stream()
                .map(entry -> release(entry.getAsJsonObject())).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Release::published).reversed()).toList();
    }

    private static Release release(JsonObject entry) {
        var files = entry.getAsJsonArray("files").asList().stream().map(file -> file.getAsJsonObject()).toList();
        if (files.isEmpty()) {
            return null;
        }
        var file = files.stream().filter(candidate -> candidate.get("primary").getAsBoolean())
                .findFirst().orElse(files.getFirst());
        return new Release(entry.get("version_number").getAsString(),
                OffsetDateTime.parse(entry.get("date_published").getAsString()),
                new File(file.get("filename").getAsString(), URI.create(file.get("url").getAsString()),
                        file.getAsJsonObject("hashes").get("sha1").getAsString(), file.get("size").getAsLong()));
    }

    private static String filter(String value) {
        return URLEncoder.encode(JSON.toJson(List.of(value)), StandardCharsets.UTF_8);
    }
}

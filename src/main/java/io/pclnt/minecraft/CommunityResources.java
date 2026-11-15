package io.pclnt.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class CommunityResources {
    private static final String MODRINTH = "https://api.modrinth.com/v2/";
    private static final String CURSEFORGE = "https://api.curseforge.com/v1/";
    private static final int PAGE_SIZE = 20;

    public enum Source {
        MODRINTH("Modrinth"), CURSEFORGE("CurseForge");
        private final String label;
        Source(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Kind {
        MOD("Mod", "mod", 6), MODPACK("整合包", "modpack", 4471),
        DATAPACK("数据包", "datapack", 6945), RESOURCEPACK("资源包", "resourcepack", 12),
        SHADER("光影包", "shader", 6552), WORLD("世界", "", 17);

        private final String label;
        private final String type;
        private final int classId;
        Kind(String label, String type, int classId) {
            this.label = label;
            this.type = type;
            this.classId = classId;
        }
        @Override public String toString() { return label; }
    }

    public record Project(Source source, String id, String title, String summary, URI website, URI icon,
                          List<String> gameVersions, List<String> categories, long downloads, String updated, String slug) {
        public String localizedTitle() {
            var entry = ResourceWiki.lookup(source, slug);
            return entry != null && !entry.title().isBlank() ? entry.title() : title;
        }

        public URI wiki() {
            var entry = ResourceWiki.lookup(source, slug);
            return entry == null ? null : entry.website();
        }
    }
    public record Page<T>(List<T> items, boolean more) {}
    public record SearchPage(List<Project> items, boolean more, List<String> warnings) {}
    public enum ReleaseType { RELEASE, BETA, ALPHA }
    public record File(String title, String name, URI url, long size, String sha1, List<String> gameVersions,
                       List<String> loaders, ReleaseType releaseType, String id, List<String> dependencies,
                       Instant published) {
        public File(String title, String name, URI url, long size, String sha1, List<String> gameVersions,
                    List<String> loaders, ReleaseType releaseType, String id, List<String> dependencies) {
            this(title, name, url, size, sha1, gameVersions, loaders, releaseType, id, dependencies, Instant.EPOCH);
        }

        public File(String title, String name, URI url, long size, String sha1, List<String> gameVersions,
                    List<String> loaders, ReleaseType releaseType) {
            this(title, name, url, size, sha1, gameVersions, loaders, releaseType, "", List.of());
        }
    }
    public record Filter(String query, String gameVersion, String loader, String curseCategory, String modrinthCategory) {
        public Filter(String query, String gameVersion, String loader) {
            this(query, gameVersion, loader, "", "");
        }
    }

    private final HttpClient client;
    private final HttpClient curseForgeClient;
    private final String curseForgeKey;

    public CommunityResources(HttpClient client, String curseForgeKey) {
        this(client, curseForgeKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build());
    }

    CommunityResources(HttpClient client, String curseForgeKey, HttpClient curseForgeClient) {
        this.client = client;
        this.curseForgeKey = curseForgeKey.strip();
        this.curseForgeClient = curseForgeClient;
    }

    public SearchPage searchAll(Kind kind, Filter filter, int offset) throws IOException, InterruptedException {
        boolean category = !filter.modrinthCategory().isEmpty() || !filter.curseCategory().isEmpty();
        boolean modrinth = kind != Kind.WORLD && (!category || !filter.modrinthCategory().isEmpty());
        boolean curseforge = !curseForgeKey.isBlank() && (!category || !filter.curseCategory().isEmpty());
        if (!modrinth) return combined(search(Source.CURSEFORGE, kind, filter, offset));
        if (!curseforge) return combined(search(Source.MODRINTH, kind, filter, offset));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> search(Source.MODRINTH, kind, filter, offset));
            var second = executor.submit(() -> search(Source.CURSEFORGE, kind, filter, offset));
            var projects = new ArrayList<Project>();
            var warnings = new ArrayList<String>();
            boolean more = false;
            try {
                for (var task : List.of(first, second)) {
                    try {
                        var page = task.get();
                        projects.addAll(page.items());
                        more |= page.more();
                    } catch (java.util.concurrent.ExecutionException failure) {
                        warnings.add(failure.getCause().getMessage());
                    }
                }
            } finally {
                first.cancel(true);
                second.cancel(true);
            }
            if (warnings.size() == 2) throw new IOException(String.join("；", warnings));
            var merged = new java.util.LinkedHashMap<String, Project>();
            projects.stream().sorted(java.util.Comparator.comparingLong(Project::downloads).reversed())
                    .forEach(project -> merged.putIfAbsent(project.title().strip().toLowerCase(Locale.ROOT)
                            + "|" + project.summary().strip().toLowerCase(Locale.ROOT), project));
            return new SearchPage(List.copyOf(merged.values()), more, warnings);
        }
    }

    private static SearchPage combined(Page<Project> page) {
        return new SearchPage(page.items(), page.more(), List.of());
    }

    public List<URI> iconSources(Project project) {
        if (project.icon() == null) return List.of();
        var sources = new ArrayList<>(DownloadMirrors.sources(project.icon()));
        if (sources.size() > 1) java.util.Collections.rotate(sources, 1);
        return List.copyOf(sources);
    }

    public URI filePage(Project project, File file) {
        if (file.id().isEmpty() || project.website() == null) return project.website();
        String path = project.source() == Source.MODRINTH ? "/version/" : "/files/";
        return URI.create(project.website().toString().replaceFirst("/$", "") + path + encode(file.id()));
    }

    public List<Project> dependencies(Source source, List<String> ids) throws IOException, InterruptedException {
        if (ids.isEmpty()) return List.of();
        var projects = new ArrayList<Project>();
        if (source == Source.MODRINTH) {
            var keys = new JsonArray();
            ids.forEach(keys::add);
            var response = get(MODRINTH + "projects?ids=" + encode(keys.toString()), false).getAsJsonArray();
            for (var entry : response) {
                var item = entry.getAsJsonObject();
                String id = string(item, "id");
                projects.add(new Project(source, id, string(item, "title"), string(item, "description"),
                        URI.create("https://modrinth.com/project/" + encode(id)), https(string(item, "icon_url")),
                        strings(item.getAsJsonArray("game_versions")), strings(item.getAsJsonArray("categories")),
                        number(item, "downloads"), string(item, "updated"), string(item, "slug")));
            }
        } else {
            var request = request(URI.create(CURSEFORGE + "mods")).timeout(Duration.ofSeconds(25))
                    .header("x-api-key", curseForgeKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new com.google.gson.Gson().toJson(
                            java.util.Map.of("modIds", ids.stream().map(Long::parseLong).toList())))).build();
            var response = curseForgeClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("无法获取 CurseForge 前置资源（HTTP " + response.statusCode() + "）");
            for (var entry : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data")) {
                projects.add(curseProject(entry.getAsJsonObject()));
            }
        }
        return List.copyOf(projects);
    }

    public Page<Project> search(Source source, Kind kind, Filter filter, int offset) throws IOException, InterruptedException {
        String query = filter.query();
        String gameVersion = filter.gameVersion();
        String loader = filter.loader();
        var projects = new ArrayList<Project>();
        if (source == Source.MODRINTH) {
            if (kind == Kind.WORLD) throw new IOException("世界仅在 CurseForge 提供");
            if (!filter.curseCategory().isEmpty() && filter.modrinthCategory().isEmpty()) return new Page<>(List.of(), false);
            var facets = new JsonArray();
            facet(facets, "all_project_types:" + kind.type);
            if (!gameVersion.isBlank()) facet(facets, "versions:" + gameVersion);
            if (!loader.isBlank()) facet(facets, "categories:" + loader);
            if (!filter.modrinthCategory().isEmpty()) facet(facets, "categories:" + filter.modrinthCategory());
            var result = get(MODRINTH + "search?limit=" + PAGE_SIZE + "&offset=" + offset + "&query="
                    + encode(query) + "&facets=" + encode(facets.toString()), false).getAsJsonObject();
            for (var hit : result.getAsJsonArray("hits")) {
                var item = hit.getAsJsonObject();
                var id = string(item, "project_id");
                projects.add(new Project(source, id, string(item, "title"), string(item, "description"),
                        URI.create("https://modrinth.com/project/" + encode(id)), https(string(item, "icon_url")),
                        strings(item.getAsJsonArray("versions")), strings(item.getAsJsonArray("display_categories")),
                        number(item, "downloads"), string(item, "date_modified"), string(item, "slug")));
            }
            return new Page<>(projects, offset + projects.size() < result.get("total_hits").getAsInt());
        }
        if (!filter.modrinthCategory().isEmpty() && filter.curseCategory().isEmpty()) return new Page<>(List.of(), false);
        String category = filter.curseCategory().isEmpty() ? "" : "&categoryId=" + encode(filter.curseCategory());
        String address = CURSEFORGE + "mods/search?gameId=432&classId=" + kind.classId + "&pageSize=" + PAGE_SIZE
                + "&index=" + offset + "&searchFilter=" + encode(query) + category + curseFilters(filter);
        var result = get(address, true).getAsJsonObject();
        for (var hit : result.getAsJsonArray("data")) {
            projects.add(curseProject(hit.getAsJsonObject()));
        }
        return new Page<>(projects, moreCurseForge(result));
    }

    private static Project curseProject(JsonObject item) {
        var logo = item.get("logo");
        URI icon = logo != null && logo.isJsonObject()
                ? https(string(logo.getAsJsonObject(), "thumbnailUrl")) : null;
        if (icon == null && logo != null && logo.isJsonObject()) {
            icon = https(string(logo.getAsJsonObject(), "url"));
        }
        var versions = new java.util.LinkedHashSet<String>();
        var tags = new ArrayList<String>();
        var indexes = item.getAsJsonArray("latestFilesIndexes");
        var categories = item.getAsJsonArray("categories");
        if (indexes != null) indexes.forEach(value -> versions.add(string(value.getAsJsonObject(), "gameVersion")));
        if (categories != null) categories.forEach(value -> tags.add(string(value.getAsJsonObject(), "name")));
        return new Project(Source.CURSEFORGE, string(item, "id"), string(item, "name"), string(item, "summary"),
                https(string(item.getAsJsonObject("links"), "websiteUrl")), icon, List.copyOf(versions),
                List.copyOf(tags), number(item, "downloadCount"), string(item, "dateModified"), string(item, "slug"));
    }

    public Page<File> files(Project project, Filter filter, int offset) throws IOException, InterruptedException {
        if (project.source() == Source.MODRINTH) {
            var files = modrinthFiles(project, filter);
            int end = Math.min(offset + PAGE_SIZE, files.size());
            return new Page<>(files.subList(Math.min(offset, end), end), end < files.size());
        }
        return curseFiles(project, filter, offset, PAGE_SIZE);
    }

    public List<File> projectFiles(Project project) throws IOException, InterruptedException {
        var all = new Filter("", "", "");
        if (project.source() == Source.MODRINTH) return modrinthFiles(project, all);
        var collected = new java.util.LinkedHashSet<File>();
        int offset = 0;
        int pageSize = 10000;
        while (true) {
            var page = curseFiles(project, all, offset, pageSize);
            boolean receivedNewFiles = collected.addAll(page.items());
            if (!page.more()) return List.copyOf(collected);
            if (!receivedNewFiles) throw new IOException("CurseForge 返回的资源版本列表存在缺失");
            offset += page.items().size();
            pageSize = 50;
        }
    }

    private List<File> modrinthFiles(Project project, Filter filter) throws IOException, InterruptedException {
        var files = new ArrayList<File>();
        String query = "?include_changelog=false";
        if (!filter.gameVersion().isBlank()) query += "&game_versions=" + encode(array(filter.gameVersion()).toString());
        if (!filter.loader().isBlank()) query += "&loaders=" + encode(array(filter.loader()).toString());
        var versions = get(MODRINTH + "project/" + encode(project.id()) + "/version" + query, false).getAsJsonArray();
        for (var version : versions) {
            var item = version.getAsJsonObject();
            var dependencies = requiredProjects(item.getAsJsonArray("dependencies"), "dependency_type", "required", "project_id");
            var releaseType = switch (string(item, "version_type")) {
                case "alpha" -> ReleaseType.ALPHA;
                case "beta" -> ReleaseType.BETA;
                default -> ReleaseType.RELEASE;
            };
            var published = published(item, "date_published");
            for (var entry : item.getAsJsonArray("files")) {
                var file = entry.getAsJsonObject();
                files.add(new File(string(item, "name"), string(file, "filename"), https(string(file, "url")),
                        file.get("size").getAsLong(), string(file.getAsJsonObject("hashes"), "sha1"),
                        strings(item.getAsJsonArray("game_versions")), strings(item.getAsJsonArray("loaders")),
                        releaseType, string(item, "id"), dependencies, published));
            }
        }
        return List.copyOf(files);
    }

    private Page<File> curseFiles(Project project, Filter filter, int offset, int pageSize)
            throws IOException, InterruptedException {
        var result = get(CURSEFORGE + "mods/" + encode(project.id()) + "/files?pageSize=" + pageSize
                + "&index=" + offset + curseFilters(filter), true).getAsJsonObject();
        var files = new ArrayList<File>();
        for (var entry : result.getAsJsonArray("data")) files.add(curseFile(entry.getAsJsonObject()));
        return new Page<>(files, moreCurseForge(result));
    }

    record CurseFile(JsonObject metadata, File file) {}

    java.util.Map<Long, CurseFile> cursePackFiles(List<Long> ids) throws IOException, InterruptedException {
        if (curseForgeKey.isBlank()) throw new IOException("CurseForge 需要配置 PCLNT_CURSEFORGE_API_KEY");
        var result = new java.util.HashMap<Long, CurseFile>();
        var distinct = ids.stream().distinct().toList();
        for (int offset = 0; offset < distinct.size(); offset += 50) {
            var batch = distinct.subList(offset, Math.min(offset + 50, distinct.size()));
            var json = new com.google.gson.Gson().toJson(java.util.Map.of("fileIds", batch));
            var query = request(URI.create(CURSEFORGE + "mods/files")).timeout(Duration.ofSeconds(25))
                    .header("x-api-key", curseForgeKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = curseForgeClient.send(query, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("CurseForge 文件请求失败（HTTP " + response.statusCode() + "）");
            try {
                for (var item : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data")) {
                    var metadata = item.getAsJsonObject();
                    result.put(metadata.get("id").getAsLong(), new CurseFile(metadata, curseFile(metadata)));
                }
            } catch (RuntimeException error) {
                throw new IOException("CurseForge 文件响应格式无效", error);
            }
        }
        return result;
    }

    private static File curseFile(JsonObject file) {
        var sha1 = stringsForHash(file.getAsJsonArray("hashes"));
        var tags = strings(file.getAsJsonArray("gameVersions"));
        var loaders = tags.stream().map(tag -> tag.toLowerCase(Locale.ROOT))
                .filter(List.of("fabric", "forge", "neoforge", "quilt", "liteloader")::contains).toList();
        var gameVersions = tags.stream().filter(tag -> tag.matches(".*[0-9]\\..*") || tag.matches("[0-9]{2}w[0-9]{2}[a-z].*")).toList();
        var releaseType = switch (string(file, "releaseType")) {
            case "2" -> ReleaseType.BETA;
            case "3" -> ReleaseType.ALPHA;
            default -> ReleaseType.RELEASE;
        };
        return new File(string(file, "displayName"), string(file, "fileName"), https(string(file, "downloadUrl")),
                file.get("fileLength").getAsLong(), sha1, gameVersions, loaders, releaseType, string(file, "id"),
                requiredProjects(file.getAsJsonArray("dependencies"), "relationType", "3", "modId"), published(file, "fileDate"));
    }

    private static Instant published(JsonObject item, String key) {
        try {
            return Instant.parse(string(item, key));
        } catch (java.time.format.DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }

    private static List<String> requiredProjects(JsonArray dependencies, String relation, String required, String id) {
        if (dependencies == null) return List.of();
        return dependencies.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> required.equals(string(item, relation))).map(item -> string(item, id))
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    public void download(File file, Path destination) throws IOException, InterruptedException {
        if (file.url() == null) throw new IOException("作者未开放第三方下载，请前往项目网页");
        Path target = destination.toAbsolutePath().normalize();
        Path pending = Files.createTempFile(target.getParent(), ".pcl-nt-resource-", ".part");
        try {
            var sources = file.sha1().isBlank() ? List.of(file.url()) : DownloadMirrors.sources(file.url());
            IOException failure = null;
            for (var source : sources) {
                try {
                    download(file, source, pending);
                    failure = null;
                    break;
                } catch (IOException error) {
                    if (failure != null) error.addSuppressed(failure);
                    failure = error;
                }
            }
            if (failure != null) throw failure;
            Files.move(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private void download(File file, URI source, Path pending) throws IOException, InterruptedException {
        var request = request(source).timeout(Duration.ofSeconds(source.equals(file.url()) ? 60 : 10)).build();
        var transfer = client.sendAsync(request, TaskIo.download(pending, file.name(),
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
        HttpResponse<Path> response;
        try {
            response = transfer.get(5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IOException("下载文件失败", error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IOException("下载文件超时", error);
        } finally {
            transfer.cancel(true);
        }
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        if (file.size() > 0 && Files.size(pending) != file.size()) throw new IOException("下载文件大小不一致");
        if (!file.sha1().isBlank()) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-1");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            try (var stream = new DigestInputStream(Files.newInputStream(pending), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.sha1())) {
                throw new IOException("下载文件校验失败");
            }
        }
    }

    private JsonElement get(String url, boolean curseForge) throws IOException, InterruptedException {
        if (!curseForge) {
            var data = DownloadMirrors.read(client, URI.create(url), body -> {
                var value = JsonParser.parseString(body);
                boolean search = URI.create(url).getPath().endsWith("/search");
                if (search ? !value.isJsonObject() || !value.getAsJsonObject().has("hits") : !value.isJsonArray()) {
                    throw new IOException("Modrinth 返回了无效的数据格式");
                }
                return value;
            });
            if (data == null) throw new IOException("找不到资源平台数据（HTTP 404）");
            return data;
        }
        var request = request(URI.create(url)).timeout(Duration.ofSeconds(25));
        if (curseForgeKey.isBlank()) throw new IOException("CurseForge 需要配置 PCLNT_CURSEFORGE_API_KEY");
        request.header("x-api-key", curseForgeKey);
        var response = curseForgeClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("资源平台请求失败（HTTP " + response.statusCode() + "）");
        return JsonParser.parseString(response.body());
    }

    private static HttpRequest.Builder request(URI url) {
        return HttpRequest.newBuilder(url).header("User-Agent", "PCL-NT/0.1 (Minecraft launcher)");
    }

    private static boolean moreCurseForge(JsonObject page) {
        var p = page.getAsJsonObject("pagination");
        return p.get("index").getAsInt() + p.get("resultCount").getAsInt() < p.get("totalCount").getAsInt();
    }

    private static String curseFilters(Filter filter) {
        String version = filter.gameVersion().isBlank() ? "" : "&gameVersion=" + encode(filter.gameVersion());
        int loader = switch (filter.loader().toLowerCase(Locale.ROOT)) {
            case "forge" -> 1;
            case "liteloader" -> 3;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
        return version + (loader == 0 ? "" : "&modLoaderType=" + loader);
    }

    private static String stringsForHash(JsonArray hashes) {
        if (hashes == null) return "";
        for (var entry : hashes) {
            var hash = entry.getAsJsonObject();
            if (hash.get("algo").getAsInt() == 1) return string(hash, "value");
        }
        return "";
    }

    private static List<String> strings(JsonArray array) {
        var values = new ArrayList<String>();
        if (array != null) array.forEach(value -> values.add(value.getAsString()));
        return values;
    }

    private static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static URI https(String address) {
        try {
            var uri = URI.create(address);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static JsonArray array(String value) {
        var array = new JsonArray();
        array.add(value);
        return array;
    }

    private static void facet(JsonArray facets, String value) { facets.add(array(value)); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}

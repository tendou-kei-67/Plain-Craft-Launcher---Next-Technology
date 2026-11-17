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
import java.util.Map;
import java.util.LinkedHashMap;

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
                          List<String> gameVersions, List<String> categories, long downloads, String updated, String slug,
                          URI iconFallback) {
        public Project(Source source, String id, String title, String summary, URI website, URI icon,
                       List<String> gameVersions, List<String> categories, long downloads, String updated, String slug) {
            this(source, id, title, summary, website, icon, gameVersions, categories, downloads, updated, slug, null);
        }

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
    public record CatalogProject(Kind kind, Project project) {}
    public record InstalledResource(Project project, String version, Instant published, String sha1) {}
    public record Identification(Map<String, InstalledResource> matches, List<Source> unavailable) {}
    public record DatapackUpdates(Map<String, File> candidates, List<Source> unavailable) {}
    private record ResourceVersion(String projectId, String version, Instant published) {}
    private record DescriptionKey(Source source, String project, String original) {}
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
    private final java.util.concurrent.ConcurrentMap<DescriptionKey, java.util.Optional<String>> translations =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        var sources = new java.util.LinkedHashSet<URI>();
        for (var icon : new URI[]{project.icon(), project.iconFallback()}) {
            if (icon == null) continue;
            var variants = new ArrayList<>(DownloadMirrors.sources(icon));
            if (variants.size() > 1) java.util.Collections.rotate(variants, 1);
            sources.addAll(variants);
        }
        return List.copyOf(sources);
    }

    public byte[] iconBytes(URI uri) throws IOException, InterruptedException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("无效的资源图片地址");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "PCL-NT/0.1.0").header("Accept", "image/*").build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("资源图片 HTTP " + response.statusCode());
            var timer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Closing a body that is already complete needs no retry.
                }
            }, java.util.concurrent.CompletableFuture.delayedExecutor(12, java.util.concurrent.TimeUnit.SECONDS));
            try {
                int limit = 4 * 1024 * 1024;
                byte[] bytes = body.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("资源图片超过大小限制");
                return bytes;
            } finally {
                timer.cancel(false);
            }
        }
    }

    public URI filePage(Project project, File file) {
        if (file.id().isEmpty() || project.website() == null) return project.website();
        String path = project.source() == Source.MODRINTH ? "/version/" : "/files/";
        return URI.create(project.website().toString().replaceFirst("/$", "") + path + encode(file.id()));
    }

    public java.util.Optional<String> translateDescription(Project project) throws IOException, InterruptedException {
        if (project.summary().isBlank()) return java.util.Optional.empty();
        var key = new DescriptionKey(project.source(), project.id(), project.summary());
        var previous = translations.get(key);
        if (previous != null) return previous;
        String provider = project.source().name().toLowerCase(Locale.ROOT);
        var response = client.send(request(URI.create("https://mod.mcimirror.top/translate/" + provider + "/" + encode(project.id())))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return java.util.Optional.empty();
        if (response.statusCode() != 200) throw new IOException("翻译服务暂时不可用（HTTP " + response.statusCode() + "）");
        try {
            String translated = string(JsonParser.parseString(response.body()).getAsJsonObject(), "translated").strip();
            var result = translated.isBlank() ? java.util.Optional.<String>empty() : java.util.Optional.of(translated);
            if (translations.size() >= 256) translations.clear();
            translations.put(key, result);
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("翻译服务返回了无效的数据", invalid);
        }
    }

    public CatalogProject findProject(String id) throws IOException, InterruptedException {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("无效的资源 ID");
        if (id.matches("[0-9]+")) {
            var item = get(CURSEFORGE + "mods/" + encode(id), true).getAsJsonObject().getAsJsonObject("data");
            String type = string(item, "classId");
            var kind = java.util.Arrays.stream(Kind.values()).filter(entry -> Integer.toString(entry.classId).equals(type))
                    .findFirst().orElseThrow(() -> new IOException("不支持的 CurseForge 资源类型"));
            return new CatalogProject(kind, curseProject(item));
        }
        var item = get(MODRINTH + "project/" + encode(id), false).getAsJsonObject();
        String type = string(item, "project_type");
        var kind = java.util.Arrays.stream(Kind.values()).filter(entry -> entry != Kind.WORLD && entry.type.equals(type))
                .findFirst().orElseThrow(() -> new IOException("不支持的 Modrinth 资源类型"));
        return new CatalogProject(kind, modrinthProject(item));
    }

    public List<Project> dependencies(Source source, List<String> ids) throws IOException, InterruptedException {
        return projectData(source, ids).stream().map(source == Source.MODRINTH
                ? CommunityResources::modrinthProject : CommunityResources::curseProject).toList();
    }

    public boolean canIdentifyCurseForge() { return !curseForgeKey.isBlank(); }

    public Identification identifyDatapacks(List<WorldDatapacks.Fingerprint> fingerprints) throws InterruptedException {
        var matches = new LinkedHashMap<String, InstalledResource>();
        var unavailable = new java.util.LinkedHashSet<Source>();
        var unique = fingerprints.stream().distinct().toList();
        for (int start = 0; start < unique.size(); start += 50) {
            var batch = unique.subList(start, Math.min(start + 50, unique.size()));
            for (var source : Source.values()) {
                if (source == Source.CURSEFORGE && !canIdentifyCurseForge()) continue;
                try {
                    var eligible = batch.stream().filter(file -> !matches.containsKey(file.sha1())).toList();
                    if (eligible.isEmpty()) continue;
                    var versions = source == Source.MODRINTH ? modrinthMatches(eligible) : curseForgeMatches(eligible);
                    var ids = versions.values().stream().map(ResourceVersion::projectId).distinct().toList();
                    var projects = new LinkedHashMap<String, Project>();
                    for (var data : projectData(source, ids)) {
                        if (source == Source.MODRINTH
                                ? Kind.DATAPACK.type.equals(string(data, "project_type"))
                                    || strings(data.getAsJsonArray("loaders")).contains(Kind.DATAPACK.type)
                                    || strings(data.getAsJsonArray("project_types")).contains(Kind.DATAPACK.type)
                                : number(data, "classId") == Kind.DATAPACK.classId) {
                            var project = source == Source.MODRINTH ? modrinthProject(data) : curseProject(data);
                            projects.put(project.id(), project);
                        }
                    }
                    versions.forEach((hash, version) -> {
                        var project = projects.get(version.projectId());
                        if (project != null) matches.putIfAbsent(hash,
                                new InstalledResource(project, version.version(), version.published(), hash));
                    });
                } catch (IOException | IllegalStateException | com.google.gson.JsonParseException
                         | NumberFormatException malformedOrUnavailable) {
                    unavailable.add(source);
                }
            }
        }
        return new Identification(Map.copyOf(matches), List.copyOf(unavailable));
    }

    public DatapackUpdates datapackUpdates(List<InstalledResource> installed, String gameVersion) throws InterruptedException {
        if (gameVersion.isBlank()) return new DatapackUpdates(Map.of(), List.of());
        var results = new LinkedHashMap<String, File>();
        var unavailable = new java.util.LinkedHashSet<Source>();
        var modrinth = installed.stream().distinct().filter(item -> item.project().source() == Source.MODRINTH).toList();
        for (int start = 0; start < modrinth.size(); start += 50) {
            var batch = modrinth.subList(start, Math.min(start + 50, modrinth.size()));
            try {
                var hashes = new JsonArray();
                batch.forEach(item -> hashes.add(item.sha1()));
                var body = new JsonObject();
                body.add("hashes", hashes);
                body.addProperty("algorithm", "sha1");
                body.add("loaders", array("datapack"));
                body.add("game_versions", array(gameVersion));
                var response = postCatalog(Source.MODRINTH, "version_files/update", body);
                for (var item : batch) {
                    if (!(response.get(item.sha1()) instanceof JsonObject version)
                            || !item.project().id().equals(string(version, "project_id"))
                            || !strings(version.getAsJsonArray("loaders")).contains("datapack")) continue;
                    var files = version.getAsJsonArray("files");
                    if (files == null) continue;
                    File preferred = null;
                    for (var entry : files) {
                        var metadata = entry.getAsJsonObject();
                        if (!string(metadata, "filename").toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                        if (!string(metadata, "file_type").isBlank() && !"true".equals(string(metadata, "primary"))) continue;
                        if ("true".equals(string(metadata, "primary"))) {
                            preferred = modrinthFile(version, metadata);
                            break;
                        }
                        if (preferred == null) preferred = modrinthFile(version, metadata);
                    }
                    if (preferred != null && compatibleUpdate(item, preferred, gameVersion)) results.put(item.sha1(), preferred);
                }
            } catch (IOException | RuntimeException failure) {
                unavailable.add(Source.MODRINTH);
            }
        }
        var curseForgeLists = new LinkedHashMap<String, List<File>>();
        for (var item : installed.stream().distinct().filter(resource -> resource.project().source() == Source.CURSEFORGE).toList()) {
            try {
                var versions = curseForgeLists.get(item.project().id());
                if (versions == null) {
                    versions = curseDatapackFiles(item.project(), gameVersion);
                    curseForgeLists.put(item.project().id(), versions);
                }
                versions.stream().filter(file -> compatibleUpdate(item, file, gameVersion))
                        .max(java.util.Comparator.comparing(File::published)).ifPresent(file -> results.put(item.sha1(), file));
            } catch (IOException | RuntimeException failure) {
                unavailable.add(Source.CURSEFORGE);
                curseForgeLists.put(item.project().id(), List.of());
            }
        }
        return new DatapackUpdates(Map.copyOf(results), List.copyOf(unavailable));
    }

    private List<File> curseDatapackFiles(Project project, String gameVersion) throws IOException, InterruptedException {
        var files = new ArrayList<File>();
        var filter = new Filter("", gameVersion, "");
        for (int index = 0; index <= 9950;) {
            var page = curseFiles(project, filter, index, 50);
            files.addAll(page.items());
            if (!page.more()) return List.copyOf(files);
            if (page.items().isEmpty()) throw new IOException("CurseForge 返回了不完整的数据包版本");
            index += page.items().size();
        }
        throw new IOException("CurseForge 数据包版本超出可查询范围");
    }

    private static boolean compatibleUpdate(InstalledResource installed, File file, String gameVersion) {
        return file.url() != null && file.size() > 0 && file.sha1().matches("(?i)[a-f0-9]{40}")
                && file.name().toLowerCase(Locale.ROOT).endsWith(".zip") && file.gameVersions().contains(gameVersion)
                && !file.sha1().equalsIgnoreCase(installed.sha1()) && !Instant.EPOCH.equals(installed.published())
                && file.published().isAfter(installed.published());
    }

    private Map<String, ResourceVersion> modrinthMatches(List<WorldDatapacks.Fingerprint> batch)
            throws IOException, InterruptedException {
        var hashes = new JsonArray();
        batch.forEach(file -> hashes.add(file.sha1()));
        var body = new JsonObject();
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        var response = postCatalog(Source.MODRINTH, "version_files", body);
        var result = new LinkedHashMap<String, ResourceVersion>();
        for (var file : batch) {
            if (!(response.get(file.sha1()) instanceof JsonObject version)) continue;
            var files = version.getAsJsonArray("files");
            if (files == null || files.asList().stream().noneMatch(item -> item instanceof JsonObject entry
                    && entry.get("hashes") instanceof JsonObject hashesOfFile
                    && file.sha1().equalsIgnoreCase(string(hashesOfFile, "sha1")))) continue;
            var project = string(version, "project_id");
            if (!project.isBlank()) result.put(file.sha1(), new ResourceVersion(project,
                    string(version, "version_number"), published(version, "date_published")));
        }
        return result;
    }

    private Map<String, ResourceVersion> curseForgeMatches(List<WorldDatapacks.Fingerprint> batch)
            throws IOException, InterruptedException {
        var hashes = new JsonArray();
        batch.stream().map(WorldDatapacks.Fingerprint::curseForge).filter(java.util.Objects::nonNull)
                .distinct().forEach(hashes::add);
        if (hashes.isEmpty()) return Map.of();
        var body = new JsonObject();
        body.add("fingerprints", hashes);
        var response = postCatalog(Source.CURSEFORGE, "fingerprints/432", body);
        if (!(response.get("data") instanceof JsonObject data)
                || !(data.get("exactMatches") instanceof JsonArray exact)) {
            throw new IOException("CurseForge 返回了无效的指纹结果");
        }
        var result = new LinkedHashMap<String, ResourceVersion>();
        for (var item : exact) {
            if (!(item instanceof JsonObject match) || !(match.get("file") instanceof JsonObject file)) continue;
            var id = string(file, "modId");
            if (id.isBlank()) id = string(match, "id");
            if (!id.matches("[0-9]+") || !file.has("fileFingerprint")) continue;
            long fingerprint = number(file, "fileFingerprint");
            String sha = stringsForHash(file.getAsJsonArray("hashes"));
            var version = new ResourceVersion(id, string(file, "displayName"), published(file, "fileDate"));
            for (var local : batch) {
                if (local.curseForge() != null && local.curseForge() == fingerprint
                        && (sha.isBlank() || sha.equalsIgnoreCase(local.sha1()))) result.putIfAbsent(local.sha1(), version);
            }
        }
        return result;
    }

    private JsonObject postCatalog(Source source, String path, JsonObject body) throws IOException, InterruptedException {
        var request = request(URI.create((source == Source.MODRINTH ? MODRINTH : CURSEFORGE) + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (source == Source.CURSEFORGE) request.header("x-api-key", curseForgeKey);
        var http = source == Source.MODRINTH ? client : curseForgeClient;
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException(source + " HTTP " + response.statusCode());
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException(source + " 返回了无效的项目数据", invalid);
        }
    }

    private List<JsonObject> projectData(Source source, List<String> ids) throws IOException, InterruptedException {
        if (ids.isEmpty()) return List.of();
        JsonArray projects;
        if (source == Source.MODRINTH) {
            var keys = new JsonArray();
            ids.forEach(keys::add);
            projects = get(MODRINTH + "projects?ids=" + encode(keys.toString()), false).getAsJsonArray();
        } else {
            if (!canIdentifyCurseForge()) throw new IOException("CurseForge 需要配置 PCLNT_CURSEFORGE_API_KEY");
            var data = new JsonObject();
            var values = new JsonArray();
            ids.stream().map(Long::parseLong).forEach(values::add);
            data.add("modIds", values);
            projects = postCatalog(source, "mods", data).getAsJsonArray("data");
        }
        if (projects == null) throw new IOException(source + " 返回了无效的项目列表");
        return projects.asList().stream().map(JsonElement::getAsJsonObject).toList();
    }

    private static Project modrinthProject(JsonObject item) {
        String id = string(item, "id");
        return new Project(Source.MODRINTH, id, string(item, "title"), string(item, "description"),
                URI.create("https://modrinth.com/project/" + encode(id)), https(string(item, "icon_url")),
                strings(item.getAsJsonArray("game_versions")), strings(item.getAsJsonArray("categories")),
                number(item, "downloads"), string(item, "updated"), string(item, "slug"));
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
        URI original = logo != null && logo.isJsonObject()
                ? https(string(logo.getAsJsonObject(), "url")) : null;
        var versions = new java.util.LinkedHashSet<String>();
        var tags = new ArrayList<String>();
        var indexes = item.getAsJsonArray("latestFilesIndexes");
        var categories = item.getAsJsonArray("categories");
        if (indexes != null) indexes.forEach(value -> versions.add(string(value.getAsJsonObject(), "gameVersion")));
        if (categories != null) categories.forEach(value -> tags.add(string(value.getAsJsonObject(), "name")));
        return new Project(Source.CURSEFORGE, string(item, "id"), string(item, "name"), string(item, "summary"),
                https(string(item.getAsJsonObject("links"), "websiteUrl")), icon == null ? original : icon, List.copyOf(versions),
                List.copyOf(tags), number(item, "downloadCount"), string(item, "dateModified"), string(item, "slug"), original);
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
            for (var entry : item.getAsJsonArray("files")) {
                files.add(modrinthFile(item, entry.getAsJsonObject()));
            }
        }
        return List.copyOf(files);
    }

    private static File modrinthFile(JsonObject version, JsonObject file) {
        var releaseType = switch (string(version, "version_type")) {
            case "alpha" -> ReleaseType.ALPHA;
            case "beta" -> ReleaseType.BETA;
            default -> ReleaseType.RELEASE;
        };
        var hashes = file.getAsJsonObject("hashes");
        return new File(string(version, "name"), string(file, "filename"), https(string(file, "url")),
                number(file, "size"), hashes == null ? "" : string(hashes, "sha1"),
                strings(version.getAsJsonArray("game_versions")), strings(version.getAsJsonArray("loaders")),
                releaseType, string(version, "id"),
                requiredProjects(version.getAsJsonArray("dependencies"), "dependency_type", "required", "project_id"),
                published(version, "date_published"));
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
                String path = URI.create(url).getPath();
                boolean search = path.endsWith("/search");
                boolean project = path.matches("/v2/project/[^/]+/?");
                if (search ? !value.isJsonObject() || !value.getAsJsonObject().has("hits")
                        : project ? !value.isJsonObject() || !value.getAsJsonObject().has("id") : !value.isJsonArray()) {
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

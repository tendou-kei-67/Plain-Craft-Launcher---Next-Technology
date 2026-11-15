package io.pclnt.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VanillaInstaller {
    private static final Logger LOG = Logger.getLogger(VanillaInstaller.class.getName());
    private static final String LIBRARY_HOST = "https://libraries.minecraft.net/";
    private static final String ASSET_HOST = "https://resources.download.minecraft.net/";
    private final HttpClient client;
    private final Path root;

    public VanillaInstaller(HttpClient client, Path root) {
        this.client = client;
        this.root = root.toAbsolutePath().normalize();
    }

    public record Instance(String id, Path directory) {}

    public record InstanceInfo(String gameVersion, String type, List<String> loaders) {}

    public InstanceInfo describe(Instance instance) throws IOException {
        var version = launchVersion(instance);
        var loaders = new java.util.LinkedHashSet<String>();
        for (var entry : array(version.metadata(), "libraries")) {
            String coordinate = string(entry.getAsJsonObject(), "name");
            if (coordinate.startsWith("net.fabricmc:fabric-loader:")) loaders.add("Fabric");
            if (coordinate.startsWith("net.minecraftforge:forge:") || coordinate.startsWith("net.minecraftforge:fmlloader:")) loaders.add("Forge");
            if (coordinate.startsWith("net.neoforged:neoforge:") || coordinate.startsWith("net.neoforged.fancymodloader:loader:")) loaders.add("NeoForge");
            if (coordinate.startsWith("org.quiltmc:quilt-loader:")) loaders.add("Quilt");
            if (coordinate.startsWith("com.mumfrey:liteloader:")) loaders.add("LiteLoader");
            if (coordinate.toLowerCase(java.util.Locale.ROOT).startsWith("optifine:optifine:")) loaders.add("OptiFine");
        }
        return new InstanceInfo(version.client().id(), string(version.metadata(), "type"), List.copyOf(loaders));
    }

    private record Download(URI uri, Path target, String sha1, long size) {
        static Download of(JsonObject metadata, Path target) {
            return new Download(URI.create(string(metadata, "url")), target, string(metadata, "sha1"),
                    metadata.has("size") ? metadata.get("size").getAsLong() : -1);
        }
    }

    public Instance install(VanillaCatalog.Version version, BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        var instance = instance(version.id());
        if (!version.sha1().matches("[0-9a-f]{40}")) {
            throw new IOException("版本元数据缺少有效的 SHA-1");
        }
        Path manifest = root.resolve(".pcl-nt-cache").resolve(version.sha1() + ".json");
        fetch(new Download(version.metadata(), manifest, version.sha1(), -1));
        var metadata = readJson(manifest);
        if (!version.id().equals(string(metadata, "id"))) {
            throw new IOException("版本元数据与所选版本不一致");
        }
        fetchAll(clientFiles(instance, metadata, false), progress);
        publishVersion(instance, metadata);
        return instance;
    }

    public void repair(Instance instance, BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        if (!instance(instance.id()).equals(instance)) {
            throw new IOException("实例目录与当前游戏文件夹不匹配");
        }
        var version = launchVersion(instance);
        fetchAll(clientFiles(version.client(), version.metadata(), true), progress);
    }

    private List<Download> clientFiles(Instance client, JsonObject metadata, boolean repair)
            throws IOException, InterruptedException {
        var files = new LinkedHashMap<Path, Download>();
        var downloads = metadata.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("client")) {
            throw new IOException("缺少客户端文件的下载信息");
        }
        addDownload(files, Download.of(downloads.getAsJsonObject("client"), clientJar(client)));
        var logging = metadata.getAsJsonObject("logging");
        if (logging != null && logging.has("client")) {
            var file = logging.getAsJsonObject("client").getAsJsonObject("file");
            addDownload(files, Download.of(file, root.resolve("assets/log_configs").resolve(fileName(string(file, "id")))));
        }
        for (var library : array(metadata, "libraries")) {
            var entry = library.getAsJsonObject();
            if (!libraryApplies(entry)) {
                continue;
            }
            var libraryFiles = entry.getAsJsonObject("downloads");
            if (libraryFiles != null && libraryFiles.has("artifact")) {
                var artifact = libraryFiles.getAsJsonObject("artifact");
                addDownload(files, Download.of(artifact, libraryPath(artifact)));
            } else if (libraryFiles == null) {
                String path = mavenPath(string(entry, "name"));
                String repository = string(entry, "url");
                boolean installerGenerated = repair && repository.isEmpty()
                        && string(entry, "name").toLowerCase(Locale.ROOT).startsWith("optifine:optifine:");
                URI url = installerGenerated ? URI.create("")
                        : URI.create(repository.isEmpty() ? LIBRARY_HOST : repository).resolve(path);
                addDownload(files, new Download(url, resolve(root.resolve("libraries"), path),
                        string(entry, "sha1"), entry.has("size") ? entry.get("size").getAsLong() : -1));
            }
            var nativeFile = nativeDownload(entry);
            if (nativeFile != null) {
                addDownload(files, Download.of(nativeFile, libraryPath(nativeFile)));
            }
        }
        var assetIndex = metadata.getAsJsonObject("assetIndex");
        if (assetIndex != null) {
            String id = fileName(string(assetIndex, "id"));
            var index = root.resolve("assets/indexes").resolve(id + ".json");
            fetch(Download.of(assetIndex, index), repair);
            var assets = readJson(index);
            var objects = assets.getAsJsonObject("objects");
            var seen = new java.util.HashSet<String>();
            for (var object : objects.entrySet()) {
                var asset = object.getValue().getAsJsonObject();
                String hash = string(asset, "hash");
                if (!hash.matches("[0-9a-f]{40}")) {
                    throw new IOException("无效的资源哈希");
                }
                if (seen.add(hash)) {
                    String location = hash.substring(0, 2) + "/" + hash;
                    addDownload(files, new Download(URI.create(ASSET_HOST + location), root.resolve("assets/objects").resolve(location),
                            hash, asset.get("size").getAsLong()));
                }
            }
        }
        return List.copyOf(files.values());
    }

    private static void addDownload(Map<Path, Download> files, Download file) {
        files.putIfAbsent(file.target(), file);
    }

    public Instance installFabric(VanillaCatalog.Version version, FabricMeta.Loader loader,
                                  BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        return installFabric(version, loader, null, progress);
    }

    public Instance installFabric(VanillaCatalog.Version version, FabricMeta.Loader loader, Modrinth.Release api,
                                  BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        return installFabric(version, loader, api, instance -> true, progress);
    }

    public Instance installFabric(VanillaCatalog.Version version, FabricMeta.Loader loader, Modrinth.Release api,
                                  java.util.function.Predicate<Instance> isolated,
                                  BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        var profile = new FabricMeta(client).profile(version.id(), loader);
        String profileId = string(profile, "id");
        if (!version.id().replace("∞", "infinite").equals(string(profile, "inheritsFrom"))
                || profileId.equalsIgnoreCase(version.id())) {
            throw new IOException("Fabric 安装配置与所选 Minecraft 版本不一致");
        }
        profile.addProperty("inheritsFrom", version.id());
        var instance = instance(profileId);
        var dependencies = new ArrayList<Download>();
        for (var element : array(profile, "libraries")) {
            var library = element.getAsJsonObject();
            String location = mavenPath(string(library, "name"));
            var uri = URI.create("https://maven.fabricmc.net/").resolve(location);
            String hash = string(library, "sha1");
            if (hash.isEmpty()) {
                hash = remoteHash(uri);
            }
            if (!hash.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("Fabric 依赖缺少有效的 SHA-1：" + location);
            }
            dependencies.add(new Download(uri, resolve(root.resolve("libraries"), location), hash,
                    library.has("size") ? library.get("size").getAsLong() : -1));
        }
        int extra = dependencies.size() + (api == null ? 0 : 1);
        int[] baseFiles = {0};
        install(version, (completed, total) -> {
            baseFiles[0] = total;
            progress.accept(completed, total + extra);
        });
        fetchAll(dependencies, (completed, total) -> progress.accept(baseFiles[0] + completed, baseFiles[0] + extra));
        if (api != null) {
            var file = api.file();
            if (!file.name().endsWith(".jar") || !file.sha1().matches("[0-9a-fA-F]{40}") || file.size() <= 0
                    || !"cdn.modrinth.com".equalsIgnoreCase(file.url().getHost())) {
                throw new IOException("Modrinth 返回了无效的 Fabric API 文件");
            }
            boolean independent = isolated.test(instance);
            var mods = gameDirectory(instance, independent).resolve("mods");
            var target = mods.resolve(independent ? "fabric-api-pclnt.jar" : fileName(file.name()));
            if (Files.isDirectory(mods)) {
                try (var files = Files.list(mods)) {
                    if (files.anyMatch(path -> !path.equals(target) && Files.isRegularFile(path)
                            && path.getFileName().toString().matches("(?i)fabric-api[+-].*\\.jar"))) {
                        throw new IOException("mods 文件夹已有其他 Fabric API，请先检查并移除冲突文件");
                    }
                }
            }
            if (!independent && Files.exists(target) && !matches(new Download(file.url(), target, file.sha1(), file.size()))) {
                throw new IOException("共享 mods 文件夹已有同名但内容不同的 Fabric API");
            }
            fetch(new Download(file.url(), target, file.sha1(), file.size()));
            progress.accept(baseFiles[0] + extra, baseFiles[0] + extra);
        }
        publishVersion(instance, profile);
        return instance;
    }

    void fetchVerified(URI uri, Path target) throws IOException, InterruptedException {
        String hash = remoteHash(uri);
        if (!hash.matches("[0-9a-fA-F]{40}")) {
            throw new IOException("安装器缺少有效的 SHA-1");
        }
        fetch(new Download(uri, target, hash, -1));
    }

    void fetchFromOfficialSite(URI uri, Path target) throws IOException, InterruptedException {
        fetch(new Download(uri, target, "", -1));
    }

    private String remoteHash(URI uri) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(uri + ".sha1")).timeout(Duration.ofSeconds(25)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("无法获取文件校验值（HTTP " + response.statusCode() + "）");
        }
        return response.body().strip();
    }

    private void fetchAll(List<Download> downloads, BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        progress.accept(0, downloads.size());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var completions = new java.util.concurrent.ExecutorCompletionService<Void>(workers);
            var tasks = new ArrayList<java.util.concurrent.Future<Void>>();
            for (var file : downloads) {
                tasks.add(completions.submit(() -> {
                    fetch(file);
                    return null;
                }));
            }
            try {
                for (int completed = 1; completed <= tasks.size(); completed++) {
                    completions.take().get();
                    progress.accept(completed, tasks.size());
                }
            } catch (ExecutionException e) {
                throw new IOException(e.getCause().getMessage(), e.getCause());
            } finally {
                tasks.forEach(task -> task.cancel(true));
            }
        }
    }

    static void publishVersion(Instance instance, JsonObject metadata) throws IOException {
        Path versionJson = versionJson(instance);
        Files.createDirectories(versionJson.getParent());
        Path pending = Files.createTempFile(versionJson.getParent(), "version-", ".part");
        try {
            Files.writeString(pending, metadata.toString());
            replace(pending, versionJson);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public List<Instance> installed() throws IOException {
        return instances().stream().filter(this::hasClient).toList();
    }

    public List<Instance> instances() throws IOException {
        var versions = root.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (var folders = Files.list(versions)) {
            return folders.filter(Files::isDirectory).map(path -> new Instance(path.getFileName().toString(), path))
                    .filter(instance -> Files.isRegularFile(versionJson(instance)))
                    .sorted(Comparator.comparing(Instance::id, String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    private boolean hasClient(Instance instance) {
        if (!Files.isRegularFile(versionJson(instance))) {
            return false;
        }
        if (Files.isRegularFile(clientJar(instance))) {
            return true;
        }
        try {
            String parent = string(readJson(versionJson(instance)), "inheritsFrom");
            if (parent.isBlank() || instance.id().equalsIgnoreCase(parent)) {
                return false;
            }
            var base = instance(parent);
            return Files.isRegularFile(versionJson(base)) && Files.isRegularFile(clientJar(base));
        } catch (IOException | com.google.gson.JsonParseException e) {
            return false;
        }
    }

    record LaunchVersion(JsonObject metadata, Instance client) {}

    LaunchVersion launchVersion(Instance instance) throws IOException {
        var child = readJson(versionJson(instance));
        String parentId = string(child, "inheritsFrom");
        if (parentId.isEmpty()) {
            return new LaunchVersion(child, instance);
        }
        if (parentId.equalsIgnoreCase(instance.id())) {
            throw new IOException("版本不能继承自身");
        }
        var parent = instance(parentId);
        var merged = readJson(versionJson(parent));
        if (merged.has("inheritsFrom")) {
            throw new IOException("暂不支持多层版本继承");
        }
        for (String key : List.of("mainClass", "type", "minecraftArguments")) {
            if (child.has(key)) {
                merged.add(key, child.get(key));
            }
        }
        var arguments = merged.getAsJsonObject("arguments");
        if (arguments != null && child.has("arguments")) {
            var childArguments = child.getAsJsonObject("arguments");
            for (String kind : List.of("jvm", "game")) {
                var values = array(arguments, kind);
                values.addAll(array(childArguments, kind));
                arguments.add(kind, values);
            }
        }
        var libraries = new LinkedHashMap<String, JsonElement>();
        for (JsonObject version : List.of(merged, child)) {
            for (var element : array(version, "libraries")) {
                var library = element.getAsJsonObject();
                if (libraryApplies(library)) {
                    String[] parts = string(library, "name").split(":", -1);
                    String key = parts.length >= 3
                            ? parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "")
                            : string(library, "name");
                    libraries.put(key, element);
                }
            }
        }
        var list = new JsonArray();
        libraries.values().forEach(list::add);
        merged.add("libraries", list);
        return new LaunchVersion(merged, parent);
    }

    public Path gameDirectory(Instance instance, boolean isolated) {
        return isolated ? instance.directory() : root;
    }

    public Path root() {
        return root;
    }

    Instance instance(String id) throws IOException {
        return new Instance(id, root.resolve("versions").resolve(fileName(id)));
    }

    private void fetch(Download file) throws IOException, InterruptedException {
        fetch(file, false);
    }

    private void fetch(Download file, boolean refresh) throws IOException, InterruptedException {
        if (!refresh && Files.isRegularFile(file.target()) && matches(file)) {
            return;
        }
        if (file.uri().toString().isEmpty()) {
            throw new IOException("缺少安装器生成的文件 " + file.target().getFileName() + "，请重新安装对应组件");
        }
        if (!"https".equalsIgnoreCase(file.uri().getScheme()) || file.uri().getHost() == null) {
            throw new IOException("下载地址必须使用 HTTPS");
        }
        Files.createDirectories(file.target().getParent());
        Path pending = Files.createTempFile(file.target().getParent(), "download-", ".part");
        IOException last = null;
        try {
            var sources = new ArrayList<>(file.sha1().isBlank() ? List.of(file.uri()) : DownloadMirrors.sources(file.uri()));
            if (!DownloadMirrors.isMirror(file.uri())) sources.add(file.uri());
            for (int attempt = 0; attempt < sources.size(); attempt++) {
                var source = sources.get(attempt);
                try {
                    boolean mirror = DownloadMirrors.isMirror(source);
                    var request = HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(mirror ? 10 : 45));
                    if (attempt == sources.size() - 1) {
                        request.version(HttpClient.Version.HTTP_1_1);
                    }
                    long timeout = Math.clamp(file.size() / (64 * 1024), 60, 600);
                    var response = download(request.build(), pending, file.target(), timeout, mirror ? 15 : 60);
                    if (response.statusCode() != 200) {
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    if (!matches(new Download(file.uri(), pending, file.sha1(), file.size()))) {
                        throw new IOException("文件校验失败");
                    }
                    replace(pending, file.target());
                    return;
                } catch (IOException e) {
                    last = e;
                    LOG.log(Level.FINE, "Download failed: " + source, e);
                }
            }
            throw new IOException("下载 " + file.target().getFileName() + " 失败：" + last.getMessage(), last);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private HttpResponse<Path> download(HttpRequest request, Path pending, Path target, long timeoutSeconds, int pollSeconds)
            throws IOException, InterruptedException {
        var transfer = client.sendAsync(request, TaskIo.download(pending, target,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
        try {
            long size = 0;
            for (long remaining = timeoutSeconds; remaining > 0; remaining -= pollSeconds) {
                try {
                    return transfer.get(Math.min(pollSeconds, remaining), TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    long written = Files.size(pending);
                    if (written <= size || remaining <= pollSeconds) {
                        throw new IOException("文件传输超时", e);
                    }
                    size = written;
                }
            }
            throw new IOException("文件传输超时");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } finally {
            transfer.cancel(true);
        }
    }

    private static boolean matches(Download file) throws IOException {
        if (file.size() >= 0 && Files.size(file.target()) != file.size()) {
            return false;
        }
        if (file.sha1().isEmpty()) {
            return Files.size(file.target()) > 0;
        }
        if (!file.target().getFileName().toString().endsWith(".part")) TaskIo.currentFile(file.target());
        try (InputStream input = TaskIo.read(Files.newInputStream(file.target()));
                var digest = new DigestInputStream(input, MessageDigest.getInstance("SHA-1"))) {
            digest.transferTo(OutputStream.nullOutputStream());
            return file.sha1().equalsIgnoreCase(HexFormat.of().formatHex(digest.getMessageDigest().digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static JsonObject readJson(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("无法读取 " + path.getFileName(), e);
        }
    }

    static Path versionJson(Instance instance) {
        return instance.directory().resolve(instance.id() + ".json");
    }

    static Path clientJar(Instance instance) {
        return instance.directory().resolve(instance.id() + ".jar");
    }

    static String string(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static JsonArray array(JsonObject object, String key) {
        var value = object.getAsJsonArray(key);
        return value == null ? new JsonArray() : value;
    }

    static Path resolve(Path base, String relative) throws IOException {
        Path path = base.resolve(relative).normalize();
        if (!path.startsWith(base) || path.equals(base)) {
            throw new IOException("无效的文件路径：" + relative);
        }
        return path;
    }

    static String fileName(String value) throws IOException {
        if (value.isBlank() || value.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")
                || value.endsWith(".") || value.endsWith(" ")) {
            throw new IOException("无效的文件名：" + value);
        }
        return value;
    }

    Path libraryPath(JsonObject artifact) throws IOException {
        return resolve(root.resolve("libraries"), string(artifact, "path"));
    }

    static String mavenPath(String coordinate) throws IOException {
        String[] parts = coordinate.split(":", -1);
        if (parts.length < 3 || parts.length > 4) {
            throw new IOException("无效的库名称：" + coordinate);
        }
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-"
                + parts[2] + (parts.length == 4 ? "-" + parts[3] : "") + ".jar";
    }

    static JsonObject nativeDownload(JsonObject library) {
        var natives = library.getAsJsonObject("natives");
        var downloads = library.getAsJsonObject("downloads");
        if (natives == null || downloads == null || !downloads.has("classifiers")) {
            return null;
        }
        String classifier = string(natives, platform()).replace("${arch}", System.getProperty("os.arch").contains("64") ? "64" : "32");
        return downloads.getAsJsonObject("classifiers").getAsJsonObject(classifier);
    }

    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "osx" : "linux";
    }

    static boolean libraryApplies(JsonObject library) {
        if (!rulesAllow(library, Map.of())) {
            return false;
        }
        String[] coordinate = string(library, "name").split(":", -1);
        if (coordinate.length != 4 || !coordinate[3].startsWith("natives-")) {
            return true;
        }
        String classifier = coordinate[3];
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x86 = arch.equals("x86") || arch.matches("i[3-6]86");
        if (classifier.endsWith("-arm64")) {
            return arm;
        }
        if (classifier.endsWith("-x86")) {
            return x86;
        }
        if (classifier.endsWith("-arm32")) {
            return arch.equals("arm");
        }
        return !arm && !x86 && !arch.equals("arm");
    }

    static boolean rulesAllow(JsonObject item, Map<String, Boolean> features) {
        if (!item.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (var element : array(item, "rules")) {
            var rule = element.getAsJsonObject();
            var os = rule.getAsJsonObject("os");
            if (os != null && ((os.has("name") && !platform().equals(string(os, "name")))
                    || (os.has("version") && !java.util.regex.Pattern.compile(string(os, "version"))
                            .matcher(System.getProperty("os.version")).find())
                    || (os.has("arch") && !java.util.regex.Pattern.compile(string(os, "arch"))
                            .matcher(System.getProperty("os.arch")).matches()))) {
                continue;
            }
            var required = rule.getAsJsonObject("features");
            if (required != null && !required.entrySet().stream().allMatch(
                    feature -> features.getOrDefault(feature.getKey(), false) == feature.getValue().getAsBoolean())) {
                continue;
            }
            allowed = "allow".equals(string(rule, "action"));
        }
        return allowed;
    }
}

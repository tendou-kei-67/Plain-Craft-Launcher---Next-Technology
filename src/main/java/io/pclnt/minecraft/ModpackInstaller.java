package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;

public final class ModpackInstaller {
    private static final int MAX_INDEX = 8 * 1024 * 1024;
    private static final long MAX_OVERRIDES = 2L * 1024 * 1024 * 1024;
    private static final Set<String> DOWNLOAD_HOSTS = Set.of("cdn.modrinth.com", "github.com",
            "raw.githubusercontent.com", "gitlab.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    private final HttpClient client;
    private final VanillaInstaller vanilla;
    private final CommunityResources resources;
    private final HttpClient downloads = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public ModpackInstaller(HttpClient client, VanillaInstaller vanilla, CommunityResources resources) {
        this.client = client;
        this.vanilla = vanilla;
        this.resources = resources;
    }

    public record Pack(String name, String minecraft, String loader, String loaderVersion, List<String> optionalFiles) {}

    public Pack inspect(Path archive) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            return manifest(zip).pack();
        }
    }

    public VanillaInstaller.Instance install(Path archive, String name, Set<String> optional,
                                             BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        var instance = vanilla.instance(ResourceDestination.suggestedName(CommunityResources.Kind.MODPACK, name));
        if (Files.exists(instance.directory(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("已有同名版本");
        var cache = vanilla.root().resolve(".pcl-nt-cache");
        Files.createDirectories(cache);
        var staging = Files.createTempDirectory(cache, "mrpack-");
        try (var zip = new ZipFile(archive.toFile())) {
            var manifest = manifest(zip);
            var pack = manifest.pack();
            if (!pack.optionalFiles().containsAll(optional)) throw new IOException("可选文件与整合包索引不匹配");
            if (manifest.curseForge()) {
                installCurseFiles(manifest, optional, staging, instance.id(), progress);
                if (!manifest.overrides().isEmpty()) {
                    String prefix = manifest.overrides().equals("./") ? "" : manifest.overrides();
                    copyOverrides(zip, staging, instance.id(), prefix, MAX_OVERRIDES);
                }
            } else {
                var entries = entries(zip, optional);
                var targets = new java.util.HashSet<String>();
                for (var entry : entries) {
                    var path = packPath(staging, entry.path(), instance.id());
                    uniqueTarget(staging, targets, path);
                }
                int done = 0;
                progress.accept(done, entries.size() + 1);
                for (var entry : entries) {
                    fetch(entry, packPath(staging, entry.path(), instance.id()));
                    progress.accept(++done, entries.size() + 1);
                }
                long remaining = copyOverrides(zip, staging, instance.id(), "overrides/", MAX_OVERRIDES);
                copyOverrides(zip, staging, instance.id(), "client-overrides/", remaining);
            }
            var base = installBase(pack, (count, total) -> progress.accept(count, total + 1));
            var metadata = VanillaInstaller.readJson(VanillaInstaller.versionJson(base));
            if (!metadata.has("inheritsFrom")) {
                metadata = new JsonObject();
                metadata.addProperty("inheritsFrom", base.id());
            }
            metadata.addProperty("id", instance.id());
            var prepared = new VanillaInstaller.Instance(instance.id(), staging);
            VanillaInstaller.publishVersion(prepared, metadata);
            Files.createDirectories(instance.directory().getParent());
            Files.move(staging, instance.directory());
            progress.accept(1, 1);
            return instance;
        } finally {
            if (Files.isDirectory(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    private record CurseReference(long project, long file, boolean required) {
        String label() { return "项目 " + project + " / 文件 " + file; }
    }

    private record Manifest(Pack pack, boolean curseForge, List<CurseReference> references, String overrides) {}

    private static Manifest manifest(ZipFile zip) throws IOException {
        if (zip.getEntry("modrinth.index.json") != null) return new Manifest(parse(zip), false, List.of(), "");
        try {
            var index = index(zip, "manifest.json");
            if (!"minecraftModpack".equals(VanillaInstaller.string(index, "manifestType"))
                    || index.get("manifestVersion").getAsInt() != 1) throw new IOException("不支持的 CurseForge 整合包格式");
            var minecraft = index.getAsJsonObject("minecraft");
            String game = required(minecraft, "version");
            var loaders = VanillaInstaller.array(minecraft, "modLoaders");
            String loader = "";
            String version = "";
            if (!loaders.isEmpty()) {
                JsonObject primary = loaders.get(0).getAsJsonObject();
                for (var entry : loaders) {
                    var candidate = entry.getAsJsonObject();
                    if (candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
                        primary = candidate;
                        break;
                    }
                }
                String id = required(primary, "id");
                int split = id.indexOf('-');
                if (split <= 0 || split == id.length() - 1) throw new IOException("无效的整合包加载器：" + id);
                loader = switch (id.substring(0, split)) {
                    case "fabric" -> "fabric-loader";
                    case "forge" -> "forge";
                    case "neoforge" -> "neoforge";
                    default -> throw new IOException("暂不支持整合包加载器：" + id);
                };
                version = id.substring(split + 1);
            }
            var references = new ArrayList<CurseReference>();
            var seen = new java.util.HashSet<Long>();
            for (var entry : index.getAsJsonArray("files")) {
                var file = entry.getAsJsonObject();
                long project = file.get("projectID").getAsLong();
                long id = file.get("fileID").getAsLong();
                if (project <= 0 || id <= 0 || !seen.add(id)) throw new IOException("整合包内的 CurseForge 文件引用无效或重复");
                references.add(new CurseReference(project, id, !file.has("required") || file.get("required").getAsBoolean()));
            }
            var overrides = VanillaInstaller.string(index, "overrides");
            if (overrides.endsWith("/")) overrides = overrides.substring(0, overrides.length() - 1);
            if (overrides.equals(".")) overrides = "";
            if (!overrides.isEmpty()) {
                validateRelativePath(overrides);
                overrides += "/";
            } else if (VanillaInstaller.string(index, "overrides").matches("\\./?")) {
                overrides = "./";
            }
            var optional = references.stream().filter(ref -> !ref.required()).map(CurseReference::label).toList();
            return new Manifest(new Pack(required(index, "name"), game, loader, version, optional),
                    true, List.copyOf(references), overrides);
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("CurseForge 整合包清单格式无效", e);
        }
    }

    private void installCurseFiles(Manifest manifest, Set<String> optional, Path staging, String name,
                                   BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        var needed = manifest.references().stream().filter(ref -> ref.required() || optional.contains(ref.label())).toList();
        if (needed.isEmpty()) return;
        var lookup = resources.cursePackFiles(needed.stream().map(CurseReference::file).toList());
        var downloads = new java.util.LinkedHashMap<Path, CommunityResources.File>();
        var targets = new java.util.HashSet<String>();
        for (var reference : needed) {
            var result = lookup.get(reference.file());
            if (result == null || result.metadata().get("modId").getAsLong() != reference.project()) {
                throw new IOException("CurseForge 找不到引用的文件：" + reference.label());
            }
            if (result.file().url() == null) throw new IOException("作者未开放第三方下载：" + reference.label());
            String folder = curseFolder(result.metadata(), result.file().name());
            String fileName = ResourceDestination.suggestedName(CommunityResources.Kind.MODPACK, result.file().name());
            var target = packPath(staging, folder + "/" + fileName, name);
            uniqueTarget(staging, targets, target);
            downloads.put(target, result.file());
        }
        int done = 0;
        progress.accept(done, downloads.size() + 1);
        for (var download : downloads.entrySet()) {
            Files.createDirectories(download.getKey().getParent());
            resources.download(download.getValue(), download.getKey());
            progress.accept(++done, downloads.size() + 1);
        }
    }

    private static String curseFolder(JsonObject metadata, String filename) {
        var modules = new java.util.HashSet<String>();
        for (var entry : VanillaInstaller.array(metadata, "modules")) {
            modules.add(VanillaInstaller.string(entry.getAsJsonObject(), "name"));
        }
        if (filename.toLowerCase(Locale.ROOT).endsWith(".jar") || modules.contains("META-INF")
                || modules.contains("mcmod.info") || modules.contains("fabric.mod.json")) return "mods";
        if (modules.contains("shaders")) return "shaderpacks";
        if (modules.contains("pack.mcmeta")) return "resourcepacks";
        return "mods";
    }

    private static void uniqueTarget(Path staging, Set<String> targets, Path path) throws IOException {
        if (!targets.add(staging.relativize(path).toString().toLowerCase(Locale.ROOT))) {
            throw new IOException("整合包中有重复文件：" + path.getFileName());
        }
    }

    private VanillaInstaller.Instance installBase(Pack pack, BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        var game = VanillaCatalog.load(client).versions().stream().filter(version -> version.id().equals(pack.minecraft()))
                .findFirst().orElseThrow(() -> new IOException("官方目录中没有 Minecraft " + pack.minecraft()));
        if ("fabric-loader".equals(pack.loader())) {
            return vanilla.installFabric(game, new FabricMeta.Loader(pack.loaderVersion(), false), progress);
        }
        if (pack.loader().isEmpty()) return vanilla.install(game, progress);
        var forge = new ForgeInstaller(client, vanilla);
        var distribution = "forge".equals(pack.loader()) ? ForgeInstaller.Distribution.FORGE : ForgeInstaller.Distribution.NEOFORGE;
        var release = forge.releases(distribution, game.id()).stream().filter(item ->
                item.version().equals(pack.loaderVersion()) || item.coordinate().equals(pack.loaderVersion()))
                .findFirst().orElseThrow(() -> new IOException("找不到整合包要求的 " + pack.loader() + " " + pack.loaderVersion()));
        return forge.install(game, release, progress);
    }

    private static Pack parse(ZipFile zip) throws IOException {
        var index = index(zip, "modrinth.index.json");
        try {
            if (index.get("formatVersion").getAsInt() != 1 || !"minecraft".equals(VanillaInstaller.string(index, "game"))) {
                throw new IOException("本启动器支持 Minecraft 的第 1 版 .mrpack 格式");
            }
            var dependencies = index.getAsJsonObject("dependencies");
            String game = required(dependencies, "minecraft");
            String loader = "";
            String loaderVersion = "";
            for (var dependency : dependencies.keySet()) {
                if (dependency.equals("minecraft")) continue;
                if (!loader.isEmpty() || !Set.of("fabric-loader", "forge", "neoforge", "neo-forge").contains(dependency)) {
                    throw new IOException("暂不支持整合包的加载器组合：" + dependency);
                }
                loader = dependency;
                loaderVersion = required(dependencies, dependency);
            }
            var optional = new ArrayList<String>();
            for (var entry : index.getAsJsonArray("files")) {
                var file = entry.getAsJsonObject();
                if ("optional".equals(environment(file))) optional.add(required(file, "path"));
            }
            return new Pack(required(index, "name"), game, loader, loaderVersion, List.copyOf(optional));
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("整合包索引格式无效", e);
        }
    }

    private record Entry(String path, String sha512, List<URI> sources) {}

    private static List<Entry> entries(ZipFile zip, Set<String> optional) throws IOException {
        var result = new ArrayList<Entry>();
        try {
            for (var item : index(zip, "modrinth.index.json").getAsJsonArray("files")) {
                var file = item.getAsJsonObject();
                var path = required(file, "path");
                String environment = environment(file);
                if ("unsupported".equals(environment) || "optional".equals(environment) && !optional.contains(path)) continue;
                String hash = required(file.getAsJsonObject("hashes"), "sha512");
                if (!hash.matches("[0-9a-fA-F]{128}")) throw new IOException("无效的文件校验和：" + path);
                var sources = new ArrayList<URI>();
                for (var source : file.getAsJsonArray("downloads")) sources.add(URI.create(source.getAsString()));
                if (sources.isEmpty()) throw new IOException("文件缺少下载地址：" + path);
                result.add(new Entry(path, hash, List.copyOf(sources)));
            }
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("整合包文件列表无效", e);
        }
        return result;
    }

    private static JsonObject index(ZipFile zip, String name) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("整合包缺少 " + name);
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_INDEX + 1);
            if (bytes.length > MAX_INDEX) throw new IOException("整合包索引过大");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("整合包索引格式无效", e);
        }
    }

    private void fetch(Entry entry, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        IOException last = null;
        for (var source : entry.sources()) {
            try {
                download(source, target);
                if (matches(target, entry.sha512())) return;
                throw new IOException("SHA-512 校验失败");
            } catch (IOException error) {
                last = error;
                Files.deleteIfExists(target);
            }
        }
        throw new IOException("无法下载整合包文件 " + entry.path() + "：" + last.getMessage(), last);
    }

    private void download(URI address, Path target) throws IOException, InterruptedException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                    || !DOWNLOAD_HOSTS.contains(address.getHost().toLowerCase(Locale.ROOT)) || address.getUserInfo() != null
                    || address.getPort() != -1 && address.getPort() != 443) {
                throw new IOException("文件使用不受支持的整合包下载地址");
            }
            var request = HttpRequest.newBuilder(address).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "PCL-NT/0.1 (Minecraft launcher)").build();
            var transfer = downloads.sendAsync(request, TaskIo.download(target, target));
            HttpResponse<Path> response;
            try {
                response = transfer.get(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("文件下载失败", e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IOException("文件下载超时", e);
            } finally {
                transfer.cancel(true);
            }
            if (response.statusCode() == 200) return;
            if (response.statusCode() < 300 || response.statusCode() > 399) throw new IOException("HTTP " + response.statusCode());
            try {
                address = address.resolve(response.headers().firstValue("Location").orElseThrow(
                        () -> new IOException("重定向缺少目标地址")));
            } catch (IllegalArgumentException e) {
                throw new IOException("重定向地址无效", e);
            }
            Files.deleteIfExists(target);
        }
        throw new IOException("整合包文件重定向次数过多");
    }

    private static boolean matches(Path path, String expected) throws IOException {
        TaskIo.currentFile(path);
        try (var input = new DigestInputStream(TaskIo.read(Files.newInputStream(path)), MessageDigest.getInstance("SHA-512"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest()).equalsIgnoreCase(expected);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long copyOverrides(ZipFile zip, Path staging, String name, String prefix, long remaining)
            throws IOException, InterruptedException {
        var entries = zip.entries();
        byte[] buffer = new byte[8192];
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (!entry.getName().startsWith(prefix) || entry.isDirectory()
                    || prefix.isEmpty() && entry.getName().equals("manifest.json")) continue;
            var path = packPath(staging, entry.getName().substring(prefix.length()), name);
            Files.createDirectories(path.getParent());
            TaskIo.currentFile(path);
            try (var input = zip.getInputStream(entry); var output = TaskIo.write(Files.newOutputStream(path))) {
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    remaining -= count;
                    if (remaining < 0) throw new IOException("整合包覆盖文件解压后超过大小限制");
                    output.write(buffer, 0, count);
                }
            }
        }
        return remaining;
    }

    private static Path packPath(Path root, String relative, String name) throws IOException {
        validateRelativePath(relative);
        var target = VanillaInstaller.resolve(root, relative);
        if (target.getParent().equals(root) && (target.getFileName().toString().equalsIgnoreCase(name + ".json")
                || target.getFileName().toString().equalsIgnoreCase(name + ".jar"))) {
            throw new IOException("整合包文件与游戏核心重名：" + relative);
        }
        return target;
    }

    private static void validateRelativePath(String relative) throws IOException {
        for (String part : relative.split("/", -1)) {
            if (part.equals(".") || part.equals("..")) throw new IOException("整合包文件路径无效：" + relative);
            ResourceDestination.suggestedName(CommunityResources.Kind.MODPACK, part);
        }
    }

    private static String environment(JsonObject file) throws IOException {
        var env = file.getAsJsonObject("env");
        if (env == null) return "required";
        var value = VanillaInstaller.string(env, "client");
        if (!Set.of("required", "optional", "unsupported").contains(value)) throw new IOException("无效的客户端文件环境");
        return value;
    }

    private static String required(JsonObject object, String field) throws IOException {
        String value = VanillaInstaller.string(object, field);
        if (value.isBlank()) throw new IOException("整合包缺少 " + field);
        return value;
    }
}

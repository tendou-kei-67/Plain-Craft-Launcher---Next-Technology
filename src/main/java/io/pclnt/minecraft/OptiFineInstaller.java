package io.pclnt.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class OptiFineInstaller {
    private static final URI SITE = URI.create("https://optifine.net/");
    private static final URI BMCL_LIST = URI.create("https://bmclapi2.bangbang93.com/optifine/versionList");
    private static final Pattern MIRROR = Pattern.compile(
            "href=['\"]https?://optifine\\.net/adloadx\\?f=((?:preview_)?OptiFine_[\\w.]+\\.jar)['\"]");
    private static final Pattern DOWNLOAD = Pattern.compile("href=['\"](downloadx\\?f=[\\w.]+&(?:amp;)?x=[a-fA-F0-9]+)['\"]");
    private static final Pattern BMCL_EDITION = Pattern.compile("^(HD_U(?:_[A-Z]+)?)_([A-Z][0-9]+)(?:_(pre[0-9]+))?$");
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>.*?</tr>", Pattern.DOTALL);
    private static final Pattern FORGE = Pattern.compile("<td class=['\"]colForge['\"]>\\s*Forge\\s+([\\d.]+)\\s*</td>");
    private final HttpClient client;
    private final VanillaInstaller vanilla;

    public OptiFineInstaller(HttpClient client, VanillaInstaller vanilla) {
        this.client = client;
        this.vanilla = vanilla;
    }

    public record Release(String file, String version, boolean preview, String forgeVersion) {
        public boolean supports(ForgeInstaller.Release forge) {
            if (forge == null || forge.distribution() != ForgeInstaller.Distribution.FORGE || forgeVersion.isBlank()) {
                return false;
            }
            return forgeVersion.contains(".") ? forgeVersion.equals(forge.version())
                    : forge.version().endsWith("." + forgeVersion);
        }
    }

    public List<Release> releases(String game) throws IOException, InterruptedException {
        try {
            var official = officialReleases(game);
            if (!official.isEmpty()) return official;
        } catch (IOException officialError) {
            try {
                return mirroredReleases(game);
            } catch (IOException mirrorError) {
                officialError.addSuppressed(mirrorError);
                throw officialError;
            }
        }
        return mirroredReleases(game);
    }

    private List<Release> mirroredReleases(String game) throws IOException, InterruptedException {
        var releases = DownloadMirrors.read(client, BMCL_LIST, body -> {
            var result = new LinkedHashMap<String, Release>();
            String prefix = "OptiFine_" + game + "_";
            for (var entry : JsonParser.parseString(body).getAsJsonArray()) {
                var item = entry.getAsJsonObject();
                String file = VanillaInstaller.string(item, "filename");
                String plain = file.replaceFirst("^preview_", "");
                if (!plain.startsWith(prefix) || !file.matches("(?:preview_)?OptiFine_[\\w.]+\\.jar")) continue;
                String forge = VanillaInstaller.string(item, "forge").replaceFirst("(?i)^Forge\\s+#?", "");
                result.putIfAbsent(file, new Release(file,
                        plain.substring(prefix.length(), plain.length() - 4).replace('_', ' '),
                        file.startsWith("preview_"), forge.matches("[0-9]+(?:\\.[0-9]+)*") ? forge : ""));
            }
            return result.values().stream().sorted(Comparator.comparing(Release::preview)).toList();
        });
        return releases == null ? List.of() : releases;
    }

    private List<Release> officialReleases(String game) throws IOException, InterruptedException {
        var names = new LinkedHashMap<String, Release>();
        var rows = ROW.matcher(page("downloads"));
        String prefix = "OptiFine_" + game + "_";
        while (rows.find()) {
            var matches = MIRROR.matcher(rows.group());
            if (!matches.find()) {
                continue;
            }
            String file = matches.group(1);
            String plain = file.replaceFirst("^preview_", "");
            if (!plain.startsWith(prefix)) {
                continue;
            }
            var forge = FORGE.matcher(rows.group());
            names.putIfAbsent(file, new Release(file, plain.substring(prefix.length(), plain.length() - 4).replace('_', ' '),
                    file.startsWith("preview_"), forge.find() ? forge.group(1) : ""));
        }
        return names.values().stream().sorted(Comparator.comparing(Release::preview)).toList();
    }

    public VanillaInstaller.Instance install(VanillaCatalog.Version game, Release release,
                                             BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        String name = validateName(game, release);
        var cache = Files.createDirectories(vanilla.root().resolve(".pcl-nt-cache/installers"));
        var log = cache.resolve(name + ".log");
        var stage = Files.createTempDirectory(cache, "optifine-");
        try {
            progress.accept(0, 0);
            Path jar = stage.resolve(name);
            download(game.id(), name, jar);
            var base = vanilla.install(game, (completed, total) -> progress.accept(completed, total + 1));
            if (usesLaunchWrapper(game.id())) {
                var instance = installLegacy(base, name, jar);
                progress.accept(1, 1);
                return instance;
            }
            int required = installerJava(jar);
            Path root = Files.createDirectories(stage.resolve(".minecraft"));
            Path versionFolder = Files.createDirectories(root.resolve("versions").resolve(game.id()));
            Files.copy(VanillaInstaller.clientJar(base), versionFolder.resolve(game.id() + ".jar"));
            Files.copy(VanillaInstaller.versionJson(base), versionFolder.resolve(game.id() + ".json"));
            Files.writeString(root.resolve("launcher_profiles.json"), "{\"profiles\":{},\"settings\":{}}");
            runInstaller(jar, root, log, required);
            var generated = new VanillaInstaller(client, root);
            var candidates = generated.installed().stream().filter(instance -> !instance.id().equals(game.id())).toList();
            if (candidates.size() != 1) {
                throw new IOException("OptiFine 安装器没有生成预期的版本。日志：" + log);
            }
            var source = candidates.getFirst();
            var profile = VanillaInstaller.readJson(VanillaInstaller.versionJson(source));
            if (!source.id().equals(VanillaInstaller.string(profile, "id"))
                    || !game.id().equals(VanillaInstaller.string(profile, "inheritsFrom"))) {
                throw new IOException("OptiFine 安装器生成的版本配置无效。日志：" + log);
            }
            var target = vanilla.instance(source.id());
            Path libraries = root.resolve("libraries");
            if (Files.isDirectory(libraries)) {
                try (var paths = Files.walk(libraries)) {
                    for (var file : paths.filter(Files::isRegularFile).toList()) {
                        var destination = VanillaInstaller.resolve(vanilla.root().resolve("libraries"),
                                libraries.relativize(file).toString());
                        Files.createDirectories(destination.getParent());
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            VanillaInstaller.publishVersion(target, profile);
            progress.accept(1, 1);
            return target;
        } finally {
            try (var paths = Files.walk(stage)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public VanillaInstaller.Instance installWithForge(VanillaCatalog.Version game, Release release,
                                                      ForgeInstaller.Release forge, BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        if (!release.supports(forge)) {
            throw new IOException("所选 Forge 版本与 OptiFine 不兼容");
        }
        String name = validateName(game, release);
        var loader = new ForgeInstaller(client, vanilla).install(game, forge,
                (completed, total) -> progress.accept(completed, total + 1));
        var profile = VanillaInstaller.readJson(VanillaInstaller.versionJson(loader));
        var target = vanilla.instance(loader.id() + "-OptiFine_" + release.version().replace(' ', '_'));
        var mods = Files.createDirectories(target.directory().resolve("mods"));
        Path pending = Files.createTempFile(mods, "optifine-", ".part");
        try {
            download(game.id(), name, pending);
            try (var archive = new ZipFile(pending.toFile())) {
                if (archive.size() == 0) {
                    throw new IOException("OptiFine 文件为空");
                }
            }
            VanillaInstaller.replace(pending, mods.resolve(name));
            profile.addProperty("id", target.id());
            VanillaInstaller.publishVersion(target, profile);
            progress.accept(1, 1);
            return target;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static String validateName(VanillaCatalog.Version game, Release release) throws IOException {
        String name = VanillaInstaller.fileName(release.file());
        if (!name.matches("(?:preview_)?OptiFine_[\\w.]+\\.jar")
                || !name.replaceFirst("^preview_", "").startsWith("OptiFine_" + game.id() + "_")) {
            throw new IOException("OptiFine 文件与所选游戏版本不一致");
        }
        return name;
    }

    private void download(String game, String name, Path target) throws IOException, InterruptedException {
        IOException mirrorFailure = null;
        String prefix = "OptiFine_" + game + "_";
        String plain = name.replaceFirst("^preview_", "");
        String edition = plain.substring(prefix.length(), plain.length() - 4);
        var patch = BMCL_EDITION.matcher(edition);
        if (patch.matches()) {
            String version = game.equals("1.8") || game.equals("1.9") ? game + ".0" : game;
            String variant = patch.group(1);
            String revision = patch.group(2);
            if (patch.group(3) != null) {
                variant += "_" + revision;
                revision = patch.group(3);
            }
            URI uri = BMCL_LIST.resolve("/optifine/" + version + "/" + variant + "/" + revision);
            try {
                vanilla.fetchFromOfficialSite(uri, target);
                try (var archive = new ZipFile(target.toFile())) {
                    if (Files.size(target) < 300 * 1024 || archive.getEntry("optifine/Installer.class") == null
                            && archive.getEntry("optifine/OptiFineTweaker.class") == null) {
                        throw new IOException("BMCLAPI 返回的 OptiFine 安装包无效");
                    }
                }
                return;
            } catch (IOException failure) {
                mirrorFailure = failure;
                Files.deleteIfExists(target);
            }
        }
        try {
            downloadOfficial(name, target);
        } catch (IOException failure) {
            if (mirrorFailure != null) failure.addSuppressed(mirrorFailure);
            throw failure;
        }
    }

    private void downloadOfficial(String name, Path target) throws IOException, InterruptedException {
        var download = DOWNLOAD.matcher(page("adloadx?f=" + name));
        if (!download.find() || !download.group(1).startsWith("downloadx?f=" + name + "&")) {
            throw new IOException("找不到所选 OptiFine 的官方下载地址");
        }
        vanilla.fetchFromOfficialSite(SITE.resolve(download.group(1).replace("&amp;", "&")), target);
    }

    private static boolean usesLaunchWrapper(String game) {
        var version = Pattern.compile("^1\\.(\\d+)(?:\\.\\d+)?$").matcher(game);
        return version.matches() && Integer.parseInt(version.group(1)) < 14;
    }

    private VanillaInstaller.Instance installLegacy(VanillaInstaller.Instance base, String file, Path jar)
            throws IOException, InterruptedException {
        try (var archive = new ZipFile(jar.toFile())) {
            if (archive.getEntry("optifine/OptiFineTweaker.class") == null) {
                throw new IOException("此旧版 OptiFine 文件缺少启动组件");
            }
        }
        String edition = file.replaceFirst("^(preview_)?OptiFine_", "").replaceFirst("\\.jar$", "");
        String variant = edition.substring(base.id().length() + 1);
        var instance = vanilla.instance(base.id() + "-OptiFine_" + variant);
        Path libraries = vanilla.root().resolve("libraries");
        String wrapper = "net.minecraft:launchwrapper:1.12";
        String opti = "optifine:OptiFine:" + edition;
        String wrapperPath = VanillaInstaller.mavenPath(wrapper);
        vanilla.fetchVerified(URI.create("https://libraries.minecraft.net/" + wrapperPath),
                VanillaInstaller.resolve(libraries, wrapperPath));
        Path optiPath = VanillaInstaller.resolve(libraries, VanillaInstaller.mavenPath(opti));
        Files.createDirectories(optiPath.getParent());
        VanillaInstaller.replace(jar, optiPath);
        var metadata = VanillaInstaller.readJson(VanillaInstaller.versionJson(base));
        var profile = new JsonObject();
        profile.addProperty("id", instance.id());
        profile.addProperty("inheritsFrom", base.id());
        profile.addProperty("mainClass", "net.minecraft.launchwrapper.Launch");
        var dependencies = new JsonArray();
        for (String coordinate : List.of(opti, wrapper)) {
            var library = new JsonObject();
            library.addProperty("name", coordinate);
            dependencies.add(library);
        }
        profile.add("libraries", dependencies);
        if (metadata.has("minecraftArguments")) {
            profile.addProperty("minecraftArguments", VanillaInstaller.string(metadata, "minecraftArguments")
                    + " --tweakClass optifine.OptiFineTweaker");
        } else {
            var arguments = new JsonObject();
            var game = new JsonArray();
            game.add("--tweakClass");
            game.add("optifine.OptiFineTweaker");
            arguments.add("game", game);
            profile.add("arguments", arguments);
        }
        VanillaInstaller.publishVersion(instance, profile);
        return instance;
    }

    private static int installerJava(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("optifine/Installer.class");
            if (entry == null) {
                throw new IOException("此版本没有 OptiFine 安装器");
            }
            try (var data = new java.io.DataInputStream(zip.getInputStream(entry))) {
                if (data.readInt() != 0xcafebabe) {
                    throw new IOException("OptiFine 安装器格式无效");
                }
                data.readUnsignedShort();
                return Math.max(21, data.readUnsignedShort() - 44);
            }
        }
    }

    private static void runInstaller(Path jar, Path root, Path log, int required) throws IOException, InterruptedException {
        var executable = GameLaunch.javaExecutable(required);
        Path bridge;
        try {
            bridge = Path.of(OptiFineInstallProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("无法定位 OptiFine 安装入口", e);
        }
        var process = new ProcessBuilder(executable.toString(), "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + root.getParent(),
                "-cp", bridge + java.io.File.pathSeparator + jar, OptiFineInstallProcess.class.getName(), root.toString())
                .directory(root.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(3, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IOException("OptiFine 安装器运行失败。日志：" + log);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.onExit().join();
            }
        }
    }

    private String page(String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(SITE.resolve(path)).timeout(Duration.ofSeconds(30)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OptiFine 官方网站请求失败（HTTP " + response.statusCode() + "）");
        }
        return response.body();
    }
}

package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public final class ForgeInstaller {
    public enum Distribution {
        FORGE("Forge", "https://maven.minecraftforge.net/net/minecraftforge/forge/", "forge", "--installClient"),
        NEOFORGE("NeoForge", "https://maven.neoforged.net/releases/net/neoforged/neoforge/", "neoforge", "--install-client");

        public final String title;
        final String repository;
        final String artifact;
        final String command;

        Distribution(String title, String repository, String artifact, String command) {
            this.title = title;
            this.repository = repository;
            this.artifact = artifact;
            this.command = command;
        }
    }

    public record Release(Distribution distribution, String version, String coordinate) {
        @Override public String toString() {
            return version;
        }
    }

    private final HttpClient client;
    private final VanillaInstaller vanilla;
    private static final String LEGACY_NEO_REPOSITORY = "https://maven.neoforged.net/releases/net/neoforged/forge/";

    public ForgeInstaller(HttpClient client, VanillaInstaller vanilla) {
        this.client = client;
        this.vanilla = vanilla;
    }

    public List<Release> releases(Distribution distribution, String game) throws IOException, InterruptedException {
        String neoPrefix = neoPrefix(game);
        if (distribution == Distribution.NEOFORGE && neoPrefix.isEmpty()) {
            return List.of();
        }
        var releases = DownloadMirrors.read(client, URI.create(repository(distribution, game) + "maven-metadata.xml"),
                body -> parseReleases(distribution, game, neoPrefix, body));
        if (releases == null) throw new IOException("找不到 " + distribution.title + " 版本列表");
        return releases;
    }

    private static List<Release> parseReleases(Distribution distribution, String game, String neoPrefix, String body)
            throws IOException {
        try {
            var parser = DocumentBuilderFactory.newInstance();
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var xml = parser.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
            if (!xml.getDocumentElement().getTagName().equals("metadata")) {
                throw new IOException("Maven 版本列表格式无效");
            }
            var versions = xml.getElementsByTagName("version");
            var releases = new ArrayList<Release>();
            for (int index = versions.getLength() - 1; index >= 0; index--) {
                String coordinate = versions.item(index).getTextContent().strip();
                if ((distribution == Distribution.FORGE || legacyNeo(distribution, game))
                        && coordinate.startsWith(game + "-")) {
                    String version = coordinate.substring(game.length() + 1);
                    if (version.matches("[0-9][0-9.]*")) {
                        releases.add(new Release(distribution, version, coordinate));
                    }
                } else if (distribution == Distribution.NEOFORGE && !legacyNeo(distribution, game)
                        && coordinate.startsWith(neoPrefix)
                        && coordinate.matches("[0-9][0-9.]*(?:-beta)?")) {
                    releases.add(new Release(distribution, coordinate, coordinate));
                }
            }
            return List.copyOf(releases);
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException(distribution.title + " 版本列表格式无效", e);
        }
    }

    private static boolean legacyNeo(Distribution distribution, String game) {
        return distribution == Distribution.NEOFORGE && "1.20.1".equals(game);
    }

    private static String repository(Distribution distribution, String game) {
        return legacyNeo(distribution, game) ? LEGACY_NEO_REPOSITORY : distribution.repository;
    }

    private static String neoPrefix(String game) {
        var segments = game.split("\\.");
        if (!game.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) {
            return "";
        }
        if (segments[0].equals("1")) {
            return segments[1] + "." + (segments.length == 3 ? segments[2] : "0") + ".";
        }
        return game + ".";
    }

    public VanillaInstaller.Instance install(VanillaCatalog.Version game, Release release,
                                             BiConsumer<Integer, Integer> progress)
            throws IOException, InterruptedException {
        var distribution = release.distribution();
        String coordinate = VanillaInstaller.fileName(release.coordinate());
        boolean legacyNeo = legacyNeo(distribution, game.id());
        String artifact = legacyNeo ? "forge" : distribution.artifact;
        String name = artifact + "-" + coordinate + "-installer.jar";
        var url = URI.create(repository(distribution, game.id()) + coordinate + "/" + name);
        var root = vanilla.root();
        var cache = root.resolve(".pcl-nt-cache").resolve("installers");
        var jar = cache.resolve(legacyNeo ? "neo-" + name : name);
        progress.accept(0, 0);
        vanilla.fetchVerified(url, jar);
        JsonObject expected = versionProfile(jar);
        String id = VanillaInstaller.string(expected, "id");
        if (!game.id().equals(VanillaInstaller.string(expected, "inheritsFrom")) || id.equalsIgnoreCase(game.id())) {
            throw new IOException("安装器中的游戏版本与所选版本不一致");
        }
        var instance = vanilla.instance(id);
        var base = vanilla.install(game, (completed, total) -> progress.accept(completed, total + 1));
        var baseProfile = VanillaInstaller.readJson(VanillaInstaller.versionJson(base));
        var javaVersion = baseProfile.getAsJsonObject("javaVersion");
        int required = javaVersion == null ? 8 : javaVersion.get("majorVersion").getAsInt();
        var executable = GameLaunch.javaExecutable(required);
        var profileFile = root.resolve("launcher_profiles.json");
        if (Files.notExists(profileFile)) {
            Files.writeString(profileFile, "{\"profiles\":{},\"settings\":{}}", java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        var log = cache.resolve(distribution.artifact + "-" + coordinate + ".log");
        var temporary = Files.createDirectories(cache.resolve("tmp"));
        var versionFile = VanillaInstaller.versionJson(instance);
        byte[] original = Files.exists(versionFile) ? Files.readAllBytes(versionFile) : null;
        var process = new ProcessBuilder(executable.toString(), "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + temporary,
                "-jar", jar.toString(), legacyNeo ? "--installClient" : distribution.command, root.toString())
                .directory(cache.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean success = false;
        try {
            if (!process.waitFor(12, TimeUnit.MINUTES)) {
                throw new IOException(distribution.title + " 安装超时。日志：" + log);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(versionFile)) {
                throw new IOException(distribution.title + " 安装失败。日志：" + log);
            }
            var installed = VanillaInstaller.readJson(versionFile);
            if (!id.equals(VanillaInstaller.string(installed, "id"))
                    || !game.id().equals(VanillaInstaller.string(installed, "inheritsFrom"))) {
                throw new IOException("安装器生成了无效的版本配置。日志：" + log);
            }
            success = true;
            progress.accept(1, 1);
            return instance;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.onExit().join();
            }
            if (!success) {
                if (original == null) {
                    Files.deleteIfExists(versionFile);
                } else {
                    Files.write(versionFile, original);
                }
            }
        }
    }

    private static JsonObject versionProfile(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            var version = zip.getEntry("version.json");
            if (version != null) {
                try (var reader = new InputStreamReader(zip.getInputStream(version), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
            throw new IOException("此旧版安装器未提供命令行安装所需的 version.json");
        }
    }
}

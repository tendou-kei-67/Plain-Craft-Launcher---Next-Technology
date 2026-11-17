package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static io.pclnt.minecraft.VanillaInstaller.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class GameLaunch {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern COMMAND_VARIABLE = Pattern.compile("(?<!\\$)\\{([a-z_]+)}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/M/d");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final VanillaInstaller installer;

    public GameLaunch(VanillaInstaller installer) {
        this.installer = installer;
    }

    public static boolean validName(String name) {
        return !name.isBlank() && name.length() <= 16 && name.codePoints().noneMatch(
                character -> Character.isISOControl(character) || character == '"' || character == '¨');
    }

    public Process offline(Instance instance, String name) throws IOException {
        return offline(instance, name, LaunchOptions.DEFAULT);
    }

    public Process offline(Instance instance, String name, LaunchOptions options) throws IOException {
        return offline(instance, name, offlineUuid(name), options);
    }

    public Process offline(Instance instance, String name, String uuid, LaunchOptions options) throws IOException {
        return offline(instance, name, uuid, options, null);
    }

    public Process offline(Instance instance, String name, String uuid, LaunchOptions options, String world) throws IOException {
        if (!validName(name)) {
            throw new IOException("请填写 1–16 个字符的玩家名，玩家名不能包含英文引号");
        }
        if (!uuid.matches("[a-fA-F0-9]{32}")) {
            throw new IOException("无效的离线档案 UUID");
        }
        return launch(instance, name, uuid, uuid, uuid, "legacy", "", List.of(), options, world);
    }

    public Process thirdParty(Instance instance, Yggdrasil service, Yggdrasil.Session session, LaunchOptions options)
            throws IOException, InterruptedException {
        return thirdParty(instance, service, session, options, null);
    }

    public Process thirdParty(Instance instance, Yggdrasil service, Yggdrasil.Session session, LaunchOptions options,
                              String world) throws IOException, InterruptedException {
        var character = session.selected();
        String token = session.accessToken();
        var agent = service.agent(installer.root().resolve(".pcl-nt-cache"));
        var jvm = List.of("-javaagent:" + agent + "=" + session.server(), "-Dauthlibinjector.side=client");
        return launch(instance, character.name(), character.id(), token, "token:" + token + ":" + character.id(),
                "legacy", "", jvm, options, world);
    }

    public Process microsoft(Instance instance, MicrosoftLogin.Session session, LaunchOptions options) throws IOException {
        return microsoft(instance, session, options, null);
    }

    public Process microsoft(Instance instance, MicrosoftLogin.Session session, LaunchOptions options, String world) throws IOException {
        return launch(instance, session.name(), session.uuid(), session.accessToken(),
                "token:" + session.accessToken() + ":" + session.uuid(), "msa", session.xuid(), List.of(), options, world);
    }

    public boolean supportsQuickPlay(Instance instance) throws IOException {
        return supportsQuickPlay(installer.launchVersion(instance).metadata());
    }

    private static boolean supportsQuickPlay(JsonObject metadata) {
        var arguments = metadata.getAsJsonObject("arguments");
        if (arguments == null) return false;
        for (var entry : array(arguments, "game")) {
            var value = entry.isJsonObject() ? entry.getAsJsonObject().get("value") : entry;
            if (value == null) continue;
            var values = value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
            if (values.stream().anyMatch(item -> item.isJsonPrimitive()
                    && "--quickPlaySingleplayer".equals(item.getAsString()))) return true;
        }
        return false;
    }

    private Process launch(Instance instance, String name, String uuid, String token, String session,
                           String userType, String xuid, List<String> extraJvm, LaunchOptions options, String world)
            throws IOException {
        var version = installer.launchVersion(instance);
        var metadata = version.metadata();
        int required = metadata.has("javaVersion")
                ? metadata.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
        var root = installer.root();
        var java = javaExecutable(required, options.javaPath(), root);
        var gameDirectory = installer.gameDirectory(instance, options.isolated());
        if (world != null) {
            if (!supportsQuickPlay(metadata)) throw new IOException("此版本不支持存档快捷启动");
            var saves = gameDirectory.resolve("saves");
            var save = saves.resolve(world);
            if (!saves.equals(save.getParent()) || !save.getFileName().toString().equals(world)
                    || !Files.isDirectory(save, NOFOLLOW_LINKS)
                    || !Files.isRegularFile(save.resolve("level.dat"), NOFOLLOW_LINKS)) {
                throw new IOException("存档已移动或缺少 level.dat");
            }
        }
        var natives = instance.directory().resolve("natives");
        var classpath = new LinkedHashSet<Path>();
        Files.createDirectories(natives);
        for (var element : array(metadata, "libraries")) {
            var library = element.getAsJsonObject();
            if (!libraryApplies(library)) {
                continue;
            }
            var downloads = library.getAsJsonObject("downloads");
            if (downloads != null && downloads.has("artifact")) {
                classpath.add(require(installer.libraryPath(downloads.getAsJsonObject("artifact"))));
            } else if (downloads == null) {
                classpath.add(require(resolve(root.resolve("libraries"), mavenPath(string(library, "name")))));
            }
            var nativeFile = nativeDownload(library);
            if (nativeFile != null) {
                extractNatives(require(installer.libraryPath(nativeFile)), natives, library.getAsJsonObject("extract"));
            }
        }
        classpath.add(require(clientJar(version.client())));
        String cp = String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList());
        String assetsId = string(metadata, "assets");
        var assetsIndex = VanillaInstaller.assetIndex(metadata);
        if (assetsIndex != null) assetsId = string(assetsIndex, "id");
        else if (assetsId.isBlank() && metadata.get("assetIndex") instanceof JsonObject index) assetsId = string(index, "id");
        Path gameAssets = materializeLegacyAssets(root, gameDirectory, assetsId);
        var context = Map.ofEntries(
                Map.entry("path", folder(Path.of("").toAbsolutePath())),
                Map.entry("minecraft", folder(root)), Map.entry("verpath", folder(instance.directory())),
                Map.entry("version_path", folder(instance.directory())), Map.entry("verindie", folder(gameDirectory)),
                Map.entry("version_indie", folder(gameDirectory)), Map.entry("java", folder(java.getParent())),
                Map.entry("user", name), Map.entry("uuid", uuid), Map.entry("name", instance.id()),
                Map.entry("login", userType.equals("msa") ? "微软登录" : extraJvm.isEmpty() ? "离线登录" : "第三方登录"),
                Map.entry("version", version.client().id()));
        String info = renderLaunchText(options.versionInfo(), context);
        boolean resolution = options.windowMode() >= 2;
        Map<String, String> values = Map.ofEntries(
                Map.entry("auth_player_name", name), Map.entry("auth_uuid", uuid), Map.entry("auth_access_token", token),
                Map.entry("auth_session", session), Map.entry("clientid", ""), Map.entry("auth_xuid", xuid),
                Map.entry("user_type", userType), Map.entry("user_properties", "{}"), Map.entry("profile_properties", "{}"),
                Map.entry("version_name", instance.id()), Map.entry("version_type", info),
                Map.entry("game_directory", gameDirectory.toString()), Map.entry("game_assets", gameAssets.toString()),
                Map.entry("assets_root", root.resolve("assets").toString()), Map.entry("assets_index_name", assetsId),
                Map.entry("natives_directory", natives.toString()), Map.entry("library_directory", root.resolve("libraries").toString()),
                Map.entry("classpath", cp), Map.entry("classpath_separator", File.pathSeparator),
                Map.entry("launcher_name", "PCL-NT"), Map.entry("launcher_version", "0.1.0"),
                Map.entry("resolution_width", Integer.toString(options.width())),
                Map.entry("resolution_height", Integer.toString(options.height())));
        var command = new ArrayList<String>();
        command.add(java.toString());
        int maximumHeap = options.heapMegabytes(instance, gameDirectory);
        command.add("-Xmx" + maximumHeap + "M");
        options.applyIpPreference(command);
        command.addAll(extraJvm);
        for (String argument : LaunchOptions.splitArguments(options.jvmArguments())) {
            command.add(substitute(argument, values));
        }
        var arguments = metadata.getAsJsonObject("arguments");
        if (arguments == null) {
            if ("osx".equals(platform())) {
                command.add("-XstartOnFirstThread");
            }
            command.add("-Djava.library.path=" + natives);
            command.add("-cp");
            command.add(cp);
        } else {
            addArguments(command, arguments, "jvm", values, Map.of());
        }
        if (!instance.equals(version.client())) {
            // Forge's module bootstrap must ignore the unpatched parent client on the classpath.
            String parentJar = clientJar(version.client()).getFileName().toString();
            for (int index = 0; index < command.size(); index++) {
                String argument = command.get(index);
                if (argument.startsWith("-DignoreList=")) {
                    command.set(index, argument + "," + parentJar);
                }
            }
        }
        var logging = metadata.getAsJsonObject("logging");
        if (logging != null && logging.has("client")) {
            var log = logging.getAsJsonObject("client");
            var file = require(root.resolve("assets/log_configs").resolve(fileName(string(log.getAsJsonObject("file"), "id"))));
            command.add(substitute(string(log, "argument"), Map.of("path", file.toString())));
        }
        String main = string(metadata, "mainClass");
        if (!main.matches("[\\w.$]+")) {
            throw new IOException("版本缺少有效的游戏主类");
        }
        options.configureHeap(command, maximumHeap);
        options.configureCollector(command, required);
        command.add(main);
        int gameStart = command.size();
        if (arguments == null) {
            for (String argument : string(metadata, "minecraftArguments").split("\\s+")) {
                if (!argument.isEmpty()) {
                    command.add(substitute(argument, values));
                }
            }
        } else {
            addArguments(command, arguments, "game", values, Map.of("has_custom_resolution", resolution));
        }
        if (info.isBlank()) {
            var gameArguments = command.subList(gameStart, command.size());
            for (int index = gameArguments.size() - 2; index >= 0; index--) {
                if ("--versionType".equals(gameArguments.get(index)) && gameArguments.get(index + 1).isEmpty()) {
                    gameArguments.subList(index, index + 2).clear();
                }
            }
        }
        if (options.windowMode() == 0) {
            command.add("--fullscreen");
        }
        if (resolution && !command.subList(gameStart, command.size()).contains("--width")) {
            command.addAll(List.of("--width", Integer.toString(options.width()), "--height", Integer.toString(options.height())));
        }
        for (String argument : LaunchOptions.splitArguments(options.gameArguments())) {
            command.add(substitute(argument, values));
        }
        if (world != null) {
            command.addAll(List.of("--quickPlaySingleplayer", world));
        }
        Path logs = instance.directory().resolve("logs");
        Files.createDirectories(logs);
        if (options.preLaunchCommands().stream().anyMatch(hook -> !hook.command().isBlank())) {
            var now = LocalDateTime.now();
            var commandContext = new java.util.HashMap<>(context);
            commandContext.put("date", now.format(DATE));
            commandContext.put("time", now.format(TIME));
            for (int index = 0; index < options.preLaunchCommands().size(); index++) {
                var hook = options.preLaunchCommands().get(index);
                if (!hook.command().isBlank()) {
                    runPreLaunch(hook, root, logs.resolve("pcl-nt-pre-launch-" + (index + 1) + ".log"), commandContext);
                }
            }
        }
        var process = new ProcessBuilder(command).directory(gameDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve("pcl-nt-launch.log").toFile()).start();
        GameProcessPriority.apply(process, options.processPriority());
        if ("windows".equals(platform()) && !options.windowTitle().isBlank()) {
            GameWindowTitle.watch(process, () -> renderLaunchText(options.windowTitle(), context));
        }
        return process;
    }

    private static String renderLaunchText(String template, Map<String, String> context) {
        var now = LocalDateTime.now();
        return COMMAND_VARIABLE.matcher(template).replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
            case "date" -> now.format(DATE);
            case "time" -> now.format(TIME);
            default -> context.getOrDefault(match.group(1), match.group());
        }));
    }

    private static String folder(Path path) {
        String text = path.toString();
        return text.endsWith(File.separator) ? text : text + File.separator;
    }

    private static void runPreLaunch(LaunchOptions.PreLaunchCommand hook, Path root, Path log,
                                     Map<String, String> context) throws IOException {
        boolean windows = "windows".equals(platform());
        var variables = new java.util.HashMap<String, String>();
        String command = COMMAND_VARIABLE.matcher(hook.command()).replaceAll(match -> {
            String value = context.get(match.group(1));
            if (value == null) return Matcher.quoteReplacement(match.group());
            String variable = "PCLNT_LAUNCH_" + match.group(1).toUpperCase(Locale.ROOT);
            variables.put(variable, value);
            // Expand environment variables inside the shell so placeholder contents are treated as data.
            return Matcher.quoteReplacement(windows ? "!" + variable + "!" : "${" + variable + "}");
        });
        List<String> shell;
        if (windows) {
            Path cmd = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe");
            shell = List.of(cmd.toString(), "/d", variables.isEmpty() ? "/v:off" : "/v:on", "/s", "/c", "\"" + command + "\"");
        } else {
            shell = List.of("/bin/sh", "-c", command);
        }
        var builder = new ProcessBuilder(shell).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(variables);
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            System.getLogger(GameLaunch.class.getName()).log(System.Logger.Level.WARNING, "无法启动自定义命令，继续启动游戏", error);
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The command may already have exited.
        }
        if (!hook.waitForExit()) return;
        try {
            int exit = process.waitFor();
            if (exit != 0) System.getLogger(GameLaunch.class.getName()).log(System.Logger.Level.WARNING,
                    "启动前命令退出码为 " + exit + "，日志位于 " + log);
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("启动已取消");
        }
    }

    public static String offlineUuid(String name) {
        long hash = 5381;
        for (int index = 0; index < name.length(); index++) {
            hash = (hash << 5) ^ hash ^ name.charAt(index);
        }
        String hex = String.format(Locale.ROOT, "%016X%016X", name.length(), hash ^ 0xA98F501BC684032FL);
        return hex.substring(0, 12) + "3" + hex.substring(13, 16) + "9" + hex.substring(17);
    }

    private static Path require(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("缺少游戏文件 " + file.getFileName() + "，请重新安装该版本");
        }
        return file;
    }

    private static void addArguments(List<String> command, JsonObject arguments, String kind, Map<String, String> values,
                                     Map<String, Boolean> features)
            throws IOException {
        for (var entry : array(arguments, kind)) {
            if (entry.isJsonPrimitive()) {
                command.add(substitute(entry.getAsString(), values));
            } else if (rulesAllow(entry.getAsJsonObject(), features)) {
                var value = entry.getAsJsonObject().get("value");
                if (value.isJsonArray()) {
                    for (var arg : value.getAsJsonArray()) {
                        command.add(substitute(arg.getAsString(), values));
                    }
                } else {
                    command.add(substitute(value.getAsString(), values));
                }
            }
        }
    }

    private static String substitute(String source, Map<String, String> values) throws IOException {
        var match = VARIABLE.matcher(source);
        var result = new StringBuilder();
        while (match.find()) {
            String value = values.get(match.group(1));
            if (value == null) {
                throw new IOException("未知启动参数：" + match.group());
            }
            match.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        return match.appendTail(result).toString();
    }

    private static void extractNatives(Path jar, Path target, JsonObject extraction) throws IOException {
        var excludes = extraction == null ? List.of("META-INF/") : array(extraction, "exclude").asList().stream()
                .map(entry -> entry.getAsString()).toList();
        try (var archive = new ZipFile(jar.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || excludes.stream().anyMatch(entry.getName()::startsWith)) {
                    continue;
                }
                Path file = resolve(target, entry.getName());
                Files.createDirectories(file.getParent());
                try (var data = archive.getInputStream(entry)) {
                    Files.copy(data, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path materializeLegacyAssets(Path root, Path instance, String id) throws IOException {
        Path assets = root.resolve("assets");
        if (id.isEmpty()) {
            return assets;
        }
        var index = readJson(assets.resolve("indexes").resolve(fileName(id) + ".json"));
        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean resources = index.has("map_to_resources") && index.get("map_to_resources").getAsBoolean();
        Path target = virtual ? assets.resolve("virtual").resolve(id) : instance.resolve("resources");
        if (virtual || resources) {
            for (var object : index.getAsJsonObject("objects").entrySet()) {
                String hash = string(object.getValue().getAsJsonObject(), "hash");
                if (!hash.matches("[0-9a-f]{40}")) {
                    throw new IOException("无效的资源哈希");
                }
                Path file = resolve(target, object.getKey());
                Path source = require(assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash));
                Files.createDirectories(file.getParent());
                if (Files.notExists(file) || Files.mismatch(source, file) != -1) {
                    Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return virtual ? target : assets;
    }

    static Path javaExecutable(int required) throws IOException {
        return javaExecutable(required, "", null);
    }

    private static Path javaExecutable(int required, String preferred, Path gameDirectory) throws IOException {
        String binary = "windows".equals(platform()) ? "java.exe" : "java";
        // An explicitly selected Java always wins, even when its major version differs from the
        // version metadata. Players deliberately pick newer runtimes (for example Java 21 for a
        // 1.20.1 instance), and silently falling back to the previously detected Java made the
        // setting look like it had no effect.
        Path selected = explicitJava(preferred, binary);
        if (selected != null) {
            int major = javaMajor(selected);
            if (major <= 0) {
                throw new IOException("无法读取所选 Java 的版本：" + selected);
            }
            if (major < required) {
                throw new IOException("所选 Java " + major + " 低于该游戏需要的 Java " + required
                        + "，请在 设置 → 启动设置 或 版本设置 中重新选择。");
            }
            return selected;
        }
        var candidates = new LinkedHashSet<Path>();
        String configured = System.getenv("PCLNT_JAVA_" + required + "_HOME");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured, "bin", binary));
        }
        candidates.add(Path.of(System.getProperty("java.home"), "bin", binary));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(Path.of(javaHome, "bin", binary));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            Arrays.stream(path.split(Pattern.quote(File.pathSeparator))).filter(value -> !value.isBlank())
                    .map(value -> Path.of(value.replace("\"", ""), binary)).forEach(candidates::add);
        }
        switch (platform()) {
            case "windows" -> {
                for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
                    String programFiles = System.getenv(key);
                    if (programFiles != null && !programFiles.isBlank()) {
                        for (String vendor : List.of("Java", "Eclipse Adoptium", "Microsoft", "Amazon Corretto", "BellSoft")) {
                            addJavaHomes(candidates, Path.of(programFiles, vendor), "bin/" + binary);
                        }
                    }
                }
            }
            case "osx" -> {
                addJavaHomes(candidates, Path.of("/Library/Java/JavaVirtualMachines"), "Contents/Home/bin/java");
                addJavaHomes(candidates, Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
                        "Contents/Home/bin/java");
            }
            case "linux" -> {
                addJavaHomes(candidates, Path.of("/usr/lib/jvm"), "bin/java");
                addJavaHomes(candidates, Path.of("/usr/lib64/jvm"), "bin/java");
            }
            default -> { }
        }
        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate) && javaMajor(candidate) == required) {
                return candidate.toAbsolutePath();
            }
        }
        for (var installation : JavaInstallations.scan(gameDirectory)) {
            if (installation.major() == required) return installation.executable();
        }
        throw new IOException("未找到 Java " + required + "。请在 设置 → 启动设置 或 版本设置 中选择 Java，"
                + "或安装 Java " + required + " 后重试。");
    }

    /** Resolves the Java path explicitly chosen in the launcher, or null when it is left on automatic. */
    static Path explicitJava(String preferred, String binary) throws IOException {
        if (preferred == null || preferred.isBlank()) {
            return null;
        }
        String value = preferred.strip().replace("\"", "");
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (RuntimeException error) {
            throw new IOException("所选 Java 路径无效：" + preferred);
        }
        if (Files.isDirectory(candidate)) {
            Path direct = candidate.resolve(binary);
            candidate = Files.isExecutable(direct) ? direct : candidate.resolve("bin").resolve(binary);
        }
        if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
            throw new IOException("找不到所选的 Java：" + preferred + "，请在 设置 → 启动设置 或 版本设置 中重新选择。");
        }
        return candidate.toAbsolutePath().normalize();
    }

    private static void addJavaHomes(LinkedHashSet<Path> candidates, Path parent, String executable) {
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (var directories = Files.list(parent)) {
            directories.filter(Files::isDirectory).sorted().map(path -> path.resolve(executable)).forEach(candidates::add);
        } catch (IOException ignored) {
            // Try the other standard locations when a directory is unreadable.
        }
    }

    static int javaMajor(Path executable) {
        int released = JavaInstallations.releaseVersion(executable);
        if (released > 0) return released;
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
            if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("version \"(?:1\\.)?(\\d+)").matcher(text);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        } catch (IOException ignored) {
            return -1;
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}

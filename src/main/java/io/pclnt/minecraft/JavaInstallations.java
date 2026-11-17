package io.pclnt.minecraft;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class JavaInstallations {
    private static final boolean WINDOWS = com.sun.jna.Platform.isWindows();
    private static final String BINARY = WINDOWS ? "java.exe" : "java";
    private static final Pattern KEYWORD = Pattern.compile(
            "java|jdk|jre|dragonwell|azul|zulu|oracle|open|amazon|corretto|eclipse|temurin|hotspot|semeru|kona|bellsoft"
                    + "|environment|env|runtime|x86_64|amd64|arm64|x64|pcl|hmcl|baka|minecraft|microsoft|adoptium|^\\d[\\d._-]*$");
    private static final Pattern EXCLUDED = Pattern.compile("javapath|java8path|common files|netease");
    private static final Pattern RELEASE = Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)[^\"]*\"");

    private JavaInstallations() {}

    public record Installation(int major, Path executable) {
        @Override public String toString() { return "Java " + major + "  " + executable; }
    }

    public static Installation inspect(Path location) throws IOException {
        var executable = GameLaunch.explicitJava(location.toString(), BINARY).toRealPath();
        int major = GameLaunch.javaMajor(executable);
        if (major <= 0) throw new IOException("无法读取所选 Java 的版本");
        return new Installation(major, executable);
    }

    public static List<Installation> scan(Path gameDirectory) {
        var candidates = new LinkedHashSet<Path>();
        var directories = new LinkedHashSet<Path>();
        var home = Path.of(System.getProperty("user.home"));
        addHome(candidates, System.getProperty("java.home"));
        Map<String, String> environment = System.getenv();
        addHome(candidates, environment.get("JAVA_HOME"));
        environment.forEach((key, value) -> {
            if (key.matches("PCLNT_JAVA_\\d+_HOME")) addHome(candidates, value);
        });
        for (var segment : environment.getOrDefault("PATH", "").split(Pattern.quote(File.pathSeparator))) {
            if (!segment.isBlank()) add(candidates, segment.replace("\"", ""), BINARY);
        }
        directories.addAll(List.of(home, home.resolve(".jdks"), home.resolve(".sdkman/candidates/java"),
                home.resolve(".local/share/mise/installs/java"), home.resolve(".gradle/jdks")));
        if (gameDirectory != null) directories.add(gameDirectory.resolve("runtime"));
        if (WINDOWS) {
            String roaming = environment.getOrDefault("APPDATA", home.resolve("AppData/Roaming").toString());
            add(directories, roaming, ".minecraft/runtime");
            String local = environment.getOrDefault("LOCALAPPDATA", home.resolve("AppData/Local").toString());
            add(directories, local, "");
            add(directories, local, "Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime");
            for (Path drive : FileSystems.getDefault().getRootDirectories()) {
                if (Kernel32Holder.LIB.GetDriveTypeW(new WString(drive.toString())) != 3) continue;
                directories.add(drive);
                directories.add(drive.resolve("Program Files"));
                directories.add(drive.resolve("Program Files (x86)"));
            }
            scanRegistry(candidates);
        } else {
            directories.addAll(List.of(Path.of("/usr/lib/jvm"), Path.of("/usr/lib64/jvm"), Path.of("/usr/local/lib/jvm"),
                    Path.of("/opt"), Path.of("/usr/local"), Path.of("/Library/Java/JavaVirtualMachines"),
                    home.resolve("Library/Java/JavaVirtualMachines"),
                    home.resolve("Library/Application Support/minecraft/runtime"), home.resolve(".minecraft/runtime")));
        }
        directories.forEach(root -> traverse(root, candidates));
        var resolved = new LinkedHashMap<Path, Installation>();
        for (Path path : candidates) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) continue;
            try {
                Path canonical = path.toRealPath();
                if (!resolved.containsKey(canonical)) resolved.put(canonical, inspect(canonical));
            } catch (IOException ignored) {
                // A Java installation can be removed while a scan is running.
            }
        }
        return resolved.values().stream().sorted(Comparator.comparingInt(Installation::major).reversed()
                .thenComparing(item -> item.executable().toString(), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    static int releaseVersion(Path executable) {
        var bin = executable.getParent();
        if (bin == null || bin.getParent() == null) return -1;
        try (var reader = Files.newBufferedReader(bin.getParent().resolve("release"))) {
            for (int index = 0; index < 32; index++) {
                String line = reader.readLine();
                if (line == null) break;
                var match = RELEASE.matcher(line);
                if (match.matches()) return Integer.parseInt(match.group(1));
            }
        } catch (IOException | NumberFormatException ignored) {
            // Older installations need the executable's version output.
        }
        return -1;
    }

    private static void traverse(Path root, LinkedHashSet<Path> candidates) {
        record Directory(Path path, int depth) {}
        var queue = new ArrayDeque<Directory>();
        queue.add(new Directory(root, 0));
        while (!queue.isEmpty() && !Thread.currentThread().isInterrupted()) {
            var entry = queue.remove();
            var path = entry.path();
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
            for (String suffix : List.of(BINARY, "bin/" + BINARY, "Contents/Home/bin/" + BINARY)) {
                var candidate = path.resolve(suffix);
                if (Files.isRegularFile(candidate)) candidates.add(candidate);
            }
            if (entry.depth() >= 6) continue;
            try (var children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (KEYWORD.matcher(name).find() && !EXCLUDED.matcher(name).find()
                            && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        queue.add(new Directory(child, entry.depth() + 1));
                    }
                }
            } catch (IOException | java.nio.file.DirectoryIteratorException ignored) {
                // Known directories can require elevated permissions.
            }
        }
    }

    private static void scanRegistry(LinkedHashSet<Path> candidates) {
        List<String> vendors = List.of("JavaSoft", "Azul Systems", "BellSoft", "Eclipse Adoptium", "Microsoft\\JDK");
        var keyValue = Pattern.compile("(?im)^\\s*(?:JavaHome|InstallationPath|Path)\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
        for (String hive : List.of("HKLM", "HKCU")) {
            for (String prefix : List.of("\\SOFTWARE\\", "\\SOFTWARE\\WOW6432Node\\")) {
                for (String vendor : vendors) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String reg = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                            "System32", "reg.exe").toString();
                    try {
                        var process = new ProcessBuilder(reg, "query", hive + prefix + vendor, "/s")
                                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                        var output = process.inputReader(Charset.forName(System.getProperty("native.encoding", "UTF-8")));
                        try (output) {
                            var data = new StringBuilder();
                            for (String line; (line = output.readLine()) != null;) data.append(line).append('\n');
                            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) continue;
                            var matches = keyValue.matcher(data);
                            while (matches.find()) addHome(candidates, matches.group(1));
                        } finally {
                            if (process.isAlive()) process.destroyForcibly();
                        }
                    } catch (IOException ignored) {
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static void addHome(LinkedHashSet<Path> paths, String home) { add(paths, home, "bin/" + BINARY); }

    private static void add(LinkedHashSet<Path> paths, String base, String suffix) {
        if (base == null || base.isBlank()) return;
        try {
            paths.add(Path.of(base).resolve(suffix));
        } catch (java.nio.file.InvalidPathException ignored) {
            // Environment variables can contain entries unusable as file paths.
        }
    }

    private interface Kernel32 extends StdCallLibrary {
        int GetDriveTypeW(WString root);
    }

    private static final class Kernel32Holder {
        static final Kernel32 LIB = Native.load("Kernel32", Kernel32.class);
    }
}

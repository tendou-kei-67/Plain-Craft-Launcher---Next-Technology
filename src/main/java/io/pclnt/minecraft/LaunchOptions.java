package io.pclnt.minecraft;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record LaunchOptions(int memoryMode, int memorySlider, int gcMode, String javaPath, String jvmArguments,
                            String gameArguments, int windowMode, int width, int height,
                            int initialMemorySlider, boolean lockMemory, boolean isolated,
                            List<PreLaunchCommand> preLaunchCommands, String windowTitle, String versionInfo) {
    public record PreLaunchCommand(String command, boolean waitForExit) {}

    public LaunchOptions {
        preLaunchCommands = List.copyOf(preLaunchCommands);
    }

    public LaunchOptions(int memoryMode, int memorySlider, int gcMode, String javaPath, String jvmArguments,
                         String gameArguments, int windowMode, int width, int height, int initialMemorySlider,
                         boolean lockMemory, boolean isolated, List<PreLaunchCommand> preLaunchCommands) {
        this(memoryMode, memorySlider, gcMode, javaPath, jvmArguments, gameArguments, windowMode, width, height,
                initialMemorySlider, lockMemory, isolated, preLaunchCommands, "", "PCL-NT");
    }

    public LaunchOptions(int memoryMode, int memorySlider, int gcMode, String javaPath, String jvmArguments,
                         String gameArguments, int windowMode, int width, int height,
                         int initialMemorySlider, boolean lockMemory, boolean isolated) {
        this(memoryMode, memorySlider, gcMode, javaPath, jvmArguments, gameArguments, windowMode, width, height,
                initialMemorySlider, lockMemory, isolated, List.of());
    }

    public LaunchOptions(int memoryMode, int memorySlider, int gcMode, String javaPath, String jvmArguments,
                         String gameArguments, int windowMode, int width, int height,
                         int initialMemorySlider, boolean lockMemory) {
        this(memoryMode, memorySlider, gcMode, javaPath, jvmArguments, gameArguments, windowMode, width, height,
                initialMemorySlider, lockMemory, true);
    }

    public LaunchOptions(int memoryMode, int memorySlider, int gcMode, String javaPath, String jvmArguments,
                         String gameArguments, int windowMode, int width, int height) {
        this(memoryMode, memorySlider, gcMode, javaPath, jvmArguments, gameArguments, windowMode, width, height, 0, false);
    }

    public static final LaunchOptions DEFAULT = new LaunchOptions(0, 15, 4, "", "", "", 1, 854, 480);
    private static final double GIB = 1024.0 * 1024 * 1024;

    public static long totalMemory() {
        return memoryBean().getTotalMemorySize();
    }

    public static long availableMemory() {
        return memoryBean().getFreeMemorySize();
    }

    private static com.sun.management.OperatingSystemMXBean memoryBean() {
        return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    public int heapMegabytes(VanillaInstaller.Instance instance) {
        return heapMegabytes(instance, instance == null ? null : instance.directory());
    }

    public int heapMegabytes(VanillaInstaller.Instance instance, Path gameDirectory) {
        double gigabytes;
        if (memoryMode == 1) {
            gigabytes = customGigabytes(memorySlider);
        } else {
            String id = instance == null ? "" : instance.id().toLowerCase(Locale.ROOT);
            boolean modded = id.contains("forge") || id.contains("fabric") || id.contains("liteloader");
            int mods = modded ? modCount(gameDirectory.resolve("mods")) : 0;
            double first = 1.5 + mods / 90.0;
            double second = modded ? 2.7 + mods / 50.0 : id.contains("optifine") ? 3 : 2.5;
            double third = modded ? 4.5 + mods / 25.0 : id.contains("optifine") ? 5 : 4;
            double available = Math.max(0, Math.rint(availableMemory() / GIB * 10) / 10);
            gigabytes = 0;
            double[] targets = {first, second - first, third - second, third};
            double[] rates = {1, .7, .4, .15};
            for (int i = 0; i < targets.length && available >= .1; i++) {
                gigabytes += Math.min(available * rates[i], targets[i]);
                available -= targets[i] / rates[i];
            }
            gigabytes = Math.rint(Math.max(gigabytes, .5 + mods / 150.0) * 10) / 10;
        }
        return (int) Math.floor(gigabytes * 1024);
    }

    private static int modCount(Path directory) {
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                return (int) files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(jar|zip|litemod)$")).count();
            } catch (IOException ignored) {
                // The default allocation still applies if the folder cannot be read.
            }
        }
        return 0;
    }

    public static double customGigabytes(int value) {
        if (value <= 12) return value * .1 + .3;
        if (value <= 25) return (value - 12) * .5 + 1.5;
        if (value <= 33) return value - 25 + 8;
        return (value - 33) * 2 + 16;
    }

    void configureHeap(List<String> command, int maximum) {
        if (command.stream().skip(1).anyMatch(argument -> argument.startsWith("-Xms"))) return;
        String maximumArgument = command.reversed().stream().filter(argument -> argument.startsWith("-Xmx"))
                .findFirst().orElse("-Xmx" + maximum + "M");
        if (lockMemory) {
            command.add("-Xms" + maximumArgument.substring(4));
        } else if (memoryMode == 1 && initialMemorySlider > 0) {
            long initial = (long) (customGigabytes(initialMemorySlider) * 1024);
            long bounded = Math.min(initial, heapLimitMiB(maximumArgument.substring(4), maximum));
            if (bounded > 0) command.add("-Xms" + bounded + "M");
        }
    }

    private static long heapLimitMiB(String value, int fallback) {
        if (value.isEmpty()) return fallback;
        char unit = Character.toLowerCase(value.charAt(value.length() - 1));
        try {
            long amount = Long.parseLong(Character.isLetter(unit) ? value.substring(0, value.length() - 1) : value);
            return switch (unit) {
                case 'g' -> Math.multiplyExact(amount, 1024);
                case 'm' -> amount;
                case 'k' -> amount / 1024;
                default -> amount / (1024 * 1024);
            };
        } catch (NumberFormatException | ArithmeticException ignored) {
            return fallback;
        }
    }

    void configureCollector(List<String> jvm, int java) {
        if (gcMode == 3) {
            return;
        }
        jvm.removeIf(arg -> arg.matches("-XX:[+-]?(Use\\w+GC|ZGenerational|UseCompactObjectHeaders|G1\\w+(Percent|Size)"
                + "|(?:Max|Min)(?:GCPauseMillis|HeapFreeRatio))(?:=.*)?"));
        jvm.add("-XX:+UnlockExperimentalVMOptions");
        if (java >= 24) {
            jvm.add("-XX:+UseCompactObjectHeaders");
        }
        if ((gcMode == 0 && java >= 15) || (gcMode == 1 && java >= 21)) {
            jvm.add("-XX:+UseZGC");
            if (java == 21 || java == 22) {
                jvm.add("-XX:+ZGenerational");
            }
        } else {
            jvm.addAll(List.of("-XX:+UseG1GC", "-XX:G1NewSizePercent=20", "-XX:G1ReservePercent=20",
                    "-XX:G1HeapRegionSize=32M", "-XX:MaxGCPauseMillis=50"));
            if (gcMode == 4) {
                jvm.add("-XX:+PerfDisableSharedMem");
                if (java == 8) {
                    jvm.add("-XX:+ParallelRefProcEnabled");
                } else if (java >= 12) {
                    jvm.addAll(List.of("-XX:MinHeapFreeRatio=25", "-XX:MaxHeapFreeRatio=40"));
                }
            }
        }
    }

    static List<String> splitArguments(String input) throws IOException {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == quote && quote != 0) {
                quote = 0;
            } else if (quote == 0 && (ch == '"' || ch == '\'')) {
                quote = ch;
                started = true;
            } else if (quote == 0 && Character.isWhitespace(ch)) {
                if (started) {
                    result.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else if (quote == '"' && ch == '\\' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                current.append(input.charAt(++i));
                started = true;
            } else {
                current.append(ch);
                started = true;
            }
        }
        if (quote != 0) {
            throw new IOException("启动参数的引号未闭合");
        }
        if (started) {
            result.add(current.toString());
        }
        return result;
    }
}

package io.pclnt.minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Access to the Microsoft Store release of Minecraft for Windows. */
public final class BedrockEdition {
    private static final String PRODUCT_ID = "9NBLGGH2JHXJ";
    public static final String PRODUCT_URL = "https://www.xbox.com/games/store/minecraft-for-windows/" + PRODUCT_ID;
    private static final String STORE_URI = "ms-windows-store://pdp/?productid=" + PRODUCT_ID;
    private static final String PACKAGE = "Microsoft.MinecraftUWP";
    private static final String FAMILY = "Microsoft.MinecraftUWP_8wekyb3d8bbwe";

    private BedrockEdition() {}

    public record Installation(String version, String appId, String drive, String volume) {
        public String driveLabel() { return (drive + " " + volume).strip(); }
    }

    public static boolean supported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static Optional<Installation> detect() throws IOException, InterruptedException {
        if (!supported()) {
            return Optional.empty();
        }
        String script = """
                $ErrorActionPreference = 'Stop'
                $package = Get-AppxPackage -Name '%s' | Where-Object {
                    $_.PackageFamilyName -eq '%s' -and -not $_.IsResourcePackage
                } | Sort-Object Version -Descending | Select-Object -First 1
                if ($null -eq $package) { exit 0 }
                $apps = @((Get-AppxPackageManifest -Package $package.PackageFullName).Package.Applications.Application |
                    Where-Object { $_.Id })
                $app = if ($apps.Count -eq 1) { $apps[0] } else {
                    $apps | Where-Object { $_.Id -eq 'Game' } | Select-Object -First 1
                }
                if ($null -eq $app) {
                    $app = $apps | Where-Object {
                        $_.Id -eq 'App' -and $_.Executable -match '(^|[\\\\/])Minecraft\\.Windows\\.exe$'
                    } | Select-Object -First 1
                }
                if ($null -eq $app) { exit 2 }
                $root = [IO.Path]::GetPathRoot($package.InstallLocation)
                $label = ''
                if ($root) {
                    try { $label = [IO.DriveInfo]::new($root).VolumeLabel } catch {}
                }
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
                $drive = if ($root) { $root.TrimEnd([char]92) } else { '' }
                [Console]::WriteLine(('{0}|{1}!{2}|{3}|{4}' -f $package.Version, $package.PackageFamilyName, $app.Id, $drive, $label))
                """.formatted(PACKAGE, FAMILY);
        String line = run(script, "无法查询 Minecraft for Windows 的安装状态");
        if (line.isEmpty()) {
            return Optional.empty();
        }
        var fields = line.split("\\|", 4);
        if (fields.length != 4 || !fields[0].matches("\\d+(?:\\.\\d+){3}") || !officialAppId(fields[1])) {
            throw new IOException("Windows 返回了无效的基岩版安装信息");
        }
        return Optional.of(new Installation(fields[0], fields[1], fields[2], fields[3]));
    }

    public static void openStore() throws IOException, InterruptedException {
        requireWindows();
        run("Start-Process -FilePath '" + STORE_URI + "' -ErrorAction Stop", "无法打开 Microsoft Store");
    }

    public static Installation install() throws IOException, InterruptedException {
        requireWindows();
        var installed = detect();
        if (installed.isPresent()) {
            return installed.get();
        }
        int exitCode = installFromStore();
        for (int attempt = 0; attempt < 5; attempt++) {
            installed = detect();
            if (installed.isPresent()) {
                return installed.get();
            }
            if (exitCode != 0) {
                throw new IOException("Microsoft Store 安装失败（0x%08X）。请打开商店检查账户和游戏许可。"
                        .formatted(exitCode));
            }
            if (attempt < 4) {
                Thread.sleep(1_000);
            }
        }
        throw new IOException("Windows 尚未完成游戏注册。请在 Microsoft Store 查看安装状态，再点击刷新。");
    }

    private static int installFromStore() throws IOException, InterruptedException {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IOException("找不到 Windows 应用安装程序，请打开 Microsoft Store 安装。");
        }
        var winget = Path.of(localAppData, "Microsoft", "WindowsApps", "winget.exe");
        Process process;
        try {
            process = new ProcessBuilder(winget.toString(), "install", "--id", PRODUCT_ID, "--exact", "--source", "msstore",
                    "--accept-source-agreements", "--accept-package-agreements", "--disable-interactivity")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException exception) {
            throw new IOException("无法启动 WinGet，请打开 Microsoft Store 安装。", exception);
        }
        try {
            process.getOutputStream().close();
            return process.waitFor();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static void launch(Installation installation) throws IOException, InterruptedException {
        requireWindows();
        if (!officialAppId(installation.appId())) {
            throw new IOException("无效的基岩版应用标识");
        }
        run("Start-Process -FilePath explorer.exe -ArgumentList 'shell:AppsFolder\\" + installation.appId()
                + "' -ErrorAction Stop", "无法向 Windows 提交基岩版启动请求");
    }

    private static boolean officialAppId(String value) {
        return value.matches(java.util.regex.Pattern.quote(FAMILY) + "![A-Za-z0-9._-]+");
    }

    private static void requireWindows() throws IOException {
        if (!supported()) {
            throw new IOException("Minecraft for Windows 仅在 Windows 上提供");
        }
    }

    private static String run(String command, String errorMessage) throws IOException, InterruptedException {
        var powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(12, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException(errorMessage);
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}

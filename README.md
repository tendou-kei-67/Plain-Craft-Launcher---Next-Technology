# PCL-NT

Plain Craft Launcher - New Technology。项目根目录是独立的 JavaFX 启动器工程，`PCL-2/` 是用于参考的 VB.NET/WPF 源码目录。

界面沿用原版 PCL 的蓝色顶栏、左侧启动面板和右侧主题背景。顶栏依次是启动、下载、联机、开服、设置和更多。联机和开服保留空白内容区。

在「下载 → 原版游戏」选择并安装 Java 版后，可以在「版本选择」切换已安装版本，再回到「启动 → 离线」填写玩家名并启动。游戏文件保存在启动器数据目录下的 `.minecraft`，启动日志位于各版本目录的 `logs/pcl-nt-launch.log`。启动器按 Mojang 元数据查找所需的 Java 主版本。如未找到，可以把对应 Java 的安装根目录填入环境变量，例如 `PCLNT_JAVA_17_HOME`，然后重启启动器。

「设置 → 启动设置 → 高级选项 → Java 列表」和「版本设置 → 启动选项 → Java」可以直接指定 Java 可执行文件。显式指定的 Java 会被直接用于启动（即使其主版本高于游戏元数据要求，例如用 Java 21 运行 1.20.1），留空时才按版本要求自动匹配；如果指定的路径无效或主版本低于要求，启动时会给出明确的提示。

启动器已内置微软登录的 Client ID，无需额外配置。如需更换为自己的 Azure 应用，可在启动前设置环境变量 `PCLNT_MICROSOFT_CLIENT_ID`（Azure 应用需允许公共客户端设备代码流）。注意：新建的 Azure 应用还需要通过 Mojang 的 [Minecraft Java App ID 审核](https://aka.ms/mce-reviewappid) 才能调用 Minecraft 登录接口；未获批准时 `login_with_xbox` 会返回 HTTP 403 `Invalid app registration`，此时启动器会显示审核提示和链接。如果 403 响应为 `The request is blocked`，则是当前 IP/代理被拦截，请关闭或更换 VPN、加速器节点后重试。

## 在 IntelliJ IDEA 中打开

1. 安装或准备 **JDK 21**。在 IDEA 中选择 **File → Open**，打开本目录的 `pom.xml`，选择 **Open as Project**。
2. 在 **File → Project Structure → Project** 将 **SDK** 设为 JDK 21。在 **Settings → Build, Execution, Deployment → Build Tools → Maven** 选择 **Bundled Maven**，在 **Runner → JRE** 选择 **Project JDK**。
3. Maven 导入完成后，在工具栏运行 **PCL-NT** 配置。也可以在 **View → Tool Windows → Maven → PCL-NT → Plugins → javafx** 双击 **javafx:run**。
4. 修改代码、FXML 或 CSS 后，重新运行该配置查看效果。首次导入时 Maven 会下载本机平台的 JavaFX 依赖。

JavaFX 使用运行 Maven 的同一个 Java。命令行运行需要 JDK 21 和 Maven 3.9。将 `JAVA_HOME` 指向 JDK 21 的安装根目录（不含 `bin`），并用 `mvn -v` 确认 Maven 使用 Java 21，再从项目根目录运行：

```sh
mvn javafx:run
```

开发目标包括 Windows、macOS、Ubuntu/Debian 系 Linux 和 Arch 系 Linux。在 Linux 桌面运行 JavaFX 21 需要 GTK 3。JavaFX 的本机库由 Maven 按运行平台解析。

## 打包为多平台 jar

`pom.xml` 用 `maven-shade-plugin` 生成自包含 jar：应用类、`src/main/resources` 资源、Gson、JNA 以及对应平台的 JavaFX 运行时（含本机库）都在 jar 内，目标电脑只需要 Java 21 或更高版本，不必单独安装 JavaFX。`PCL-2/`、`PCL-CE/` 不参与 Maven 打包（只打包 `src/main`），因此不会进入 jar。

构建当前平台：

```sh
mvn package
```

产物为 `target/pcl-nt-0.1.0-SNAPSHOT-<平台>.jar`，例如 Windows x64 上是 `target/pcl-nt-0.1.0-SNAPSHOT-windows-x64.jar`。

构建全部平台（Windows x64、Linux x64、Linux ARM64、macOS Intel、macOS Apple 芯片）并把产物复制到 `dist/`：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package-multi-platform.ps1
```

脚本会依次以 `-Djavafx.platform=win|linux|linux-aarch64|mac|mac-aarch64` 交叉构建，检查每个 jar 是否包含 `io/pclnt/Main.class` 和该平台的原生库（`glass.dll`、`libglass.so` 或 `libglass.dylib`），并在 `dist/` 生成不含 `PCL-2`、`PCL-CE` 的源码包 `PCL-NT-0.1.0-SNAPSHOT-sources.zip`（加 `-NoSources` 可跳过）。也可以只构建部分平台：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\package-multi-platform.ps1 -Platform linux-x64,macos-arm64
```

运行：

```sh
java -jar PCL-NT-0.1.0-SNAPSHOT-windows-x64.jar
```

在 IntelliJ IDEA 中，Maven 工具窗口的 `pcl-nt → Lifecycle → package`，或运行配置 **Package JAR (host)**，会构建当前平台的 jar；全部平台请在 IDEA 的 Terminal 中运行上面的脚本。

说明：jar 的 `Main-Class` 是 `io.pclnt.Main`，它再调用 `LauncherApp`，这样 `java -jar` 才能直接启动。JavaFX 以 classpath 方式加载，启动时控制台会输出一条 `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` 警告，可以忽略。macOS 上如果双击 jar 被 Gatekeeper 拦截，可在终端执行 `java -jar ...`，或右键选择「打开」。

「更多」默认进入帮助。分类和正文随应用打包，配图直接显示在正文中；来自原版网站的配图需要联网。文档来自 PCL 原版，涉及原版启动器的操作可能与 PCL-NT 不同。保留原版开源版的百宝箱说明。反馈和新功能投票的地址暂时留空，点击只会提示「网址尚未配置」；之后可在 `MorePage.java` 中分别填写 `FEEDBACK_URL` 和 `VOTE_URL`。

「设置 → 个性化」可以更改窗口不透明度、主题、启动图标、背景、标题栏和自定义主页，也可以隐藏页面入口。按 **F12** 暂时恢复隐藏的入口。设置保存在用户目录的 `.pcl-nt/appearance.properties`。将图片或 H.264 编码的 MP4 / M4V 视频放入 `.pcl-nt/Pictures/`，点击「刷新背景内容」随机加载。视频静音循环，默认在游戏运行时暂停。

本地主页使用数据目录的 `Custom.xaml`，也支持联网来源。JavaFX 目前识别原版 XAML 中的卡片、文字、提示、常见布局、图片和部分交互；WPF 专用控件、绑定与未移植的事件仍有兼容差异。

## 代码入口

| 文件 | 用途 |
| --- | --- |
| `src/main/java/io/pclnt/LauncherApp.java` | 创建窗口、加载 FXML 和样式 |
| `src/main/java/io/pclnt/ui/LauncherController.java` | 页面切换、下载页版本选择和外观开关 |
| `src/main/java/io/pclnt/ui/MorePage.java`、`HelpPage.java` | 更多菜单、反馈与投票地址、帮助搜索和正文 |
| `src/main/java/io/pclnt/ui/PclCard.java`、`SidebarItem.java` | 可复用的卡片和边栏项，含原版路径与动效 |
| `src/main/java/io/pclnt/ui/SpringScrollPane.java` | 弹簧滚动，质量 1、刚度 120、阻尼 18 |
| `src/main/java/io/pclnt/ui/SettingsPage.java`、`AppearanceOptions.java` | 个性化设置、入口隐藏和本地配置 |
| `src/main/resources/io/pclnt/ui/launcher.fxml` | 顶栏和启动页布局 |
| `src/main/resources/io/pclnt/ui/launcher.css` | 配色、排版和控件状态 |
| `src/main/java/io/pclnt/ui/WindowChrome.java` | 窗口开合、拖动和调整大小 |
| `src/main/java/io/pclnt/ui/ViewTransition.java`、`UiMotion.java` | 页面淡入滑动和控件按压动画 |

原版启动器由龙腾猫跃开发，界面使用原版内置的 PCL English 字体、窗口图标、XAML 图形和 Minecraft Steve 预览皮肤。参考源码和使用指南见 [Plain Craft Launcher](https://github.com/Meloong-Git/PCL)。

「更多」图标来自原版 `PageOtherLeft.xaml`，离线帮助来自 PCL 帮助库作者和贡献者编写的 `Resources/Help.zip`。如果本地有 `PCL-2/` 参考源码，可用 `powershell -ExecutionPolicy Bypass -File .\tools\import-more-assets.ps1` 更新这些资源。查看参考源码目录的 `LICENCE` 了解原版使用指南。

QQ交流群/反馈群/内测群：1041410415
_预计开发个官网，目前还属于新建文件夹阶段_

### 赞助（爱发电）：https://ifdian.net/a/shittimchest
_(喜欢的话就支持一下吧喵）_

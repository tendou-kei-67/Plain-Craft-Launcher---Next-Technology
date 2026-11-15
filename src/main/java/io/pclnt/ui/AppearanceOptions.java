package io.pclnt.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import io.pclnt.minecraft.LaunchOptions;
import javafx.animation.PauseTransition;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.util.Duration;

/** The persisted personalization settings, using original PCL setting IDs where applicable. */
final class AppearanceOptions {
    static final String DOWNLOAD = "UiHiddenPageDownload", LINK = "UiHiddenPageLink",
            SETTINGS = "UiHiddenPageSetup", MORE = "UiHiddenPageOther",
            SETUP_LAUNCH = "UiHiddenSetupLaunch", SETUP_LINK = "UiHiddenSetupLink",
            SETUP_UI = "UiHiddenSetupUi", SETUP_OTHER = "UiHiddenSetupSystem",
            HELP = "UiHiddenOtherHelp", ABOUT = "UiHiddenOtherAbout", TOOLBOX = "UiHiddenOtherTest",
            FEEDBACK = "UiHiddenOtherFeedback", VOTE = "UiHiddenOtherVote",
            VERSION = "UiHiddenFunctionSelect", EMAIL = "UiLauncherEmail", MOD_UPDATE = "UiHiddenFunctionModUpdate",
            MODIFY = "UiHiddenFunctionInstanceModify", HIDE = "UiHiddenFunctionHidden";
    static final List<String> SETTINGS_ROWS = List.of(SETUP_LAUNCH, SETUP_LINK, SETUP_UI, SETUP_OTHER);
    static final List<String> MORE_ROWS = List.of(HELP, ABOUT, TOOLBOX, FEEDBACK, VOTE);
    private static final List<String> OTHER_CONTENT = List.of(HELP, ABOUT, TOOLBOX);
    private static final List<String> HIDDEN_KEYS = List.of(DOWNLOAD, LINK, SETTINGS, MORE, SETUP_LAUNCH, SETUP_LINK,
            SETUP_UI, SETUP_OTHER, HELP, ABOUT, TOOLBOX, FEEDBACK, VOTE, VERSION, EMAIL, MOD_UPDATE, MODIFY, HIDE);
    private static final System.Logger LOG = System.getLogger(AppearanceOptions.class.getName());

    final Path directory = Path.of(System.getProperty("pclnt.dataDir",
            Path.of(System.getProperty("user.home"), ".pcl-nt").toString())).toAbsolutePath().normalize();
    private final Path file = directory.resolve("appearance.properties");
    private final Properties values = new Properties();
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(250));
    private final Map<String, BooleanProperty> hidden = new LinkedHashMap<>();
    final BooleanProperty forceShow = new SimpleBooleanProperty(false);
    final IntegerProperty windowOpacity, imageOpacity, imageBlur, imageFit, logoType, homeType, homePreset;
    final IntegerProperty colorMode, lightColor, darkColor;
    final BooleanProperty startupLogo, colorfulBackground, logoLeft;
    final BooleanProperty autoPauseVideo;
    final BooleanProperty materialEnabled;
    final IntegerProperty materialRadius, materialSampling, materialMethod;
    final IntegerProperty launchMemoryMode, launchMemory, launchCollector, launchWindow, launchWidth, launchHeight;
    final IntegerProperty defaultIsolation;
    final IntegerProperty launchInitialMemory;
    final BooleanProperty launchLockMemory;
    final BooleanProperty optimizeMemoryBeforeLaunch;
    final StringProperty launchJava, launchJvmArgs, launchGameArgs;
    final StringProperty preLaunchCommand;
    final BooleanProperty preLaunchWait;
    final StringProperty launchTitle, launchInfo;
    final IntegerProperty launchVisibility;
    final StringProperty logoText, homeUrl, offlineNames;
    final GameFolders gameFolders;
    final StringProperty toolDownloadFolder;
    final IntegerProperty downloadSpeedUnit, diskSpeedUnit, taskFloatMode;
    final IntegerProperty launchCount;
    private boolean synchronizing, dirty;

    AppearanceOptions() {
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException | IllegalArgumentException exception) {
                LOG.log(System.Logger.Level.WARNING, "Cannot read appearance settings", exception);
            }
        }
        saveDelay.setOnFinished(event -> flush());
        windowOpacity = integer("UiLauncherTransparent", 600, 0, 600);
        colorMode = integer("UiDarkMode", 2, 0, 2);
        lightColor = integer("UiLightColor", 1, 0, 2);
        darkColor = integer("UiDarkColor", 1, 0, 2);
        startupLogo = flag("UiLauncherLogo", true);
        autoPauseVideo = flag("UiAutoPauseVideo", true);
        materialEnabled = flag("UiBlur", false);
        materialRadius = integer("UiBlurValue", 16, 0, 40);
        materialSampling = integer("UiBlurSamplingRate", 70, 0, 100);
        materialMethod = integer("UiBlurType", 0, 0, 1);
        launchMemoryMode = integer("LaunchRamType", 0, 0, 1);
        launchMemory = integer("LaunchRamCustom", 15, 0, 49);
        launchInitialMemory = integer("LaunchRamCustomInitial", 0, 0, 49);
        launchLockMemory = flag("LaunchAdvanceLockMemory", false);
        optimizeMemoryBeforeLaunch = flag("LaunchArgumentRam", false);
        launchCollector = integer("LaunchAdvanceGC", 4, 0, 4);
        launchWindow = integer("LaunchArgumentWindowType", 1, 0, 4);
        defaultIsolation = integer("LaunchArgumentIndieV2", 4, 0, 4);
        launchVisibility = integer("LaunchArgumentVisible", 5, 0, 5);
        launchWidth = integer("LaunchArgumentWindowWidth", 854, 1, 99999);
        launchHeight = integer("LaunchArgumentWindowHeight", 480, 1, 99999);
        launchJava = string("LaunchArgumentJavaSelect");
        launchJvmArgs = string("LaunchAdvanceJvm");
        launchGameArgs = string("LaunchAdvanceGame");
        preLaunchCommand = string("LaunchAdvanceRun");
        preLaunchWait = flag("LaunchAdvanceRunWait", true);
        launchTitle = string("LaunchArgumentTitle");
        launchInfo = string("LaunchArgumentInfo", "PCL-NT");
        imageOpacity = integer("UiBackgroundOpacity", 1000, 0, 1000);
        imageBlur = integer("UiBackgroundBlur", 0, 0, 40);
        imageFit = integer("UiBackgroundSuit", 0, 0, 12);
        colorfulBackground = flag("UiBackgroundColorful", true);
        logoType = integer("UiLogoType", 1, 0, 3);
        logoLeft = flag("UiLogoLeft", false);
        logoText = string("UiLogoText");
        homeType = integer("UiCustomType", 0, 0, 3);
        homePreset = integer("UiCustomPreset", 0, 0, 24);
        homeUrl = string("UiCustomNet");
        gameFolders = new GameFolders(directory, key -> values.getProperty(key, ""), this::update);
        offlineNames = string("LoginLegacyName");
        toolDownloadFolder = string("ToolDownloadFolder");
        downloadSpeedUnit = integer("TaskDownloadRateUnit", 0, 0, 1);
        diskSpeedUnit = integer("TaskDiskRateUnit", 0, 0, 1);
        taskFloatMode = integer("UiTaskFloatMode", 0, 0, 2);
        launchCount = integer("SystemLaunchCount", 0, 0, Integer.MAX_VALUE);
        for (String key : HIDDEN_KEYS) {
            hidden.put(key, flag(key, false));
        }
        hidden.forEach((key, property) -> property.addListener((obs, old, value) -> synchronize(key, value)));
    }

    Path pictures() { return directory.resolve("Pictures"); }
    Path logo() { return directory.resolve("Logo.png"); }
    Path customHome() { return directory.resolve("Custom.xaml"); }
    private String instanceKey(String id) {
        return gameFolders.instancePrefix(gameFolders.active()) + id + ".";
    }

    void clearFolderSettings(Path folder) {
        var prefix = gameFolders.instancePrefix(folder);
        values.stringPropertyNames().stream().filter(key -> key.startsWith(prefix)).forEach(values::remove);
        gameFolders.selectedVersion(folder, "");
    }

    boolean favorite(String id) {
        return Boolean.parseBoolean(values.getProperty(instanceKey(id) + "Favorite", "false"));
    }

    void favorite(String id, boolean selected) {
        update(instanceKey(id) + "Favorite", Boolean.toString(selected));
    }

    String description(String id) {
        return values.getProperty(instanceKey(id) + "Description", "");
    }

    void description(String id, String description) {
        update(instanceKey(id) + "Description", description);
    }

    InstanceLaunchSettings instanceLaunchSettings(String id) {
        String prefix = instanceKey(id);
        return new InstanceLaunchSettings(integer(prefix + "VersionRamType", 2, 0, 2),
                integer(prefix + "VersionRamCustom", 15, 0, 49), integer(prefix + "VersionRamCustomInitial", 0, 0, 49),
                string(prefix + "VersionJavaPath"),
                string(prefix + "VersionAdvanceJvm"), string(prefix + "VersionAdvanceGame"),
                flag(prefix + "VersionArgumentIndieV2", true), string(prefix + "VersionAdvanceRun"),
                flag(prefix + "VersionAdvanceRunWait", true), string(prefix + "VersionArgumentTitle"),
                string(prefix + "VersionArgumentInfo"), flag(prefix + "VersionArgumentTitleEmpty", false),
                integer(prefix + "VersionRamOptimize", 0, 0, 2));
    }

    boolean isolated(String id) {
        return isolated(gameFolders.active(), id);
    }

    boolean isolated(Path folder, String id) {
        return Boolean.parseBoolean(values.getProperty(gameFolders.instancePrefix(folder) + id + ".VersionArgumentIndieV2", "true"));
    }

    void initializeIsolation(String id, boolean isolated) {
        update(instanceKey(id) + "VersionArgumentIndieV2", Boolean.toString(isolated));
    }

    void inheritSharedMods(Path folder, List<String> versions) {
        for (var id : versions) {
            var key = gameFolders.instancePrefix(folder) + id + ".VersionArgumentIndieV2";
            if (!values.containsKey(key)) update(key, "false");
        }
    }

    static boolean isolateNewInstance(int policy, boolean modded, boolean nonRelease) {
        return switch (policy) {
            case 0 -> false;
            case 1 -> modded;
            case 2 -> nonRelease;
            case 3 -> modded || nonRelease;
            default -> true;
        };
    }

    record InstanceLaunchSettings(IntegerProperty memoryMode, IntegerProperty memory, IntegerProperty initialMemory,
                                  StringProperty javaPath,
                                  StringProperty jvmArguments, StringProperty gameArguments, BooleanProperty isolated,
                                  StringProperty preLaunchCommand, BooleanProperty preLaunchWait,
                                  StringProperty title, StringProperty info, BooleanProperty defaultTitle,
                                  IntegerProperty memoryOptimization) {
        void reset() {
            memoryMode.set(2);
            memory.set(15);
            initialMemory.set(0);
            javaPath.set("");
            jvmArguments.set("");
            gameArguments.set("");
            preLaunchCommand.set("");
            preLaunchWait.set(true);
            title.set("");
            info.set("");
            defaultTitle.set(false);
            memoryOptimization.set(0);
        }

        boolean optimizeBeforeLaunch(AppearanceOptions global) {
            return switch (memoryOptimization.get()) {
                case 1 -> true;
                case 2 -> false;
                default -> global.optimizeMemoryBeforeLaunch.get();
            };
        }

        io.pclnt.minecraft.LaunchOptions resolve(AppearanceOptions global, int windowMode, int width, int height) {
            boolean inherited = memoryMode.get() == 2;
            return new io.pclnt.minecraft.LaunchOptions(inherited ? global.launchMemoryMode.get() : memoryMode.get(),
                    inherited ? global.launchMemory.get() : memory.get(), global.launchCollector.get(),
                    override(javaPath, global.launchJava), override(jvmArguments, global.launchJvmArgs),
                    override(gameArguments, global.launchGameArgs), windowMode, width, height,
                    inherited ? global.launchInitialMemory.get() : initialMemory.get(), global.launchLockMemory.get(),
                    isolated.get(), List.of(
                            new LaunchOptions.PreLaunchCommand(global.preLaunchCommand.get(), global.preLaunchWait.get()),
                            new LaunchOptions.PreLaunchCommand(preLaunchCommand.get(), preLaunchWait.get())),
                    defaultTitle.get() ? "" : override(title, global.launchTitle), override(info, global.launchInfo));
        }

        private static String override(StringProperty value, StringProperty global) {
            return value.get().isBlank() ? global.get() : value.get();
        }
    }
    BooleanProperty hiddenFlag(String key) { return hidden.get(key); }
    BooleanBinding visible(String key) { return forceShow.or(hiddenFlag(key).not()); }
    boolean isVisible(String key) { return forceShow.get() || !hiddenFlag(key).get(); }

    void reset() {
        synchronizing = true;
        try {
            windowOpacity.set(600);
            colorMode.set(2);
            lightColor.set(1);
            darkColor.set(1);
            startupLogo.set(true);
            autoPauseVideo.set(true);
            imageOpacity.set(1000);
            imageBlur.set(0);
            imageFit.set(0);
            colorfulBackground.set(true);
            logoType.set(1);
            logoLeft.set(false);
            logoText.set("");
            homeType.set(0);
            homePreset.set(0);
            homeUrl.set("");
            hidden.values().forEach(property -> property.set(false));
        } finally {
            synchronizing = false;
        }
    }

    private void synchronize(String key, boolean value) {
        if (synchronizing) {
            return;
        }
        synchronizing = true;
        try {
            if (key.equals(SETTINGS)) {
                if (value || allHidden(SETTINGS_ROWS)) {
                    setHidden(SETTINGS_ROWS, value);
                }
            } else if (SETTINGS_ROWS.contains(key)) {
                hiddenFlag(SETTINGS).set(allHidden(SETTINGS_ROWS));
            } else if (key.equals(MORE)) {
                if (value || allHidden(MORE_ROWS)) {
                    setHidden(MORE_ROWS, value);
                }
            } else if (OTHER_CONTENT.contains(key)) {
                boolean all = allHidden(OTHER_CONTENT);
                hiddenFlag(MORE).set(all);
                if (all) {
                    setHidden(List.of(FEEDBACK, VOTE), true);
                }
            } else if ((key.equals(FEEDBACK) || key.equals(VOTE)) && !value && allHidden(OTHER_CONTENT)) {
                setHidden(OTHER_CONTENT, false);
                hiddenFlag(MORE).set(false);
            }
        } finally {
            synchronizing = false;
        }
    }

    private boolean allHidden(List<String> keys) {
        return keys.stream().allMatch(key -> hiddenFlag(key).get());
    }

    private void setHidden(List<String> keys, boolean value) {
        keys.forEach(key -> hiddenFlag(key).set(value));
    }

    private IntegerProperty integer(String key, int fallback, int min, int max) {
        int value;
        try {
            value = Math.clamp(Integer.parseInt(values.getProperty(key, String.valueOf(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            value = fallback;
        }
        var property = new SimpleIntegerProperty(value);
        property.addListener((obs, old, next) -> update(key, next.toString()));
        return property;
    }

    private BooleanProperty flag(String key, boolean fallback) {
        var property = new SimpleBooleanProperty(Boolean.parseBoolean(values.getProperty(key, String.valueOf(fallback))));
        property.addListener((obs, old, next) -> update(key, next.toString()));
        return property;
    }

    private StringProperty string(String key) {
        return string(key, "");
    }

    private StringProperty string(String key, String fallback) {
        var property = new SimpleStringProperty(values.getProperty(key, fallback));
        property.addListener((obs, old, next) -> update(key, next));
        return property;
    }

    private void update(String key, String value) {
        values.setProperty(key, value);
        dirty = true;
        saveDelay.playFromStart();
    }

    void flush() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(directory);
            try (var out = Files.newOutputStream(file)) {
                values.store(out, "PCL-NT appearance");
            }
            dirty = false;
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save appearance settings", exception);
        }
    }
}

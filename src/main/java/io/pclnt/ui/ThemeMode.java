package io.pclnt.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

final class ThemeMode {
    static final PseudoClass DARK = PseudoClass.getPseudoClass("dark");
    private final AppearanceOptions options;
    private final Node root;
    private final BooleanProperty dark;
    private final Timeline poll;
    private boolean systemDark;
    private boolean reading;
    private boolean closed;
    private boolean appliedDark;
    private int appliedColor = -1;

    ThemeMode(AppearanceOptions options, Node root, BooleanProperty dark) {
        this.options = options;
        this.root = root;
        this.dark = dark;
        options.colorMode.addListener(ignored -> refresh());
        options.lightColor.addListener(ignored -> apply());
        options.darkColor.addListener(ignored -> apply());
        poll = new Timeline(new KeyFrame(Duration.seconds(3), event -> {
            if (options.colorMode.get() == 3) apply();
            else querySystem();
        }));
        poll.setCycleCount(Timeline.INDEFINITE);
        apply();
    }

    void watch(Stage stage) {
        stage.focusedProperty().addListener((observable, old, focused) -> {
            if (focused) {
                refresh();
            }
        });
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> {
            closed = true;
            poll.stop();
        });
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, event -> {
            closed = false;
            refresh();
        });
        refresh();
    }

    private void refresh() {
        apply();
        if ((options.colorMode.get() == 2 || options.colorMode.get() == 3) && !closed) {
            querySystem();
            poll.play();
        } else {
            poll.stop();
        }
    }

    private void querySystem() {
        if (closed || reading || options.colorMode.get() != 2) {
            return;
        }
        reading = true;
        var task = new Task<Boolean>() {
            @Override protected Boolean call() {
                return readSystemTheme();
            }
        };
        task.setOnSucceeded(event -> {
            reading = false;
            systemDark = task.getValue();
            if (!closed) {
                apply();
            }
        });
        task.setOnFailed(event -> reading = false);
        Thread.startVirtualThread(task);
    }

    private void apply() {
        boolean useDark = switch (options.colorMode.get()) {
            case 1 -> true;
            case 2 -> systemDark;
            case 3 -> LocalThemeTime.isDark(java.time.ZonedDateTime.now());
            default -> false;
        };
        int color = useDark ? options.darkColor.get() : options.lightColor.get();
        dark.set(useDark);
        root.pseudoClassStateChanged(DARK, useDark);
        root.pseudoClassStateChanged(PseudoClass.getPseudoClass("sky-blue"), color == 0);
        root.pseudoClassStateChanged(PseudoClass.getPseudoClass("crash-blue"), color == 2);
        if (appliedDark != useDark || appliedColor != color) {
            root.setStyle(palette(useDark, color));
            appliedDark = useDark;
            appliedColor = color;
        }
    }

    static BooleanProperty observeDark(Node node) {
        var dark = new javafx.beans.property.SimpleBooleanProperty();
        javafx.collections.SetChangeListener<PseudoClass> listener = change -> {
            if (DARK.equals(change.getElementAdded()) || DARK.equals(change.getElementRemoved())) {
                dark.set(change.wasAdded());
            }
        };
        node.sceneProperty().addListener((observable, old, scene) -> {
            Node oldFrame = old == null ? null : old.lookup("#frame");
            Node frame = scene == null ? null : scene.lookup("#frame");
            if (oldFrame != null) oldFrame.getPseudoClassStates().removeListener(listener);
            if (frame != null) frame.getPseudoClassStates().addListener(listener);
            dark.set(frame != null && frame.getPseudoClassStates().contains(DARK));
        });
        return dark;
    }

    private static String palette(boolean dark, int scheme) {
        // Retain the existing original PCL light palette for the default Dragon Cat Blue.
        if (!dark && scheme == 1) {
            return "";
        }
        double hue = hue(scheme);
        String[] colors = accentColors(dark, scheme);
        double background = dark ? .2 : 1;
        String blue = dark ? oklch(.83, .09, hue) : colors[2];
        String outline = dark ? oklch(.81, .10, hue) : colors[1];
        return "-blue:" + blue + ";-outline:" + outline + ";-line:" + colors[dark ? 4 : 3]
                + ";-hover:" + (dark ? oklch(.32, .055, hue) : colors[6])
                + ";-pressed:" + (dark ? oklch(.39, .07, hue) : colors[5])
                + ";-title-start:" + (dark ? oklch(.40, .10, hue) : colors[1])
                + ";-title-center:" + (scheme == 0 ? "#00BBFF" : dark ? oklch(.45, .11, hue) : colors[2])
                + ";-title-end:" + (dark ? oklch(.40, .10, hue) : colors[1])
                + ";-home-start:" + oklch(.84 * background, .093, hue + 15)
                + ";-home-center:" + oklch(.96 * background, .028, hue)
                + ";-home-end:" + oklch(.84 * background, .093, hue - 15)
                + ";-avatar:" + colors[3] + ";";
    }

    private static double hue(int scheme) {
        return switch (scheme) {
            case 0 -> 235;
            case 2 -> 268;
            default -> 255;
        };
    }

    private static String[] accentColors(boolean dark, int scheme) {
        double hue = hue(scheme);
        double light = scheme == 0 ? .36 : scheme == 2 ? -.05 : 0;
        double chroma = scheme == 0 ? .2 : scheme == 2 ? -.1 : -.2;
        double[] l = dark ? new double[]{.96, .75, .6, .65, .45, .25, .225, .2}
                : new double[]{.35, .5, .575, .65, .8, .92, .94, .96};
        double[] c = {.025, .188, .213, .168, .093, .036, .028, .018};
        String[] colors = new String[8];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = oklch(adjust(l[i], i == 0 ? light * .1 : light),
                    adjust(c[i], i == 0 ? chroma * .25 : chroma), hue);
        }
        return colors;
    }

    record CardColors(javafx.scene.paint.Color ink, javafx.scene.paint.Color highlight,
                      javafx.scene.paint.Color shadow, javafx.scene.paint.Color surface) {
        private static CardColors from(String ink, String highlight, String shadow, String surface) {
            return new CardColors(javafx.scene.paint.Color.web(ink), javafx.scene.paint.Color.web(highlight),
                    javafx.scene.paint.Color.web(shadow), javafx.scene.paint.Color.web(surface, .733));
        }

        private static final CardColors DEFAULT = from("#343d4a", "#0b5bcb", "#4890f5", "#e0eafd");
        private static final java.util.Map<Integer, CardColors> THEMED = new java.util.HashMap<>();

        static CardColors of(Node frame) {
            if (frame == null) return DEFAULT;
            var states = frame.getPseudoClassStates();
            boolean dark = states.contains(DARK);
            int scheme = states.contains(PseudoClass.getPseudoClass("sky-blue")) ? 0
                    : states.contains(PseudoClass.getPseudoClass("crash-blue")) ? 2 : 1;
            if (!dark && scheme == 1) return DEFAULT;
            return THEMED.computeIfAbsent((dark ? 3 : 0) + scheme, ignored -> {
                var colors = accentColors(dark, scheme);
                return from(colors[0], colors[1], colors[3], colors[6]);
            });
        }
    }

    private static double adjust(double value, double amount) {
        return value + (amount >= 0 ? 1 - value : value) * amount;
    }

    private static String oklch(double light, double chroma, double hue) {
        double angle = Math.toRadians(hue);
        double lower = 0;
        double upper = chroma;
        double[] rgb = linearRgb(light, chroma, angle);
        if (!inGamut(rgb)) {
            for (int i = 0; i < 22; i++) {
                double middle = (lower + upper) / 2;
                if (inGamut(linearRgb(light, middle, angle))) {
                    lower = middle;
                } else {
                    upper = middle;
                }
            }
            rgb = linearRgb(light, lower, angle);
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", srgb(rgb[0]), srgb(rgb[1]), srgb(rgb[2]));
    }

    private static boolean inGamut(double[] rgb) {
        for (double channel : rgb) {
            if (channel < 0 || channel > 1) {
                return false;
            }
        }
        return true;
    }

    private static double[] linearRgb(double light, double chroma, double angle) {
        double a = chroma * Math.cos(angle);
        double b = chroma * Math.sin(angle);
        double l = Math.pow(light + .3963377774 * a + .2158037573 * b, 3);
        double m = Math.pow(light - .1055613458 * a - .0638541728 * b, 3);
        double s = Math.pow(light - .0894841775 * a - 1.2914855480 * b, 3);
        return new double[]{4.0767416621 * l - 3.3077115913 * m + .2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s,
                -.0041960863 * l - .7034186147 * m + 1.7076147010 * s};
    }

    private static int srgb(double linear) {
        double value = linear <= .0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        return (int) Math.round(255 * Math.clamp(value, 0, 1));
    }

    private static boolean readSystemTheme() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return read("reg.exe", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").matches("(?s).*REG_DWORD\\s+0x0\\s*$");
        }
        if (os.startsWith("mac")) {
            return read("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle").strip().equalsIgnoreCase("Dark");
        }
        String portal = read("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop", "--method",
                "org.freedesktop.portal.Settings.Read", "org.freedesktop.appearance", "color-scheme");
        if (portal.contains("uint32 1")) {
            return true;
        }
        if (portal.contains("uint32 2")) {
            return false;
        }
        return read("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").strip().equals("'prefer-dark'");
    }

    private static String read(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // A desktop may not expose a color preference.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return "";
    }
}

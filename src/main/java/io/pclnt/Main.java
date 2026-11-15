package io.pclnt;

/**
 * Entry point used by the packaged jar.
 *
 * <p>{@link LauncherApp} extends {@code javafx.application.Application}, and the Java launcher
 * refuses to start such a class as the jar's {@code Main-Class} when JavaFX is on the classpath
 * (it reports "JavaFX runtime components are missing"). This small wrapper has no JavaFX
 * superclass, so {@code java -jar PCL-NT.jar} can start the application while the JavaFX classes
 * are loaded from the shaded jar itself.</p>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LauncherApp.main(args);
    }
}

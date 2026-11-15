package io.pclnt.minecraft;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Set;
import java.util.function.Supplier;

final class GameWindowTitle {
    private static final Set<String> GAME_CLASSES = Set.of("GLFW30", "LWJGL", "SDL_app", "SunAwtFrame");
    private static final int WM_SETTEXT = 0x000c;
    private static final int SMTO_ABORTIFHUNG = 0x0002;

    public interface WindowVisitor extends StdCallLibrary.StdCallCallback {
        boolean visit(Pointer window, Pointer data);
    }

    public interface User32 extends StdCallLibrary {
        boolean EnumWindows(WindowVisitor visitor, Pointer data);
        boolean IsWindowVisible(Pointer window);
        int GetWindowThreadProcessId(Pointer window, IntByReference pid);
        int GetClassNameW(Pointer window, char[] buffer, int capacity);
        int GetWindowTextW(Pointer window, char[] buffer, int capacity);
        Pointer SendMessageTimeoutW(Pointer window, int message, Pointer wParam, WString text, int flags,
                                    int timeout, Pointer result);
    }

    private GameWindowTitle() {}

    static void watch(Process process, Supplier<String> title) {
        // The loop calls native code and never touches the JavaFX application thread.
        Thread.ofVirtual().name("Minecraft window title " + process.pid()).start(() -> {
            try {
                var user = Native.load("user32", User32.class);
                var owner = new IntByReference();
                var buffer = new char[512];
                while (process.isAlive()) {
                    String desired = title.get();
                    user.EnumWindows((window, data) -> {
                        if (!process.isAlive()) return false;
                        if (!user.IsWindowVisible(window)
                                || user.GetWindowThreadProcessId(window, owner) == 0
                                || Integer.toUnsignedLong(owner.getValue()) != process.pid()) return true;
                        int length = user.GetClassNameW(window, buffer, buffer.length);
                        if (!GAME_CLASSES.contains(new String(buffer, 0, length))) return true;
                        length = user.GetWindowTextW(window, buffer, buffer.length);
                        String current = new String(buffer, 0, length);
                        if (current.isBlank() || current.startsWith("GLFW") || current.endsWith("Hidden Utility Window")) {
                            return true;
                        }
                        if (!current.equals(desired)) {
                            user.SendMessageTimeoutW(window, WM_SETTEXT, null, new WString(desired),
                                    SMTO_ABORTIFHUNG, 100, null);
                        }
                        return false;
                    }, null);
                    Thread.sleep(200);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (LinkageError | RuntimeException error) {
                System.getLogger(GameWindowTitle.class.getName()).log(System.Logger.Level.WARNING, "无法修改游戏窗口标题", error);
            }
        });
    }
}

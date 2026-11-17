package io.pclnt.minecraft;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

final class GameProcessPriority {
    private GameProcessPriority() {}

    static void apply(Process process, int priority) {
        if (priority == 1 || priority < 0 || priority > 4) return;
        try {
            if (com.sun.jna.Platform.isWindows()) {
                int priorityClass = switch (priority) {
                    case 0 -> 0x8000; // ABOVE_NORMAL_PRIORITY_CLASS
                    case 2 -> 0x4000; // BELOW_NORMAL_PRIORITY_CLASS
                    case 3 -> 0x0080; // HIGH_PRIORITY_CLASS
                    default -> 0x0100; // REALTIME_PRIORITY_CLASS
                };
                var api = Windows.LIB;
                var handle = api.OpenProcess(0x0200, false, (int) process.pid());
                if (handle == null) throw new IllegalStateException("OpenProcess: " + Native.getLastError());
                try {
                    if (!api.SetPriorityClass(handle, priorityClass)) {
                        throw new IllegalStateException("SetPriorityClass: " + Native.getLastError());
                    }
                } finally {
                    api.CloseHandle(handle);
                }
            } else {
                int nice = switch (priority) {
                    case 0 -> -5;
                    case 2 -> 5;
                    case 3 -> -10;
                    default -> -20;
                };
                if (Posix.LIB.setpriority(0, (int) process.pid(), nice) != 0) {
                    throw new IllegalStateException("setpriority: " + Native.getLastError());
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            System.getLogger(GameProcessPriority.class.getName()).log(System.Logger.Level.WARNING,
                    "无法调整游戏进程优先级，游戏继续运行", failure);
        }
    }

    private interface Windows extends StdCallLibrary {
        Windows LIB = Native.load("Kernel32", Windows.class);
        Pointer OpenProcess(int access, boolean inherit, int processId);
        boolean SetPriorityClass(Pointer process, int priorityClass);
        boolean CloseHandle(Pointer handle);
    }

    private interface Posix extends com.sun.jna.Library {
        Posix LIB = Native.load(com.sun.jna.Platform.C_LIBRARY_NAME, Posix.class);
        int setpriority(int which, int who, int priority);
    }
}

package io.pclnt.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Locale;

final class WindowsMemory {
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final long MIN_WORKING_SET = 64L * 1024 * 1024;

    public interface Kernel extends StdCallLibrary {
        Pointer OpenProcess(int access, boolean inherit, int id);
        boolean CloseHandle(Pointer process);
        boolean ProcessIdToSessionId(int id, IntByReference session);
        boolean K32EmptyWorkingSet(Pointer process);
        boolean K32GetProcessMemoryInfo(Pointer process, Pointer counters, int size);
    }

    public interface User extends StdCallLibrary {
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer window, IntByReference id);
    }

    record Result(int processes, long reducedBytes) {}

    private WindowsMemory() {}

    static boolean supported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static synchronized Result optimize() throws IOException {
        if (!supported()) throw new IOException("当前系统不支持内存优化");
        try {
            return optimize(Native.load("kernel32", Kernel.class), Native.load("user32", User.class));
        } catch (LinkageError failure) {
            throw new IOException("无法加载 Windows 内存优化接口", failure);
        }
    }

    private static Result optimize(Kernel kernel, User user) throws IOException {
        int launcher = (int) ProcessHandle.current().pid();
        var launcherSession = new IntByReference();
        if (!kernel.ProcessIdToSessionId(launcher, launcherSession)) {
            throw new IOException("无法读取当前 Windows 会话");
        }
        var foreground = new IntByReference();
        var window = user.GetForegroundWindow();
        if (window != null) user.GetWindowThreadProcessId(window, foreground);
        var session = new IntByReference();
        int count = 0;
        long reduced = 0;
        // PROCESS_MEMORY_COUNTERS has two DWORD fields followed by eight SIZE_T fields.
        int counterSize = 8 + 8 * Native.SIZE_T_SIZE;
        try (var processes = ProcessHandle.allProcesses(); var counters = new Memory(counterSize)) {
            for (var iterator = processes.iterator(); iterator.hasNext();) {
                int pid = (int) iterator.next().pid();
                if (pid == launcher || pid == foreground.getValue()
                        || !kernel.ProcessIdToSessionId(pid, session) || session.getValue() != launcherSession.getValue()) {
                    continue;
                }
                var handle = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SET_QUOTA, false, pid);
                if (handle == null) continue;
                try {
                    long before = workingSet(kernel, handle, counters, counterSize);
                    if (before < MIN_WORKING_SET || !kernel.K32EmptyWorkingSet(handle)) continue;
                    count++;
                    long after = workingSet(kernel, handle, counters, counterSize);
                    if (after >= 0) reduced += Math.max(0, before - after);
                } finally {
                    kernel.CloseHandle(handle);
                }
            }
        }
        return new Result(count, reduced);
    }

    private static long workingSet(Kernel kernel, Pointer handle, Memory counters, int counterSize) {
        counters.setInt(0, counterSize);
        if (!kernel.K32GetProcessMemoryInfo(handle, counters, counterSize)) return -1;
        int offset = 8 + Native.SIZE_T_SIZE;
        return Native.SIZE_T_SIZE == 8 ? counters.getLong(offset) : Integer.toUnsignedLong(counters.getInt(offset));
    }
}

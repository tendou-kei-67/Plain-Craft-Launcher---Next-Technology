package io.pclnt.minecraft;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;

/** Keeps refresh tokens separate from the public parts of player profiles. */
public final class MicrosoftTokenCache {
    private static final boolean WINDOWS = com.sun.jna.Platform.isWindows();
    private static final int MAX_BYTES = 64 * 1024;
    private final Path directory;

    public MicrosoftTokenCache(Path data) { directory = data.resolve("microsoft-sessions"); }

    public MicrosoftLogin.Session read(PlayerProfile profile) throws IOException {
        Path file = file(profile.id());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!WINDOWS && Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                .stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                        && permission != PosixFilePermission.OWNER_WRITE)) {
            throw new IOException("微软会话文件权限不安全");
        }
        byte[] stored;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            stored = input.readNBytes(MAX_BYTES + 1);
        }
        if (stored.length == 0 || stored.length > MAX_BYTES) throw new IOException("无效的微软会话文件");
        byte[] tokenBytes = WINDOWS ? protect(stored, false) : stored;
        try {
            String token = new String(tokenBytes, StandardCharsets.UTF_8);
            return token.isBlank() ? null : new MicrosoftLogin.Session(profile.name(), profile.uuid(), "", token, "", Instant.EPOCH);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
        }
    }

    public void save(UUID id, MicrosoftLogin.Session session) throws IOException {
        if (session.refreshToken().isBlank()) return;
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("无效的微软会话目录");
        byte[] token = session.refreshToken().getBytes(StandardCharsets.UTF_8);
        Path staged = WINDOWS ? Files.createTempFile(directory, "session-", ".tmp")
                : Files.createTempFile(directory, "session-", ".tmp", PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        try {
            Files.write(staged, WINDOWS ? protect(token, true) : token);
            try {
                Files.move(staged, file(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, file(id), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(token, (byte) 0);
            Files.deleteIfExists(staged);
        }
    }

    public void remove(UUID id) throws IOException { Files.deleteIfExists(file(id)); }

    private Path file(UUID id) { return directory.resolve(id + ".token"); }

    @Structure.FieldOrder({"size", "data"})
    public static class Blob extends Structure {
        public int size;
        public Pointer data;
    }

    private interface Crypt32 extends StdCallLibrary {
        boolean CryptProtectData(Blob input, Pointer description, Pointer entropy, Pointer reserved, Pointer prompt,
                                 int flags, Blob output);
        boolean CryptUnprotectData(Blob input, Pointer description, Pointer entropy, Pointer reserved, Pointer prompt,
                                   int flags, Blob output);
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer LocalFree(Pointer memory);
    }

    private static final class WindowsLibraries {
        static final Crypt32 CRYPT = Native.load("Crypt32", Crypt32.class);
        static final Kernel32 KERNEL = Native.load("Kernel32", Kernel32.class);
    }

    private static byte[] protect(byte[] bytes, boolean encrypt) throws IOException {
        try (var memory = new Memory(bytes.length)) {
            memory.write(0, bytes, 0, bytes.length);
            var input = new Blob();
            input.size = bytes.length;
            input.data = memory;
            var output = new Blob();
            int noUi = 1;
            boolean success = encrypt ? WindowsLibraries.CRYPT.CryptProtectData(input, null, null, null, null, noUi, output)
                    : WindowsLibraries.CRYPT.CryptUnprotectData(input, null, null, null, null, noUi, output);
            memory.clear();
            if (!success) throw new IOException("Windows 无法" + (encrypt ? "保护" : "读取") + "微软会话");
            try {
                return output.data.getByteArray(0, output.size);
            } finally {
                output.data.clear(output.size);
                WindowsLibraries.KERNEL.LocalFree(output.data);
            }
        }
    }
}

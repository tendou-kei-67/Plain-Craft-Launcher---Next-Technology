package io.pclnt.minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/** Reads Java Edition world metadata and replaces only the edited NBT tags. */
public final class WorldSave {
    private static final int MAX_NBT_BYTES = 32 * 1024 * 1024;
    private static final List<String> DIFFICULTIES = List.of("peaceful", "easy", "normal", "hard");

    public record Position(int x, int y, int z) {}

    public record Info(String name, String version, Integer dataVersion, Long seed, Instant lastPlayed, Position spawn,
                       int gameMode, long playSeconds, boolean hardcore, Boolean commands, Integer difficulty,
                       Boolean difficultyLocked, boolean recoveredFromBackup) {}

    public record Settings(Boolean commands, Integer difficulty, Boolean difficultyLocked) {}

    private WorldSave() {}

    public static Info read(Path world) throws IOException {
        return metadata(load(world), world);
    }

    public static void update(Path world, Settings settings) throws IOException {
        withSaveLock(world, () -> {
            Path level = world.resolve("level.dat");
            Path backup = world.resolve("level.dat_old");
            if (Files.isSymbolicLink(level) || Files.isSymbolicLink(backup)) {
                throw new IOException("存档元数据是符号链接");
            }
            var stamp = Stamp.read(level);
            var document = load(world);
            var info = metadata(document, world);
            var changes = new ArrayList<Change>();
            if (settings.commands() != null) {
                changes.add(change(document.data().child("allowCommands"), 1, byteValue(settings.commands())));
            }
            if (settings.difficulty() != null || settings.difficultyLocked() != null) {
                editDifficulty(document.data(), info, settings, changes);
            }
            if (!changes.isEmpty()) replace(world, document, stamp, changes);
            return null;
        });
    }

    @FunctionalInterface
    interface SaveEdit<T> {
        T run() throws IOException;
    }

    static <T> T withSaveLock(Path world, SaveEdit<T> edit) throws IOException {
        checkDirectory(world);
        Path lockPath = world.resolve("session.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("存档锁文件是符号链接");
        try (var channel = Files.exists(lockPath, NOFOLLOW_LINKS) ? FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS) : null;
             var lock = channel == null ? null : acquireLock(channel)) {
            return edit.run();
        }
    }

    private static void editDifficulty(Tag data, Info info, Settings settings, List<Change> changes) throws IOException {
        var modern = data.child("difficulty_settings");
        boolean namespaced = modern != null && modern.type() == 10 && modern.child("difficulty") != null;
        if (settings.difficulty() != null) {
            int difficulty = settings.difficulty();
            if (difficulty < 0 || difficulty >= DIFFICULTIES.size()) throw new IOException("无效的游戏难度");
            changes.add(namespaced ? change(modern.child("difficulty"), 8, encodedString(DIFFICULTIES.get(difficulty)))
                    : change(data.child("Difficulty"), 1, new byte[] {(byte) difficulty}));
        }
        if (settings.difficultyLocked() != null && !info.hardcore()) {
            byte[] value = byteValue(settings.difficultyLocked());
            var field = namespaced ? modern.child("locked") : data.child("DifficultyLocked");
            if (field == null && namespaced) {
                var entry = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(entry)) {
                    out.writeByte(1);
                    out.writeUTF("locked");
                    out.write(value);
                }
                changes.add(new Change(modern.end() - 1, modern.end() - 1, entry.toByteArray()));
            } else {
                changes.add(change(field, 1, value));
            }
        }
    }

    private static Change change(Tag tag, int type, byte[] replacement) throws IOException {
        if (tag == null || tag.type() != type) throw new IOException("当前存档不包含此设置");
        return new Change(tag.start(), tag.end(), replacement);
    }

    private static byte[] byteValue(boolean value) {
        return new byte[] {(byte) (value ? 1 : 0)};
    }

    private static byte[] encodedString(String value) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(result)) {
            out.writeUTF(value);
        }
        return result.toByteArray();
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        try {
            var lock = channel.tryLock();
            if (lock != null) return lock;
        } catch (OverlappingFileLockException ignored) {
            // Another operation in this launcher holds the same world's lock.
        }
        throw new IOException("存档正在使用，请退出该世界后再修改");
    }

    private static void replace(Path world, Document document, Stamp stamp, List<Change> changes) throws IOException {
        Path level = world.resolve("level.dat");
        Path edited = Files.createTempFile(world, ".pcl-nt-level-", ".dat");
        Path backupCopy = null;
        try {
            changes.sort(Comparator.comparingInt(Change::start));
            try (var out = new GZIPOutputStream(Files.newOutputStream(edited))) {
                int cursor = 0;
                for (var change : changes) {
                    out.write(document.bytes(), cursor, change.start() - cursor);
                    out.write(change.bytes());
                    cursor = change.end();
                }
                out.write(document.bytes(), cursor, document.bytes().length - cursor);
            }
            // A failed edit must keep level.dat readable, including on filesystems without atomic replacement.
            checkUnchanged(level, stamp);
            if (!document.backup()) {
                backupCopy = Files.createTempFile(world, ".pcl-nt-backup-", ".dat");
                Files.copy(level, backupCopy, REPLACE_EXISTING, NOFOLLOW_LINKS);
                checkUnchanged(level, stamp);
                Files.move(backupCopy, world.resolve("level.dat_old"), ATOMIC_MOVE, REPLACE_EXISTING);
            }
            checkUnchanged(level, stamp);
            Files.move(edited, level, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(edited);
            if (backupCopy != null) Files.deleteIfExists(backupCopy);
        }
    }

    private static void checkUnchanged(Path level, Stamp stamp) throws IOException {
        if (!Objects.equals(stamp, Stamp.read(level))) throw new IOException("存档已被其他程序修改，请刷新后重试");
    }

    private static Document load(Path world) throws IOException {
        checkDirectory(world);
        try {
            return document(world.resolve("level.dat"), false);
        } catch (IOException primary) {
            try {
                return document(world.resolve("level.dat_old"), true);
            } catch (IOException backup) {
                primary.addSuppressed(backup);
                throw primary;
            }
        }
    }

    private static void checkDirectory(Path world) throws IOException {
        if (!Files.isDirectory(world, NOFOLLOW_LINKS)) throw new IOException("存档文件夹不存在");
    }

    private static Document document(Path file, boolean backup) throws IOException {
        var parsed = nbt(file);
        var data = parsed.root().child("Data");
        if (data == null || data.type() != 10) throw new IOException(file.getFileName() + " 中缺少 Data 标签");
        return new Document(parsed.bytes(), data, backup);
    }

    private static Info metadata(Document document, Path world) throws IOException {
        var buffer = ByteBuffer.wrap(document.bytes());
        var data = document.data();
        var version = data.child("Version");
        var dataVersion = version == null ? null : version.integer(buffer, "Id");
        if (dataVersion == null) dataVersion = data.integer(buffer, "DataVersion");
        var seed = longValue(buffer, data.child("WorldGenSettings"), "seed");
        if (seed == null) seed = data.longValue(buffer, "RandomSeed");
        if (seed == null) seed = externalSeed(world);
        var lastPlayed = data.longValue(buffer, "LastPlayed");
        var ticks = data.longValue(buffer, "Time");
        var difficulty = data.child("difficulty_settings");
        var named = string(document.bytes(), difficulty, "difficulty");
        Integer level = named == null ? data.byteInt(buffer, "Difficulty") : Integer.valueOf(DIFFICULTIES.indexOf(named));
        if (level != null && (level < 0 || level > 3)) level = null;
        var commands = flag(buffer, data, "allowCommands");
        var hardcore = flag(buffer, difficulty, "hardcore");
        if (hardcore == null) hardcore = flag(buffer, data, "hardcore");
        var locked = flag(buffer, difficulty, "locked");
        if (locked == null) locked = flag(buffer, data, "DifficultyLocked");
        if (named != null && locked == null) locked = false;
        return new Info(data.string(document.bytes(), "LevelName"), string(document.bytes(), version, "Name"),
                dataVersion, seed, lastPlayed == null ? null : Instant.ofEpochMilli(lastPlayed),
                position(buffer, data), Objects.requireNonNullElse(data.integer(buffer, "GameType"), 0),
                ticks == null ? 0 : Math.max(0, ticks / 20), Boolean.TRUE.equals(hardcore), commands, level, locked,
                document.backup());
    }

    private static Position position(ByteBuffer buffer, Tag data) {
        var spawn = data.child("spawn");
        var array = spawn == null ? null : spawn.child("pos");
        if (array != null && array.type() == 11 && buffer.getInt(array.start()) == 3) {
            int offset = array.start() + 4;
            return new Position(buffer.getInt(offset), buffer.getInt(offset + 4), buffer.getInt(offset + 8));
        }
        Integer x = data.integer(buffer, "SpawnX"), y = data.integer(buffer, "SpawnY"), z = data.integer(buffer, "SpawnZ");
        return x == null || y == null || z == null ? null : new Position(x, y, z);
    }

    private static Long externalSeed(Path world) {
        try {
            var parsed = nbt(world.resolve("data/minecraft/world_gen_settings.dat"));
            return longValue(ByteBuffer.wrap(parsed.bytes()), parsed.root().child("data"), "seed");
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Long longValue(ByteBuffer bytes, Tag tag, String name) {
        return tag == null ? null : tag.longValue(bytes, name);
    }

    private static String string(byte[] bytes, Tag tag, String name) throws IOException {
        return tag == null ? null : tag.string(bytes, name);
    }

    private static Boolean flag(ByteBuffer bytes, Tag tag, String name) {
        var value = tag == null ? null : tag.byteInt(bytes, name);
        return value == null ? null : value != 0;
    }

    private static Nbt nbt(Path file) throws IOException {
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("找不到 " + file.getFileName());
        byte[] bytes;
        try (var buffered = new java.io.BufferedInputStream(Files.newInputStream(file, NOFOLLOW_LINKS))) {
            buffered.mark(2);
            int first = buffered.read(), second = buffered.read();
            buffered.reset();
            try (var stream = first == 0x1f && second == 0x8b ? new GZIPInputStream(buffered)
                    : first == 0x78 && second >= 0 && ((first << 8) + second) % 31 == 0
                    ? new InflaterInputStream(buffered) : buffered) {
                bytes = stream.readNBytes(MAX_NBT_BYTES + 1);
            }
        }
        if (bytes.length > MAX_NBT_BYTES) throw new IOException("存档元数据过大");
        var reader = new Reader(bytes);
        if (reader.in.readUnsignedByte() != 10) throw new IOException("无效的 NBT 根标签");
        reader.in.readUTF();
        return new Nbt(bytes, reader.tag(10, 0, true));
    }

    private record Nbt(byte[] bytes, Tag root) {}
    private record Document(byte[] bytes, Tag data, boolean backup) {}
    private record Change(int start, int end, byte[] bytes) {}

    private record Stamp(FileTime modified, long size, Object key) {
        static Stamp read(Path path) throws IOException {
            try {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) throw new IOException("level.dat 不是普通文件");
                return new Stamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey());
            } catch (NoSuchFileException missing) {
                return null;
            }
        }
    }

    private record Tag(int type, int start, int end, Map<String, Tag> children) {
        Tag child(String name) { return children.get(name); }

        Integer integer(ByteBuffer bytes, String name) {
            var tag = child(name);
            return tag != null && tag.type == 3 ? bytes.getInt(tag.start) : null;
        }

        Integer byteInt(ByteBuffer bytes, String name) {
            var tag = child(name);
            return tag != null && tag.type == 1 ? (int) bytes.get(tag.start) : null;
        }

        Long longValue(ByteBuffer bytes, String name) {
            var tag = child(name);
            return tag != null && tag.type == 4 ? bytes.getLong(tag.start) : null;
        }

        String string(byte[] bytes, String name) throws IOException {
            var tag = child(name);
            if (tag == null || tag.type != 8) return null;
            return new DataInputStream(new ByteArrayInputStream(bytes, tag.start, tag.end - tag.start)).readUTF();
        }
    }

    private static final class Reader {
        private final DataInputStream in;
        private final int length;
        private int tagCount;

        Reader(byte[] bytes) {
            in = new DataInputStream(new ByteArrayInputStream(bytes));
            length = bytes.length;
        }

        Tag tag(int type, int depth, boolean index) throws IOException {
            if (depth > 64 || ++tagCount > 500_000) throw new IOException("存档 NBT 结构过于复杂");
            int start = length - in.available();
            Map<String, Tag> children = Map.of();
            switch (type) {
                case 1 -> in.skipNBytes(1);
                case 2 -> in.skipNBytes(2);
                case 3, 5 -> in.skipNBytes(4);
                case 4, 6 -> in.skipNBytes(8);
                case 7 -> in.skipNBytes(arraySize(1));
                case 8 -> {
                    if (index) in.readUTF();
                    else in.skipNBytes(in.readUnsignedShort());
                }
                case 9 -> list(depth);
                case 10 -> children = compound(depth, index);
                case 11 -> in.skipNBytes(arraySize(4));
                case 12 -> in.skipNBytes(arraySize(8));
                default -> throw new IOException("无效的 NBT 标签类型 " + type);
            }
            return index ? new Tag(type, start, length - in.available(), children) : null;
        }

        private void list(int depth) throws IOException {
            int type = in.readUnsignedByte(), size = in.readInt();
            if (size <= 0) return;
            int width = switch (type) {
                case 1 -> 1;
                case 2 -> 2;
                case 3, 5 -> 4;
                case 4, 6 -> 8;
                default -> 0;
            };
            if (width != 0) {
                in.skipNBytes((long) size * width);
                return;
            }
            if (size > in.available() || type == 0 || type > 12) throw new IOException("无效的 NBT 列表");
            for (int index = 0; index < size; index++) tag(type, depth + 1, false);
        }

        private Map<String, Tag> compound(int depth, boolean index) throws IOException {
            Map<String, Tag> children = index ? new HashMap<>() : Map.of();
            for (int type; (type = in.readUnsignedByte()) != 0;) {
                if (index && depth < 3) {
                    String name = in.readUTF();
                    if (children.putIfAbsent(name, tag(type, depth + 1, true)) != null)
                        throw new IOException("NBT 中有重复标签：" + name);
                } else {
                    in.skipNBytes(in.readUnsignedShort());
                    tag(type, depth + 1, false);
                }
            }
            return children;
        }

        private long arraySize(int width) throws IOException {
            int length = in.readInt();
            if (length < 0) throw new IOException("无效的 NBT 数组");
            return (long) length * width;
        }
    }
}

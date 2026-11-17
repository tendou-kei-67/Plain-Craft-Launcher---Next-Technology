package io.pclnt.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** ZIP data packs follow PCL-CE's .zip.disabled convention. */
public final class WorldDatapacks {
    public static final int FIRST_DATA_VERSION = 1443; // 17w47a
    private static final String DISABLED = ".disabled";
    private static final int MAX_METADATA_BYTES = 128 * 1024;

    public record Failure(Path path, IOException cause) {}
    public record BatchResult(int changed, List<Failure> failures) {}
    public enum State { ENABLED, DISABLED, UNAVAILABLE }
    public record Metadata(String description, Integer packFormat) {
        private static final Metadata EMPTY = new Metadata("", null);
    }
    public record Archive(Path path, long size, FileTime created, FileTime modified, Metadata metadata, String error) {
        public String fileName() { return enabledName(path.getFileName().toString()); }
        public String resourceName() {
            String name = fileName();
            return name.substring(0, name.length() - ".zip".length());
        }

        public State state() {
            if (error != null) return State.UNAVAILABLE;
            return fileName().equals(path.getFileName().toString()) ? State.ENABLED : State.DISABLED;
        }
    }
    public record Fingerprint(String sha1, Long curseForge) {}
    public record Update(Archive archive, String installedSha1, CommunityResources.File file) {}
    public record UpdateResult(BatchResult batch, List<Path> backups) {}

    private WorldDatapacks() {}

    public static List<Path> list(Path world) throws IOException {
        Path folder = folder(world);
        if (!Files.exists(folder, NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(folder)) {
            return entries.filter(WorldDatapacks::isArchive)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public static List<Archive> describe(Path world) throws IOException {
        var archives = new ArrayList<Archive>();
        for (Path path : list(world)) {
            try {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) continue;
                Metadata metadata;
                try (var zip = new ZipFile(path.toFile())) {
                    metadata = metadata(zip);
                } catch (IOException failure) {
                    String error = failure.getLocalizedMessage();
                    archives.add(new Archive(path, attributes.size(), attributes.creationTime(), attributes.lastModifiedTime(), Metadata.EMPTY,
                            error == null || error.isBlank() ? failure.getClass().getSimpleName() : error));
                    continue;
                }
                archives.add(new Archive(path, attributes.size(), attributes.creationTime(), attributes.lastModifiedTime(), metadata, null));
            } catch (NoSuchFileException ignored) {
                // The file was removed or renamed during the scan.
            }
        }
        return List.copyOf(archives);
    }

    private static Metadata metadata(ZipFile zip) {
        var entry = zip.getEntry("pack.mcmeta");
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_METADATA_BYTES) return Metadata.EMPTY;
        try (var stream = zip.getInputStream(entry)) {
            byte[] bytes = stream.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) return Metadata.EMPTY;
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (json.startsWith("\uFEFF")) json = json.substring(1);
            var root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return Metadata.EMPTY;
            var pack = root.getAsJsonObject().get("pack");
            if (pack == null || !pack.isJsonObject()) return Metadata.EMPTY;
            var data = pack.getAsJsonObject();
            var description = new StringBuilder();
            appendText(data.get("description"), description, 0);
            var format = data.get("pack_format");
            Integer version = null;
            if (format != null && format.isJsonPrimitive() && format.getAsJsonPrimitive().isNumber()
                    && format.getAsString().matches("[0-9]{1,9}")) version = format.getAsInt();
            return new Metadata(description.toString().strip(), version);
        } catch (IOException | JsonParseException ignored) {
            return Metadata.EMPTY;
        }
    }

    private static void appendText(JsonElement component, StringBuilder result, int depth) {
        if (component == null || depth == 32 || result.length() >= 4096) return;
        if (component.isJsonPrimitive() && component.getAsJsonPrimitive().isString()) {
            String text = component.getAsString();
            result.append(text, 0, Math.min(4096 - result.length(), text.length()));
        } else if (component.isJsonArray()) {
            for (var child : component.getAsJsonArray()) appendText(child, result, depth + 1);
        } else if (component.isJsonObject()) {
            var item = component.getAsJsonObject();
            var text = item.get("text");
            if (text == null) text = item.has("fallback") ? item.get("fallback") : item.get("translate");
            appendText(text, result, depth + 1);
            appendText(item.get("extra"), result, depth + 1);
        }
    }

    public static Path locate(Path world, Path archive) throws IOException {
        return entry(folder(world), archive);
    }

    public static Fingerprint fingerprint(Path world, Archive archive, boolean curseForge) throws IOException, InterruptedException {
        Path path = locate(world, archive.path());
        var before = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (before.size() != archive.size() || !before.lastModifiedTime().equals(archive.modified())) {
            throw new IOException("数据包在识别前已更改");
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        int filteredLength = 0;
        try (var stream = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            for (int amount; (amount = stream.read(buffer)) != -1;) {
                if (Thread.interrupted()) throw new InterruptedException();
                sha.update(buffer, 0, amount);
                if (curseForge) {
                    for (int i = 0; i < amount; i++) if (fingerprintByte(buffer[i] & 255)) filteredLength++;
                }
            }
        }
        Long fingerprint = null;
        if (curseForge) {
            try (var stream = Files.newInputStream(path, NOFOLLOW_LINKS)) {
                int hash = 1 ^ filteredLength;
                int word = 0, shift = 0;
                int multiplier = 0x5bd1e995;
                for (int amount; (amount = stream.read(buffer)) != -1;) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    for (int i = 0; i < amount; i++) {
                        int value = buffer[i] & 255;
                        if (!fingerprintByte(value)) continue;
                        word |= value << shift;
                        shift += 8;
                        if (shift == 32) {
                            word *= multiplier;
                            word ^= word >>> 24;
                            hash = hash * multiplier ^ word * multiplier;
                            word = shift = 0;
                        }
                    }
                }
                if (shift != 0) hash = (hash ^ word) * multiplier;
                hash ^= hash >>> 13;
                hash *= multiplier;
                fingerprint = Integer.toUnsignedLong(hash ^ (hash >>> 15));
            }
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
                || !java.util.Objects.equals(after.fileKey(), before.fileKey())) throw new IOException("数据包在识别期间已更改");
        return new Fingerprint(HexFormat.of().formatHex(sha.digest()), fingerprint);
    }

    private static boolean fingerprintByte(int value) {
        return value != 9 && value != 10 && value != 13 && value != 32;
    }

    public static void export(Path world, Path destination, boolean csv) throws IOException {
        export(world, destination, csv, Map.of());
    }

    public static void export(Path world, Path destination, boolean csv,
                              Map<Archive, CommunityResources.InstalledResource> matched) throws IOException {
        Path output = destination.toAbsolutePath().normalize();
        String suffix = csv ? ".csv" : ".txt";
        if (!output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
            throw new IOException("请将导出文件保存为 " + suffix);
        }
        var records = describe(world);
        Path staged = Files.createTempFile(output.getParent(), ".pcl-nt-datapack-info-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(staged, java.nio.charset.StandardCharsets.UTF_8)) {
                // Match the reference's UTF-8 signature and CRLF line endings for spreadsheet apps.
                writer.write('\uFEFF');
                if (csv) writer.write("文件名,数据包名称,数据包版本,此版本更新时间,工程 ID,文件大小（字节）,文件路径");
                boolean first = !csv;
                for (var archive : records) {
                    if (!first) writer.write("\r\n");
                    first = false;
                    if (csv) {
                        var resource = matched.get(archive);
                        writer.write(csvField(archive.fileName()));
                        if (resource == null) {
                            writer.write(",,,,,");
                        } else {
                            String published = resource.published().equals(java.time.Instant.EPOCH) ? "" : resource.published().toString();
                            writer.write("," + csvField(resource.project().localizedTitle()) + "," + csvField(resource.version())
                                    + "," + csvField(published) + "," + csvField(resource.project().id()) + ",");
                        }
                        writer.write(archive.size() + "," + csvField(archive.path().toString()));
                    } else {
                        writer.write(archive.fileName());
                    }
                }
            }
            try {
                Files.move(staged, output, ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staged, output, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static String csvField(String value) {
        String trimmed = value.stripLeading();
        if (value.startsWith("\t") || value.startsWith("\r") || value.startsWith("\n")
                || !trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean isArchive(Path path) {
        return Files.isRegularFile(path, NOFOLLOW_LINKS)
                && enabledName(path.getFileName().toString()).toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    public static String enabledName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(DISABLED) ? name.substring(0, name.length() - DISABLED.length()) : name;
    }

    public static Path ensureFolder(Path world) throws IOException {
        return WorldSave.withSaveLock(world, () -> createFolder(world));
    }

    public static void setEnabled(Path world, Path archive, boolean enabled) throws IOException {
        WorldSave.withSaveLock(world, () -> {
            rename(folder(world), archive, enabled);
            return null;
        });
    }

    public static void install(Path world, Path archive) throws IOException {
        WorldSave.withSaveLock(world, () -> {
            copyArchive(createFolder(world), archive);
            return null;
        });
    }

    public static void moveToTrash(Path world, Path archive) throws IOException {
        WorldSave.withSaveLock(world, () -> {
            trash(folder(world), archive);
            return null;
        });
    }

    public static BatchResult setEnabled(Path world, List<Path> archives, boolean enabled) throws IOException {
        return batch(world, archives, false, (folder, archive) -> rename(folder, archive, enabled));
    }

    public static BatchResult install(Path world, List<Path> archives) throws IOException {
        return batch(world, archives, true, WorldDatapacks::copyArchive);
    }

    public static BatchResult moveToTrash(Path world, List<Path> archives) throws IOException {
        return batch(world, archives, false, WorldDatapacks::trash);
    }

    public static UpdateResult update(Path world, List<Update> updates, CommunityResources catalog,
                                      java.util.function.IntConsumer completed)
            throws IOException, InterruptedException {
        Path folder = folder(world);
        var backups = new ArrayList<Path>();
        var failures = new ArrayList<Failure>();
        int changed = 0;
        for (var update : updates) {
            try {
                replace(world, folder, update, catalog, backups);
                changed++;
            } catch (IOException failure) {
                failures.add(new Failure(update.archive().path(), failure));
            }
            completed.accept(changed + failures.size());
        }
        return new UpdateResult(new BatchResult(changed, List.copyOf(failures)), List.copyOf(backups));
    }

    private static void replace(Path world, Path folder, Update update, CommunityResources catalog, List<Path> backups)
            throws IOException, InterruptedException {
        var archive = update.archive();
        var file = update.file();
        if (archive.state() == State.UNAVAILABLE || !update.installedSha1().matches("(?i)[0-9a-f]{40}")
                || !file.sha1().matches("(?i)[0-9a-f]{40}") || file.sha1().equalsIgnoreCase(update.installedSha1())) {
            throw new IOException("找不到可验证的数据包更新");
        }
        String name = ResourceDestination.suggestedName(CommunityResources.Kind.DATAPACK, file.name());
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("更新文件不是 ZIP 数据包");
        Path stage = Files.createTempDirectory(folder, ".pcl-nt-datapack-update-");
        Path downloaded = stage.resolve("download.part");
        Path backup = stage.resolve(archive.path().getFileName());
        try {
            catalog.download(file, downloaded);
            try (var zip = new ZipFile(downloaded.toFile())) {
                if (zip.getEntry("pack.mcmeta") == null) throw new IOException("下载的 ZIP 缺少 pack.mcmeta");
            }
            WorldSave.withSaveLock(world, () -> {
                Path source = locate(world, archive.path());
                String current;
                try {
                    current = fingerprint(world, archive, false).sha1();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("已取消更新数据包");
                }
                if (!current.equalsIgnoreCase(update.installedSha1())) throw new IOException("数据包内容已更改，请刷新后重试");
                Path enabled = folder.resolve(name);
                Path disabled = folder.resolve(name + DISABLED);
                for (var target : List.of(enabled, disabled)) {
                    if (!source.equals(target) && Files.exists(target, NOFOLLOW_LINKS)) {
                        throw new java.nio.file.FileAlreadyExistsException(target.toString());
                    }
                }
                Path destination = archive.state() == State.DISABLED ? disabled : enabled;
                Files.move(source, backup);
                try {
                    Files.move(downloaded, destination);
                } catch (IOException failure) {
                    try {
                        Files.move(backup, source);
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                        throw new IOException("无法恢复原文件，备份位于 " + backup, failure);
                    }
                    throw failure;
                }
                return null;
            });
            try {
                if (!moveToDesktopTrash(backup)) backups.add(backup);
            } catch (RuntimeException failure) {
                backups.add(backup);
            }
        } finally {
            try {
                Files.deleteIfExists(downloaded);
                if (!Files.exists(backup, NOFOLLOW_LINKS)) Files.deleteIfExists(stage);
            } catch (IOException failure) {
                System.getLogger(WorldDatapacks.class.getName()).log(System.Logger.Level.WARNING, "无法清理数据包更新临时文件", failure);
            }
        }
    }

    @FunctionalInterface
    private interface ArchiveOperation {
        boolean run(Path folder, Path archive) throws IOException;
    }

    private static BatchResult batch(Path world, List<Path> archives, boolean create, ArchiveOperation operation) throws IOException {
        return WorldSave.withSaveLock(world, () -> {
            Path folder = create ? createFolder(world) : folder(world);
            var failures = new ArrayList<Failure>();
            int changed = 0;
            for (Path archive : archives.stream().distinct().toList()) {
                try {
                    if (operation.run(folder, archive)) changed++;
                } catch (IOException failure) {
                    failures.add(new Failure(archive, failure));
                }
            }
            return new BatchResult(changed, List.copyOf(failures));
        });
    }

    private static boolean rename(Path folder, Path archive, boolean enabled) throws IOException {
        Path source = entry(folder, archive);
        String name = source.getFileName().toString();
        String raw = enabledName(name);
        if (name.equals(raw) == enabled) return false;
        // The reference only offers state changes for readable archives.
        try (var ignored = new ZipFile(source.toFile())) {
            // Opening the central directory is the availability check.
        }
        Files.move(source, folder.resolve(enabled ? raw : raw + DISABLED));
        return true;
    }

    private static boolean copyArchive(Path folder, Path archive) throws IOException {
        if (!isArchive(archive)) throw new IOException("请选择 ZIP 数据包");
        Path target = folder.resolve(archive.getFileName());
        if (Files.exists(target, NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
        var temporary = Files.createTempFile(folder, ".pcl-nt-datapack-", ".part");
        try {
            Files.copy(archive, temporary, REPLACE_EXISTING, NOFOLLOW_LINKS);
            if (!Files.isRegularFile(temporary, NOFOLLOW_LINKS)) throw new IOException("数据包已被替换");
            Files.move(temporary, target);
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean trash(Path folder, Path archive) throws IOException {
        Path source = entry(folder, archive);
        if (!moveToDesktopTrash(source)) throw new IOException("无法将数据包移入回收站");
        return true;
    }

    private static boolean moveToDesktopTrash(Path file) {
        return java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
                && java.awt.Desktop.getDesktop().moveToTrash(file.toFile());
    }

    private static Path entry(Path folder, Path archive) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        if (!folder.equals(source.getParent()) || !isArchive(source)) throw new IOException("此存档中没有该数据包");
        return source;
    }

    private static Path folder(Path world) throws IOException {
        if (!Files.isDirectory(world, NOFOLLOW_LINKS)) throw new IOException("存档文件夹已移动或删除");
        Path folder = world.toAbsolutePath().normalize().resolve("datapacks");
        if (Files.exists(folder, NOFOLLOW_LINKS) && !Files.isDirectory(folder, NOFOLLOW_LINKS)) {
            throw new IOException("数据包目录不可用");
        }
        return folder;
    }

    private static Path createFolder(Path world) throws IOException {
        Path folder = folder(world);
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(folder);
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) throw exists;
            }
        }
        return folder;
    }
}

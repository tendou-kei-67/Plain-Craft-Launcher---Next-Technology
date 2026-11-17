package io.pclnt.ui;

import com.google.gson.Gson;
import io.pclnt.minecraft.GameLaunch;
import io.pclnt.minecraft.PlayerProfile;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class PlayerProfiles {
    private record State(List<PlayerProfile> profiles, UUID selectedId) {
        private State {
            if (profiles == null || profiles.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("缺少档案数据");
            }
            profiles = List.copyOf(profiles);
            if (profiles.stream().map(PlayerProfile::id).distinct().count() != profiles.size()) {
                throw new IllegalArgumentException("档案 ID 重复");
            }
        }
    }

    private final Path file;
    private final Gson gson = new Gson();
    private State state;
    private boolean readFailed;

    PlayerProfiles(Path directory, String previousNames, Notifications notifications) {
        file = directory.resolve("offline-profiles.json");
        state = new State(Arrays.stream(previousNames.split("¨")).filter(GameLaunch::validName)
                .distinct().map(PlayerProfile::fromLegacyName).toList(), null);
        if (Files.notExists(file)) {
            return;
        }
        try (var reader = Files.newBufferedReader(file)) {
            var loaded = gson.fromJson(reader, State.class);
            if (loaded == null) {
                throw new IOException("档案文件为空");
            }
            state = loaded;
        } catch (IOException | RuntimeException failure) {
            // Gson can wrap record constructor validation failures in RuntimeException.
            readFailed = true;
            notifications.post("无法读取离线档案。原档案文件已保留。", Notifications.Kind.ERROR);
        }
    }

    List<PlayerProfile> profiles() { return state.profiles(); }

    PlayerProfile selected() {
        return profiles().stream().filter(profile -> profile.id().equals(state.selectedId())).findFirst().orElse(null);
    }

    void select(PlayerProfile profile) throws IOException {
        save(new State(profiles(), profile == null ? null : profile.id()));
    }

    void put(PlayerProfile profile) throws IOException {
        put(profile, state.selectedId());
    }

    void putAndSelect(PlayerProfile profile) throws IOException {
        put(profile, profile.id());
    }

    private void put(PlayerProfile profile, UUID selectedId) throws IOException {
        var updated = new java.util.ArrayList<>(profiles());
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).id().equals(profile.id())) {
                updated.set(index, profile);
                save(new State(updated, selectedId));
                return;
            }
        }
        updated.add(profile);
        save(new State(updated, selectedId));
    }

    void delete(PlayerProfile profile) throws IOException {
        var remaining = profiles().stream().filter(item -> !item.id().equals(profile.id())).toList();
        save(new State(remaining, profile.id().equals(state.selectedId()) ? null : state.selectedId()));
    }

    private void save(State next) throws IOException {
        if (readFailed) {
            throw new IOException("请先恢复离线档案文件");
        }
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), "offline-profiles-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(next));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            state = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

package io.pclnt.minecraft;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record PlayerProfile(UUID id, String name, String uuid, String server, boolean microsoft) {
    public PlayerProfile(UUID id, String name, String uuid) {
        this(id, name, uuid, "", false);
    }

    public PlayerProfile(UUID id, String name, String uuid, String server) {
        this(id, name, uuid, server, false);
    }

    public PlayerProfile {
        if (id == null || name == null || !GameLaunch.validName(name) || uuid == null || !uuid.matches("[a-fA-F0-9]{32}")) {
            throw new IllegalArgumentException("无效的游戏档案");
        }
        server = server == null || server.isBlank() ? "" : Yggdrasil.server(server).toString();
        if (microsoft && !server.isEmpty()) throw new IllegalArgumentException("档案验证类型冲突");
    }

    public boolean thirdParty() { return !server.isEmpty(); }

    public static String standardUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    public static PlayerProfile fromLegacyName(String name) {
        return new PlayerProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, GameLaunch.offlineUuid(name));
    }

    public String skinVariant() {
        int parity = 0;
        for (int index = 7; index < uuid.length(); index += 8) {
            parity ^= Character.digit(uuid.charAt(index), 16);
        }
        return (parity & 1) == 0 ? "Steve" : "Alex";
    }
}

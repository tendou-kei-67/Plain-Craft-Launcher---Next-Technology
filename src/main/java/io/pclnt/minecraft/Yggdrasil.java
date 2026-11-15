package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class Yggdrasil {
    private static final URI AGENT = URI.create(
            "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar");
    private static final String SHA256 = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";
    // Credentials are only sent to the API address entered by the user.
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();

    public record Character(String id, String name) {
        public Character {
            if (!id.matches("[a-fA-F0-9]{32}") || !GameLaunch.validName(name)) {
                throw new IllegalArgumentException("服务器返回了无效角色");
            }
        }

        @Override public String toString() { return name; }
    }

    public static final class Session {
        private final URI server;
        private final String accessToken;
        private final String clientToken;
        private final Character selected;
        private final List<Character> characters;

        private Session(URI server, String accessToken, String clientToken, Character selected, List<Character> characters) {
            this.server = server;
            this.accessToken = accessToken;
            this.clientToken = clientToken;
            this.selected = selected;
            this.characters = List.copyOf(characters);
        }

        public URI server() { return server; }
        public Character selected() { return selected; }
        public List<Character> characters() { return characters; }
        String accessToken() { return accessToken; }
    }

    public static URI server(String address) {
        var server = URI.create(address.strip()).normalize();
        if (!"https".equalsIgnoreCase(server.getScheme()) || server.getHost() == null
                || server.getRawUserInfo() != null || server.getRawQuery() != null || server.getRawFragment() != null) {
            throw new IllegalArgumentException("请输入完整的 HTTPS 验证服务器 API 地址");
        }
        String value = server.toASCIIString();
        return URI.create(value.endsWith("/") ? value : value + "/");
    }

    public Session login(URI server, String username, String password) throws IOException, InterruptedException {
        var request = new JsonObject();
        request.addProperty("username", username);
        request.addProperty("password", password);
        var agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        request.add("agent", agent);
        return session(server, post(server, "authenticate", request), "", null);
    }

    public Session choose(Session previous, Character character) throws IOException, InterruptedException {
        if (previous.selected() != null || !previous.characters().contains(character)) {
            throw new IOException("无法选择此角色");
        }
        return refresh(previous, character);
    }

    public Session forLaunch(Session previous) throws IOException, InterruptedException {
        if (previous.selected() == null) throw new IOException("请先选择第三方角色");
        int status = send(previous.server(), "validate", tokenRequest(previous)).statusCode();
        if (status == 204) return previous;
        if (status == 401 || status == 403) return refresh(previous, null);
        throw new IOException("验证会话失败（HTTP " + status + "）");
    }

    public record Appearance(Character character, byte[] skin) {}

    public Appearance appearance(URI server, String uuid) throws IOException, InterruptedException {
        if (!uuid.matches("[a-fA-F0-9]{32}")) throw new IOException("无效的角色 UUID");
        var api = server(server.toString());
        var profile = readObject(api.resolve("sessionserver/session/minecraft/profile/" + uuid));
        try {
            var character = character(profile);
            if (!uuid.equalsIgnoreCase(character.id())) throw new IOException("验证服务器返回了其他角色");
            for (var entry : VanillaInstaller.array(profile, "properties")) {
                var property = entry.getAsJsonObject();
                if (!"textures".equals(VanillaInstaller.string(property, "name"))) continue;
                var decoded = Base64.getDecoder().decode(VanillaInstaller.string(property, "value"));
                var data = JsonParser.parseString(new String(decoded, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var textures = data.getAsJsonObject("textures");
                var skin = textures == null ? null : textures.getAsJsonObject("SKIN");
                if (skin != null) {
                    URI url = httpsLink(VanillaInstaller.string(skin, "url")).orElseThrow(
                            () -> new IOException("服务器皮肤地址必须使用 HTTPS"));
                    return new Appearance(character, texture(url));
                }
            }
            return new Appearance(character, null);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException | JsonParseException error) {
            throw new IOException("验证服务器返回的皮肤资料无效");
        }
    }

    public URI managementPage(URI server, boolean wardrobe) throws IOException, InterruptedException {
        var api = server(server.toString());
        String path = api.getRawPath();
        String suffix = "/api/yggdrasil/";
        if (path.endsWith(suffix)) {
            var origin = api.resolve("/").toASCIIString();
            String prefix = path.substring(1, path.length() - suffix.length() + 1);
            return URI.create(origin + prefix + (wardrobe ? "user/closet" : "user/profile"));
        }
        var meta = readObject(api).getAsJsonObject("meta");
        var links = meta == null ? null : meta.getAsJsonObject("links");
        return links == null ? api.resolve("/") : httpsLink(VanillaInstaller.string(links, "homepage")).orElse(api.resolve("/"));
    }

    private JsonObject readObject(URI address) throws IOException, InterruptedException {
        var response = client.send(HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("读取验证服务器资料失败（HTTP " + response.statusCode() + "）");
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException error) {
            throw new IOException("验证服务器未返回有效 JSON");
        }
    }

    private byte[] texture(URI address) throws IOException, InterruptedException {
        int limit = 2 * 1024 * 1024;
        var response = client.send(HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var content = response.body()) {
            if (response.statusCode() != 200) throw new IOException("读取皮肤失败（HTTP " + response.statusCode() + "）");
            byte[] bytes = content.readNBytes(limit + 1);
            if (bytes.length < 24 || bytes.length > limit
                    || !HexFormat.of().formatHex(bytes, 0, 8).equals("89504e470d0a1a0a")) {
                throw new IOException("服务器皮肤不是有效的 PNG");
            }
            var header = java.nio.ByteBuffer.wrap(bytes);
            int width = header.getInt(16);
            int height = header.getInt(20);
            if (width < 64 || width > 1024 || height < 32 || height > 1024
                    || width % 64 != 0 || height != width && height != width / 2) {
                throw new IOException("服务器皮肤尺寸无效");
            }
            return bytes;
        }
    }

    private static Optional<URI> httpsLink(String value) {
        try {
            var link = URI.create(value);
            if ("https".equalsIgnoreCase(link.getScheme()) && link.getHost() != null && link.getRawUserInfo() == null) {
                return Optional.of(link);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid server-supplied links are unavailable.
        }
        return Optional.empty();
    }

    private Session refresh(Session previous, Character character) throws IOException, InterruptedException {
        var request = tokenRequest(previous);
        if (character != null) {
            var selected = new JsonObject();
            selected.addProperty("id", character.id());
            selected.addProperty("name", character.name());
            request.add("selectedProfile", selected);
        }
        var expected = character == null ? previous.selected() : character;
        var result = session(previous.server(), post(previous.server(), "refresh", request), previous.clientToken, expected);
        if (!result.selected().id().equalsIgnoreCase(expected.id())) throw new IOException("验证服务器返回了其他角色");
        return result;
    }

    private static JsonObject tokenRequest(Session session) {
        var request = new JsonObject();
        request.addProperty("accessToken", session.accessToken);
        if (!session.clientToken.isBlank()) request.addProperty("clientToken", session.clientToken);
        return request;
    }

    private JsonObject post(URI server, String method, JsonObject request) throws IOException, InterruptedException {
        var response = send(server, method, request);
        if (response.statusCode() != 200) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IOException("验证服务器拒绝登录，请检查凭据或重新登录");
            }
            throw new IOException("验证服务器请求失败（HTTP " + response.statusCode() + "）");
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("验证服务器未返回有效 JSON");
        }
    }

    private HttpResponse<String> send(URI server, String method, JsonObject request)
            throws IOException, InterruptedException {
        URI endpoint = server(server.toString()).resolve("authserver/" + method);
        return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Session session(URI server, JsonObject response, String oldClientToken, Character expected)
            throws IOException {
        try {
            String token = VanillaInstaller.string(response, "accessToken");
            if (token.isBlank() || token.codePoints().anyMatch(java.lang.Character::isISOControl)) {
                throw new IllegalArgumentException();
            }
            String clientToken = VanillaInstaller.string(response, "clientToken");
            if (!oldClientToken.isBlank() && !clientToken.isBlank() && !clientToken.equals(oldClientToken)) {
                throw new IllegalArgumentException();
            }
            var profiles = new ArrayList<Character>();
            for (var entry : VanillaInstaller.array(response, "availableProfiles")) {
                profiles.add(character(entry.getAsJsonObject()));
            }
            var selected = response.getAsJsonObject("selectedProfile");
            return new Session(server, token, clientToken.isBlank() ? oldClientToken : clientToken,
                    selected == null ? expected : character(selected), profiles);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException e) {
            throw new IOException("验证服务器返回的档案或令牌无效");
        }
    }

    private static Character character(JsonObject profile) {
        return new Character(VanillaInstaller.string(profile, "id"), VanillaInstaller.string(profile, "name"));
    }

    synchronized Path agent(Path directory) throws IOException, InterruptedException {
        // A JVM -javaagent option uses the first '=' to separate the JAR path from its options.
        if (directory.toString().contains("=")) throw new IOException("验证组件所在目录不能包含等号");
        Files.createDirectories(directory);
        var jar = directory.resolve("authlib-injector-1.2.8.jar");
        if (Files.isRegularFile(jar) && verified(jar)) return jar;
        var temporary = Files.createTempFile(directory, "authlib-injector-", ".part");
        try {
            var response = client.send(HttpRequest.newBuilder(AGENT).timeout(Duration.ofSeconds(45)).build(),
                    HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200 || !verified(temporary)) {
                throw new IOException("Authlib Injector 下载或 SHA-256 校验失败");
            }
            VanillaInstaller.replace(temporary, jar);
            return jar;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean verified(Path file) throws IOException {
        try (var input = new java.security.DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest()).equals(SHA256);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

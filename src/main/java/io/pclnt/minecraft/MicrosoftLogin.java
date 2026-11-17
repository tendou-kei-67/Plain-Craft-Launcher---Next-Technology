package io.pclnt.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MicrosoftLogin {
    /** Azure application (client) ID used for the device code flow when no override is provided. */
    public static final String DEFAULT_CLIENT_ID = "041eb1d3-3069-4594-8044-99c0eec71989";
    public static final URI VERIFICATION_PAGE = URI.create("https://www.microsoft.com/link");
    /** Where Mojang reviews the Azure app ID before it may call the Minecraft services. */
    public static final URI APP_REVIEW_PAGE = URI.create("https://aka.ms/mce-reviewappid");

    /** Raised when Mojang has not approved the Azure application for the Minecraft API. */
    public static final class ApprovalRequiredException extends IOException {
        public ApprovalRequiredException(String message) {
            super(message);
        }
    }

    public static final class ReauthenticationRequiredException extends IOException {
        ReauthenticationRequiredException() { super("微软会话已失效，请重新登录。"); }
    }
    private static final String OAUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private static final String MINECRAFT = "https://api.minecraftservices.com/";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String clientId;

    public MicrosoftLogin(String clientId) {
        this(clientId, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build());
    }

    MicrosoftLogin(String clientId, HttpClient http) {
        this.clientId = clientId.strip();
        this.http = http;
    }

    public boolean configured() { return !clientId.isBlank(); }

    public record Cape(String id, String name, boolean active) {
        @Override public String toString() { return name; }
    }

    public record Appearance(byte[] skin, List<Cape> capes) {}

    public static final class Device {
        private final String code;
        private final String userCode;
        private final int interval;
        private final long deadline;

        private Device(String code, String userCode, int interval, int expiresIn) {
            this.code = code;
            this.userCode = userCode;
            this.interval = interval;
            this.deadline = System.nanoTime() + Duration.ofSeconds(expiresIn).toNanos();
        }

        public String userCode() { return userCode; }
    }

    public static final class Session {
        private final String name, uuid, accessToken, refreshToken, xuid;
        private final Instant expires;

        Session(String name, String uuid, String accessToken, String refreshToken, String xuid, Instant expires) {
            this.name = name;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.xuid = xuid;
            this.expires = expires;
        }

        public String name() { return name; }
        public String uuid() { return uuid; }
        String accessToken() { return accessToken; }
        String refreshToken() { return refreshToken; }
        String xuid() { return xuid; }
        public boolean hasAccessToken() { return !accessToken.isBlank(); }
    }

    public Device begin() throws IOException, InterruptedException {
        if (!configured()) throw new IOException("尚未配置微软 Client ID");
        var result = form("devicecode", Map.of("client_id", clientId, "scope", SCOPE));
        return new Device(required(result, "device_code"), required(result, "user_code"),
                seconds(result, "interval", 5, 300), seconds(result, "expires_in", 900, 3600));
    }

    public Session await(Device device) throws IOException, InterruptedException {
        int interval = device.interval;
        while (true) {
            long remaining = device.deadline - System.nanoTime();
            if (remaining <= Duration.ofSeconds(interval).toNanos()) throw new IOException("微软登录代码已过期");
            Thread.sleep(Duration.ofSeconds(interval));
            var response = sendForm("token", Map.of("client_id", clientId, "device_code", device.code,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
            var result = json(response);
            if (response.statusCode() == 200) return complete(result, "");
            switch (VanillaInstaller.string(result, "error")) {
                case "authorization_pending" -> { }
                case "slow_down" -> interval += 5;
                case "authorization_declined", "access_denied" -> throw new IOException("微软登录授权已取消");
                case "expired_token" -> throw new IOException("微软登录代码已过期");
                default -> throw new IOException("微软令牌请求失败（HTTP " + response.statusCode() + "）");
            }
        }
    }

    public Session forLaunch(Session previous) throws IOException, InterruptedException {
        if (Instant.now().plusSeconds(60).isBefore(previous.expires)) return previous;
        if (previous.refreshToken.isBlank()) throw new ReauthenticationRequiredException();
        var response = sendForm("token", Map.of("client_id", clientId, "scope", SCOPE,
                "grant_type", "refresh_token", "refresh_token", previous.refreshToken));
        if (response.statusCode() != 200) {
            if (response.statusCode() == 400 || response.statusCode() == 401) {
                String error = VanillaInstaller.string(json(response), "error");
                if (error.equals("invalid_grant") || error.equals("interaction_required")) {
                    throw new ReauthenticationRequiredException();
                }
            }
            throw new IOException("刷新微软会话失败（HTTP " + response.statusCode() + "）");
        }
        var result = complete(json(response), previous.refreshToken);
        if (!result.uuid.equalsIgnoreCase(previous.uuid)) throw new ReauthenticationRequiredException();
        return result;
    }

    public Appearance appearance(Session session) throws IOException, InterruptedException {
        var profile = get("minecraft/profile", session.accessToken);
        var capes = new java.util.ArrayList<Cape>();
        for (var entry : VanillaInstaller.array(profile, "capes")) {
            var cape = entry.getAsJsonObject();
            String id = required(cape, "id");
            String name = VanillaInstaller.string(cape, "alias");
            capes.add(new Cape(id, name.isBlank() ? id : name, "ACTIVE".equals(VanillaInstaller.string(cape, "state"))));
        }
        for (var entry : VanillaInstaller.array(profile, "skins")) {
            var skin = entry.getAsJsonObject();
            if ("ACTIVE".equals(VanillaInstaller.string(skin, "state"))) {
                var uri = OfficialSkin.textureAddress(required(skin, "url"));
                var response = http.send(request(uri.toString()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) throw new IOException("读取皮肤失败（HTTP " + response.statusCode() + "）");
                OfficialSkin.verifySkin(response.body());
                return new Appearance(response.body(), List.copyOf(capes));
            }
        }
        return new Appearance(null, List.copyOf(capes));
    }

    public void uploadSkin(Session session, byte[] png, boolean slim) throws IOException, InterruptedException {
        OfficialSkin.verifySkin(png);
        String boundary = java.util.UUID.randomUUID().toString();
        String begin = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(begin),
                HttpRequest.BodyPublishers.ofByteArray(png), HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        checkChange(send(profileRequest(session, "skins").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body)), "更改皮肤");
    }

    public void selectCape(Session session, String capeId) throws IOException, InterruptedException {
        var request = profileRequest(session, "capes/active");
        if (capeId == null) request.DELETE();
        else request.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("capeId", capeId))));
        checkChange(send(request), "更改披风");
    }

    private static HttpRequest.Builder profileRequest(Session session, String path) {
        return request(MINECRAFT + "minecraft/profile/" + path).header("Authorization", "Bearer " + session.accessToken);
    }

    private static void checkChange(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() != 200 && response.statusCode() != 204) {
            throw new IOException(action + "失败（HTTP " + response.statusCode() + "）");
        }
    }

    private Session complete(JsonObject oauth, String previousRefresh) throws IOException, InterruptedException {
        var xbox = post("https://user.auth.xboxlive.com/user/authenticate", Map.of(
                "Properties", Map.of("AuthMethod", "RPS", "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + required(oauth, "access_token")),
                "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"), "Xbox Live 登录");
        var xsts = post("https://xsts.auth.xboxlive.com/xsts/authorize", Map.of(
                "Properties", Map.of("SandboxId", "RETAIL", "UserTokens", List.of(required(xbox, "Token"))),
                "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"), "Xbox 游戏授权");
        String userHash;
        String xuid;
        try {
            var user = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject();
            userHash = required(user, "uhs");
            xuid = VanillaInstaller.string(user, "xid");
        } catch (RuntimeException error) {
            throw new IOException("Xbox 授权响应缺少用户资料");
        }
        var game = post(MINECRAFT + "authentication/login_with_xbox",
                Map.of("identityToken", "XBL3.0 x=" + userHash + ";" + required(xsts, "Token")), "Minecraft 登录");
        String token = required(game, "access_token");
        var ownership = get("entitlements/mcstore", token);
        if (VanillaInstaller.array(ownership, "items").isEmpty()) {
            throw new IOException("此微软账户没有 Minecraft Java 版使用权");
        }
        var profile = get("minecraft/profile", token);
        String name = required(profile, "name");
        String uuid = required(profile, "id");
        if (!GameLaunch.validName(name) || !uuid.matches("[a-fA-F0-9]{32}")) throw new IOException("无效的 Minecraft 档案");
        String refresh = VanillaInstaller.string(oauth, "refresh_token");
        return new Session(name, uuid, token, refresh.isBlank() ? previousRefresh : refresh,
                xuid.matches("[0-9]+") ? xuid : "", Instant.now().plusSeconds(seconds(game, "expires_in", 3600, 86400)));
    }

    private JsonObject get(String path, String token) throws IOException, InterruptedException {
        return success(send(request(MINECRAFT + path).header("Authorization", "Bearer " + token).GET()), "读取 Minecraft 档案");
    }

    private JsonObject post(String address, Object body, String action) throws IOException, InterruptedException {
        return success(send(request(address).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))), action);
    }

    private JsonObject form(String path, Map<String, String> values) throws IOException, InterruptedException {
        return success(sendForm(path, values), "微软授权");
    }

    private HttpResponse<String> sendForm(String path, Map<String, String> values) throws IOException, InterruptedException {
        var body = values.entrySet().stream().map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(request(OAUTH + path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpRequest.Builder request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "PCL-NT/0.1.0 (Microsoft login)");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject success(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() == 200) {
            return json(response);
        }
        String body = response.body() == null ? "" : response.body().strip();
        String detail = body.length() > 400 ? body.substring(0, 400) + "…" : body;
        switch (response.statusCode()) {
            case 403 -> {
                if (body.contains("Invalid app registration")) {
                    throw new ApprovalRequiredException("Mojang 尚未批准此 Azure 应用的 Minecraft API 权限（Invalid app registration）。"
                            + "请查看 https://aka.ms/AppRegInfo，并通过 https://aka.ms/mce-reviewappid 提交 App ID 审核；"
                            + "审核通过前无法用微软账户启动正版游戏。");
                }
                if (body.toLowerCase(java.util.Locale.ROOT).contains("blocked")) {
                    throw new IOException("Minecraft 服务拦截了当前网络请求（HTTP 403）。"
                            + "如果正在使用 VPN 或加速器，请关闭或更换节点后再试。");
                }
                throw new IOException(action + "失败（HTTP 403）" + (detail.isBlank() ? "" : "：" + detail));
            }
            case 429 -> throw new IOException("登录尝试过于频繁，请等待几分钟后再试。");
            case 503 -> throw new IOException("Mojang 服务器暂时不可用，请稍后再试。");
            default -> throw new IOException(action + "失败（HTTP " + response.statusCode() + "）"
                    + (detail.isBlank() ? "" : "：" + detail));
        }
    }

    private static JsonObject json(HttpResponse<String> response) throws IOException {
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("认证服务返回了无效的 JSON");
        }
    }

    private static String required(JsonObject object, String name) throws IOException {
        String value = VanillaInstaller.string(object, name);
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("认证响应缺少有效字段 " + name);
        }
        return value;
    }

    private static int seconds(JsonObject object, String key, int fallback, int maximum) throws IOException {
        try {
            return object.has(key) ? Math.clamp(object.get(key).getAsInt(), 1, maximum) : fallback;
        } catch (RuntimeException error) {
            throw new IOException("认证响应中的有效期或轮询间隔无效");
        }
    }
}

package io.pclnt.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Public Mojang player lookup and the official Minecraft texture rules shared by both skin entry points. */
public final class OfficialSkin {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final int MAX_PROFILE = 65_536;
    private static final int MAX_PNG = 1024 * 1024;
    private final HttpClient client;

    public record Download(String player, byte[] png) {}

    public OfficialSkin(HttpClient client) {
        this.client = client;
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public Download download(String player) throws IOException, InterruptedException {
        if (!validName(player)) throw new IOException("请输入有效的正版玩家名");
        var identity = json(fetch(URI.create("https://api.mojang.com/users/profiles/minecraft/" + player), MAX_PROFILE, "查询玩家"));
        String uuid = string(identity, "id");
        String name = string(identity, "name");
        if (!uuid.matches("[0-9a-fA-F]{32}") || !validName(name) || !name.equalsIgnoreCase(player)) {
            throw new IOException("Mojang 返回的玩家资料无效");
        }
        var session = json(fetch(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid),
                MAX_PROFILE, "查询皮肤"));
        if (!uuid.equalsIgnoreCase(string(session, "id"))) throw new IOException("Mojang 返回了不匹配的玩家资料");
        URI address = textureAddress(skin(session));
        byte[] png = fetch(address, MAX_PNG, "下载皮肤");
        verifySkin(png);
        return new Download(name, png);
    }

    private static String skin(JsonObject session) throws IOException {
        if (session.get("properties") instanceof com.google.gson.JsonArray properties) {
            for (var value : properties) {
                if (!(value instanceof JsonObject property) || !"textures".equals(string(property, "name"))) continue;
                byte[] encoded;
                try {
                    encoded = Base64.getDecoder().decode(string(property, "value"));
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Mojang 返回的皮肤资料无效", failure);
                }
                var payload = json(encoded);
                if (payload.get("textures") instanceof JsonObject textures && textures.get("SKIN") instanceof JsonObject skin) {
                    var url = string(skin, "url");
                    if (!url.isBlank()) return url;
                }
            }
        }
        throw new IOException("该玩家没有设置可下载的自定义皮肤");
    }

    private byte[] fetch(URI address, int maxLength, String action) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "PCL-NT/0.1 (Minecraft launcher)").GET().build();
        var operation = client.sendAsync(request, ignored -> new LimitedBody(maxLength));
        try {
            var response = operation.get(15, TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status == 429) throw new IOException("获取皮肤太频繁，请 5 分钟后再试");
            if ((status == 404 || status == 204) && action.equals("查询玩家")) throw new IOException("找不到这个正版玩家");
            if (status != 200) throw new IOException(action + "失败（HTTP " + status + "）");
            byte[] bytes = response.body();
            if (bytes.length == 0) throw new IOException(action + "返回的数据为空");
            return bytes;
        } catch (TimeoutException failure) {
            throw new IOException(action + "超时", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException(action + "失败", failure.getCause());
        } finally {
            operation.cancel(true);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final int limit;
        private Flow.Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }

        @Override public CompletableFuture<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (var item : items) {
                if (item.remaining() > limit - buffer.size()) {
                    result.completeExceptionally(new IOException("Mojang 返回的数据超过大小限制"));
                    subscription.cancel();
                    return;
                }
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }

    private static JsonObject json(byte[] body) throws IOException {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IllegalStateException | JsonParseException failure) {
            throw new IOException("Mojang 返回的资料无效", failure);
        }
    }

    private static String string(JsonObject data, String key) {
        return data.get(key) instanceof com.google.gson.JsonPrimitive value && value.isString() ? value.getAsString() : "";
    }

    static URI textureAddress(String value) throws IOException {
        try {
            var uri = URI.create(value);
            var path = uri.getRawPath();
            if ("textures.minecraft.net".equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo() == null
                    && uri.getPort() == -1 && path != null && path.matches("/texture/[a-fA-F0-9]+")
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return URI.create("https://textures.minecraft.net" + path);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid texture URLs cannot be used.
        }
        throw new IOException("Minecraft 皮肤地址无效");
    }

    static void verifySkin(byte[] bytes) throws IOException {
        if (bytes.length >= 24 && bytes.length <= MAX_PNG) {
            var header = ByteBuffer.wrap(bytes);
            if (header.getLong() == 0x89504e470d0a1a0aL && header.getInt() == 13 && header.getInt() == 0x49484452) {
                int width = header.getInt(), height = header.getInt();
                if (width == 64 && (height == 64 || height == 32)) return;
            }
        }
        throw new IOException("皮肤须为 64×64 或 64×32 的 PNG");
    }
}

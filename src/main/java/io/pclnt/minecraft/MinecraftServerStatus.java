package io.pclnt.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

/** Java Edition server-list ping, using the same handshake as PCL-CE's McPingService. */
public final class MinecraftServerStatus {
    private static final int DEFAULT_PORT = 25565, TIMEOUT_MS = 10_000, MAX_PACKET = 1 << 20;
    private static final Pattern BRACKETED = Pattern.compile("^\\[([^\\]]+)](?::([0-9]{1,5}))?$");

    public record Status(JsonElement description, String version, int online, int capacity, List<String> sample,
                         long latencyMillis, String favicon) {}
    private record Address(String host, int port, boolean explicitPort) {}
    private record Target(String host, int port, int weight) {}

    private MinecraftServerStatus() {}

    public static Status query(String text) throws IOException {
        Address address = address(text);
        var targets = new ArrayList<Target>();
        if (!address.explicitPort() && address.host().contains(".") && !address.host().contains(":")
                && !address.host().matches("[0-9.]+")) {
            targets.addAll(srvTargets(address.host()));
        }
        targets.add(new Target(address.host(), address.port(), 0));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        try (var socket = connect(targets, deadline)) {
            return exchange(socket, address.host(), deadline);
        }
    }

    private static Address address(String text) throws IOException {
        var value = text.strip().replace('：', ':').replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
                .replaceFirst("/+$", "");
        var bracketed = BRACKETED.matcher(value);
        String host = value;
        String portText = null;
        if (bracketed.matches()) {
            host = bracketed.group(1);
            portText = bracketed.group(2);
            if (!host.contains(":")) throw new IOException("方括号中需要填写 IPv6 地址");
        } else {
            int colon = value.indexOf(':');
            if (colon >= 0 && colon == value.lastIndexOf(':')) {
                host = value.substring(0, colon);
                portText = value.substring(colon + 1);
            }
        }
        if (host.isBlank() || host.length() > 255 || host.codePoints().anyMatch(c ->
                Character.isWhitespace(c) || Character.isISOControl(c) || "/\\[]@?#".indexOf(c) >= 0)) {
            throw new IOException("请输入有效的服务器地址");
        }
        int port = DEFAULT_PORT;
        if (portText != null) {
            if (!portText.matches("[0-9]{1,5}")) throw new IOException("端口必须在 1 到 65535 之间");
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new IOException("端口必须在 1 到 65535 之间");
        }
        if (!host.contains(":")) {
            try {
                host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            } catch (IllegalArgumentException failure) {
                throw new IOException("请输入有效的服务器地址", failure);
            }
        }
        if (host.getBytes(StandardCharsets.UTF_8).length > 255) throw new IOException("服务器地址过长");
        return new Address(host, port, portText != null);
    }

    private static List<Target> srvTargets(String host) {
        var options = new Hashtable<String, String>();
        options.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        options.put("com.sun.jndi.dns.timeout.initial", "600");
        options.put("com.sun.jndi.dns.timeout.retries", "1");
        var groups = new TreeMap<Integer, List<Target>>();
        try {
            var dns = new InitialDirContext(options);
            try {
                var srv = dns.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("SRV");
                if (srv == null) return List.of();
                var records = srv.getAll();
                try {
                    while (records.hasMore()) {
                        var fields = records.next().toString().strip().split("\\s+");
                        if (fields.length != 4 || fields[3].equals(".")) continue;
                        try {
                            int priority = Integer.parseInt(fields[0]);
                            int weight = Integer.parseInt(fields[1]);
                            int port = Integer.parseInt(fields[2]);
                            if (priority < 0 || weight < 0 || weight > 65535 || port < 1 || port > 65535) continue;
                            groups.computeIfAbsent(priority, ignored -> new ArrayList<>())
                                    .add(new Target(fields[3].replaceFirst("\\.$", ""), port, weight));
                        } catch (NumberFormatException ignored) {
                            // Ignore a malformed DNS record and try the rest.
                        }
                    }
                } finally {
                    records.close();
                }
            } finally {
                dns.close();
            }
        } catch (NamingException ignored) {
            return List.of();
        }
        var ordered = new ArrayList<Target>();
        for (var group : groups.values()) {
            while (!group.isEmpty()) {
                long total = group.stream().mapToLong(Target::weight).sum();
                int index;
                if (total == 0) {
                    index = ThreadLocalRandom.current().nextInt(group.size());
                } else {
                    long choice = ThreadLocalRandom.current().nextLong(total);
                    index = 0;
                    while (choice >= group.get(index).weight()) choice -= group.get(index++).weight();
                }
                ordered.add(group.remove(index));
            }
        }
        return ordered;
    }

    private static Socket connect(List<Target> targets, long deadline) throws IOException {
        IOException last = null;
        for (var target : targets) {
            try {
                for (var address : InetAddress.getAllByName(target.host())) {
                    int timeout = Math.min(2500, remaining(deadline));
                    var socket = new Socket();
                    try {
                        socket.connect(new InetSocketAddress(address, target.port()), timeout);
                        socket.setTcpNoDelay(true);
                        return socket;
                    } catch (IOException failure) {
                        socket.close();
                        last = failure;
                    }
                }
            } catch (IOException failure) {
                last = failure;
            }
        }
        if (last != null) throw last;
        throw new IOException("未找到可连接的服务器地址");
    }

    private static Status exchange(Socket socket, String host, long deadline) throws IOException {
        var output = new DataOutputStream(socket.getOutputStream());
        var handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0);
        writeVarInt(handshake, 772);
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        writeVarInt(handshake, name.length);
        handshake.write(name);
        new DataOutputStream(handshake).writeShort(socket.getPort());
        writeVarInt(handshake, 1);
        writePacket(output, handshake.toByteArray());
        writePacket(output, new byte[]{0});

        var input = new FilterInputStream(socket.getInputStream()) {
            @Override public int read() throws IOException {
                socket.setSoTimeout(remaining(deadline));
                return in.read();
            }
            @Override public int read(byte[] bytes, int start, int length) throws IOException {
                socket.setSoTimeout(remaining(deadline));
                return in.read(bytes, start, length);
            }
        };
        var reply = readPacket(input);
        if (readVarInt(reply) != 0) throw new IOException("服务器返回了错误的状态包");
        int length = readVarInt(reply);
        if (length <= 0 || length != reply.available()) throw new IOException("服务器返回了错误的状态内容");
        byte[] json = reply.readNBytes(length);
        var ping = new ByteArrayOutputStream(9);
        ping.write(1);
        long sent = System.nanoTime();
        new DataOutputStream(ping).writeLong(sent);
        writePacket(output, ping.toByteArray());
        var pong = readPacket(input);
        if (readVarInt(pong) != 1 || pong.available() != Long.BYTES || new DataInputStream(pong).readLong() != sent) {
            throw new IOException("服务器返回了错误的延迟响应");
        }
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent);
        return parseStatus(new String(json, StandardCharsets.UTF_8), latency);
    }

    private static Status parseStatus(String json, long latency) throws IOException {
        try {
            var document = JsonParser.parseString(json).getAsJsonObject();
            var version = document.getAsJsonObject("version");
            if (version == null) throw new IOException("未返回服务器版本");
            var players = document.getAsJsonObject("players");
            var sample = new ArrayList<String>();
            if (players != null && players.get("sample") instanceof com.google.gson.JsonArray entries) {
                for (var entry : entries) {
                    if (entry instanceof JsonObject player) {
                        var name = VanillaInstaller.string(player, "name");
                        if (!name.isBlank()) sample.add(name);
                    }
                    if (sample.size() == 100) break;
                }
            }
            return new Status(document.get("description"), VanillaInstaller.string(version, "name"),
                    number(players, "online"), number(players, "max"), List.copyOf(sample), latency,
                    VanillaInstaller.string(document, "favicon"));
        } catch (RuntimeException error) {
            throw new IOException("服务器返回了错误的信息", error);
        }
    }

    private static int number(JsonObject value, String field) {
        return value != null && value.get(field) != null && value.get(field).isJsonPrimitive() ? value.get(field).getAsInt() : -1;
    }

    private static int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("查询服务器超时");
        return Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private static ByteArrayInputStream readPacket(InputStream input) throws IOException {
        int size = readVarInt(input);
        if (size < 1 || size > MAX_PACKET) throw new IOException("服务器返回的数据包长度无效");
        byte[] payload = input.readNBytes(size);
        if (payload.length != size) throw new EOFException("服务器在返回完整数据包前断开了连接");
        return new ByteArrayInputStream(payload);
    }

    private static int readVarInt(InputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int value = input.read();
            if (value < 0) throw new EOFException("服务器提前断开了连接");
            if (shift == 28 && (value & 0xf0) != 0) break;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("服务器返回了无效的数据包长度");
    }

    private static void writePacket(DataOutputStream output, byte[] payload) throws IOException {
        writeVarInt(output, payload.length);
        output.write(payload);
        output.flush();
    }

    private static void writeVarInt(java.io.OutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }
}

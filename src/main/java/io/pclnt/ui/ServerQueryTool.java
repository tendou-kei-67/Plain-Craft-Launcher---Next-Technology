package io.pclnt.ui;

import com.google.gson.JsonElement;
import io.pclnt.minecraft.MinecraftServerStatus;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

/** JavaFX counterpart of the PCL-CE server query and result controls. */
final class ServerQueryTool {
    private static final String ICON = "reference-icons/default-server.png";
    private static final String PNG_HEADER = "data:image/png;base64,";
    private static final List<String> PALETTE = List.of("#000000", "#0000aa", "#00aa00", "#00aaaa",
            "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa", "#555555", "#5555ff", "#55ff55",
            "#55ffff", "#ff5555", "#ff55ff", "#ffff55", "#ffffff");
    private static final List<String> COLOR_NAMES = List.of("black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white");
    private static final Map<String, Color> COLORS;
    static {
        var colors = new java.util.HashMap<String, Color>();
        for (int i = 0; i < PALETTE.size(); i++) colors.put(COLOR_NAMES.get(i), Color.web(PALETTE.get(i)));
        COLORS = Map.copyOf(colors);
    }

    private static final Style DEFAULT_STYLE = new Style(Color.WHITE, false, false, false, false);
    private final TextField address = new TextField();
    private final Button query = button("查询", this::query);
    private final ImageView icon = PclArtwork.image(ICON, 54);
    private final Label title = label("", "server-query-text");
    private final Label players = label("-/-", "server-query-text");
    private final Label latency = label("", "server-query-text");
    private final TextFlow motd = new TextFlow();
    private final HBox result;
    private final PclCard card;

    ServerQueryTool() {
        address.setPromptText("输入服务器地址");
        address.setAccessibleText("服务器地址");
        address.getStyleClass().add("account-input");
        address.setOnAction(event -> {
            if (!query.isDisabled()) query();
        });
        query.setMinWidth(60);
        query.disableProperty().bind(address.textProperty().map(String::isBlank));
        var input = new HBox(15, address, query);
        input.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(address, Priority.ALWAYS);

        var clip = new Rectangle();
        clip.widthProperty().bind(motd.widthProperty());
        clip.heightProperty().bind(motd.heightProperty());
        motd.setClip(clip);
        motd.setMinWidth(0);
        motd.setMaxHeight(36);
        var description = new VBox(2, title, motd);
        description.setMinWidth(0);
        description.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(description, Priority.ALWAYS);
        var right = new VBox(3, players, latency);
        right.setAlignment(Pos.TOP_RIGHT);
        result = new HBox(8, icon, description, right);
        result.setAlignment(Pos.CENTER_LEFT);
        result.getStyleClass().add("server-query-result");
        result.setVisible(false);
        result.managedProperty().bind(result.visibleProperty());
        card = PclCard.fixedBody("瞅眼服务器", new VBox(7, input, result));
    }

    PclCard card() { return card; }

    private void query() {
        String server = address.getText();
        address.setDisable(true);
        query.disableProperty().unbind();
        query.setDisable(true);
        result.setVisible(true);
        title.setText("查询中……");
        title.setStyle("");
        title.setTooltip(null);
        players.setText("-/-");
        players.setTooltip(null);
        latency.setText("");
        icon.setImage(PclArtwork.image(ICON));
        motd.getChildren().clear();
        var task = new Task<MinecraftServerStatus.Status>() {
            @Override protected MinecraftServerStatus.Status call() throws Exception {
                return MinecraftServerStatus.query(server);
            }
        };
        task.setOnSucceeded(event -> {
            show(task.getValue());
            ready();
        });
        task.setOnFailed(event -> {
            var error = task.getException();
            String reason = error instanceof java.net.SocketTimeoutException ? "查询超时"
                    : VanillaDownloadPage.message(error);
            title.setText("无法连接：" + reason);
            title.setStyle("-fx-text-fill: #ff5555");
            ready();
        });
        Thread.startVirtualThread(task);
    }

    private void ready() {
        address.setDisable(false);
        query.disableProperty().bind(address.textProperty().map(String::isBlank));
    }

    private void show(MinecraftServerStatus.Status status) {
        title.setText("Minecraft 服务器");
        if (!status.version().isBlank()) title.setTooltip(new Tooltip(status.version()));
        players.setText(status.online() < 0 || status.capacity() < 0 ? "-/-" : status.online() + "/" + status.capacity());
        if (!status.sample().isEmpty()) players.setTooltip(new Tooltip(String.join("\n", status.sample())));
        long ms = status.latencyMillis();
        latency.setText(ms + "ms");
        latency.setStyle("-fx-text-fill: " + (ms < 150 ? "#55ff55" : ms < 400 ? "#ffaa00" : "#ff5555"));
        render(status.description(), DEFAULT_STYLE, 0);
        if (status.favicon().startsWith(PNG_HEADER) && status.favicon().length() <= 100_000) {
            try {
                byte[] bytes = Base64.getDecoder().decode(status.favicon().substring(PNG_HEADER.length()));
                if (!isSmallPng(bytes)) return;
                var image = new Image(new ByteArrayInputStream(bytes), 54, 54, true, false);
                if (!image.isError()) icon.setImage(image);
            } catch (IllegalArgumentException ignored) {
                // A malformed icon leaves the original default server icon visible.
            }
        }
    }

    private static boolean isSmallPng(byte[] bytes) {
        if (bytes.length < 24) return false;
        var header = java.nio.ByteBuffer.wrap(bytes);
        if (header.getLong() != 0x89504e470d0a1a0aL || header.getInt() != 13 || header.getInt() != 0x49484452) return false;
        int width = header.getInt(), height = header.getInt();
        return width > 0 && width <= 256 && height > 0 && height <= 256;
    }

    private record Style(Color color, boolean bold, boolean italic, boolean underline, boolean strike) {
        Style color(Color value) { return new Style(value, bold, italic, underline, strike); }
    }

    private void render(JsonElement value, Style style, int depth) {
        if (value == null || value.isJsonNull() || depth > 24 || motd.getChildren().size() > 180) return;
        if (value.isJsonPrimitive()) {
            legacy(value.getAsString(), style);
        } else if (value.isJsonArray()) {
            for (var part : value.getAsJsonArray()) render(part, style, depth + 1);
        } else if (value.isJsonObject()) {
            var component = value.getAsJsonObject();
            Color color = style.color();
            if (component.get("color") instanceof com.google.gson.JsonPrimitive name) {
                if (name.getAsString().matches("#[0-9a-fA-F]{6}")) color = Color.web(name.getAsString());
                else color = COLORS.getOrDefault(name.getAsString(), color);
            }
            var current = new Style(color, flag(component, "bold", style.bold()), flag(component, "italic", style.italic()),
                    flag(component, "underlined", style.underline()), flag(component, "strikethrough", style.strike()));
            var text = component.has("text") ? component.get("text") : component.get("translate");
            render(text, current, depth + 1);
            render(component.get("extra"), current, depth + 1);
        }
    }

    private void legacy(String text, Style base) {
        Style style = base;
        var segment = new StringBuilder();
        for (int i = 0; i < Math.min(text.length(), 1000); i++) {
            char c = text.charAt(i);
            if (c != '§' || i + 1 == text.length()) {
                segment.append(c);
                continue;
            }
            if (!segment.isEmpty()) append(segment.toString(), style);
            segment.setLength(0);
            char code = Character.toLowerCase(text.charAt(++i));
            int color = Character.digit(code, 16);
            style = color >= 0 ? DEFAULT_STYLE.color(Color.web(PALETTE.get(color))) : switch (code) {
                case 'l' -> new Style(style.color(), true, style.italic(), style.underline(), style.strike());
                case 'o' -> new Style(style.color(), style.bold(), true, style.underline(), style.strike());
                case 'n' -> new Style(style.color(), style.bold(), style.italic(), true, style.strike());
                case 'm' -> new Style(style.color(), style.bold(), style.italic(), style.underline(), true);
                case 'r' -> base;
                default -> style;
            };
        }
        if (!segment.isEmpty()) append(segment.toString(), style);
    }

    private void append(String content, Style style) {
        if (motd.getChildren().size() >= 180) return;
        var text = new Text(content);
        text.setFill(style.color());
        text.setUnderline(style.underline());
        text.setStrikethrough(style.strike());
        text.setStyle("-fx-font-size: 14px;" + (style.bold() ? "-fx-font-weight: bold;" : "")
                + (style.italic() ? "-fx-font-style: italic;" : ""));
        motd.getChildren().add(text);
    }

    private static boolean flag(com.google.gson.JsonObject element, String key, boolean fallback) {
        return element.get(key) instanceof com.google.gson.JsonPrimitive value ? value.getAsBoolean() : fallback;
    }
}

package io.pclnt.ui;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static io.pclnt.ui.LauncherController.*;

/** Renders the declarative subset of original PCL home pages on JavaFX. */
final class CustomHome {
    record Preset(int id, String title, String url) { }
    static final List<Preset> PRESETS = readPresets();
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(CustomHome.class.getName());

    private final AppearanceOptions options;
    private final BorderPane launch;
    private final HostServices links;
    private HttpClient client;
    private CompletableFuture<?> pending;
    private long generation;
    private boolean loaded;

    CustomHome(AppearanceOptions options, BorderPane launch, HostServices links) {
        this.options = options;
        this.launch = launch;
        this.links = links;
        options.homeType.addListener(observable -> loaded = false);
        options.homePreset.addListener(observable -> loaded = false);
        options.homeUrl.addListener(observable -> loaded = false);
    }

    void show() {
        if (!loaded) {
            refresh();
        }
    }

    void refresh() {
        long request = ++generation;
        loaded = true;
        if (pending != null) {
            pending.cancel(true);
        }
        switch (options.homeType.get()) {
            case 1 -> loadLocal();
            case 2 -> loadRemote(options.homeUrl.get(), request);
            case 3 -> {
                var preset = PRESETS.stream().filter(item -> item.id() == options.homePreset.get()).findFirst()
                        .orElse(PRESETS.getFirst());
                if (preset.id() <= 1) {
                    var refresh = new Button("刷新主页");
                    refresh.getStyleClass().add("outline-button");
                    UiMotion.installPress(refresh);
                    refresh.setOnAction(event -> refresh());
                    launch.setCenter(content(PclCard.fixed(preset.title(), label(
                            "为便于维护，开源内容中不包含百宝箱功能……", "card-text"), refresh)));
                } else {
                    loadRemote(preset.url(), request);
                }
            }
            default -> clear();
        }
    }

    void writeTutorial() throws IOException {
        Files.createDirectories(options.directory);
        try (var stream = CustomHome.class.getResourceAsStream("custom-home-example.xaml")) {
            if (stream == null) {
                throw new IOException("找不到内置教学文件");
            }
            Files.copy(stream, options.customHome(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loadLocal() {
        if (!Files.isRegularFile(options.customHome())) {
            clear();
            return;
        }
        try (var stream = Files.newInputStream(options.customHome())) {
            render(readLimited(stream), null);
        } catch (Exception exception) {
            showFailure(exception);
        }
    }

    private void loadRemote(String url, long request) {
        if (url.isBlank()) {
            clear();
            return;
        }
        URI address = webUri(url.strip());
        if (address == null) {
            launch.setCenter(content(card("无法加载主页", "请输入有效的 HTTP 或 HTTPS 地址。")));
            return;
        }
        launch.setCenter(content(card("主页", "正在加载主页……")));
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
        }
        pending = CompletableFuture.supplyAsync(() -> {
            var fetch = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(12)).GET().build();
            try {
                var response = client.send(fetch, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = response.body()) {
                    if (response.statusCode() / 100 != 2) {
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    return readLimited(stream);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(exception);
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((markup, error) -> Platform.runLater(() -> {
            if (generation != request || !loaded) {
                return;
            }
            try {
                if (error != null) {
                    throw new IOException(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                }
                render(markup, address);
            } catch (Exception exception) {
                showFailure(exception);
            }
        }));
    }

    private static String readLimited(java.io.InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("主页文件超过 2 MB");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    private void render(String markup, URI origin) throws Exception {
        if (markup.isBlank()) {
            clear();
            return;
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var xml = markup.strip().replaceFirst("^<\\?xml[^?]+\\?>", "");
        var wrapped = "<home xmlns:local=\"urn:pcl\" xmlns:x=\"http://schemas.microsoft.com/winfx/2006/xaml\""
                + " xmlns:sys=\"urn:sys\">" + xml + "</home>";
        var root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(wrapped))).getDocumentElement();
        var children = renderChildren(root, origin, 0);
        launch.setCenter(content(children.toArray(Node[]::new)));
    }

    private List<Node> renderChildren(Element parent, URI origin, int depth) {
        var children = new ArrayList<Node>();
        if (depth >= 35) {
            return children;
        }
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                var view = renderElement(element, origin, depth + 1);
                if (view != null) {
                    children.add(view);
                }
            }
        }
        return children;
    }

    private Node renderElement(Element element, URI origin, int depth) {
        String title = element.getAttribute("Title");
        String text = first(element.getAttribute("Text"), title, element.getAttribute("Content"));
        return switch (element.getLocalName()) {
            case "MyCard" -> {
                var body = new VBox(8);
                body.getChildren().addAll(renderChildren(element, origin, depth));
                if ("true".equalsIgnoreCase(element.getAttribute("CanSwap"))
                        || element.hasAttribute("IsSwapped")) {
                    yield PclCard.foldable(title, body, !"true".equalsIgnoreCase(element.getAttribute("IsSwapped")));
                }
                yield PclCard.fixed(title, body);
            }
            case "TextBlock", "Label", "MyHint" -> {
                String value = first(text, element.getTextContent().strip());
                yield value.isBlank() ? null : label(value, element.getLocalName().equals("MyHint") ? "help-hint" : "card-text");
            }
            case "MyListItem", "MyButton", "MyTextButton", "MyIconButton", "MyIconTextButton" -> action(element, text);
            case "Image", "MyImage" -> image(element.getAttribute("Source"), origin);
            case "StackPanel", "Grid", "DockPanel", "WrapPanel", "Border", "ScrollViewer" ->
                    new VBox(8, renderChildren(element, origin, depth).toArray(Node[]::new));
            default -> null;
        };
    }

    private Node action(Element element, String text) {
        String type = first(element.getAttribute("EventType"), element.getAttribute("local:CustomEventService.EventType"));
        String data = first(element.getAttribute("EventData"), element.getAttribute("local:CustomEventService.EventData"));
        if (text.isBlank()) {
            text = type;
        }
        var action = switch (type) {
            case "打开网页" -> {
                URI uri = webUri(data);
                yield uri == null ? null : (Runnable) () -> links.showDocument(uri.toASCIIString());
            }
            case "刷新主页" -> (Runnable) this::refresh;
            case "复制文本" -> (Runnable) () -> {
                var content = new ClipboardContent();
                content.putString(data);
                Clipboard.getSystemClipboard().setContent(content);
            };
            case "弹出窗口" -> (Runnable) () -> {
                var parts = data.split("\\|", 2);
                PclDialogs.message(launch, parts.length == 2 ? parts[0] : "主页",
                        parts[parts.length - 1].replace("\\n", "\n"), false);
            };
            default -> null;
        };
        if (action == null) {
            return text.isBlank() ? null : label(text, "card-text");
        }
        var button = new Button(text);
        button.getStyleClass().add("outline-button");
        UiMotion.installPress(button);
        button.setOnAction(event -> action.run());
        return button;
    }

    private Node image(String source, URI origin) {
        if (source.isBlank()) {
            return null;
        }
        URI remote = webUri(source);
        if (origin != null && remote == null) {
            try {
                remote = webUri(origin.resolve(source).toString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (remote != null) {
            var link = new Hyperlink("查看配图");
            link.getStyleClass().add("about-link");
            URI url = remote;
            link.setOnAction(event -> links.showDocument(url.toASCIIString()));
            return link;
        }
        try {
            Path path = options.directory.resolve(source).normalize();
            if (path.startsWith(options.directory) && Files.isRegularFile(path)
                    && path.toRealPath().startsWith(options.directory.toRealPath())) {
                var image = WindowAppearance.loadImage(path);
                if (image != null) {
                    var view = new ImageView(image);
                    view.setPreserveRatio(true);
                    view.setFitWidth(Math.min(image.getWidth(), 360));
                    return view;
                }
            }
        } catch (IllegalArgumentException | IOException ignored) {
            // This source cannot be shown as a local picture.
        }
        return null;
    }

    private void showFailure(Exception exception) {
        LOG.log(System.Logger.Level.DEBUG, "Unable to load custom home", exception);
        launch.setCenter(content(card("无法加载主页", first(exception.getMessage(), "请检查主页文件。"))));
    }

    private void clear() {
        var empty = new Region();
        empty.getStyleClass().add("home-background");
        launch.setCenter(empty);
    }

    private static URI webUri(String address) {
        try {
            var uri = URI.create(address);
            if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // A non-web URI is not a homepage network source.
        }
        return null;
    }

    private static String first(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<Preset> readPresets() {
        var catalog = ResourceBundle.getBundle("io.pclnt.ui.home-presets");
        return catalog.keySet().stream().map(key -> {
            var fields = catalog.getString(key).split("\\|", 2);
            return new Preset(Integer.parseInt(key), fields[0], fields[1]);
        }).sorted(Comparator.comparingInt(Preset::id)).toList();
    }
}

package io.pclnt.ui;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import static io.pclnt.ui.LauncherController.*;

final class HelpPage {
    private static final System.Logger LOG = System.getLogger(HelpPage.class.getName());
    private final ViewTransition pages;
    private final HostServices links;
    private final TextField search = new TextField();
    private final VBox results = new VBox(15);
    private final ScrollPane index;
    private Map<String, Element> topics;

    HelpPage(ViewTransition pages, HostServices links) {
        this.pages = pages;
        this.links = links;
        search.setPromptText("搜索帮助");
        search.setAccessibleText("搜索帮助");
        search.getStyleClass().addAll("account-input", "help-search");
        search.textProperty().addListener((observable, before, after) -> updateResults());
        index = scroll(search, results);
        reload();
    }

    void show() {
        pages.show(index);
    }

    void refresh() {
        reload();
        search.clear();
        updateResults();
        index.setVvalue(0);
        show();
    }

    private void reload() {
        try (var source = HelpPage.class.getResourceAsStream("help/catalog.xml")) {
            if (source == null) {
                throw new IOException("Missing help/catalog.xml");
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var catalog = factory.newDocumentBuilder().parse(source).getDocumentElement();
            topics = new LinkedHashMap<>();
            for (var topic : children(catalog)) {
                topics.put(topic.getAttribute("id"), topic);
            }
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            topics = Map.of();
            LOG.log(System.Logger.Level.WARNING, "Could not load the bundled help catalog", exception);
        }
        updateResults();
    }

    private void updateResults() {
        results.getChildren().clear();
        if (topics.isEmpty()) {
            results.getChildren().add(card("无法加载帮助", "请使用侧栏的刷新按钮重试。"));
            return;
        }

        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            var rows = new VBox(3);
            for (var topic : topics.values()) {
                String searchable = String.join(" ", topic.getAttribute("title"), topic.getAttribute("description"),
                        topic.getAttribute("keywords"), topic.getAttribute("categories")).toLowerCase(Locale.ROOT);
                if (!"false".equals(topic.getAttribute("searchable"))
                        && Arrays.stream(query.split("\\s+")).allMatch(searchable::contains)) {
                    rows.getChildren().add(row(topic));
                }
            }
            results.getChildren().add(rows.getChildren().isEmpty() ? card("无搜索结果", "试试其他关键词。")
                    : category("搜索结果", rows, true));
            index.setVvalue(0);
            return;
        }

        var categories = new LinkedHashMap<String, VBox>();
        categories.put("指南", new VBox(3));
        for (var topic : topics.values()) {
            for (var name : topic.getAttribute("categories").split("\\|")) {
                if (!name.isBlank()) {
                    categories.computeIfAbsent(name, ignored -> new VBox(3)).getChildren().add(row(topic));
                }
            }
        }
        categories.forEach((name, rows) -> {
            if (!rows.getChildren().isEmpty()) {
                results.getChildren().add(category(name, rows, name.equals("指南")));
            }
        });
    }

    private Button row(Element topic) {
        String title = topic.getAttribute("title");
        String description = topic.getAttribute("description");
        var heading = new Label(title);
        var summary = new Label(description);
        summary.getStyleClass().add("help-row-description");
        var captions = new VBox(2, heading, summary);
        captions.setAlignment(Pos.CENTER_LEFT);
        captions.setMinWidth(0);
        HBox.setHgrow(captions, Priority.ALWAYS);
        var graphic = new HBox(10, icon(topic.getAttribute("icon")), captions);
        graphic.setAlignment(Pos.CENTER_LEFT);
        var button = new Button(null, graphic);
        button.setAccessibleText(title + " " + description);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("help-row");
        button.setOnAction(event -> open(topic, index));
        return button;
    }

    private void open(Element topic, ScrollPane previous) {
        String url = topic.getAttribute("url");
        if (!url.isBlank()) {
            openWeb(url);
            return;
        }
        var back = new Button("返回");
        back.getStyleClass().addAll("outline-button", "help-back");
        UiMotion.installPress(back);
        back.setOnAction(event -> pages.show(previous));
        var article = scroll(back, card(topic.getAttribute("title"), "以下内容适用于原版 PCL。"));
        var blocks = (VBox) article.getContent();
        for (var block : children(topic)) {
            blocks.getChildren().add(render(block, article));
        }
        pages.show(article);
    }

    private Node render(Element block, ScrollPane article) {
        String title = block.getAttribute("title");
        String value = block.getTextContent();
        return switch (block.getTagName()) {
            case "section" -> {
                var body = new VBox(7);
                for (var item : children(block)) {
                    body.getChildren().add(render(item, article));
                }
                if (title.isBlank()) {
                    yield body;
                }
                yield block.hasAttribute("expanded") ? category(title, body, !"false".equals(block.getAttribute("expanded")))
                        : PclCard.fixed(title, body);
            }
            case "link" -> link(title, () -> openWeb(value));
            case "image" -> illustration(block, value);
            case "topic" -> {
                var target = topics.get(value);
                yield target == null ? label(title, "card-text")
                        : link(title.isBlank() ? target.getAttribute("title") : title, () -> open(target, article));
            }
            case "copy" -> link(title, () -> {
                var content = new ClipboardContent();
                content.putString(value);
                Clipboard.getSystemClipboard().setContent(content);
            });
            case "hint" -> label(value, "help-hint");
            default -> label(value, "card-text");
        };
    }

    private void openWeb(String url) {
        try {
            var uri = URI.create(url);
            if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                links.showDocument(uri.toASCIIString());
            }
        } catch (IllegalArgumentException exception) {
            LOG.log(System.Logger.Level.WARNING, "Invalid link in the bundled help catalog", exception);
        }
    }

    private static Hyperlink link(String title, Runnable open) {
        var link = new Hyperlink(title);
        link.getStyleClass().add("about-link");
        link.setWrapText(true);
        link.setOnAction(event -> open.run());
        return link;
    }

    private static ImageView icon(String name) {
        var asset = HelpPage.class.getResource("help/" + name);
        var image = new ImageView(asset == null ? null : new Image(asset.toExternalForm()));
        image.setFitWidth(28);
        image.setFitHeight(28);
        image.setPreserveRatio(true);
        image.setSmooth(false);
        return image;
    }

    private static Node illustration(Element block, String source) {
        var box = new javafx.scene.layout.StackPane();
        box.getStyleClass().add("help-illustration");
        box.setAlignment(switch (block.getAttribute("HorizontalAlignment")) {
            case "Left" -> Pos.CENTER_LEFT;
            case "Right" -> Pos.CENTER_RIGHT;
            default -> Pos.CENTER;
        });
        var asset = HelpPage.class.getResource("help/" + source);
        boolean remote = source.startsWith("https://") || source.startsWith("http://");
        if (!remote && asset == null) {
            return box;
        }
        double width = dimension(block, "Width", dimension(block, "MaxWidth", Double.MAX_VALUE));
        double height = dimension(block, "Height", dimension(block, "MaxHeight", Double.MAX_VALUE));
        // Bilibili's @.webp suffix requests a transcode; JavaFX can decode the original PNG or JPEG.
        String imageUrl = remote ? source.replaceFirst("(?i)^(https?://i[0-9]+\\.hdslb\\.com/.+\\.(?:png|jpe?g))@\\.webp$", "$1")
                : asset.toExternalForm();
        var image = remote ? new Image(imageUrl, 1600, 1200, true, true, true) : new Image(imageUrl);
        var view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setSmooth(remote);
        box.setMinWidth(0);
        view.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(box.widthProperty(),
                javafx.beans.binding.Bindings.min(image.widthProperty(), width)));
        view.setFitHeight(height == Double.MAX_VALUE ? 0 : height);
        var progress = new javafx.scene.control.ProgressIndicator();
        progress.setMaxSize(24, 24);
        progress.visibleProperty().bind(image.progressProperty().lessThan(1).and(image.errorProperty().not()));
        progress.managedProperty().bind(progress.visibleProperty());
        var error = label("配图加载失败", "help-row-description");
        error.visibleProperty().bind(image.errorProperty());
        error.managedProperty().bind(error.visibleProperty());
        box.getChildren().addAll(view, progress, error);
        return box;
    }

    private static double dimension(Element block, String name, double fallback) {
        try {
            double value = Double.parseDouble(block.getAttribute(name));
            return Double.isFinite(value) && value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static PclCard category(String title, VBox body, boolean expanded) {
        var pane = PclCard.foldable(title, body, expanded);
        pane.getStyleClass().add("help-category");
        pane.setExpanded(expanded);
        return pane;
    }

    private static ScrollPane scroll(Node... children) {
        var scroll = content(children);
        scroll.getStyleClass().addAll("about-scroll", "help-scroll");
        return scroll;
    }

    private static List<Element> children(Element parent) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }
}

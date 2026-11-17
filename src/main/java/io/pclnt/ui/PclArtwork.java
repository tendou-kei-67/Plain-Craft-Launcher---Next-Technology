package io.pclnt.ui;

import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.Group;
import javafx.scene.control.Labeled;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Scale;

final class PclArtwork {
    private static final ResourceBundle PATHS = ResourceBundle.getBundle("io.pclnt.ui.pcl-artwork");
    private static final ResourceBundle PCL2 = ResourceBundle.getBundle("io.pclnt.ui.pcl2-reference-icons");
    private static final java.util.Map<String, Image> IMAGES = new java.util.HashMap<>();
    private static final java.util.Map<String, org.w3c.dom.Element> LUCIDE = new java.util.HashMap<>();

    private PclArtwork() {
    }

    static void setIcon(Labeled control, String name, double size) {
        setIcon(control, name, size, 16);
    }

    static ImageView block(String name, double size) {
        var view = new ImageView(blockImage(name));
        sizeImage(view, size);
        return view;
    }

    static Image blockImage(String name) {
        return image("help/" + (name.equals("CobbleStone") ? "Cobblestone" : name) + ".png");
    }

    static ImageView image(String resource, double size) {
        var view = new ImageView(image(resource));
        sizeImage(view, size);
        return view;
    }

    private static void sizeImage(ImageView view, double size) {
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(false);
    }

    static Image image(String resource) {
        return IMAGES.computeIfAbsent(resource, key -> new Image(PclArtwork.class.getResource(key).toExternalForm()));
    }

    static Node menuIcon(String name, double size) {
        var artwork = path(name, size, size);
        artwork.getStyleClass().add("pcl-menu-icon");
        return graphic(new Group(artwork), size, size);
    }

    static String instanceBlock(io.pclnt.minecraft.VanillaInstaller.InstanceInfo info) {
        for (String loader : java.util.List.of("Forge", "NeoForge", "Fabric", "Quilt", "OptiFine", "LiteLoader")) {
            if (info.loaders().contains(loader)) {
                return switch (loader) {
                    case "Forge" -> "Anvil";
                    case "OptiFine" -> "GrassPath";
                    case "LiteLoader" -> "Egg";
                    default -> loader;
                };
            }
        }
        return versionBlock(info.type(), info.gameVersion());
    }

    static String versionBlock(String type, String id) {
        if (java.util.Set.of("20w14infinite", "20w14∞", "22w13oneblockatatime", "23w13a_or_b",
                "24w14potato", "25w14craftmine", "15w14a", "1.RV-Pre1", "3D Shareware v1.34").contains(id)) {
            return "GoldBlock";
        }
        return switch (type) {
            case "snapshot" -> "CommandBlock";
            case "old_beta", "old_alpha" -> "CobbleStone";
            default -> "Grass";
        };
    }

    static void setIcon(Labeled control, String name, double size, double slot) {
        if (name.startsWith("lucide/")) {
            var svg = LUCIDE.computeIfAbsent(name, PclArtwork::readLucide);
            var group = new Group();
            var artwork = svg.getChildNodes();
            for (int i = 0; i < artwork.getLength(); i++) {
                if (!(artwork.item(i) instanceof org.w3c.dom.Element element)) continue;
                var stroke = lucideShape(element);
                if (stroke == null) continue;
                stroke.setFill(null);
                String fill = element.hasAttribute("fill") ? element.getAttribute("fill") : svg.getAttribute("fill");
                if ("currentColor".equals(fill)) {
                    stroke.fillProperty().bind(control.textFillProperty());
                }
                String paint = element.hasAttribute("stroke") ? element.getAttribute("stroke") : svg.getAttribute("stroke");
                if ("currentColor".equals(paint)) {
                    stroke.strokeProperty().bind(control.textFillProperty());
                    stroke.setStrokeWidth(number(svg, "stroke-width"));
                }
                stroke.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
                stroke.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
                group.getChildren().add(stroke);
            }
            group.getTransforms().add(new Scale(size / 24, size / 24));
            control.setGraphic(graphic(group, slot, slot));
            return;
        }
        var path = path(name, size, size);
        path.fillProperty().bind(control.textFillProperty());
        control.setGraphic(graphic(new Group(path), slot, slot));
    }

    private static org.w3c.dom.Element readLucide(String name) {
        if (!name.matches("lucide/[a-z0-9-]+")) throw new IllegalArgumentException(name);
        try (var stream = PclArtwork.class.getResourceAsStream("reference-icons/" + name + ".svg")) {
            if (stream == null) throw new java.io.IOException(name);
            var factory = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(stream).getDocumentElement();
        } catch (java.io.IOException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException failure) {
            throw new IllegalStateException("无法读取原版图标 " + name, failure);
        }
    }

    private static Shape lucideShape(org.w3c.dom.Element element) {
        return switch (element.getTagName()) {
            case "path" -> {
                var path = new SVGPath();
                path.setContent(element.getAttribute("d"));
                yield path;
            }
            case "line" -> new Line(number(element, "x1"), number(element, "y1"), number(element, "x2"), number(element, "y2"));
            case "circle" -> new Circle(number(element, "cx"), number(element, "cy"), number(element, "r"));
            case "rect" -> {
                var rect = new Rectangle(number(element, "x"), number(element, "y"),
                        number(element, "width"), number(element, "height"));
                double radiusX = number(element, "rx");
                rect.setArcWidth(2 * radiusX);
                rect.setArcHeight(2 * (element.hasAttribute("ry") ? number(element, "ry") : radiusX));
                yield rect;
            }
            case "polyline" -> new Polyline(java.util.Arrays.stream(element.getAttribute("points").trim().split("[,\\s]+"))
                    .mapToDouble(Double::parseDouble).toArray());
            default -> null;
        };
    }

    private static double number(org.w3c.dom.Element element, String attribute) {
        return element.hasAttribute(attribute) ? Double.parseDouble(element.getAttribute(attribute)) : 0;
    }

    static void setGlobeIcon(Labeled control) {
        var outline = new Circle(8, 8, 6.4);
        var meridians = new Ellipse(8, 8, 3.1, 6.4);
        var equator = new Line(1.6, 8, 14.4, 8);
        for (Shape shape : new Shape[]{outline, meridians, equator}) {
            shape.setFill(Color.TRANSPARENT);
            shape.strokeProperty().bind(control.textFillProperty());
            shape.setStrokeWidth(1.25);
        }
        control.setGraphic(graphic(new Group(outline, meridians, equator), 16, 16));
    }

    static void setBrand(Labeled label) {
        var logo = new SVGPath();
        logo.setContent(PATHS.getString("brand"));
        logo.setFill(null);

        // WPF stretches the path to a 39 by 17 slot and draws a 2.2 px stroke.
        logo.setStrokeWidth(3.6);
        logo.setStroke(Color.WHITE);
        var bounds = logo.getBoundsInLocal();
        logo.getTransforms().add(new Scale(39 / bounds.getWidth(), 17 / bounds.getHeight()));
        var mark = graphic(new Group(logo), 39, 17);
        mark.setSnapToPixel(false);
        label.setGraphic(mark);
    }

    static StackPane accountAvatar() {
        var path = path("avatar", 40, 44);
        path.getStyleClass().add("account-avatar");
        return graphic(new Group(path), 40, 44);
    }

    private static SVGPath path(String name, double width, double height) {
        var path = new SVGPath();
        var data = name.startsWith("pcl2.") ? PCL2.getString(name.substring(5)) : PATHS.getString(name);
        path.setFillRule(data.startsWith("F1 ") ? FillRule.NON_ZERO : FillRule.EVEN_ODD);
        path.setContent(data.replaceFirst("^F[01]\\s+", ""));
        var bounds = path.getBoundsInLocal();
        double scale = Math.min(width / bounds.getWidth(), height / bounds.getHeight());
        path.getTransforms().add(new Scale(scale, scale));
        return path;
    }

    private static StackPane graphic(Group artwork, double width, double height) {
        artwork.setManaged(false);
        var graphic = new StackPane(artwork) {
            @Override protected void layoutChildren() {
                artwork.layout();
                var bounds = artwork.getBoundsInParent();
                artwork.setLayoutX(artwork.getLayoutX() + getWidth() / 2 - bounds.getCenterX());
                artwork.setLayoutY(artwork.getLayoutY() + getHeight() / 2 - bounds.getCenterY());
            }
        };
        artwork.boundsInLocalProperty().addListener(ignored -> graphic.requestLayout());
        graphic.setMinSize(width, height);
        graphic.setPrefSize(width, height);
        graphic.setMaxSize(width, height);
        graphic.setMouseTransparent(true);
        return graphic;
    }
}

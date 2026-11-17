package io.pclnt.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/** The 36 px, non-scaling variant of PCL's MyListItem. */
final class SidebarItem extends StackPane {
    private static final Color INK = Color.rgb(52, 61, 74);
    private static final Color CHECKED = Color.rgb(19, 112, 243);
    private static final Color HOVER = Color.rgb(222, 236, 253, 190.0 / 255);
    private static final Color DOWN = Color.rgb(213, 230, 253);
    private static final Color ICON_IDLE = Color.rgb(72, 144, 245);
    private static final Color ICON_HOVER = Color.rgb(11, 91, 203);

    private final Region backdrop = new Region();
    private final Region marker = new Region();
    private final ButtonBase button;
    private final Button trailing;
    private final SimpleObjectProperty<Color> background = new SimpleObjectProperty<>(HOVER);
    private final SimpleObjectProperty<Color> ink = new SimpleObjectProperty<>(INK);
    private final javafx.beans.property.BooleanProperty dark = ThemeMode.observeDark(this);
    private ParallelTransition hoverMotion, selectionMotion;
    private int state = -1;

    static SidebarItem page(String title, String icon, double size, ToggleGroup group, Runnable onSelect) {
        return new SidebarItem(pageButton(title, group, onSelect), icon, size, null);
    }

    static SidebarItem page(String title, String icon, double size, ToggleGroup group, Runnable onSelect,
                            String action, String actionIcon, double actionSize, Runnable onAction) {
        var button = pageButton(title, group, onSelect);
        return new SidebarItem(button, icon, size, iconButton(action, actionIcon, actionSize, () -> {
            button.fire();
            onAction.run();
        }));
    }

    private static ToggleButton pageButton(String title, ToggleGroup group, Runnable onSelect) {
        var button = new ToggleButton(title) {
            @Override public void fire() {
                if (!isSelected()) {
                    super.fire();
                }
            }
        };
        button.setToggleGroup(group);
        button.setOnAction(event -> onSelect.run());
        return button;
    }

    static SidebarItem action(String title, String icon, double size, Runnable run) {
        var button = new Button(title);
        button.setOnAction(event -> run.run());
        return new SidebarItem(button, icon, size, null);
    }

    static SidebarItem folder(String title, String path, ToggleGroup group, Runnable select, ContextMenu menu) {
        return folder(title, new javafx.beans.property.SimpleStringProperty(path), group, select, menu);
    }

    static SidebarItem folder(String title, javafx.beans.value.ObservableStringValue path, ToggleGroup group,
                              Runnable select, ContextMenu menu) {
        var button = pageButton(title, group, select);
        var gear = menu == null ? null : iconButton("管理游戏文件夹", "instance-settings", 15, () -> {});
        var row = new SidebarItem(button, null, 0, gear);
        row.getStyleClass().add("pcl-folder-item");
        var name = new Label(title);
        name.textFillProperty().bind(row.ink);
        var info = new Label();
        info.textProperty().bind(path);
        info.getStyleClass().add("pcl-folder-path");
        var labels = new VBox(2, name, info);
        labels.setAlignment(Pos.CENTER_LEFT);
        labels.setMinWidth(0);
        labels.maxWidthProperty().bind(button.widthProperty().subtract(44).map(width -> Math.max(0, width.doubleValue())));
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setGraphic(labels);
        button.accessibleTextProperty().bind(javafx.beans.binding.Bindings.concat(title, "，", path));
        var tooltip = new Tooltip();
        tooltip.textProperty().bind(path);
        button.setTooltip(tooltip);
        button.setContextMenu(menu);
        if (gear != null) gear.setOnAction(event -> menu.show(gear, javafx.geometry.Side.BOTTOM, 0, 0));
        return row;
    }

    private SidebarItem(ButtonBase button, String icon, double size, Button trailing) {
        this.button = button;
        this.trailing = trailing;
        getStyleClass().add("pcl-side-item");
        backdrop.getStyleClass().add("pcl-side-highlight");
        backdrop.setMouseTransparent(true);
        backdrop.setOpacity(0);
        backdrop.backgroundProperty().bind(Bindings.createObjectBinding(() ->
                new Background(new BackgroundFill(background.get(), CornerRadii.EMPTY, Insets.EMPTY)), background));
        button.getStyleClass().add("pcl-side-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.textFillProperty().bind(ink);
        if (icon != null) PclArtwork.setIcon(button, icon, size, 24);
        getChildren().addAll(backdrop, button);
        if (button instanceof ToggleButton toggle) {
            marker.getStyleClass().add("pcl-side-marker");
            marker.setMouseTransparent(true);
            marker.setMaxSize(4, Region.USE_PREF_SIZE);
            StackPane.setAlignment(marker, Pos.CENTER_LEFT);
            getChildren().add(marker);
            toggle.selectedProperty().addListener((observable, old, selected) -> {
                animateSelection(selected, getScene() != null);
                updateHover();
            });
            animateSelection(toggle.isSelected(), false);
        }
        if (trailing != null) {
            trailing.setOpacity(0);
            trailing.setMouseTransparent(true);
            StackPane.setAlignment(trailing, Pos.CENTER_RIGHT);
            StackPane.setMargin(trailing, new Insets(0, 5, 0, 0));
            getChildren().add(trailing);
            trailing.focusedProperty().addListener(observable -> updateHover());
        }
        hoverProperty().addListener(observable -> updateHover());
        button.armedProperty().addListener(observable -> updateHover());
        button.focusVisibleProperty().addListener(observable -> updateHover());
        dark.addListener(ignored -> {
            if (button instanceof ToggleButton toggle) {
                animateSelection(toggle.isSelected(), false);
            } else {
                ink.set(textColor(false));
            }
            state = -1;
            updateHover();
        });
    }

    ToggleButton toggle() { return (ToggleButton) button; }

    private void updateHover() {
        boolean over = isHover() || button.isFocusVisible() || trailing != null && trailing.isFocused();
        boolean down = over && button.isArmed() && !(button instanceof ToggleButton t && t.isSelected());
        int next = down ? 2 : over ? 1 : 0;
        if (state == next) {
            return;
        }
        state = next;
        if (hoverMotion != null) {
            hoverMotion.stop();
        }
        int time = over ? 120 : 180;
        hoverMotion = new ParallelTransition(
                UiMotion.track(backdrop.opacityProperty(), over ? 1 : 0, 0, time,
                        over ? UiMotion.OUT_MIDDLE : Interpolator.LINEAR));
        if (over) {
            var highlight = dark.get() ? Color.web(down ? "#35597d" : "#294664") : down ? DOWN : HOVER;
            hoverMotion.getChildren().add(color(background, highlight, time));
        }
        if (trailing != null) {
            trailing.setMouseTransparent(!over || button.isArmed());
            hoverMotion.getChildren().add(UiMotion.track(trailing.opacityProperty(), over ? 1 : 0,
                    over ? 36 : 0, over ? 84 : 72, Interpolator.LINEAR));
        }
        hoverMotion.play();
    }

    private void animateSelection(boolean selected, boolean animate) {
        if (selectionMotion != null) {
            selectionMotion.stop();
        }
        if (!animate) {
            marker.setPrefHeight(selected ? 24 : 0);
            marker.setOpacity(selected ? 1 : 0);
            ink.set(textColor(selected));
            return;
        }
        selectionMotion = new ParallelTransition(
                UiMotion.track(marker.prefHeightProperty(), selected ? 24 : 0, 0, selected ? 300 : 120,
                        selected ? UiMotion.additive(.4, 200, 300, UiMotion.OUT_WEAK, UiMotion.BACK_WEAK)
                                : UiMotion.IN_WEAK),
                UiMotion.track(marker.opacityProperty(), selected ? 1 : 0, selected ? 0 : 40,
                        selected ? 30 : 70, Interpolator.LINEAR),
                color(ink, textColor(selected), selected ? 200 : 120));
        selectionMotion.play();
    }

    private Color textColor(boolean selected) {
        return dark.get() ? Color.web(selected ? "#aed5ff" : "#f2f2f2") : selected ? CHECKED : INK;
    }

    private static Button iconButton(String text, String icon, double size, Runnable action) {
        var button = new Button();
        var ink = new SimpleObjectProperty<>(ICON_IDLE);
        button.textFillProperty().bind(ink);
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
        button.getTooltip().setShowDelay(Duration.millis(200));
        button.getStyleClass().add("pcl-side-icon");
        if ("help-refresh".equals(icon)) {
            button.getStyleClass().add("more-refresh");
        }
        PclArtwork.setIcon(button, icon, size);
        button.setOnAction(event -> action.run());
        var color = new Timeline();
        button.hoverProperty().addListener((observable, old, over) -> {
            color.stop();
            color.getKeyFrames().setAll(new KeyFrame(Duration.millis(over ? 120 : 150),
                    new KeyValue(ink, over ? ICON_HOVER : ICON_IDLE, Interpolator.LINEAR)));
            color.play();
        });
        UiMotion.installIconPress(button);
        return button;
    }

    private static Timeline color(SimpleObjectProperty<Color> property, Color color, int millis) {
        return new Timeline(new KeyFrame(Duration.millis(millis), new KeyValue(property, color, Interpolator.LINEAR)));
    }
}

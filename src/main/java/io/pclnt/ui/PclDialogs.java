package io.pclnt.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.WindowEvent;

/** MyMsgText, MyMsgInput and MyMsgSelect displayed inside the launcher frame. */
final class PclDialogs {
    private final Scene scene;
    private final Pane host;
    private final StackPane overlay = new StackPane();
    private final Rectangle shade = new Rectangle();
    private final ArrayDeque<Handle> pending = new ArrayDeque<>();
    private final EnumSet<KeyCode> heldShortcuts = EnumSet.noneOf(KeyCode.class);
    private Handle active;
    private Node previousFocus;
    private ParallelTransition animation;

    private PclDialogs(Scene scene) {
        this.scene = scene;
        host = scene.lookup("#frame") instanceof Pane frame ? frame : (Pane) scene.getRoot();
        overlay.setId("pcl-dialog-overlay");
        overlay.setManaged(false);
        overlay.setPickOnBounds(true);
        overlay.setPadding(new Insets(25));
        overlay.setFocusTraversable(true);
        overlay.prefWidthProperty().bind(host.widthProperty());
        overlay.prefHeightProperty().bind(host.heightProperty());
        host.widthProperty().addListener(ignored -> resizeOverlay());
        host.heightProperty().addListener(ignored -> resizeOverlay());
        shade.widthProperty().bind(overlay.widthProperty());
        shade.heightProperty().bind(overlay.heightProperty());
        shade.setMouseTransparent(true);
        shade.setOpacity(0);
        // Keep the full-frame backdrop outside the card's 25 px layout inset.
        shade.setManaged(false);
        overlay.getChildren().add(shade);
        scene.addEventFilter(KeyEvent.ANY, this::key);
        scene.getWindow().focusedProperty().addListener((observable, old, focused) -> {
            if (!focused) heldShortcuts.clear();
        });
        scene.getWindow().addEventHandler(WindowEvent.WINDOW_HIDDEN, event -> dismissAll());
    }

    static boolean confirm(Node owner, String title, String message) {
        return waitForMessage(owner, title, message, false, ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK;
    }

    static void message(Node owner, String title, String message, boolean warning) {
        openMessage(owner, title, message, warning, ignored -> {}, ButtonType.OK);
    }

    static void openMessage(Node owner, String title, String message, boolean warning,
                            Consumer<ButtonType> finish, ButtonType... buttons) {
        open(owner, title, caption(message), textWidth(title, message), warning, null, null, finish, buttons);
    }

    static ButtonType waitForMessage(Node owner, String title, String message, boolean warning, ButtonType... buttons) {
        return await(owner, title, caption(message), textWidth(title, message), warning, null, null, buttons);
    }

    static Optional<String> input(Node owner, String title, String message, String initial, Predicate<String> valid) {
        var editor = new TextField(initial);
        editor.setAccessibleText(title);
        editor.getStyleClass().add("account-input");
        var body = new VBox(7);
        if (!message.isBlank()) body.getChildren().add(caption(message));
        body.getChildren().add(editor);
        var disabled = Bindings.createBooleanBinding(() -> !valid.test(editor.getText()), editor.textProperty());
        var response = await(owner, title, body, 508, false, disabled, editor, ButtonType.OK, ButtonType.CANCEL);
        return response == ButtonType.OK ? Optional.of(editor.getText()) : Optional.empty();
    }

    static <T> Optional<T> choose(Node owner, String title, T selected, Collection<T> items) {
        var group = new ToggleGroup();
        var choices = new VBox(8);
        for (T item : items) {
            var radio = new RadioButton(String.valueOf(item));
            radio.setToggleGroup(group);
            radio.setUserData(item);
            radio.setSelected(item.equals(selected));
            AppearanceControlMotion.install(radio);
            choices.getChildren().add(radio);
        }
        var response = await(owner, title, choices, textWidth(title, ""), false, group.selectedToggleProperty().isNull(), null,
                ButtonType.OK, ButtonType.CANCEL);
        if (response != ButtonType.OK) return Optional.empty();
        @SuppressWarnings("unchecked")
        T value = (T) group.getSelectedToggle().getUserData();
        return Optional.of(value);
    }

    static ButtonType waitForForm(Node owner, String title, Node body, ObservableBooleanValue disabled, Node focus,
                                  ButtonType... buttons) {
        return await(owner, title, body, 508, false, disabled, focus, buttons);
    }

    static Handle openForm(Node owner, String title, Node body, Consumer<ButtonType> finish, ButtonType... buttons) {
        return open(owner, title, body, 508, false, null, null, finish, buttons);
    }

    private static ButtonType await(Node owner, String title, Node body, double width, boolean warning,
                                    ObservableBooleanValue disabled, Node focus, ButtonType... buttons) {
        var key = new Object();
        open(owner, title, body, width, warning, disabled, focus,
                result -> Platform.exitNestedEventLoop(key, result), buttons);
        return (ButtonType) Platform.enterNestedEventLoop(key);
    }

    private static Handle open(Node owner, String title, Node body, double width, boolean warning,
                               ObservableBooleanValue disabled, Node focus, Consumer<ButtonType> finish, ButtonType... buttons) {
        Scene scene = owner.getScene();
        var manager = (PclDialogs) scene.getProperties().computeIfAbsent(PclDialogs.class, ignored -> new PclDialogs(scene));
        var handle = manager.new Handle(title, body, width, warning, disabled, focus, finish, List.of(buttons));
        manager.pending.addLast(handle);
        manager.present();
        return handle;
    }

    static Label caption(String message) {
        var caption = new Label(message);
        caption.setWrapText(true);
        caption.getStyleClass().add("pcl-dialog-caption");
        return caption;
    }

    private static double textWidth(String title, String message) {
        Text text = new Text(message);
        text.setFont(Font.font(15));
        var heading = new Text(title);
        heading.setFont(Font.font(23));
        return Math.max(400, Math.min(600,
                Math.max(text.getLayoutBounds().getWidth() + 68, heading.getLayoutBounds().getWidth() + 121)));
    }

    private void present() {
        if (active != null || pending.isEmpty()) return;
        if (overlay.getParent() == null) {
            previousFocus = scene.getFocusOwner();
            host.getChildren().add(overlay);
            resizeOverlay();
        }
        active = pending.removeFirst();
        var handle = active;
        shade.setFill(handle.warning ? Color.rgb(80, 0, 0, 140.0 / 255) : Color.rgb(0, 0, 0, 90.0 / 255));
        overlay.getChildren().add(handle.card);
        overlay.toFront();
        handle.card.applyCss();
        handle.focus.requestFocus();
        animation = new ParallelTransition(
                UiMotion.track(shade.opacityProperty(), 1, 0, 200, Interpolator.LINEAR),
                UiMotion.track(handle.card.opacityProperty(), 1, 60, 120, Interpolator.LINEAR),
                UiMotion.track(handle.card.translateYProperty(), 0, 60, 300, UiMotion.BACK_WEAK),
                UiMotion.track(handle.rotation.angleProperty(), 0, 60, 300, UiMotion.OUT_WEAK));
        animation.play();
    }

    private void resizeOverlay() {
        overlay.resize(host.getWidth(), host.getHeight());
    }

    private boolean close(Handle handle, ButtonType result) {
        if (handle != active) {
            if (!pending.remove(handle)) return false;
            handle.finish.accept(result);
            return true;
        }
        if (handle.closing) return false;
        handle.closing = true;
        overlay.requestFocus();
        double backdrop = shade.getOpacity();
        double opacity = handle.card.getOpacity();
        double offset = handle.card.getTranslateY();
        double angle = handle.rotation.getAngle();
        animation.stop();
        shade.setOpacity(backdrop);
        handle.card.setOpacity(opacity);
        handle.card.setTranslateY(offset);
        handle.rotation.setAngle(angle);
        animation = new ParallelTransition(
                UiMotion.track(handle.card.opacityProperty(), 0, 20, 80, Interpolator.LINEAR),
                UiMotion.track(handle.card.translateYProperty(), 20, 0, 150, UiMotion.OUT_MIDDLE),
                UiMotion.track(handle.rotation.angleProperty(), 6, 0, 150, UiMotion.IN_WEAK));
        if (pending.isEmpty()) {
            animation.getChildren().add(UiMotion.track(shade.opacityProperty(), 0, 30, 200, UiMotion.OUT_WEAK));
        }
        animation.setOnFinished(event -> {
            overlay.getChildren().remove(handle.card);
            active = null;
            if (pending.isEmpty()) detach();
            try {
                handle.finish.accept(result);
            } finally {
                present();
            }
        });
        animation.play();
        return true;
    }

    private void dismissAll() {
        if (animation != null) animation.stop();
        var unfinished = new ArrayList<>(pending);
        pending.clear();
        if (active != null) {
            unfinished.addFirst(active);
            overlay.getChildren().remove(active.card);
            active = null;
        }
        detach();
        for (var handle : unfinished) handle.finish.accept(null);
    }

    private void detach() {
        host.getChildren().remove(overlay);
        shade.setOpacity(0);
        if (previousFocus != null && previousFocus.getScene() == scene) previousFocus.requestFocus();
        previousFocus = null;
    }

    private void key(KeyEvent event) {
        KeyCode code = event.getCode();
        boolean shortcut = code == KeyCode.ENTER || code == KeyCode.ESCAPE;
        boolean pressed = event.getEventType() == KeyEvent.KEY_PRESSED;
        boolean firstPress = shortcut && pressed && heldShortcuts.add(code);
        if (shortcut && event.getEventType() == KeyEvent.KEY_RELEASED) heldShortcuts.remove(code);
        if (active == null) return;
        if (active.closing) {
            event.consume();
            return;
        }
        if (shortcut) {
            if (firstPress) {
                (code == KeyCode.ENTER ? active.buttons.getFirst() : active.buttons.getLast()).fire();
            }
            event.consume();
        } else if (pressed && code == KeyCode.TAB) {
            var controls = new ArrayList<Node>();
            focusable(active.card, controls);
            if (!controls.isEmpty()) {
                int index = controls.indexOf(scene.getFocusOwner());
                int next = index < 0 ? (event.isShiftDown() ? controls.size() - 1 : 0)
                        : Math.floorMod(index + (event.isShiftDown() ? -1 : 1), controls.size());
                controls.get(next).requestFocus();
            }
            event.consume();
        }
    }

    private static void focusable(Node node, List<Node> controls) {
        if (!node.isVisible() || node.isDisabled()) return;
        if (node.isFocusTraversable()) controls.add(node);
        else if (node instanceof Parent group) group.getChildrenUnmodifiable().forEach(child -> focusable(child, controls));
    }

    final class Handle {
        private final VBox card = new VBox();
        private final Rotate rotation = new Rotate(-4);
        private final List<Button> buttons = new ArrayList<>();
        private final boolean warning;
        private final Node focus;
        private final Consumer<ButtonType> finish;
        private boolean closing;

        private Handle(String title, Node body, double width, boolean warning, ObservableBooleanValue disabled,
                       Node focus, Consumer<ButtonType> finish, List<ButtonType> types) {
            this.warning = warning;
            this.finish = finish;
            card.getStyleClass().add("pcl-dialog-card");
            if (warning) card.getStyleClass().add("pcl-dialog-warning");
            card.setOpacity(0);
            card.setTranslateY(40);
            card.getTransforms().add(rotation);
            rotation.pivotYProperty().bind(card.heightProperty().divide(2));
            card.prefWidthProperty().bind(Bindings.min(width, overlay.widthProperty().subtract(50)));
            card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            var heading = new Label(title);
            heading.setWrapText(true);
            heading.getStyleClass().add("pcl-dialog-title");
            VBox.setMargin(heading, new Insets(-1, 70, 9, 7));
            var line = new Region();
            line.getStyleClass().add("pcl-dialog-line");
            var scroll = new ScrollPane(body);
            scroll.getStyleClass().add("pcl-dialog-content");
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setFocusTraversable(false);
            VBox.setMargin(scroll, new Insets(13, 0, 17, 0));
            scroll.prefViewportHeightProperty().bind(Bindings.createDoubleBinding(() ->
                    Math.max(0, Math.min(overlay.getHeight() - 166,
                            body.prefHeight(Math.max(0, card.getPrefWidth() - 66)))),
                    overlay.heightProperty(), card.prefWidthProperty(), body.layoutBoundsProperty()));
            var buttonRow = new HBox(12);
            buttonRow.setAlignment(Pos.CENTER_RIGHT);
            VBox.setMargin(buttonRow, new Insets(0, 8, 0, 0));
            for (ButtonType type : types) {
                var button = new Button(type == ButtonType.OK ? "确定" : type == ButtonType.CANCEL ? "取消" : type.getText());
                button.getStyleClass().addAll("outline-button", "pcl-dialog-button");
                if (buttons.isEmpty() && (warning || types.size() > 1)) {
                    button.getStyleClass().add(warning ? "pcl-dialog-danger" : "accent-button");
                    if (disabled != null) button.disableProperty().bind(disabled);
                }
                UiMotion.installPress(button);
                button.setOnAction(event -> PclDialogs.this.close(this, type));
                buttons.add(button);
                buttonRow.getChildren().add(button);
            }
            this.focus = focus == null ? buttons.getFirst() : focus;
            card.getChildren().addAll(heading, line, scroll, buttonRow);
        }

        boolean close() {
            return PclDialogs.this.close(this, null);
        }
    }
}

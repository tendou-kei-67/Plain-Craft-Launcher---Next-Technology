package io.pclnt.ui;

import io.pclnt.minecraft.BedrockEdition;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;

import static io.pclnt.ui.LauncherController.content;
import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class BedrockDownloadPage {
    private static final String GAME = "Minecraft for Windows";
    private final Node view;
    private final Notifications notifications;
    private final TaskMonitor tasks;
    private final Label status = label("", "card-text");
    private final Button play = button("启动游戏", this::launch);
    private final Button install = button("安装游戏", this::install);
    private final Button store = button("打开 Microsoft Store", this::openStore);
    private final Button refresh = button("刷新", this::refresh);
    private boolean busy;

    BedrockDownloadPage(HostServices links, Notifications notifications, TaskMonitor tasks) {
        this.notifications = notifications;
        this.tasks = tasks;
        if (BedrockEdition.supported()) {
            play.setDisable(true);
            play.getStyleClass().add("accent-button");
            install.getStyleClass().add("accent-button");
            install.setVisible(false);
            install.managedProperty().bind(install.visibleProperty());
            status.setWrapText(true);
            view = content(PclCard.fixed(GAME, status, new FlowPane(10, 8, play, install, store, refresh)));
            view.sceneProperty().addListener((observable, old, scene) -> {
                if (scene != null) {
                    refresh();
                }
            });
        } else {
            view = content(PclCard.fixed(GAME, label("基岩版的官方桌面客户端仅支持 Windows。", "card-text"),
                    button("查看官方发行信息", () -> links.showDocument(BedrockEdition.PRODUCT_URL))));
        }
    }

    Node view() {
        return view;
    }

    private void refresh() {
        if (busy) {
            return;
        }
        status.setText("正在检测已安装版本……");
        run(BedrockEdition::detect, this::display, null);
    }

    private void display(Optional<BedrockEdition.Installation> result) {
        play.setDisable(result.isEmpty());
        install.setVisible(result.isEmpty());
        status.setText(result.map(game -> "已安装 · " + game.version())
                .orElse("尚未安装。点击「安装游戏」即可从 Microsoft Store 下载并安装。"));
    }

    private void launch() {
        status.setText("正在启动游戏……");
        run(() -> {
            var game = BedrockEdition.detect();
            if (game.isPresent()) {
                BedrockEdition.launch(game.get());
            }
            return game;
        }, game -> {
            display(game);
            if (game.isPresent()) {
                notifications.post("已请求启动 " + GAME, Notifications.Kind.INFO);
            }
        }, "启动 " + GAME);
    }

    private void install() {
        status.setText("Microsoft Store 正在下载安装……");
        run(BedrockEdition::install, game -> {
            display(Optional.of(game));
            notifications.post(GAME + " 安装完成", Notifications.Kind.INFO);
        }, "安装 " + GAME);
    }

    private void openStore() {
        run(() -> {
            BedrockEdition.openStore();
            return null;
        }, ignored -> {}, null);
    }

    private <T> void run(Callable<T> work, Consumer<T> success, String taskTitle) {
        if (busy) {
            return;
        }
        busy = true;
        boolean playDisabled = play.isDisabled();
        play.setDisable(true);
        refresh.setDisable(true);
        install.setDisable(true);
        store.setDisable(true);
        var task = new Task<T>() {
            @Override protected T call() throws Exception {
                if (taskTitle != null) {
                    updateMessage(taskTitle + "……");
                }
                return work.call();
            }
        };
        Runnable ready = () -> {
            busy = false;
            refresh.setDisable(false);
            install.setDisable(false);
            store.setDisable(false);
            play.setDisable(playDisabled);
        };
        task.setOnSucceeded(event -> {
            ready.run();
            success.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            ready.run();
            status.setText(VanillaDownloadPage.message(task.getException()));
            notifications.post(status.getText(), Notifications.Kind.ERROR);
        });
        if (taskTitle == null) {
            Thread.startVirtualThread(task);
        } else {
            tasks.start(taskTitle, "", task, false, false);
        }
    }
}

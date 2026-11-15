package io.pclnt.ui;

import io.pclnt.minecraft.TaskIo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.concurrent.WorkerStateEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static io.pclnt.ui.LauncherController.*;

final class TaskMonitor {
    private final AppearanceOptions options;
    private final List<Job> jobs = new ArrayList<>();
    private final VBox cards = new VBox(15);
    private final Label percent = label("0.00 %", "task-stat");
    private final Label speed = label("", "task-stat");
    private final Label disk = label("", "task-disk");
    private final Label remaining = label("—", "task-stat");
    private final Label threads = label("0", "task-stat");
    private final Label summary = label("", "task-summary");
    private final ProgressBar total = new ProgressBar(0);
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();
    private final Timeline sampler;
    private final BorderPane view;
    private long sampledAt;

    TaskMonitor(AppearanceOptions options, Runnable back) {
        this.options = options;
        var stats = new VBox(12, metric("总进度", percent), metric("下载速度", speed),
                metric("磁盘使用量", disk), metric("剩余文件", remaining), metric("剩余线程", threads));
        stats.getStyleClass().add("task-stats");
        stats.setAlignment(Pos.CENTER);
        var heading = new HBox(9, iconButton("返回下载", "install-back", 14, back),
                label("任务管理", "card-title"));
        heading.setAlignment(Pos.CENTER_LEFT);
        var right = new BorderPane(content(heading, cards));
        total.setMaxWidth(Double.MAX_VALUE);
        total.setAccessibleText("总进度");
        var footer = new VBox(5, summary, total);
        footer.getStyleClass().add("task-footer");
        right.setBottom(footer);
        view = section(stats, right);
        cards.getChildren().add(PclCard.fixed("暂无任务"));
        sampler = new Timeline(new KeyFrame(Duration.millis(350), event -> refresh()));
        sampler.setCycleCount(Animation.INDEFINITE);
        options.downloadSpeedUnit.addListener(ignored -> refresh());
        options.diskSpeedUnit.addListener(ignored -> refresh());
        refresh();
    }

    Node view() { return view; }

    Button floating(StackPane surface, Runnable open, SimpleBooleanProperty onTaskPage) {
        var button = iconButton("任务管理", "pcl2.FormMain.BtnExtraDownload", 25, open);
        button.getStyleClass().add("task-float");
        button.setVisible(false);
        button.setManaged(false);
        button.setScaleX(0);
        button.setScaleY(0);
        StackPane.setAlignment(button, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(button, new Insets(0, 15, 15, 0));
        surface.getChildren().add(button);
        Runnable update = new Runnable() {
            private ParallelTransition motion;
            private boolean shown;

            @Override public void run() {
                int mode = options.taskFloatMode.get();
                boolean show = !onTaskPage.get() && (mode == 1 || mode == 0 && busy.get());
                if (shown == show) return;
                shown = show;
                if (motion != null) motion.stop();
                if (show) {
                    button.setManaged(true);
                    button.setVisible(true);
                }
                button.setMouseTransparent(!show);
                var ease = show ? UiMotion.BACK_WEAK : UiMotion.IN_WEAK;
                int delay = show ? 60 : 0;
                int time = show ? 500 : 100;
                motion = new ParallelTransition(
                        UiMotion.track(button.scaleXProperty(), show ? 1 : 0, delay, time, ease),
                        UiMotion.track(button.scaleYProperty(), show ? 1 : 0, delay, time, ease));
                if (!show) motion.setOnFinished(event -> {
                    button.setVisible(false);
                    button.setManaged(false);
                });
                motion.play();
            }
        };
        busy.addListener(ignored -> update.run());
        options.taskFloatMode.addListener(ignored -> update.run());
        onTaskPage.addListener(ignored -> update.run());
        update.run();
        return button;
    }

    void start(String title, String file, Task<?> task, boolean singleTransfer, boolean canCancel) {
        var job = new Job(title, file, task, singleTransfer, canCancel);
        if (jobs.isEmpty()) cards.getChildren().clear();
        jobs.addFirst(job);
        cards.getChildren().addFirst(job.card);
        if (jobs.size() > 50) {
            for (int i = jobs.size() - 1; i >= 0; i--) {
                if (jobs.get(i).active()) continue;
                jobs.remove(i);
                cards.getChildren().remove(i);
                break;
            }
        }
        task.addEventHandler(WorkerStateEvent.ANY, event -> refresh());
        if (sampler.getStatus() == Animation.Status.STOPPED) {
            sampledAt = System.nanoTime();
            sampler.play();
        }
        job.thread = Thread.startVirtualThread(() -> TaskIo.run(job.io, task));
        refresh();
    }

    private void refresh() {
        long now = System.nanoTime();
        double elapsed = (now - sampledAt) / 1e9;
        boolean sample = elapsed >= .25;
        int active = 0, known = 0, files = 0, activeTransfers = 0;
        boolean hasFiles = false;
        double sum = 0, in = 0, reads = 0, writes = 0;
        String current = "";
        for (var job : jobs) {
            if (sample) job.sample(elapsed);
            job.refresh();
            if (!job.active()) continue;
            active++;
            activeTransfers += job.io.activeTransfers();
            if (current.isBlank()) current = job.file();
            double progress = job.progress();
            if (progress >= 0) {
                sum += progress;
                known++;
            }
            double count = job.task.getTotalWork();
            if (job.countsFiles && count >= 0) {
                files += (int) Math.max(0, count - Math.max(0, job.task.getWorkDone()));
                hasFiles = true;
            } else if (job.singleTransfer) {
                files++;
                hasFiles = true;
            }
            in += job.inRate;
            reads += job.readRate;
            writes += job.writeRate;
        }
        if (sample) sampledAt = now;
        double progress = known == 0 ? (active == 0 && !jobs.isEmpty()
                && jobs.getFirst().task.getState() == Worker.State.SUCCEEDED ? 1 : 0) : sum / active;
        String displayPercent = String.format(Locale.ROOT, "%.2f", progress * 100);
        percent.setText(displayPercent + " %");
        total.setProgress(active > 0 && known == 0 ? ProgressBar.INDETERMINATE_PROGRESS : progress);
        summary.setText("总进度：" + displayPercent + "%  当前文件：" + (current.isBlank() ? "—" : current));
        speed.setText(rate(in, options.downloadSpeedUnit.get()));
        disk.setText("读取 " + rate(reads, options.diskSpeedUnit.get()) + "\n写入 " + rate(writes, options.diskSpeedUnit.get()));
        remaining.setText(hasFiles ? Integer.toString(files) : "—");
        threads.setText(Integer.toString(activeTransfers));
        busy.set(active > 0);
        if (!busy.get()) sampler.stop();
    }

    private static VBox metric(String title, Label value) {
        var metric = new VBox(6, label(title, "task-stat-heading"), new Separator(), value);
        metric.setAlignment(Pos.CENTER);
        return metric;
    }

    static Button iconButton(String title, String icon, double size, Runnable action) {
        var button = new Button();
        button.setAccessibleText(title);
        var tooltip = new Tooltip(title);
        tooltip.setShowDelay(Duration.millis(200));
        button.setTooltip(tooltip);
        button.setOnAction(event -> action.run());
        button.getStyleClass().add("pcl-action-icon");
        PclArtwork.setIcon(button, icon, size, size);
        UiMotion.installPress(button);
        return button;
    }

    static String rate(double bytes, int unit) {
        boolean bits = unit == 1;
        var units = bits ? List.of("bit/s", "kbit/s", "Mbit/s", "Gbit/s")
                : List.of("B/s", "KB/s", "MB/s", "GB/s");
        double value = bits ? bytes * 8 : bytes;
        int scale = 0;
        while (value >= 1_000 && scale < units.size() - 1) {
            value /= 1_000;
            scale++;
        }
        return String.format(Locale.ROOT, scale == 0 ? "%.0f %s" : "%.2f %s", value, units.get(scale));
    }

    private static final class Job {
        final Task<?> task;
        final TaskIo.Counter io = new TaskIo.Counter();
        final String initialFile;
        final boolean singleTransfer, countsFiles;
        final Label state = label("", "task-state");
        final PclCard card;
        final Button cancel;
        Thread thread;
        boolean cancelRequested;
        long received, read, written;
        double inRate, readRate, writeRate;

        Job(String title, String initialFile, Task<?> task, boolean singleTransfer, boolean canCancel) {
            this.task = task;
            this.initialFile = initialFile;
            this.singleTransfer = singleTransfer;
            countsFiles = !singleTransfer && canCancel;
            cancel = iconButton("取消任务", "task-cancel", 12, () -> {
                if (thread == null || !active()) return;
                cancelRequested = true;
                state.setText("正在取消……");
                cancelState();
                thread.interrupt();
            });
            var line = new HBox(8, state);
            line.setAlignment(Pos.CENTER_LEFT);
            state.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(state, Priority.ALWAYS);
            if (canCancel) line.getChildren().add(cancel);
            card = PclCard.fixed(title, line);
        }

        boolean active() {
            return switch (task.getState()) {
                case READY, SCHEDULED, RUNNING -> true;
                default -> false;
            };
        }

        String file() { return io.file().isBlank() ? initialFile : io.file(); }

        double progress() {
            double progress = task.getProgress();
            return progress >= 0 ? progress : singleTransfer ? io.fileProgress() : -1;
        }

        void sample(double elapsed) {
            long newReceived = io.received(), newRead = io.read(), newWritten = io.written();
            inRate = (newReceived - received) / elapsed;
            readRate = (newRead - read) / elapsed;
            writeRate = (newWritten - written) / elapsed;
            received = newReceived;
            read = newRead;
            written = newWritten;
        }

        void refresh() {
            String text = switch (task.getState()) {
                case READY, SCHEDULED -> "等待中";
                case SUCCEEDED -> "已完成";
                case CANCELLED -> "已取消";
                case FAILED -> cancelRequested ? "已取消" : "失败：" + VanillaDownloadPage.message(task.getException());
                case RUNNING -> cancelRequested ? "正在取消……" : runningText();
            };
            state.setText(text);
            cancelState();
        }

        private String runningText() {
            String message = task.getMessage();
            String progress = progress() < 0 ? "" : String.format(Locale.ROOT, "%.0f%%  ", progress() * 100);
            return progress + (!message.isBlank() ? message : file().isBlank() ? "正在处理……" : file());
        }

        private void cancelState() {
            cancel.setDisable(!active() || cancelRequested);
        }
    }
}

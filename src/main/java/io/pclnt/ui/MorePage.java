package io.pclnt.ui;

import javafx.application.HostServices;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

import static io.pclnt.ui.LauncherController.*;

final class MorePage {
    // Add the PCL-NT destinations here when they are ready.
    private static final String FEEDBACK_URL = "";
    private static final String VOTE_URL = "";

    private final HostServices links;
    private final AppearanceOptions options;
    private final Parent owner;
    private final ViewTransition details = new ViewTransition(new StackPane(), ViewTransition.Type.CONTENT);
    private final HelpPage help;
    private final Notifications notifications;
    private final Runnable openToolbox;

    MorePage(HostServices links, Parent owner, AppearanceOptions options, Notifications notifications, Runnable openToolbox) {
        this.links = links;
        this.owner = owner;
        this.options = options;
        this.notifications = notifications;
        this.openToolbox = openToolbox;
        help = new HelpPage(details, links);
    }

    Parent view() {
        var group = new ToggleGroup();
        var helpTab = SidebarItem.page("帮助", "help", 19.4, group, help::show,
                "刷新", "help-refresh", 12.75, () -> {
                    notifications.post("正在刷新……", Notifications.Kind.INFO);
                    help.refresh();
                });
        var about = content(AboutPage.cards(links));
        about.getStyleClass().add("about-scroll");
        var aboutTab = SidebarItem.page("关于与鸣谢", "about", 19.6, group, () -> details.show(about));

        var toolbox = content(PclCard.fixed("百宝箱", VanillaDownloadPage.button("打开百宝箱", openToolbox)));
        var toolboxTab = SidebarItem.page("百宝箱", "toolbox", 19.4, group, () -> details.show(toolbox));

        var feedback = action("反馈", "feedback", 18, FEEDBACK_URL);
        var vote = action("新功能投票", "vote", 18.4, VOTE_URL);
        var sidebar = sidebar(helpTab, aboutTab, toolboxTab, feedback, vote);
        sidebar.getStyleClass().addAll("pcl-sidebar", "more-sidebar");
        var rows = java.util.List.of(helpTab, aboutTab, toolboxTab, feedback, vote);
        for (int i = 0; i < rows.size(); i++) {
            SettingsPage.bindVisible(rows.get(i), options.visible(AppearanceOptions.MORE_ROWS.get(i)));
        }
        var sidebarDependencies = new java.util.ArrayList<javafx.beans.Observable>();
        sidebarDependencies.add(options.forceShow);
        rows.forEach(row -> sidebarDependencies.add(row.visibleProperty()));
        SettingsPage.bindVisible(sidebar, javafx.beans.binding.Bindings.createBooleanBinding(
                () -> options.forceShow.get() || rows.stream().filter(Parent::isVisible).count() > 1,
                sidebarDependencies.toArray(javafx.beans.Observable[]::new)));
        for (var row : rows.subList(0, 3)) {
            if (row.isVisible()) {
                row.toggle().fire();
                break;
            }
        }
        return section(sidebar, details.host());
    }

    private SidebarItem action(String title, String icon, double size, String url) {
        return SidebarItem.action(title, icon, size, () -> {
            boolean configured = !url.isBlank();
            var open = new ButtonType("打开");
            PclDialogs.openMessage(owner, title,
                    configured ? "是否在浏览器中打开「" + title + "」？" : "「" + title + "」的网址尚未配置。",
                    false, result -> {
                        if (configured && result == open) links.showDocument(url);
                    }, configured ? new ButtonType[]{open, ButtonType.CANCEL} : new ButtonType[]{ButtonType.OK});
        });
    }
}

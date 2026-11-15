package io.pclnt.ui;

import io.pclnt.LauncherApp;
import java.util.ResourceBundle;
import javafx.application.HostServices;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

final class AboutPage {
    private AboutPage() {
    }

    static VBox[] cards(HostServices links) {
        String version = ResourceBundle.getBundle("io.pclnt.build").getString("version");

        var maintainer = row("天童ケイ酱（PCL-NT 项目维护者/开发者）",
                LauncherApp.FULL_NAME + " · " + version + "\n基于 PCL2启动器UI等内容 的第三方 JavaFX 重写项目。",
                "CH0335_noweapon_spr_07.png");

        var originalDeveloper = row("龙腾猫跃", "Plain Craft Launcher 启动器的原作者", "ltcat.jpg");
        originalDeveloper.getChildren().add(link("赞助原版 PCL开发者", "https://meloong.com/afd/a/LTCat", links));
        var collaborator = row("HerobrineXia", "Plain Craft Launcher 原版启动器的协力开发者", "herobrinexia.png");
        var source = row("Plain Craft Launcher 2", "原版项目与使用指南", "credits/Logo.png");
        source.getChildren().add(link("PCL2启动器原版源码", "https://github.com/Meloong-Git/PCL", links));

        return new VBox[]{
                card("关于", maintainer),
                card("原版PCL2-开发者", originalDeveloper, collaborator, source),
                card("特别鸣谢",
                        credit("bangbang93", "提供 BMCLAPI 镜像源和 Forge 安装工具", "bangbang93.png",
                                link("赞助镜像源", "https://meloong.com/afd/a/bangbang93", links)),
                        credit("MC 百科", "提供模组名称的中文翻译和相关资料", "wiki.png",
                                link("打开百科", "https://www.mcmod.cn", links)),
                        credit("z0z0r4", "提供 MCIM 社区资源镜像源和帮助库图床", "z0z0r4.png",
                                link("MCIM 主页", "https://github.com/mcmod-info-mirror", links)),
                        credit("EasyTier", "为原版的 PCL2启动器 提供联机模块", "EasyTier.png",
                                link("GitHub 页面", "https://github.com/EasyTier/EasyTier", links)),
                        credit("00ll00", "提供 Java Launch Wrapper 和服务支持", "00ll00.png"),
                        credit("Patrick", "设计并制作了 PCL启动器的图标 本版本启动器是在原版的图标基础上进行了修改", "Patrick.png"),
                        credit("Hao_Tian", "参与PCL2启动器原版的内测并报告问题", "Hao_Tian.jpg"),
                        credit("Minecraft 中文论坛", "曾提供 MCBBS 镜像源，目前已关站", "MCBBS.png"),
                        credit("原版PCL 赞助者内群的各位", "感谢大家一直以来对原版 PCL 的支持，让PCL2发扬光大，诞生如此之多的不同版本", "PCL2.png"),
                        row("PCL 帮助库贡献者", "编写和维护了原版内置帮助（该版本也使用了相关资料）"))
        };
    }

    private static VBox card(String title, HBox... rows) {
        return PclCard.fixed(title, rows);
    }

    private static HBox row(String name, String description) {
        return row(name, description, null);
    }

    private static HBox credit(String name, String description, String image, Hyperlink... links) {
        var credit = row(name, description, "credits/" + image);
        credit.getChildren().addAll(links);
        return credit;
    }

    private static HBox row(String name, String description, String image) {
        var title = new Label(name);
        title.getStyleClass().add("about-name");
        var details = new Label(description);
        details.getStyleClass().add("card-text");
        details.setWrapText(true);
        var text = new VBox(3, title, details);
        HBox.setHgrow(text, Priority.ALWAYS);

        var row = new HBox(11);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("about-row");
        if (image != null) {
            var portrait = new ImageView(AboutPage.class.getResource(image).toExternalForm());
            portrait.setFitWidth(34);
            portrait.setFitHeight(34);
            portrait.setClip(new Circle(17, 17, 17));
            row.getChildren().add(portrait);
        }
        row.getChildren().add(text);
        return row;
    }

    private static Hyperlink link(String text, String url, HostServices links) {
        var link = new Hyperlink(text);
        link.setOnAction(event -> links.showDocument(url));
        link.getStyleClass().add("about-link");
        return link;
    }
}

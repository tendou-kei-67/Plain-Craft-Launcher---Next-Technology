package io.pclnt.ui;

import io.pclnt.minecraft.GameLaunch;
import io.pclnt.minecraft.MicrosoftLogin;
import io.pclnt.minecraft.PlayerProfile;
import io.pclnt.minecraft.Yggdrasil;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.control.PasswordField;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static io.pclnt.ui.LauncherController.label;
import static io.pclnt.ui.VanillaDownloadPage.button;

final class AccountPane {
    private enum UuidMode {
        STANDARD("行业规范"), LEGACY("旧版"), CUSTOM("自定义");

        private final String label;
        UuidMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @FunctionalInterface private interface Change {
        void save() throws IOException;
    }

    private final ViewTransition transition;
    private final PlayerProfiles profiles;
    private final Notifications notifications;
    private final Runnable selectionChanged;
    private final Consumer<URI> openPage;
    private final java.util.function.BooleanSupplier gameRunning;
    private final Yggdrasil yggdrasil = new Yggdrasil();
    private final Map<UUID, Yggdrasil.Session> sessions = new HashMap<>();
    private final MicrosoftLogin microsoft = new MicrosoftLogin(System.getenv()
            .getOrDefault("PCLNT_MICROSOFT_CLIENT_ID", MicrosoftLogin.DEFAULT_CLIENT_ID));
    private final Map<UUID, MicrosoftLogin.Session> microsoftSessions = new HashMap<>();
    private final javafx.beans.property.SimpleBooleanProperty microsoftAppearanceBusy = new javafx.beans.property.SimpleBooleanProperty();

    AccountPane(ViewTransition transition, PlayerProfiles profiles, Notifications notifications, Runnable selectionChanged,
                Consumer<URI> openPage, java.util.function.BooleanSupplier gameRunning) {
        this.transition = transition;
        this.profiles = profiles;
        this.notifications = notifications;
        this.selectionChanged = selectionChanged;
        this.openPage = openPage;
        this.gameRunning = gameRunning;
    }

    PlayerProfile selected() { return profiles.selected(); }
    Yggdrasil yggdrasil() { return yggdrasil; }
    Yggdrasil.Session session(PlayerProfile profile) { return sessions.get(profile.id()); }
    void session(PlayerProfile profile, Yggdrasil.Session session) { sessions.put(profile.id(), session); }
    MicrosoftLogin microsoft() { return microsoft; }
    MicrosoftLogin.Session microsoftSession(PlayerProfile profile) { return microsoftSessions.get(profile.id()); }
    void microsoftSession(PlayerProfile profile, MicrosoftLogin.Session session) { microsoftSessions.put(profile.id(), session); }

    boolean canLaunch(PlayerProfile profile) {
        return profile != null && (!profile.microsoft() || microsoftSession(profile) != null && !microsoftAppearanceBusy.get())
                && (!profile.thirdParty() || session(profile) != null);
    }

    void show() {
        var selected = selected();
        transition.show(selected == null ? profileList()
                : selected.microsoft() ? microsoftProfile(selected)
                : selected.thirdParty() ? thirdPartyProfile(selected) : profileView(selected));
        selectionChanged.run();
    }

    private Node profileList() {
        var rows = new VBox(3);
        for (var profile : profiles.profiles()) {
            var title = label(profile.name(), "card-title");
            title.setWrapText(false);
            title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            var type = label(profile.microsoft() ? "微软账户" : profile.thirdParty() ? "第三方账户" : "离线档案",
                    "help-row-description");
            type.setWrapText(false);
            type.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            var select = new Button();
            select.setAccessibleText(profile.name());
            select.getStyleClass().add("help-row");
            select.setGraphic(new HBox(10, avatar(profile, 30), new VBox(2, title, type)));
            select.setMaxWidth(Double.MAX_VALUE);
            select.setOnAction(event -> change(() -> profiles.select(profile)));
            var remove = item("删除档案", () -> delete(profile));
            select.setContextMenu(profile.thirdParty() || profile.microsoft() ? new ContextMenu(remove)
                    : new ContextMenu(item("更改 UUID", () -> editUuid(profile)), remove));
            rows.getChildren().add(select);
        }
        var list = new ScrollPane(rows);
        list.getStyleClass().add("page-scroll");
        list.setFitToWidth(true);
        list.setPrefViewportHeight(Math.min(300, profiles.profiles().size() * 52));
        list.setMaxHeight(300);
        list.setVisible(!profiles.profiles().isEmpty());
        list.managedProperty().bind(list.visibleProperty());
        var hint = label(profiles.profiles().isEmpty() ? "新建并选择一个档案以启动游戏" : "选择一个档案以启动游戏", "card-text");
        hint.setWrapText(true);
        var add = profileAction("新建档案", "lucide/user-plus", 17.25, this::create);
        HBox.setMargin(add, new Insets(0.5, 9, 0, 0));
        var addCard = new HBox(add);
        addCard.setMaxWidth(Region.USE_PREF_SIZE);
        addCard.getStyleClass().add("profile-create-actions");
        var addRow = new HBox(addCard);
        addRow.setAlignment(Pos.CENTER);
        // Mirror PageLoginProfile.xaml: hint, 10px gap, list, 10px gap, centred new-profile card, 20px bottom padding.
        var view = new VBox(hint, spacer(10), list, spacer(10), addRow, spacer(20));
        VBox.setMargin(hint, new Insets(10, 0, 0, 0));
        view.setAlignment(Pos.CENTER_LEFT);
        return view;
    }

    private static Region spacer(double height) {
        var spacer = new Region();
        spacer.setMinHeight(height);
        spacer.setPrefHeight(height);
        spacer.setMaxHeight(height);
        return spacer;
    }

    private void create() {
        PclDialogs.choose(transition.host(), "新建档案 - 选择验证类型", "离线",
                List.of("微软账户", "第三方账户", "离线")).ifPresent(type -> {
            switch (type) {
                case "离线" -> transition.show(offlineForm());
                case "微软账户" -> transition.show(microsoftForm(null));
                case "第三方账户" -> transition.show(thirdPartyForm(null));
            }
        });
    }

    private Node microsoftForm(PlayerProfile existing) {
        var signIn = button("使用微软账户登录", () -> {});
        signIn.getStyleClass().add("accent-button");
        var back = button("返回", this::show);
        signIn.setOnAction(event -> {
            if (!microsoft.configured()) {
                notifications.post("请先配置微软 Client ID（PCLNT_MICROSOFT_CLIENT_ID）。", Notifications.Kind.INFO);
                return;
            }
            signIn.setDisable(true);
            back.setDisable(true);
            background(microsoft::begin, device -> awaitMicrosoft(device, existing), () -> {
                signIn.setDisable(false);
                back.setDisable(false);
            }, "获取微软登录代码失败");
        });
        var form = new VBox(12, PclArtwork.accountAvatar(), signIn, back);
        form.setAlignment(Pos.CENTER);
        return form;
    }

    private void awaitMicrosoft(MicrosoftLogin.Device device, PlayerProfile existing) {
        var code = new TextField(device.userCode());
        code.setEditable(false);
        code.getStyleClass().add("account-input");
        var copy = button("复制代码", () -> {
            var contents = new javafx.scene.input.ClipboardContent();
            contents.putString(device.userCode());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(contents);
        });
        var browser = button("打开微软登录网页", () -> openPage.accept(MicrosoftLogin.VERIFICATION_PAGE));
        var instruction = PclDialogs.caption("在微软网页输入此代码，完成授权后返回启动器。");
        var body = new VBox(10, instruction, new HBox(8, code, copy), browser);
        var task = new Task<MicrosoftLogin.Session>() {
            @Override protected MicrosoftLogin.Session call() throws Exception { return microsoft.await(device); }
        };
        var dialog = PclDialogs.openForm(transition.host(), "微软账户登录", body,
                response -> task.cancel(), ButtonType.CANCEL);
        task.setOnSucceeded(event -> {
            if (dialog.close()) acceptMicrosoft(task.getValue(), existing);
        });
        task.setOnFailed(event -> {
            if (!dialog.close()) {
                return;
            }
            var failure = task.getException();
            if (failure instanceof MicrosoftLogin.ApprovalRequiredException approval) {
                var link = new javafx.scene.control.Hyperlink("打开 Minecraft App ID 审核页面");
                link.setOnAction(ignored -> openPage.accept(MicrosoftLogin.APP_REVIEW_PAGE));
                PclDialogs.openForm(transition.host(), "微软登录失败",
                        new VBox(10, PclDialogs.caption(approval.getMessage()), link),
                        response -> {}, ButtonType.OK);
                notifications.post("Mojang 尚未批准此 Azure 应用，无法完成微软登录。", Notifications.Kind.ERROR);
                return;
            }
            notifications.post("微软登录失败：" + VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private void acceptMicrosoft(MicrosoftLogin.Session logged, PlayerProfile existing) {
        if (existing != null && !existing.uuid().equalsIgnoreCase(logged.uuid())) {
            notifications.post("登录的微软账户与现有档案不符。", Notifications.Kind.ERROR);
            return;
        }
        var prior = existing == null ? profiles.profiles().stream().filter(profile -> profile.microsoft()
                && profile.uuid().equalsIgnoreCase(logged.uuid())).findFirst().orElse(null) : existing;
        var profile = new PlayerProfile(prior == null ? UUID.randomUUID() : prior.id(), logged.name(), logged.uuid(), "", true);
        change(() -> {
            profiles.putAndSelect(profile);
            microsoftSession(profile, logged);
            notifications.post("已登录 " + logged.name(), Notifications.Kind.SUCCESS);
        });
    }

    private Node microsoftProfile(PlayerProfile profile) {
        var edit = URI.create("https://www.minecraft.net/msaprofile/mygames/editprofile");
        var head = avatar(profile, 64);
        var appearance = new javafx.beans.property.SimpleObjectProperty<MicrosoftLogin.Appearance>();
        var working = microsoftAppearanceBusy;
        var upload = item("更改皮肤", () -> {});
        var refresh = item("刷新皮肤", () -> {});
        var cape = item("更改披风", () -> {});
        var save = item("保存皮肤", () -> {
            var bytes = appearance.get().skin();
            if (bytes == null) saveSkin(profile);
            else saveSkin(profile.name() + ".png", () -> new ByteArrayInputStream(bytes));
        });
        boolean loggedIn = microsoftSession(profile) != null;
        for (var action : new MenuItem[]{upload, refresh, cape}) {
            if (loggedIn) action.disableProperty().bind(working);
            else action.setDisable(true);
        }
        save.disableProperty().bind(working.or(appearance.isNull()));
        Runnable reload = () -> microsoftAppearanceAction(profile, working, microsoft::appearance,
                result -> applyMicrosoftAppearance(profile, head, appearance, result), "刷新皮肤失败");
        refresh.setOnAction(event -> reload.run());
        upload.setOnAction(event -> {
            var picker = new FileChooser();
            picker.setTitle("选择皮肤");
            picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
            var file = picker.showOpenDialog(transition.host().getScene().getWindow());
            if (file == null) return;
            PclDialogs.choose(transition.host(), "选择皮肤模型", "经典（Steve）",
                    List.of("经典（Steve）", "纤细（Alex）")).ifPresent(model -> microsoftAppearanceAction(profile, working, session -> {
                try (var input = Files.newInputStream(file.toPath())) {
                    microsoft.uploadSkin(session, input.readNBytes(1024 * 1024 + 1), model.startsWith("纤细"));
                }
                return null;
            }, result -> {
                notifications.post("已更改皮肤", Notifications.Kind.SUCCESS);
                reload.run();
            }, "更改皮肤失败"));
        });
        cape.setOnAction(event -> microsoftAppearanceAction(profile, working, microsoft::appearance, result -> {
            var choices = new java.util.ArrayList<MicrosoftLogin.Cape>();
            var none = new MicrosoftLogin.Cape(null, "不使用披风", false);
            choices.add(none);
            choices.addAll(result.capes());
            var active = result.capes().stream().filter(MicrosoftLogin.Cape::active).findFirst().orElse(none);
            PclDialogs.choose(transition.host(), "选择披风", active, choices).filter(choice -> !choice.equals(active)).ifPresent(choice ->
                    microsoftAppearanceAction(profile, working, session -> {
                        microsoft.selectCape(session, choice.id());
                        return null;
                    }, ignored -> notifications.post("已更改披风", Notifications.Kind.SUCCESS), "更改披风失败"));
        }, "读取披风失败"));
        var skin = new ContextMenu(upload, save, refresh, cape);
        var info = new ContextMenu(item("修改密码", () -> openPage.accept(URI.create("https://account.live.com/password/Change"))),
                item("修改玩家 ID", () -> openPage.accept(edit)));
        if (loggedIn) {
            var reauth = item("重新登录", () -> transition.show(microsoftForm(profile)));
            reauth.disableProperty().bind(working);
            info.getItems().addFirst(reauth);
        }
        var content = profileBodyParts(head, profile.name(), "微软账户", skin, info);
        if (!loggedIn) {
            var signIn = button("登录", () -> transition.show(microsoftForm(profile)));
            signIn.disableProperty().bind(working);
            VBox.setMargin(signIn, new Insets(8, 0, 0, 0));
            content.root().getChildren().add(signIn);
        }
        if (loggedIn) reload.run();
        return profilePage(content, skin, info);
    }

    @FunctionalInterface private interface MicrosoftAction<T> {
        T run(MicrosoftLogin.Session session) throws Exception;
    }

    private <T> void microsoftAppearanceAction(PlayerProfile profile, javafx.beans.property.BooleanProperty working,
                                               MicrosoftAction<T> operation, Consumer<T> complete, String failure) {
        var session = microsoftSession(profile);
        if (working.get() || session == null) return;
        if (gameRunning.getAsBoolean()) {
            notifications.post("请退出游戏后再处理皮肤与披风。", Notifications.Kind.INFO);
            return;
        }
        working.set(true);
        selectionChanged.run();
        var validated = new java.util.concurrent.atomic.AtomicReference<MicrosoftLogin.Session>();
        background(() -> {
            var current = microsoft.forLaunch(session);
            validated.set(current);
            return operation.run(current);
        }, complete, () -> {
            var current = validated.get();
            if (current != null && microsoftSession(profile) == session) microsoftSession(profile, current);
            working.set(false);
            selectionChanged.run();
        }, failure);
    }

    private void applyMicrosoftAppearance(PlayerProfile profile, Canvas head,
                                          javafx.beans.property.ObjectProperty<MicrosoftLogin.Appearance> appearance,
                                          MicrosoftLogin.Appearance result) {
        var image = result.skin() == null ? new Image(texture(profile).toExternalForm())
                : new Image(new ByteArrayInputStream(result.skin()));
        if (image.isError()) {
            notifications.post("服务器返回的皮肤无法读取。", Notifications.Kind.ERROR);
            return;
        }
        drawAvatar(head, image);
        appearance.set(result);
    }

    private Node thirdPartyProfile(PlayerProfile profile) {
        var head = avatar(profile, 64);
        var appearance = new javafx.beans.property.SimpleObjectProperty<Yggdrasil.Appearance>();
        var api = URI.create(profile.server());
        var refresh = item("刷新皮肤", () -> {});
        var save = item("保存皮肤", () -> {
            var current = appearance.get();
            if (current == null) return;
            if (current.skin() == null) saveSkin(profile);
            else saveSkin(profile.name() + ".png", () -> new ByteArrayInputStream(current.skin()));
        });
        var skin = new ContextMenu(item("更改皮肤", () -> openManagement(api, true)), save, refresh,
                item("更改披风", () -> openManagement(api, true)));
        var info = new ContextMenu(item("修改密码", () -> openManagement(api, false)),
                item("修改玩家 ID", () -> openManagement(api, false)));
        boolean loggedIn = session(profile) != null;
        if (loggedIn) {
            info.getItems().addFirst(item("重新登录", () -> transition.show(thirdPartyForm(profile))));
        }
        Runnable reload = () -> {
            refresh.setDisable(true);
            save.setDisable(true);
            background(() -> yggdrasil.appearance(api, profile.uuid()), result -> {
                if (result.skin() == null) {
                    drawAvatar(head, new Image(texture(profile).toExternalForm()));
                } else {
                    var image = new Image(new ByteArrayInputStream(result.skin()));
                    if (image.isError()) {
                        notifications.post("服务器返回的皮肤无法读取。", Notifications.Kind.ERROR);
                        return;
                    }
                    drawAvatar(head, image);
                }
                appearance.set(result);
                save.setDisable(false);
            }, () -> {
                refresh.setDisable(false);
                save.setDisable(appearance.get() == null);
            }, "刷新皮肤失败");
        };
        refresh.setOnAction(event -> reload.run());
        var content = profileBodyParts(head, profile.name(), "第三方账户 · " + api.getHost(), skin, info);
        if (!loggedIn) {
            var signIn = button("登录", () -> transition.show(thirdPartyForm(profile)));
            var actions = new HBox(signIn);
            actions.setAlignment(Pos.CENTER);
            VBox.setMargin(actions, new Insets(8, 0, 0, 0));
            content.root().getChildren().add(actions);
        }
        reload.run();
        return profilePage(content, skin, info);
    }

    private void openManagement(URI api, boolean wardrobe) {
        background(() -> yggdrasil.managementPage(api, wardrobe), openPage, () -> {}, "无法打开验证服务器页面");
    }

    private <T> void background(Callable<T> operation, Consumer<T> complete, Runnable ready, String failure) {
        var task = new Task<T>() {
            @Override protected T call() throws Exception { return operation.call(); }
        };
        task.setOnSucceeded(event -> {
            ready.run();
            complete.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            ready.run();
            notifications.post(failure + "：" + VanillaDownloadPage.message(task.getException()), Notifications.Kind.ERROR);
        });
        Thread.startVirtualThread(task);
    }

    private Node thirdPartyForm(PlayerProfile existing) {
        var server = new TextField(existing == null ? "" : existing.server());
        server.setPromptText("https://");
        server.setEditable(existing == null);
        var username = new TextField();
        var password = new PasswordField();
        for (var field : new TextField[]{server, username, password}) {
            field.getStyleClass().add("account-input");
        }
        var submit = button("登录", () -> {});
        submit.getStyleClass().add("accent-button");
        var back = button("返回", this::show);
        submit.setOnAction(event -> {
            java.net.URI api;
            try {
                api = Yggdrasil.server(server.getText());
            } catch (IllegalArgumentException error) {
                notifications.post("请输入完整的 HTTPS 验证服务器 API 地址", Notifications.Kind.ERROR);
                return;
            }
            String user = username.getText().strip();
            String secret = password.getText();
            if (user.isBlank() || secret.isEmpty()) {
                notifications.post("请输入账号和密码。", Notifications.Kind.ERROR);
                return;
            }
            password.clear();
            authenticate(submit, back, () -> yggdrasil.login(api, user, secret), logged -> {
                if (logged.selected() != null) {
                    acceptThirdParty(logged, existing);
                    return;
                }
                if (logged.characters().isEmpty()) {
                    notifications.post("此账号没有可用的 Minecraft 角色。", Notifications.Kind.ERROR);
                    return;
                }
                var available = logged.characters();
                if (existing != null) available = available.stream()
                        .filter(role -> role.id().equalsIgnoreCase(existing.uuid())).toList();
                if (available.isEmpty()) {
                    notifications.post("该账号没有此档案对应的角色。", Notifications.Kind.ERROR);
                    return;
                }
                PclDialogs.choose(transition.host(), "选择角色", available.getFirst(), available).ifPresent(role -> authenticate(submit, back,
                        () -> yggdrasil.choose(logged, role), result -> acceptThirdParty(result, existing)));
            });
        });
        password.setOnAction(event -> submit.fire());
        var actions = new HBox(10, back, submit);
        actions.setAlignment(Pos.CENTER);
        var form = new VBox(10, field("服务器", server), field("账号", username), field("密码", password), actions);
        form.setAlignment(Pos.CENTER_LEFT);
        return form;
    }

    private void authenticate(Button submit, Button back, Callable<Yggdrasil.Session> operation,
                              Consumer<Yggdrasil.Session> complete) {
        submit.setDisable(true);
        back.setDisable(true);
        background(operation, complete, () -> {
            submit.setDisable(false);
            back.setDisable(false);
        }, "第三方登录失败");
    }

    private void acceptThirdParty(Yggdrasil.Session logged, PlayerProfile existing) {
        var role = logged.selected();
        if (existing != null && !existing.uuid().equalsIgnoreCase(role.id())) {
            notifications.post("登录角色与现有档案不符。", Notifications.Kind.ERROR);
            return;
        }
        var prior = existing == null ? profiles.profiles().stream()
                .filter(profile -> profile.server().equals(logged.server().toString())
                        && profile.uuid().equalsIgnoreCase(role.id())).findFirst().orElse(null) : existing;
        var profile = new PlayerProfile(prior == null ? UUID.randomUUID() : prior.id(),
                role.name(), role.id(), logged.server().toString());
        change(() -> {
            profiles.putAndSelect(profile);
            session(profile, logged);
            notifications.post("已登录 " + role.name(), Notifications.Kind.SUCCESS);
        });
    }

    private Node offlineForm() {
        var name = new TextField();
        name.getStyleClass().add("account-input");
        name.setAccessibleText("玩家 ID");
        name.setPromptText("3 - 16 位英文字母、数字或下划线");
        var uuid = new TextField();
        uuid.getStyleClass().add("account-input");
        uuid.setAccessibleText("UUID");
        uuid.setPromptText("32 位十六进制字符串");
        var uuidRow = field("UUID", uuid);
        var group = new ToggleGroup();
        var choices = new HBox(11);
        for (var mode : UuidMode.values()) {
            var radio = new RadioButton(mode.toString());
            radio.setToggleGroup(group);
            radio.setUserData(mode);
            choices.getChildren().add(radio);
            radio.setSelected(mode == UuidMode.STANDARD);
        }
        uuidRow.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> group.getSelectedToggle() != null && group.getSelectedToggle().getUserData() == UuidMode.CUSTOM,
                group.selectedToggleProperty()));
        uuidRow.managedProperty().bind(uuidRow.visibleProperty());
        Runnable save = () -> {
            String username = name.getText().strip();
            if (!GameLaunch.validName(username)) {
                notifications.post("请填写 1–16 个字符的玩家 ID，且不要包含英文引号。", Notifications.Kind.ERROR);
                return;
            }
            if (!username.matches("[A-Za-z0-9_]{3,16}") && !confirm("玩家 ID 不符合规范",
                    "这个玩家 ID 可能导致部分游戏版本无法启动。是否继续新建档案？")) {
                return;
            }
            var mode = (UuidMode) group.getSelectedToggle().getUserData();
            String id = uuidFor(mode, username, uuid.getText());
            if (id == null) {
                return;
            }
            change(() -> {
                profiles.put(new PlayerProfile(UUID.randomUUID(), username, id));
                notifications.post("档案新建成功！", Notifications.Kind.SUCCESS);
            });
        };
        var create = button("新建", save);
        create.getStyleClass().add("accent-button");
        name.setOnAction(event -> save.run());
        var actions = new HBox(10, button("返回", this::show), create);
        actions.setAlignment(Pos.CENTER);
        var form = new VBox(10, field("玩家 ID", name), label("UUID 标准", "card-text"), choices, uuidRow, actions);
        form.setAlignment(Pos.CENTER_LEFT);
        return form;
    }

    private Node profileView(PlayerProfile profile) {
        var skin = new ContextMenu(
                item("更改皮肤", () -> unavailable("离线档案不支持更改在线皮肤。")),
                item("保存皮肤", () -> saveSkin(profile)),
                item("刷新皮肤", this::show),
                item("更改披风", () -> unavailable("离线档案不支持更改披风。")));
        var info = new ContextMenu(item("修改密码", () -> unavailable("离线档案没有密码。")),
                item("修改玩家 ID", () -> editName(profile)));
        var content = profileBodyParts(avatar(profile, 64), profile.name(), "离线档案", skin, info);
        return profilePage(content, skin, info);
    }

    private record ProfileBody(VBox root, HBox actions) {
    }

    private ProfileBody profileBodyParts(Canvas avatar, String name, String type, ContextMenu skin, ContextMenu info) {
        var skinButton = menuButton("皮肤与披风", "lucide/shirt", 16.2, skin);
        var infoButton = menuButton("修改信息", "lucide/pencil", 16.5, info);
        var select = profileAction("选择档案", "lucide/arrow-right-left", 17.25,
                () -> change(() -> profiles.select(null)));
        var actions = new HBox(9, skinButton, infoButton, select);
        actions.setAlignment(Pos.CENTER);
        actions.setMaxWidth(Region.USE_PREF_SIZE);
        actions.getStyleClass().add("profile-actions");
        HBox.setMargin(skinButton, new Insets(0.5, 0, 0, 0));
        var nameLabel = label(name, "profile-name");
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(232);
        var typeLabel = label(type, "help-row-description");
        typeLabel.setWrapText(false);
        typeLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        typeLabel.setMaxWidth(232);
        var body = new VBox(avatar, nameLabel, typeLabel, actions);
        body.setAlignment(Pos.CENTER);
        body.setFillWidth(false);
        body.setMaxWidth(Region.USE_PREF_SIZE);
        // Mirror PageLoginProfileSkin.xaml: 10px under the skin, 4px between name and type, then the
        // 5px type margin plus the card's 8px top margin.
        VBox.setMargin(avatar, new Insets(0, 0, 10, 0));
        VBox.setMargin(nameLabel, new Insets(0, 8, 0, 8));
        VBox.setMargin(typeLabel, new Insets(4, 8, 5, 8));
        VBox.setMargin(actions, new Insets(8, 0, 0, 0));
        return new ProfileBody(body, actions);
    }

    /**
     * Wraps the avatar/name/card block the way PageLoginProfileSkin.xaml does: an 80px empty row,
     * then the block, with the whole group vertically centred in the login area.
     */
    private Node profilePage(ProfileBody content, ContextMenu skin, ContextMenu info) {
        var group = new VBox(spacer(80), content.root());
        group.setAlignment(Pos.CENTER);
        group.setFillWidth(false);
        group.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        var page = new StackPane(group);
        page.setAlignment(Pos.CENTER);
        page.setPickOnBounds(true);
        page.hoverProperty().addListener((observable, old, hovered) -> fadeActions(page, content.actions(), skin, info));
        content.actions().focusWithinProperty()
                .addListener((observable, old, focused) -> fadeActions(page, content.actions(), skin, info));
        skin.setOnHidden(event -> fadeActions(page, content.actions(), skin, info));
        info.setOnHidden(event -> fadeActions(page, content.actions(), skin, info));
        content.actions().setOpacity(0);
        return page;
    }

    private javafx.animation.Timeline actionFade;

    private void fadeActions(Node hoverArea, HBox actions, ContextMenu skin, ContextMenu info) {
        if (actionFade != null) {
            actionFade.stop();
        }
        double end = hoverArea.isHover() || actions.isFocusWithin() || skin.isShowing() || info.isShowing() ? 1 : 0;
        actionFade = UiMotion.track(actions.opacityProperty(), end, 0, 120, javafx.animation.Interpolator.LINEAR);
        actionFade.play();
    }

    private static Button menuButton(String name, String icon, double size, ContextMenu menu) {
        var control = profileAction(name, icon, size, () -> {});
        control.setContextMenu(menu);
        control.setOnAction(event -> menu.show(control, javafx.geometry.Side.BOTTOM, 10, 18));
        return control;
    }

    private static Button profileAction(String name, String icon, double size, Runnable action) {
        var button = TaskMonitor.iconButton(name, icon, size, action);
        var tooltip = button.getTooltip();
        tooltip.setShowDelay(javafx.util.Duration.millis(50));
        tooltip.setOnShown(event -> {
            var bounds = button.localToScreen(button.getBoundsInLocal());
            if (bounds != null) {
                tooltip.setX(bounds.getCenterX() + 1 - tooltip.getWidth() / 2);
                tooltip.setY(bounds.getCenterY() + 35);
            }
        });
        return button;
    }

    private void editName(PlayerProfile profile) {
        input("输入新的玩家 ID", profile.name()).ifPresent(name -> {
            if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                notifications.post("玩家 ID 应为 3–16 位英文字母、数字或下划线。", Notifications.Kind.ERROR);
                return;
            }
            if (!name.equals(profile.name())) {
                change(() -> profiles.put(new PlayerProfile(profile.id(), name, PlayerProfile.standardUuid(name))));
            }
        });
    }

    private void editUuid(PlayerProfile profile) {
        PclDialogs.choose(transition.host(), "更改 UUID", UuidMode.STANDARD, List.of(UuidMode.values())).ifPresent(mode -> {
            String custom = mode == UuidMode.CUSTOM ? input("输入新的 UUID", profile.uuid()).orElse(null) : "";
            if (custom != null) {
                String id = uuidFor(mode, profile.name(), custom);
                if (id != null) {
                    change(() -> profiles.put(new PlayerProfile(profile.id(), profile.name(), id)));
                }
            }
        });
    }

    private String uuidFor(UuidMode mode, String name, String custom) {
        String id = switch (mode) {
            case STANDARD -> PlayerProfile.standardUuid(name);
            case LEGACY -> GameLaunch.offlineUuid(name);
            case CUSTOM -> custom.strip().replace("-", "");
        };
        if (id.matches("[a-fA-F0-9]{32}")) {
            return id;
        }
        notifications.post("UUID 应为 32 位十六进制字符串。", Notifications.Kind.ERROR);
        return null;
    }

    private java.util.Optional<String> input(String title, String value) {
        return PclDialogs.input(transition.host(), title, "", value, text -> true).map(String::strip);
    }

    private void delete(PlayerProfile profile) {
        if (confirm("删除档案确认", "确定删除档案 " + profile.name() + "？此操作无法撤销。")) {
            change(() -> {
                profiles.delete(profile);
                sessions.remove(profile.id());
                microsoftSessions.remove(profile.id());
            });
        }
    }

    private boolean confirm(String title, String message) {
        return PclDialogs.confirm(transition.host(), title, message);
    }

    private void change(Change change) {
        try {
            change.save();
            show();
        } catch (IOException failure) {
            notifications.post("无法保存档案：" + VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
        }
    }

    private void unavailable(String message) {
        notifications.post(message, Notifications.Kind.INFO);
    }

    private static HBox field(String title, TextField input) {
        var caption = label(title, "card-text");
        caption.setMinWidth(50);
        HBox.setHgrow(input, Priority.ALWAYS);
        var row = new HBox(caption, input);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static MenuItem item(String title, Runnable action) {
        var item = new MenuItem(title);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static java.net.URL texture(PlayerProfile profile) {
        return AccountPane.class.getResource("skins/" + profile.skinVariant() + ".png");
    }

    private static Canvas avatar(PlayerProfile profile, double size) {
        var canvas = new Canvas(size, size);
        drawAvatar(canvas, new Image(texture(profile).toExternalForm()));
        return canvas;
    }

    private static void drawAvatar(Canvas canvas, Image skin) {
        double size = canvas.getWidth();
        double scale = skin.getWidth() / 64;
        var graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, size, size);
        graphics.setImageSmoothing(false);
        graphics.drawImage(skin, 8 * scale, 8 * scale, 8 * scale, 8 * scale,
                size / 8, size / 8, size * 3 / 4, size * 3 / 4);
        graphics.drawImage(skin, 40 * scale, 8 * scale, 8 * scale, 8 * scale,
                size / 16, size / 16, size * 7 / 8, size * 7 / 8);
    }

    private void saveSkin(PlayerProfile profile) {
        saveSkin(profile.skinVariant() + ".png", () -> texture(profile).openStream());
    }

    @FunctionalInterface private interface SkinSource {
        java.io.InputStream open() throws IOException;
    }

    private void saveSkin(String fileName, SkinSource source) {
        var picker = new FileChooser();
        picker.setTitle("保存皮肤");
        picker.setInitialFileName(fileName.replaceAll("[<>:\"/\\\\|?*]", "_"));
        picker.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
        var output = picker.showSaveDialog(transition.host().getScene().getWindow());
        if (output == null) {
            return;
        }
        try (var stream = source.open()) {
            Files.copy(stream, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            notifications.post("皮肤已保存", Notifications.Kind.SUCCESS);
        } catch (IOException failure) {
            notifications.post("保存皮肤失败：" + VanillaDownloadPage.message(failure), Notifications.Kind.ERROR);
        }
    }
}

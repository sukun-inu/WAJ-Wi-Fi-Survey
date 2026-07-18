package com.opensitesurvey.tool;

import com.opensitesurvey.tool.alert.AlertContext;
import com.opensitesurvey.tool.alert.AlertEngine;
import com.opensitesurvey.tool.alert.AlertSeverity;
import com.opensitesurvey.tool.alert.AlertsView;
import com.opensitesurvey.tool.alert.NotificationService;
import com.opensitesurvey.tool.alert.SettingsDialog;
import com.opensitesurvey.tool.api.ApiServer;
import com.opensitesurvey.tool.model.ScanSnapshot;
import com.opensitesurvey.tool.persistence.AppConfig;
import com.opensitesurvey.tool.persistence.AppConfigStore;
import com.opensitesurvey.tool.persistence.AppPaths;
import com.opensitesurvey.tool.persistence.ScanLogDatabase;
import com.opensitesurvey.tool.plugin.PluginManager;
import com.opensitesurvey.tool.channel.ChannelPlanningView;
import com.opensitesurvey.tool.security.SecurityAuditView;
import com.opensitesurvey.tool.ui.dashboard.DashboardView;
import com.opensitesurvey.tool.ui.history.HistoryView;
import com.opensitesurvey.tool.ui.ping.TracerouteView;
import com.opensitesurvey.tool.ui.survey.SurveyView;
import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.util.AppTheme;
import com.opensitesurvey.tool.util.CategoricalColorPalette;
import com.opensitesurvey.tool.util.TooltipSupport;
import com.opensitesurvey.tool.wlan.WlanInterface;
import com.opensitesurvey.tool.wlan.WlanPoller;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class App extends Application {

    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 2_000;

    private WlanPoller poller;
    private ScanLogDatabase scanLogDatabase;
    private AppConfig appConfig;
    private ApiServer apiServer;
    private PluginManager pluginManager;
    private final java.util.concurrent.atomic.AtomicReference<ScanSnapshot> latestSnapshotRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Override
    public void start(Stage primaryStage) {
        appConfig = AppConfigStore.load();
        // Must happen before any view is constructed - every view builds its labels/columns/menus
        // once, at construction time, by calling Messages.get(...). Locale.setDefault() is also
        // set here (not just Messages.setLocale()) so JavaFX's own built-in strings - Alert's
        // default OK/Cancel captions, FileChooser's native labels - follow the same choice instead
        // of silently staying in the OS/JVM default language while the app's own labels switch.
        Locale locale = "en".equals(appConfig.language) ? Locale.ENGLISH : Locale.JAPANESE;
        Messages.setLocale(locale);
        Locale.setDefault(locale);
        try {
            scanLogDatabase = ScanLogDatabase.open(AppPaths.scanLogDbFile());
        } catch (Exception e) {
            scanLogDatabase = null; // long-term logging is a nice-to-have; degrade gracefully
        }
        final java.util.concurrent.atomic.AtomicLong lastDbLogMillis = new java.util.concurrent.atomic.AtomicLong(0);

        AlertContext alertContext = new AlertContext(appConfig);
        AlertEngine alertEngine = new AlertEngine(alertContext);
        NotificationService notificationService = new NotificationService();
        pluginManager = PluginManager.load(AppPaths.pluginsDir().toPath());

        // Shared across every tab so the same BSSID always gets the same identity color,
        // regardless of which view first happened to draw it - a per-view instance would let the
        // same AP end up a different color on the Dashboard vs. Security Audit vs. Channel
        // Planning, undermining the whole point of a categorical (not strength-based) color.
        CategoricalColorPalette colorPalette = new CategoricalColorPalette();

        DashboardView dashboard = new DashboardView(alertContext.trustedApRegistry, colorPalette);
        SurveyView survey = new SurveyView(appConfig.defaultPingHost, () -> dashboard.getInterfaceLabel().getText());
        SecurityAuditView securityAudit = new SecurityAuditView(alertContext.trustedApRegistry, colorPalette);
        ChannelPlanningView channelPlanning = new ChannelPlanningView(colorPalette);
        AlertsView alertsView = new AlertsView(() -> SettingsDialog.show(
                primaryStage, appConfig, alertContext.trustedApRegistry, () -> { }));
        HistoryView historyView = new HistoryView(scanLogDatabase, colorPalette);
        historyView.refreshBssidList();
        TracerouteView tracerouteView = new TracerouteView(colorPalette);

        // Left navigation rail + per-screen page header, replacing the previous TabPane - every
        // screen is wrapped identically (icon + title + one-line description at the top) so the
        // operator always sees "where am I / what does this screen do", in the same spirit as an
        // enterprise network-management console (Juniper Mist / HPE Aruba Central / JunOS-class
        // clarity) rather than a bare tab strip.
        // Icons are plain (non-emoji-presentation) Unicode symbols, not color emoji - full-color
        // emoji glyphs (the original 📡🗺🛡... set) are baked-in COLR/bitmap graphics that ignore
        // -fx-text-fill entirely, so they couldn't be tied into this app's own module-identity
        // palette and sat oddly next to the otherwise disciplined dark theme. Each module's accent
        // reuses a hex already established elsewhere in this codebase (not a new invented color):
        // Dashboard=the app's own accent blue, Site Survey=the coverage-good green, Security=the
        // existing HIGH-risk red, Channel Planning=the congestion-curve amber, Alerts=the WARNING
        // severity yellow, History/Traceroute=two hues already in CategoricalColorPalette.
        record NavEntry(String icon, String iconColor, String titleKey, String tooltipKey, Node content, Label chip) {
        }
        // Each page header's right-aligned status chip mirrors a Label the screen already
        // maintains for its own purposes (SecurityAuditView's risk summary, ChannelPlanningView's
        // recommendation, etc.) via textProperty().bind() - no new counting/summarizing logic is
        // duplicated here, so the chip can never drift out of sync with the screen's own numbers.
        // Dashboard has no such existing summary Label (its interface/last-scan labels already live
        // in the chassis footer), so its chip is the one genuinely new piece of text, updated
        // alongside the poller's other per-snapshot UI work further below.
        Label dashboardChip = new Label();
        dashboardChip.getStyleClass().add("page-header-chip");
        Label surveyChip = new Label();
        surveyChip.getStyleClass().add("page-header-chip");
        surveyChip.textProperty().bind(survey.getStatusLabel().textProperty());
        Label securityChip = new Label();
        securityChip.getStyleClass().add("page-header-chip");
        securityChip.textProperty().bind(securityAudit.getSummaryLabel().textProperty());
        Label channelChip = new Label();
        channelChip.getStyleClass().add("page-header-chip");
        channelChip.textProperty().bind(channelPlanning.getRecommendationLabel().textProperty());
        Label alertsChip = new Label();
        alertsChip.getStyleClass().add("page-header-chip");
        alertsChip.textProperty().bind(alertsView.getCountLabel().textProperty());
        Label historyChip = new Label();
        historyChip.getStyleClass().add("page-header-chip");
        historyChip.textProperty().bind(historyView.getStatusLabel().textProperty());
        Label tracerouteChip = new Label();
        tracerouteChip.getStyleClass().add("page-header-chip");
        tracerouteChip.textProperty().bind(tracerouteView.getStatusLabel().textProperty());

        List<NavEntry> navEntries = List.of(
                new NavEntry("◉", "#4aa3df", "app.tab.dashboard", "tooltip.tab.dashboard", dashboard.getRoot(), dashboardChip),
                new NavEntry("⛶", "#3ddc73", "app.tab.siteSurvey", "tooltip.tab.siteSurvey", survey.getRoot(), surveyChip),
                new NavEntry("⛨", "#e74c3c", "app.tab.securityAudit", "tooltip.tab.securityAudit", securityAudit.getRoot(), securityChip),
                new NavEntry("▤", "#f5a623", "app.tab.channelPlanning", "tooltip.tab.channelPlanning", channelPlanning.getRoot(), channelChip),
                new NavEntry("☡", "#f1c40f", "app.tab.alerts", "tooltip.tab.alerts", alertsView.getRoot(), alertsChip),
                new NavEntry("◷", "#9b7fd4", "app.tab.history", "tooltip.tab.history", historyView.getRoot(), historyChip),
                new NavEntry("⛓", "#4cbfc0", "app.tab.traceroute", "tooltip.tab.traceroute", tracerouteView.getRoot(), tracerouteChip)
        );

        ToggleGroup navGroup = new ToggleGroup();
        VBox navRail = new VBox();
        navRail.getStyleClass().add("nav-rail");
        StackPane contentStack = new StackPane();
        // Some screens' own content (e.g. Site Survey's floor-plan canvas) can demand more
        // vertical space than the window currently offers - a plain StackPane/BorderPane doesn't
        // clip overflow by default, which would otherwise bleed visually over the nav rail/footer
        // status bar below. Clipping here (once, for every screen) guarantees that can never
        // happen, the same guarantee the previous TabPane's content area provided implicitly.
        Rectangle contentClip = new Rectangle();
        contentClip.widthProperty().bind(contentStack.widthProperty());
        contentClip.heightProperty().bind(contentStack.heightProperty());
        contentStack.setClip(contentClip);
        // A StackPane's computed min size is the max of its children's min sizes by default, and
        // BorderPane honors center's min size even when that exceeds the space actually available
        // (growing center - and pushing "bottom" past the window's visible area entirely - rather
        // than shrinking it) - observed firsthand: at the default window size, Site Survey's own
        // min-height content (before a floor plan is loaded) pushed the chassis status bar clean
        // off the bottom of the window. Pinning min size to 0 tells BorderPane it's always free to
        // shrink this region to fit; the clip above then cleanly crops whichever screen's content
        // doesn't fit, instead of the window losing its footer.
        contentStack.setMinHeight(0);
        contentStack.setMinWidth(0);
        List<ToggleButton> navButtons = new ArrayList<>();
        for (NavEntry entry : navEntries) {
            ToggleButton button = buildNavButton(entry.icon(), entry.titleKey(), entry.tooltipKey(), navGroup);
            VBox page = wrapWithPageHeader(entry.icon(), entry.iconColor(), entry.titleKey(), entry.tooltipKey(), entry.content(), entry.chip());
            button.setUserData(page);
            navButtons.add(button);
            navRail.getChildren().add(button);
            contentStack.getChildren().add(page);
            // If a screen's content is taller than the space available, a StackPane would
            // otherwise center the oversized child - clipping equally off both the top (slicing
            // into the page header) and bottom. Anchoring to the top keeps the header always
            // intact and lets only the bottom run out of room instead, which reads as "this
            // window is a bit short" rather than "the title got cut off".
            StackPane.setAlignment(page, Pos.TOP_LEFT);
        }
        navGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                // A ToggleGroup normally allows clicking the already-selected button to deselect
                // it, which would leave no screen visible at all - re-select the previous one
                // instead so exactly one screen is always showing.
                if (oldToggle != null) {
                    navGroup.selectToggle(oldToggle);
                }
                return;
            }
            Node selectedPage = (Node) newToggle.getUserData();
            for (Node child : contentStack.getChildren()) {
                boolean match = child == selectedPage;
                child.setVisible(match);
                child.setManaged(match);
            }
        });
        navButtons.get(0).setSelected(true);

        Label settingsNavIcon = new Label("⚙");
        settingsNavIcon.getStyleClass().add("nav-item-icon");
        Button settingsNavButton = new Button(Messages.get("app.nav.settings"));
        settingsNavButton.setGraphic(settingsNavIcon);
        settingsNavButton.getStyleClass().add("nav-item");
        settingsNavButton.setMaxWidth(Double.MAX_VALUE);
        TooltipSupport.set(settingsNavButton, Messages.get("tooltip.alerts.settings"));
        settingsNavButton.setOnAction(e -> SettingsDialog.show(
                primaryStage, appConfig, alertContext.trustedApRegistry, () -> { }));
        Region navSpacer = new Region();
        VBox.setVgrow(navSpacer, Priority.ALWAYS);
        Separator navSeparator = new Separator();
        navSeparator.getStyleClass().add("nav-rail-separator");
        navRail.getChildren().addAll(navSpacer, navSeparator, settingsNavButton);

        // Cumulative session alert-count badge (not a live "active alarm" state - this app only
        // observes point-in-time fired alert events, not standing device conditions, so the badge
        // is deliberately worded/tooltipped as a session tally rather than implying otherwise).
        // Lives in the chassis footer alongside the other status indicators (link LED, REST API,
        // plugin count) - not in its own separate brand row, which the app's own OS-level title
        // bar/icon already made redundant.
        Label alarmBadge = new Label(Messages.get("app.footer.alerts", 0));
        alarmBadge.getStyleClass().addAll("alarm-badge", "alarm-badge-idle");
        TooltipSupport.set(alarmBadge, Messages.get("app.alarm.tooltip"));
        alarmBadge.setOnMouseClicked(e -> navButtons.get(4).setSelected(true));

        Label accessDeniedBanner = new Label();
        // #c0392b (not the RiskColors-family #e74c3c used elsewhere) - white text on plain
        // #e74c3c measured ~3.8:1 in this session's contrast audit, under the WCAG AA 4.5:1
        // minimum for text this size; this darker red reaches ~5.4:1 with the same white text.
        accessDeniedBanner.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white; -fx-padding: 6;");
        accessDeniedBanner.setMaxWidth(Double.MAX_VALUE);
        accessDeniedBanner.setVisible(false);
        accessDeniedBanner.setManaged(false);
        TooltipSupport.set(accessDeniedBanner, Messages.get("tooltip.app.locationDeniedBanner"));

        Button openSettingsButton = new Button(Messages.get("common.button.openLocationSettings"));
        openSettingsButton.setOnAction(e -> openLocationSettings());
        openSettingsButton.setVisible(false);
        openSettingsButton.setManaged(false);
        TooltipSupport.set(openSettingsButton, Messages.get("tooltip.app.openLocationSettings"));

        ComboBox<WlanInterface> interfaceSelector = new ComboBox<>();
        interfaceSelector.setPrefWidth(320);
        interfaceSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(WlanInterface iface) {
                return iface == null ? "" : iface.description() + " [" + iface.stateLabel() + "]";
            }

            @Override
            public WlanInterface fromString(String s) {
                return null;
            }
        });
        TooltipSupport.set(interfaceSelector, Messages.get("tooltip.app.interfaceSelector"));

        Button refreshInterfacesButton = new Button(Messages.get("app.button.rescan"));
        TooltipSupport.set(refreshInterfacesButton, Messages.get("tooltip.app.refreshInterfaces"));
        TooltipSupport.set(dashboard.getInterfaceLabel(), Messages.get("tooltip.app.currentInterface"));
        TooltipSupport.set(dashboard.getLastScanLabel(), Messages.get("tooltip.app.lastScan"));

        // Persistent bottom "chassis status line" - link-state LED + interface identity always
        // visible regardless of which screen is open, mirroring the always-on status line a JunOS
        // CLI prompt or a managed switch's front-panel LEDs provide.
        Circle linkLed = new Circle(4.5);
        linkLed.getStyleClass().addAll("status-led", "status-led-idle");
        TooltipSupport.set(linkLed, Messages.get("tooltip.app.linkLed"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Right-hand "subsystem health" section of the chassis bar - REST API and plugin-loader
        // state, the same idea as a managed switch's front-panel LEDs for its optional modules.
        // Plugin count is already known at this point (PluginManager.load() ran during App startup,
        // above); the REST API chip's real on/off/error text is filled in once the actual bind
        // attempt happens further below - it starts as just "restApiEnabled ? on : off" so it's
        // never blank in between.
        Label pluginChip = new Label(Messages.get("app.footer.plugins", pluginManager.loadedPluginNames().size()));
        TooltipSupport.set(pluginChip, Messages.get("tooltip.app.pluginsFooter"));
        Label restApiChip = new Label(appConfig.restApiEnabled
                ? Messages.get("app.footer.restApiOn", appConfig.restApiPort)
                : Messages.get("app.footer.restApiOff"));
        TooltipSupport.set(restApiChip, Messages.get("tooltip.app.restApiFooter"));

        HBox statusBar = new HBox(10,
                linkLed,
                dashboard.getInterfaceLabel(), dashboard.getLastScanLabel(),
                new Separator(Orientation.VERTICAL),
                new Label(Messages.get("app.label.interfaceCaption")), interfaceSelector, refreshInterfacesButton,
                spacer,
                alarmBadge, new Separator(Orientation.VERTICAL), restApiChip, new Separator(Orientation.VERTICAL), pluginChip);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.getStyleClass().add("status-footer");
        // Found during this session's resize-resilience regression pass: at a narrow window width
        // (after adding the REST API/plugin chips in this same round), the footer's content no
        // longer all fits and - since a plain HBox doesn't clip overflow any more than BorderPane's
        // center did earlier this session - the rightmost labels got raggedly cut off mid-character
        // at the window edge instead of degrading cleanly. Clipping here guarantees the footer can
        // never visually bleed past the window boundary, the same guarantee already applied to
        // contentStack above.
        Rectangle statusBarClip = new Rectangle();
        statusBarClip.widthProperty().bind(statusBar.widthProperty());
        statusBarClip.heightProperty().bind(statusBar.heightProperty());
        statusBar.setClip(statusBarClip);

        MenuBar menuBar = buildMenuBar(primaryStage, appConfig, alertContext.trustedApRegistry);

        BorderPane root = new BorderPane();
        VBox top = new VBox(menuBar, accessDeniedBanner);
        root.setTop(top);
        root.setLeft(navRail);
        root.setCenter(contentStack);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1440, 920);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

        // Ctrl+1..Ctrl+7 jump directly to each screen - a quick, keyboard-driven navigation path
        // alongside mouse clicks on the nav rail (see tooltip.app.navShortcut).
        KeyCode[] navShortcutKeys = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4,
                KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7};
        for (int i = 0; i < navButtons.size() && i < navShortcutKeys.length; i++) {
            ToggleButton target = navButtons.get(i);
            scene.getAccelerators().put(
                    new KeyCodeCombination(navShortcutKeys[i], KeyCombination.CONTROL_DOWN),
                    () -> target.setSelected(true));
        }

        primaryStage.getIcons().addAll(
                new Image(getClass().getResourceAsStream("/icons/app-16.png")),
                new Image(getClass().getResourceAsStream("/icons/app-32.png")),
                new Image(getClass().getResourceAsStream("/icons/app-48.png")),
                new Image(getClass().getResourceAsStream("/icons/app-128.png")),
                new Image(getClass().getResourceAsStream("/icons/app-256.png")));
        primaryStage.setTitle(Messages.get("app.title"));
        primaryStage.setScene(scene);
        primaryStage.show();

        // Cumulative session alert tally backing the brand bar's alarm badge - [0]=critical
        // count, [1]=warning count. See the badge's own javadoc note above on why this is a
        // session tally, not a live "active alarm" state.
        final int[] alarmCounts = {0, 0};

        poller = new WlanPoller(
                DEFAULT_POLL_INTERVAL_MILLIS,
                snapshot -> {
                    // ScanSnapshot/ApSnapshot are immutable records, so publishing the latest one
                    // here (the poller's own background thread) is safe for the REST API's HTTP
                    // handler threads to read via latestSnapshotRef.get() without any further
                    // synchronization or waiting on the JavaFX Application thread.
                    latestSnapshotRef.set(snapshot);
                    // Runs on the poller's background thread - do the (possibly blocking) DB
                    // write here rather than inside Platform.runLater, so disk I/O never stalls
                    // the JavaFX Application thread. Throttled independent of the UI poll
                    // interval so a 0.1s dashboard refresh doesn't imply 10 inserts/sec.
                    if (scanLogDatabase != null) {
                        long now = System.currentTimeMillis();
                        long last = lastDbLogMillis.get();
                        if (now - last >= appConfig.scanLogIntervalMillis && lastDbLogMillis.compareAndSet(last, now)) {
                            try {
                                scanLogDatabase.insertScanSamples(snapshot);
                            } catch (Exception ignored) {
                                // best-effort logging; a DB hiccup must not take down the poller
                            }
                        }
                    }
                    // Alert evaluation is pure in-memory computation - cheap enough to run on
                    // this background thread too, and TrayIcon notifications are safe to fire
                    // from any thread.
                    java.util.List<com.opensitesurvey.tool.alert.Alert> firedAlerts = alertEngine.onSnapshot(snapshot);
                    for (com.opensitesurvey.tool.alert.Alert fired : firedAlerts) {
                        notificationService.notify(fired, appConfig.windowsNotificationsEnabled);
                    }
                    // Same background thread as AlertEngine above - see OpenSiteSurveyPlugin's own
                    // javadoc for why plugins must not block here.
                    pluginManager.dispatchSnapshot(snapshot);

                    Platform.runLater(() -> {
                        dashboardChip.setText(Messages.get("dashboard.chip.apCount", snapshot.accessPoints().size()));
                        dashboard.onSnapshot(snapshot);
                        survey.onSnapshot(snapshot);
                        securityAudit.onSnapshot(snapshot);
                        channelPlanning.onSnapshot(snapshot);
                        alertsView.addAlerts(firedAlerts);
                        for (com.opensitesurvey.tool.alert.Alert fired : firedAlerts) {
                            if (fired.severity() == AlertSeverity.CRITICAL) {
                                alarmCounts[0]++;
                            } else if (fired.severity() == AlertSeverity.WARNING) {
                                alarmCounts[1]++;
                            }
                        }
                        if (!firedAlerts.isEmpty()) {
                            updateAlarmBadge(alarmBadge, alarmCounts[0], alarmCounts[1]);
                        }
                        setLed(linkLed, "status-led-ok");
                    });
                },
                interfaces -> Platform.runLater(() -> {
                    // Matched by description, not equals() - WlanInterface wraps a JNA GUID
                    // structure whose equals() is not guaranteed to do value comparison, so a
                    // freshly re-listed interface for the same physical adapter may not be
                    // "equal" to the previously selected instance even though it is the same NIC.
                    WlanInterface previouslySelected = interfaceSelector.getValue();
                    interfaceSelector.getItems().setAll(interfaces);
                    WlanInterface toSelect = interfaces.stream()
                            .filter(i -> previouslySelected != null && i.description().equals(previouslySelected.description()))
                            .findFirst()
                            .orElse(interfaces.isEmpty() ? null : interfaces.get(0));
                    if (toSelect != null) {
                        interfaceSelector.setValue(toSelect);
                    }
                }),
                iface -> Platform.runLater(() -> {
                    dashboard.getInterfaceLabel()
                            .setText(Messages.get("app.label.interfaceValue", iface.description(), iface.stateLabel()));
                    WlanInterface current = interfaceSelector.getValue();
                    if (current == null || !current.description().equals(iface.description())) {
                        interfaceSelector.setValue(iface);
                    }
                    // stateCode 1 = connected (see WlanInterface#stateLabel) - anything else
                    // (associating/authenticating/discovering/disconnected/...) is a transitional
                    // or degraded state, so the chassis LED reads amber rather than green.
                    setLed(linkLed, iface.stateCode() == 1 ? "status-led-ok" : "status-led-warning");
                }),
                accessDenied -> Platform.runLater(() -> {
                    accessDeniedBanner.setText(Messages.get("app.banner.locationAccessDenied", accessDenied.getMessage()));
                    accessDeniedBanner.setVisible(true);
                    accessDeniedBanner.setManaged(true);
                    openSettingsButton.setVisible(true);
                    openSettingsButton.setManaged(true);
                    if (!statusBar.getChildren().contains(openSettingsButton)) {
                        statusBar.getChildren().add(statusBar.getChildren().indexOf(spacer), openSettingsButton);
                    }
                    setLed(linkLed, "status-led-critical");
                }),
                error -> Platform.runLater(() -> {
                    dashboard.getInterfaceLabel().setText(Messages.get("common.error.prefix", error.getMessage()));
                    setLed(linkLed, "status-led-critical");
                })
        );

        interfaceSelector.setOnAction(e -> {
            WlanInterface selected = interfaceSelector.getValue();
            if (selected != null) {
                poller.selectInterface(selected);
                dashboard.getInterfaceLabel().setText(
                        Messages.get("app.label.interfaceValue", selected.description(), selected.stateLabel()));
            }
        });
        refreshInterfacesButton.setOnAction(e -> poller.refreshInterfaces());

        poller.start();

        if (appConfig.restApiEnabled) {
            try {
                apiServer = ApiServer.start(appConfig.restApiPort, latestSnapshotRef::get,
                        survey::snapshotPoints, survey::snapshotApPositionEstimates);
                restApiChip.setStyle("-fx-text-fill: #2ecc71;");
            } catch (Exception e) {
                // Most likely cause: the configured port is already in use by another process -
                // not worth blocking the whole app over, so degrade to "API not running" instead.
                dashboard.getInterfaceLabel().setText(
                        Messages.get("common.error.prefix", "REST API: " + e.getMessage()));
                restApiChip.setText(Messages.get("app.footer.restApiError"));
                restApiChip.setStyle("-fx-text-fill: #e74c3c;");
            }
        }

        primaryStage.setOnCloseRequest(e -> {
            poller.stop();
            tracerouteView.shutdown();
            survey.shutdownGps();
            if (apiServer != null) {
                apiServer.stop();
            }
            pluginManager.close();
        });
    }

    /** One left-nav-rail entry: an icon-labeled toggle button, styled/tooltipped consistently. */
    private static ToggleButton buildNavButton(String icon, String titleKey, String tooltipKey, ToggleGroup group) {
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("nav-item-icon");
        ToggleButton button = new ToggleButton(Messages.get(titleKey));
        button.setGraphic(iconLabel);
        button.getStyleClass().add("nav-item");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setToggleGroup(group);
        TooltipSupport.set(button, Messages.get(tooltipKey));
        return button;
    }

    /**
     * Wraps a screen's own root content with the shared page header (icon + title + one-line
     * description) every screen in the app shows, so "where am I / what does this screen do" is
     * answered identically everywhere rather than only in a tab's tooltip.
     */
    private static VBox wrapWithPageHeader(String icon, String iconColor, String titleKey, String descriptionKey,
            Node content, Label chip) {
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("page-header-icon");
        iconLabel.setStyle("-fx-text-fill: " + iconColor + ";");
        Label titleLabel = new Label(Messages.get(titleKey));
        titleLabel.getStyleClass().add("page-header-title");
        Label subtitleLabel = new Label(Messages.get(descriptionKey));
        subtitleLabel.getStyleClass().add("page-header-subtitle");
        VBox titleBox = new VBox(1, titleLabel, subtitleLabel);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        // Several screens' source Label starts out blank (e.g. History's search-result status,
        // before a first search) - an empty chip would just be a floating gray pill with nothing
        // in it, reading as a rendering glitch rather than an intentional empty state. Collapsing
        // it out of layout whenever its bound text is empty keeps every screen's header honest.
        chip.managedProperty().bind(Bindings.isNotEmpty(chip.textProperty()));
        chip.visibleProperty().bind(Bindings.isNotEmpty(chip.textProperty()));
        HBox header = new HBox(10, iconLabel, titleBox, headerSpacer, chip);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("page-header");

        VBox page = new VBox(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        page.setVisible(false);
        page.setManaged(false);
        return page;
    }

    /** Swaps the chassis status LED's severity style class, keeping exactly one applied at a time. */
    private static void setLed(Circle led, String severityStyleClass) {
        led.getStyleClass().removeAll("status-led-ok", "status-led-warning", "status-led-critical", "status-led-idle");
        led.getStyleClass().add(severityStyleClass);
    }

    /**
     * Recolors/relabels the chassis footer's cumulative session alert-count chip. Styled as plain
     * colored text (see .alarm-badge* in style.css), the same "text color carries the state"
     * convention as its neighboring REST API chip - not a filled pill, which would be the only
     * such shape in an otherwise plain-text footer.
     */
    private static void updateAlarmBadge(Label badge, int criticalCount, int warningCount) {
        badge.getStyleClass().removeAll("alarm-badge-critical", "alarm-badge-warning", "alarm-badge-idle");
        badge.setText(Messages.get("app.footer.alerts", criticalCount + warningCount));
        if (criticalCount > 0) {
            badge.getStyleClass().add("alarm-badge-critical");
        } else if (warningCount > 0) {
            badge.getStyleClass().add("alarm-badge-warning");
        } else {
            badge.getStyleClass().add("alarm-badge-idle");
        }
    }

    private MenuBar buildMenuBar(Stage primaryStage, AppConfig appConfig,
                                  com.opensitesurvey.tool.alert.TrustedApRegistry trustedApRegistry) {
        Menu fileMenu = new Menu(Messages.get("app.menu.file"));
        MenuItem exitItem = new MenuItem(Messages.get("app.menu.file.exit"));
        exitItem.setOnAction(e -> primaryStage.close());
        fileMenu.getItems().add(exitItem);

        Menu toolsMenu = new Menu(Messages.get("app.menu.tools"));
        MenuItem settingsItem = new MenuItem(Messages.get("common.button.settingsDialog"));
        settingsItem.setOnAction(e -> SettingsDialog.show(primaryStage, appConfig, trustedApRegistry, () -> { }));
        MenuItem locationSettingsItem = new MenuItem(Messages.get("common.button.openLocationSettings"));
        locationSettingsItem.setOnAction(e -> openLocationSettings());
        toolsMenu.getItems().addAll(settingsItem, locationSettingsItem);

        Menu helpMenu = new Menu(Messages.get("app.menu.help"));
        MenuItem aboutItem = new MenuItem(Messages.get("app.menu.help.about"));
        aboutItem.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, Messages.get("app.about.body"));
            alert.setTitle(Messages.get("app.menu.help.about"));
            alert.setHeaderText(null);
            ButtonType licenseButtonType = new ButtonType(Messages.get("common.button.aboutLicenses"), ButtonBar.ButtonData.LEFT);
            alert.getButtonTypes().add(licenseButtonType);
            AppTheme.apply(alert);
            Button licenseButton = (Button) alert.getDialogPane().lookupButton(licenseButtonType);
            licenseButton.addEventFilter(ActionEvent.ACTION, event -> {
                event.consume();
                LicenseDialog.show(primaryStage);
            });
            alert.showAndWait();
        });
        MenuItem pluginsItem = new MenuItem(Messages.get("app.menu.help.plugins"));
        pluginsItem.setOnAction(e -> {
            java.util.List<String> names = pluginManager.loadedPluginNames();
            String body = names.isEmpty()
                    ? Messages.get("app.plugins.none")
                    : Messages.get("app.plugins.list", String.join("\n", names));
            Alert alert = new Alert(Alert.AlertType.INFORMATION, body);
            alert.setTitle(Messages.get("app.menu.help.plugins"));
            alert.setHeaderText(null);
            AppTheme.apply(alert);
            alert.showAndWait();
        });
        helpMenu.getItems().addAll(aboutItem, pluginsItem);

        return new MenuBar(fileMenu, toolsMenu, helpMenu);
    }

    private static void openLocationSettings() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create("ms-settings:privacy-location"));
            }
        } catch (Exception ignored) {
            // best-effort convenience link only
        }
    }

    @Override
    public void stop() {
        if (poller != null) {
            poller.stop();
        }
        if (apiServer != null) {
            apiServer.stop();
        }
        if (pluginManager != null) {
            pluginManager.close();
        }
        if (scanLogDatabase != null) {
            scanLogDatabase.close();
        }
        if (appConfig != null) {
            AppConfigStore.save(appConfig);
        }
        // Report generation (PdfReportGenerator/HtmlReportGenerator, via java.awt.Color and
        // ImageIO/SwingFXUtils) implicitly initializes the AWT toolkit the first time a report is
        // exported. AWT then spawns its own non-daemon "AWT-EventQueue-0"/"AWT-Shutdown" threads
        // that block JVM exit forever once started, even after this JavaFX window has fully
        // closed and every other resource above is released - confirmed via a real thread dump
        // showing "DestroyJavaVM" waiting on exactly those two threads with nothing left for them
        // to ever process. Without this, closing the app's window would leave the process running
        // in the background indefinitely.
        System.exit(0);
    }

    public static void main(String[] args) {
        if (HeadlessRunner.tryRun(args)) {
            return;
        }
        launch(args);
    }
}

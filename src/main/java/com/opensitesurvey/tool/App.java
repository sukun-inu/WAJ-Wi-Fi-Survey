package com.waj.tool;

import com.waj.tool.alert.AlertContext;
import com.waj.tool.alert.AlertEngine;
import com.waj.tool.alert.AlertsView;
import com.waj.tool.alert.NotificationService;
import com.waj.tool.alert.SettingsDialog;
import com.waj.tool.persistence.AppConfig;
import com.waj.tool.persistence.AppConfigStore;
import com.waj.tool.persistence.AppPaths;
import com.waj.tool.persistence.ScanLogDatabase;
import com.waj.tool.channel.ChannelPlanningView;
import com.waj.tool.security.SecurityAuditView;
import com.waj.tool.ui.dashboard.DashboardView;
import com.waj.tool.ui.history.HistoryView;
import com.waj.tool.ui.ping.TracerouteView;
import com.waj.tool.ui.survey.SurveyView;
import com.waj.tool.i18n.Messages;
import com.waj.tool.util.AppTheme;
import com.waj.tool.util.CategoricalColorPalette;
import com.waj.tool.util.TooltipSupport;
import com.waj.tool.wlan.WlanInterface;
import com.waj.tool.wlan.WlanPoller;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

public class App extends Application {

    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 2_000;

    private WlanPoller poller;
    private ScanLogDatabase scanLogDatabase;
    private AppConfig appConfig;

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

        TabPane tabPane = new TabPane();
        Tab dashboardTab = new Tab(Messages.get("app.tab.dashboard"), dashboard.getRoot());
        dashboardTab.setClosable(false);
        dashboardTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.dashboard")));
        Tab surveyTab = new Tab(Messages.get("app.tab.siteSurvey"), survey.getRoot());
        surveyTab.setClosable(false);
        surveyTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.siteSurvey")));
        Tab securityTab = new Tab(Messages.get("app.tab.securityAudit"), securityAudit.getRoot());
        securityTab.setClosable(false);
        securityTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.securityAudit")));
        Tab channelTab = new Tab(Messages.get("app.tab.channelPlanning"), channelPlanning.getRoot());
        channelTab.setClosable(false);
        channelTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.channelPlanning")));
        Tab alertsTab = new Tab(Messages.get("app.tab.alerts"), alertsView.getRoot());
        alertsTab.setClosable(false);
        alertsTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.alerts")));
        Tab historyTab = new Tab(Messages.get("app.tab.history"), historyView.getRoot());
        historyTab.setClosable(false);
        historyTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.history")));
        Tab tracerouteTab = new Tab(Messages.get("app.tab.traceroute"), tracerouteView.getRoot());
        tracerouteTab.setClosable(false);
        tracerouteTab.setTooltip(TooltipSupport.create(Messages.get("tooltip.tab.traceroute")));
        tabPane.getTabs().addAll(dashboardTab, surveyTab, securityTab, channelTab, alertsTab, historyTab, tracerouteTab);

        Label accessDeniedBanner = new Label();
        accessDeniedBanner.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-padding: 6;");
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(10,
                dashboard.getInterfaceLabel(), dashboard.getLastScanLabel(),
                new Separator(Orientation.VERTICAL),
                new Label(Messages.get("app.label.interfaceCaption")), interfaceSelector, refreshInterfacesButton,
                spacer);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 8, 4, 8));

        MenuBar menuBar = buildMenuBar(primaryStage, appConfig, alertContext.trustedApRegistry);

        BorderPane root = new BorderPane();
        VBox top = new VBox(menuBar, accessDeniedBanner, statusBar);
        root.setTop(top);
        root.setCenter(tabPane);

        Scene scene = new Scene(root, 1400, 900);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

        primaryStage.setTitle(Messages.get("app.title"));
        primaryStage.setScene(scene);
        primaryStage.show();

        poller = new WlanPoller(
                DEFAULT_POLL_INTERVAL_MILLIS,
                snapshot -> {
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
                    java.util.List<com.waj.tool.alert.Alert> firedAlerts = alertEngine.onSnapshot(snapshot);
                    for (com.waj.tool.alert.Alert fired : firedAlerts) {
                        notificationService.notify(fired, appConfig.windowsNotificationsEnabled);
                    }

                    Platform.runLater(() -> {
                        dashboard.onSnapshot(snapshot);
                        survey.onSnapshot(snapshot);
                        securityAudit.onSnapshot(snapshot);
                        channelPlanning.onSnapshot(snapshot);
                        alertsView.addAlerts(firedAlerts);
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
                }),
                error -> Platform.runLater(() ->
                        dashboard.getInterfaceLabel().setText(Messages.get("common.error.prefix", error.getMessage())))
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

        primaryStage.setOnCloseRequest(e -> {
            poller.stop();
            tracerouteView.shutdown();
        });
    }

    private MenuBar buildMenuBar(Stage primaryStage, AppConfig appConfig,
                                  com.waj.tool.alert.TrustedApRegistry trustedApRegistry) {
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
        helpMenu.getItems().add(aboutItem);

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

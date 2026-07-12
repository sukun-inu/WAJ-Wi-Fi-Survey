package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.gps.GeoReference;
import com.opensitesurvey.tool.gps.GpsProbe;
import com.opensitesurvey.tool.gps.PathSampler;
import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.ScanSnapshot;
import com.opensitesurvey.tool.model.SurveyPoint;
import com.opensitesurvey.tool.model.SurveyProject;
import com.opensitesurvey.tool.persistence.CsvExporter;
import com.opensitesurvey.tool.persistence.GeoPackageExporter;
import com.opensitesurvey.tool.persistence.JsonExporter;
import com.opensitesurvey.tool.persistence.SurveyProjectStore;
import com.opensitesurvey.tool.ping.PingProbe;
import com.opensitesurvey.tool.ping.ThroughputProbe;
import com.opensitesurvey.tool.report.HtmlReportGenerator;
import com.opensitesurvey.tool.report.PdfReportGenerator;
import com.opensitesurvey.tool.report.ReportData;
import com.opensitesurvey.tool.util.AppTheme;
import com.opensitesurvey.tool.util.FxSync;
import com.opensitesurvey.tool.util.SignalColorScale;
import com.opensitesurvey.tool.util.TooltipSupport;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Floor-plan site survey: click a point on a loaded floor plan to record the current Wi-Fi
 * snapshot there, then render an IDW-interpolated coverage heatmap for a chosen target AP.
 */
public final class SurveyView {

    private static final String STRONGEST_KEY = "";

    private final BorderPane root = new BorderPane();
    private final Canvas canvas = new Canvas(900, 600);
    private final StackPane canvasHolder = new StackPane(canvas);
    // Shown centered over the canvas only while no floor plan is loaded (see redraw() and
    // buildEmptyStateOverlay()) - otherwise a first-time user sees only a plain dark rectangle
    // with a one-line hint down in the status bar, easy to miss entirely.
    private final VBox emptyStateOverlay = new VBox(10);
    private final ComboBox<String> targetSelector = new ComboBox<>();
    private static final String ALGO_IDW = "IDW";
    private static final String ALGO_KRIGING = "Kriging";
    private static final String ALGO_NATURAL_NEIGHBOR = "Natural Neighbor";
    private final ComboBox<String> algorithmSelector = new ComboBox<>();
    private final ToggleButton surveyModeToggle = new ToggleButton(Messages.get("survey.toggle.measureModeOff"));
    private final Label statusLabel = new Label(Messages.get("survey.status.loadFloorPlanPrompt"));

    private final TextField pingHostField = new TextField();
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "survey-ping");
        t.setDaemon(true);
        return t;
    });

    // Active Survey throughput (see ThroughputProbe) - optional, like ping: empty URL disables it.
    // Runs on its own executor (not pingExecutor) so a several-second throughput test at one point
    // doesn't delay the (much faster) ping result for the same or a later point.
    private final TextField throughputUrlField = new TextField();
    private static final int THROUGHPUT_TEST_DURATION_MILLIS = 3000;
    private final ExecutorService throughputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "survey-throughput");
        t.setDaemon(true);
        return t;
    });

    // Project loading decodes JSON plus (for a project with an embedded floor plan) a Base64
    // image potentially several MB in size - both run here, off the FX Application thread, so a
    // large project doesn't freeze the UI while loading (see resolveFloorPlan()).
    private final ExecutorService projectIoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "survey-project-io");
        t.setDaemon(true);
        return t;
    });

    // Walking heatmap (indoor + outdoor): auto-record survey points from a continuous position
    // stream instead of manual clicks, reusing recordPoint() exactly like a manual click (see its
    // own javadoc). Two interchangeable position sources share the same toggle/PathSampler/
    // recordPoint plumbing:
    //  - GPS: calibrate a GeoReference once via GpsCalibrationDialog against the currently-loaded
    //    background image, then GpsProbe streams live lat/lon. Needs calibration; works outdoors,
    //    degrades or fails indoors.
    //  - Wi-Fi: WifiPositionEstimator estimates the current position from APs whose position was
    //    already estimated (ApPositionEstimator) from survey points recorded so far, weighted by
    //    their live RSSI. Needs no calibration at all and works indoors where GPS can't get a fix,
    //    but needs a handful of points recorded first before any AP has a known position.
    private static final String POSITION_SOURCE_GPS = "GPS";
    private static final String POSITION_SOURCE_WIFI = "Wi-Fi";
    private List<GeoReference.CalibrationPoint> calibrationPoints = new ArrayList<>();
    private GeoReference geoReference;
    private GpsProbe gpsProbe;
    private PathSampler pathSampler;
    // Recomputed (see refreshKnownApPositions()) whenever points changes, so a Wi-Fi position fix
    // always uses the latest AP-position knowledge - including AP positions learned from points
    // recorded by Wi-Fi auto-record itself, letting a walking session bootstrap its own accuracy.
    private final Map<String, ApPositionEstimator.Estimate> knownApPositions = new HashMap<>();
    private final Button gpsCalibrateButton = new Button(Messages.get("survey.button.gpsCalibrate"));
    private final ComboBox<String> positionSourceSelector = new ComboBox<>();
    private final ToggleButton autoRecordToggle = new ToggleButton(Messages.get("survey.toggle.autoRecordOff"));
    private final TextField gpsMinDistanceMetersField = new TextField("3");
    private final TextField gpsMaxAccuracyMetersField = new TextField("30");
    private final Label gpsStatusLabel = new Label("");

    private final Map<String, String> ssidByBssid = new HashMap<>();
    private final List<SurveyPoint> points = new ArrayList<>();

    private Image floorPlanImage;
    private String floorPlanPath;
    // Raw source bytes of the currently loaded floor plan, embedded into saved projects (see
    // SurveyProject.floorPlanImageBase64) - null when the source file couldn't be read (project
    // save then falls back to floorPlanPath-only, same as this app's original behavior).
    private byte[] floorPlanImageBytes;
    private ScanSnapshot latestSnapshot;

    // Coverage-hole highlighting: a checkbox + dBm threshold field, redrawn as diagonal hatching
    // over any heatmap-grid cell whose interpolated RSSI falls below the threshold.
    private final CheckBox coverageHoleToggle = new CheckBox(Messages.get("survey.toggle.coverageHoles"));
    private final TextField coverageThresholdField = new TextField("-75");
    private final Label coverageSummaryLabel = new Label("");

    // Coverage Requirements: a one-shot check (like ApPlacementAdvisor's suggestion button) against
    // three fixed use-case profiles (see CoverageRequirementEvaluator) rather than a continuous
    // overlay, since the profiles themselves never change.
    private final Label coverageRequirementsSummaryLabel = new Label("");

    // Roaming analysis (see RoamingAnalyzer) - a continuously redrawn overlay like the other
    // toggles above, since it only re-groups already-recorded points/SSIDs rather than running a
    // search.
    private final CheckBox roamingAnalysisToggle = new CheckBox(Messages.get("survey.toggle.roamingAnalysis"));
    private final TextField roamingGapThresholdField = new TextField("10");

    // Heuristic estimated-AP-position markers (see ApPositionEstimator) - drawn for every BSSID
    // seen so far, not just the current heatmap target, since this is meant as an overview of
    // where every detected AP likely sits rather than a per-target detail view.
    private final CheckBox estimatedApPositionsToggle = new CheckBox(Messages.get("survey.toggle.estimatedApPositions"));

    // Coverage-gap AP placement suggestions (see ApPlacementAdvisor) - computed on demand (button
    // click) rather than on every redraw() like the toggles above, since it's a deliberate
    // one-shot "search for candidate positions" action rather than a cheap continuous overlay.
    // Cleared whenever points/floor plan/project change so a stale suggestion never lingers over
    // unrelated data (same rationale as clearComparisonState()).
    private List<ApPlacementAdvisor.Suggestion> apPlacementSuggestions = List.of();

    // Before/after comparison: a second project's points, loaded independently of the current
    // floor plan/points, used only as a baseline for a delta heatmap (see HeatmapRenderer.renderDelta).
    private List<SurveyPoint> comparisonPoints;
    private String comparisonProjectName;
    private final ToggleButton comparisonModeToggle = new ToggleButton(Messages.get("survey.toggle.comparisonMode"));
    private final Label comparisonSummaryLabel = new Label("");

    private final Supplier<String> interfaceDescriptionSupplier;

    public SurveyView(String defaultPingHost, Supplier<String> interfaceDescriptionSupplier) {
        this.interfaceDescriptionSupplier = interfaceDescriptionSupplier;
        pingHostField.setText(defaultPingHost == null ? "" : defaultPingHost);
        buildToolbar();
        buildCanvas();
        buildEmptyStateOverlay();
        // Wrapped in a holder (not the canvas directly) so the floor plan scales up/down with the
        // window instead of staying pinned at whatever size it happened to load at - Canvas isn't
        // resizable by JavaFX's own layout system, so this has to be driven explicitly.
        canvasHolder.widthProperty().addListener((obs, old, val) -> resizeCanvasToFit());
        canvasHolder.heightProperty().addListener((obs, old, val) -> resizeCanvasToFit());
        root.setCenter(canvasHolder);
        root.setRight(buildLegend());
        root.setBottom(statusLabel);
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    /** The live recorded-point-count status, reused as this screen's page-header chip. */
    public Label getStatusLabel() {
        return statusLabel;
    }

    private void buildToolbar() {
        Button loadFloorPlanButton = new Button(Messages.get("survey.button.openFloorPlan"));
        loadFloorPlanButton.setOnAction(e -> onLoadFloorPlan());
        TooltipSupport.set(loadFloorPlanButton, Messages.get("tooltip.survey.loadFloorPlan"));

        targetSelector.getItems().add(STRONGEST_KEY);
        targetSelector.getSelectionModel().select(STRONGEST_KEY);
        targetSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(String bssid) {
                if (bssid == null || bssid.isEmpty()) {
                    return Messages.get("survey.target.strongestAuto");
                }
                String ssid = ssidByBssid.getOrDefault(bssid, "");
                return (ssid.isEmpty() ? "<hidden>" : ssid) + " (" + bssid + ")";
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        targetSelector.setOnAction(e -> {
            String target = targetSelector.getSelectionModel().getSelectedItem();
            if (comparisonModeToggle.isSelected() && (target == null || target.isEmpty())) {
                // Comparison mode requires a specific AP identity (see comparisonModeToggle's own
                // handler) - switching back to "Strongest (auto)" while it's active would silently
                // start diffing two different physical APs, so turn comparison mode off instead.
                comparisonModeToggle.setSelected(false);
                showAlert(Messages.get("survey.comparison.requiresSpecificTarget"));
            }
            redraw();
        });
        TooltipSupport.set(targetSelector, Messages.get("tooltip.survey.targetSelector"));

        algorithmSelector.getItems().setAll(ALGO_IDW, ALGO_KRIGING, ALGO_NATURAL_NEIGHBOR);
        algorithmSelector.getSelectionModel().select(ALGO_IDW);
        algorithmSelector.setOnAction(e -> redraw());
        TooltipSupport.set(algorithmSelector, Messages.get("tooltip.survey.algorithmSelector"));

        surveyModeToggle.setOnAction(e -> surveyModeToggle.setText(
                surveyModeToggle.isSelected()
                        ? Messages.get("survey.toggle.measureModeOn") : Messages.get("survey.toggle.measureModeOff")));
        TooltipSupport.set(surveyModeToggle, Messages.get("tooltip.survey.measureMode"));

        Button clearButton = new Button(Messages.get("survey.button.clearPoints"));
        clearButton.setOnAction(e -> onClearPoints());
        TooltipSupport.set(clearButton, Messages.get("tooltip.survey.clearPoints"));

        Button saveButton = new Button(Messages.get("survey.button.saveProject"));
        saveButton.setOnAction(e -> onSaveProject());
        TooltipSupport.set(saveButton, Messages.get("tooltip.survey.saveProject"));

        Button loadButton = new Button(Messages.get("survey.button.loadProject"));
        loadButton.setOnAction(e -> onLoadProject());
        TooltipSupport.set(loadButton, Messages.get("tooltip.survey.loadProject"));

        Button exportCsvButton = new Button(Messages.get("survey.button.exportPointsCsv"));
        exportCsvButton.setOnAction(e -> exportPoints(true));
        TooltipSupport.set(exportCsvButton, Messages.get("tooltip.survey.exportPointsCsv"));
        Button exportJsonButton = new Button(Messages.get("survey.button.exportPointsJson"));
        exportJsonButton.setOnAction(e -> exportPoints(false));
        TooltipSupport.set(exportJsonButton, Messages.get("tooltip.survey.exportPointsJson"));
        Button exportGeoPackageButton = new Button(Messages.get("survey.button.exportGeoPackage"));
        exportGeoPackageButton.setOnAction(e -> exportGeoPackage());
        TooltipSupport.set(exportGeoPackageButton, Messages.get("tooltip.survey.exportGeoPackage"));

        pingHostField.setPromptText(Messages.get("survey.pingHost.prompt"));
        pingHostField.setPrefWidth(130);
        TooltipSupport.set(pingHostField, Messages.get("tooltip.survey.pingHost"));

        throughputUrlField.setPromptText(Messages.get("survey.throughputUrl.prompt"));
        throughputUrlField.setPrefWidth(160);
        TooltipSupport.set(throughputUrlField, Messages.get("tooltip.survey.throughputUrl"));

        Button reportHtmlButton = new Button(Messages.get("survey.button.exportReportHtml"));
        reportHtmlButton.setOnAction(e -> exportReport(true));
        TooltipSupport.set(reportHtmlButton, Messages.get("tooltip.survey.reportHtml"));
        Button reportPdfButton = new Button(Messages.get("survey.button.exportReportPdf"));
        reportPdfButton.setOnAction(e -> exportReport(false));
        TooltipSupport.set(reportPdfButton, Messages.get("tooltip.survey.reportPdf"));

        coverageHoleToggle.setOnAction(e -> redraw());
        TooltipSupport.set(coverageHoleToggle, Messages.get("tooltip.survey.coverageHoleToggle"));
        coverageThresholdField.setPrefWidth(50);
        coverageThresholdField.setOnAction(e -> redraw());
        coverageThresholdField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                redraw();
            }
        });
        TooltipSupport.set(coverageThresholdField, Messages.get("tooltip.survey.coverageThreshold"));

        estimatedApPositionsToggle.setOnAction(e -> redraw());
        TooltipSupport.set(estimatedApPositionsToggle, Messages.get("tooltip.survey.estimatedApPositions"));

        Button suggestApPlacementButton = new Button(Messages.get("survey.button.suggestApPlacement"));
        suggestApPlacementButton.setOnAction(e -> onSuggestApPlacement());
        TooltipSupport.set(suggestApPlacementButton, Messages.get("tooltip.survey.suggestApPlacement"));

        Button checkCoverageRequirementsButton = new Button(Messages.get("survey.button.checkCoverageRequirements"));
        checkCoverageRequirementsButton.setOnAction(e -> onCheckCoverageRequirements());
        TooltipSupport.set(checkCoverageRequirementsButton, Messages.get("tooltip.survey.checkCoverageRequirements"));

        roamingAnalysisToggle.setOnAction(e -> redraw());
        TooltipSupport.set(roamingAnalysisToggle, Messages.get("tooltip.survey.roamingAnalysisToggle"));
        roamingGapThresholdField.setPrefWidth(50);
        roamingGapThresholdField.setOnAction(e -> redraw());
        roamingGapThresholdField.focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                redraw();
            }
        });
        TooltipSupport.set(roamingGapThresholdField, Messages.get("tooltip.survey.roamingGapThreshold"));

        gpsCalibrateButton.setOnAction(e -> onGpsCalibrate());
        TooltipSupport.set(gpsCalibrateButton, Messages.get("tooltip.survey.gpsCalibrate"));
        positionSourceSelector.getItems().setAll(POSITION_SOURCE_GPS, POSITION_SOURCE_WIFI);
        positionSourceSelector.getSelectionModel().select(POSITION_SOURCE_GPS);
        positionSourceSelector.setOnAction(e -> updateAutoRecordAvailability());
        TooltipSupport.set(positionSourceSelector, Messages.get("tooltip.survey.positionSource"));
        updateAutoRecordAvailability(); // GPS selected by default, disabled until calibrated
        autoRecordToggle.setOnAction(e -> {
            if (autoRecordToggle.isSelected()) {
                startAutoRecord();
            } else {
                stopAutoRecord();
            }
        });
        TooltipSupport.set(autoRecordToggle, Messages.get("tooltip.survey.autoRecordToggle"));
        gpsMinDistanceMetersField.setPrefWidth(50);
        TooltipSupport.set(gpsMinDistanceMetersField, Messages.get("tooltip.survey.gpsMinDistance"));
        gpsMaxAccuracyMetersField.setPrefWidth(50);
        TooltipSupport.set(gpsMaxAccuracyMetersField, Messages.get("tooltip.survey.gpsMaxAccuracy"));

        Button loadComparisonButton = new Button(Messages.get("survey.button.loadComparison"));
        loadComparisonButton.setOnAction(e -> onLoadComparison());
        TooltipSupport.set(loadComparisonButton, Messages.get("tooltip.survey.loadComparison"));
        comparisonModeToggle.setOnAction(e -> {
            if (comparisonModeToggle.isSelected()) {
                if (comparisonPoints == null) {
                    comparisonModeToggle.setSelected(false);
                    showAlert(Messages.get("survey.comparison.noBaseline"));
                    return;
                }
                // A delta only means something between two readings of the *same* AP - with the
                // target left at "Strongest (auto)", the current and baseline surveys would each
                // independently pick whichever BSSID happened to be locally strongest at that
                // point, which can silently be two different physical APs.
                String target = targetSelector.getSelectionModel().getSelectedItem();
                if (target == null || target.isEmpty()) {
                    comparisonModeToggle.setSelected(false);
                    showAlert(Messages.get("survey.comparison.requiresSpecificTarget"));
                    return;
                }
            }
            redraw();
        });
        TooltipSupport.set(comparisonModeToggle, Messages.get("tooltip.survey.comparisonToggle"));

        // The toolbar used to be 4 flat HBox rows that grew a new button/field every time a
        // feature was added, until it overflowed the window width and button labels started
        // getting clipped. Grouped into named, independently-collapsible sections instead: only
        // "計測・保存" (the controls used on essentially every survey) starts expanded, and each
        // section uses a FlowPane rather than a fixed-width HBox so it wraps to multiple lines
        // instead of clipping if a narrower window still can't fit one section's controls on one
        // line.
        FlowPane measurementFlow = new FlowPane(8, 4, loadFloorPlanButton,
                new Label(Messages.get("survey.label.targetAp")), targetSelector,
                new Label(Messages.get("survey.label.algorithm")), algorithmSelector,
                surveyModeToggle, new Label(Messages.get("survey.label.ping")), pingHostField,
                new Label(Messages.get("survey.label.throughputUrl")), throughputUrlField,
                clearButton, saveButton, loadButton);
        TitledPane measurementPane = new TitledPane(Messages.get("survey.section.measurement"), measurementFlow);
        measurementPane.setExpanded(true);

        FlowPane analysisFlow = new FlowPane(8, 4, coverageHoleToggle,
                new Label(Messages.get("survey.label.coverageThreshold")), coverageThresholdField,
                estimatedApPositionsToggle, suggestApPlacementButton, checkCoverageRequirementsButton,
                roamingAnalysisToggle, new Label(Messages.get("survey.label.roamingGapThreshold")), roamingGapThresholdField,
                loadComparisonButton, comparisonModeToggle);
        TitledPane analysisPane = new TitledPane(Messages.get("survey.section.analysis"), analysisFlow);
        analysisPane.setExpanded(false);

        FlowPane positionFlow = new FlowPane(8, 4, gpsCalibrateButton,
                new Label(Messages.get("survey.label.positionSource")), positionSourceSelector, autoRecordToggle,
                new Label(Messages.get("survey.label.gpsMinDistance")), gpsMinDistanceMetersField,
                new Label(Messages.get("survey.label.gpsMaxAccuracy")), gpsMaxAccuracyMetersField,
                gpsStatusLabel);
        TitledPane positionPane = new TitledPane(Messages.get("survey.section.position"), positionFlow);
        positionPane.setExpanded(false);

        FlowPane exportFlow = new FlowPane(8, 4, exportCsvButton, exportJsonButton, exportGeoPackageButton,
                reportHtmlButton, reportPdfButton);
        TitledPane exportPane = new TitledPane(Messages.get("survey.section.export"), exportFlow);
        exportPane.setExpanded(false);

        VBox toolbar = new VBox(4, measurementPane, analysisPane, positionPane, exportPane);
        toolbar.setPadding(new Insets(8));
        root.setTop(toolbar);
    }

    private void buildCanvas() {
        TooltipSupport.install(canvas, Messages.get("tooltip.survey.canvas"));
        canvas.setOnMouseClicked(e -> {
            if (!surveyModeToggle.isSelected()) {
                return;
            }
            if (floorPlanImage == null) {
                showAlert(Messages.get("survey.alert.floorPlanRequired"));
                return;
            }
            if (latestSnapshot == null) {
                showAlert(Messages.get("survey.alert.noWifiDataYet"));
                return;
            }
            double xNorm = e.getX() / canvas.getWidth();
            double yNorm = e.getY() / canvas.getHeight();
            recordPoint(xNorm, yNorm);
        });
    }

    /** Centered "load a floor plan to get started" placeholder - see {@link #emptyStateOverlay}. */
    private void buildEmptyStateOverlay() {
        Label icon = new Label("⛶");
        icon.getStyleClass().add("survey-empty-state-icon");
        Label message = new Label(Messages.get("survey.status.loadFloorPlanPrompt"));
        message.getStyleClass().add("survey-empty-state-message");
        Button loadButton = new Button(Messages.get("survey.button.openFloorPlan"));
        loadButton.setDefaultButton(true);
        loadButton.setOnAction(e -> onLoadFloorPlan());
        emptyStateOverlay.getChildren().setAll(icon, message, loadButton);
        emptyStateOverlay.setAlignment(Pos.CENTER);
        emptyStateOverlay.getStyleClass().add("survey-empty-state");
        canvasHolder.getChildren().add(emptyStateOverlay);
    }

    /**
     * Records a new survey point at the given normalized (0..1) image coordinates - shared by the
     * manual canvas-click path above and both auto-record position sources ({@link #onGpsPosition},
     * {@link #onWifiPositionTick}), so all three use exactly the same Wi-Fi snapshot / optional
     * ping / optional throughput recording logic instead of duplicating it. Must be called on the
     * JavaFX Application thread, with {@link #floorPlanImage}/{@link #latestSnapshot} already
     * confirmed non-null by the caller - each caller handles a missing precondition differently
     * (the manual click path shows a blocking alert; the auto-record paths just skip silently,
     * since popping an alert on every position update would be disruptive).
     */
    private void recordPoint(double xNorm, double yNorm) {
        Map<String, Integer> rssiByBssid = new LinkedHashMap<>();
        for (ApSnapshot ap : latestSnapshot.accessPoints()) {
            rssiByBssid.put(ap.bssid(), ap.rssiDbm());
        }
        SurveyPoint point = new SurveyPoint(xNorm, yNorm, rssiByBssid, Instant.now());
        points.add(point);
        statusLabel.setText(Messages.get("survey.status.pointCount", points.size()));
        redraw();

        String pingHost = pingHostField.getText() == null ? "" : pingHostField.getText().trim();
        if (!pingHost.isEmpty()) {
            point.pingHost = pingHost;
            pingExecutor.execute(() -> {
                Integer rttMs = PingProbe.ping(pingHost, 1000).orElse(null);
                Platform.runLater(() -> {
                    point.pingRttMs = rttMs;
                    String pingResult = rttMs != null
                            ? Messages.get("survey.ping.rttMs", rttMs) : Messages.get("survey.ping.noResponse");
                    statusLabel.setText(Messages.get("survey.status.pointCountWithPing", points.size(), pingHost, pingResult));
                });
            });
        }

        String throughputUrl = throughputUrlField.getText() == null ? "" : throughputUrlField.getText().trim();
        if (!throughputUrl.isEmpty()) {
            throughputExecutor.execute(() -> {
                Double mbps = ThroughputProbe.measure(throughputUrl, THROUGHPUT_TEST_DURATION_MILLIS).orElse(null);
                Platform.runLater(() -> {
                    point.throughputMbps = mbps;
                    String throughputResult = mbps != null
                            ? Messages.get("survey.throughput.mbps", mbps) : Messages.get("survey.throughput.failed");
                    statusLabel.setText(Messages.get("survey.status.pointCountWithThroughput", points.size(), throughputResult));
                });
            });
        }
        refreshKnownApPositions();
    }

    /**
     * Recomputes {@link #knownApPositions} from every BSSID seen across {@link #points} so far -
     * called after every new point (manual or auto-recorded), so a Wi-Fi position fix always sees
     * the latest AP-position knowledge, including knowledge learned from points that Wi-Fi
     * auto-record itself just recorded (a walking session can bootstrap its own accuracy: the
     * first few points must come from manual clicks or GPS, but once enough of them are in, some
     * BSSIDs become "known" and Wi-Fi positioning can take over).
     */
    private void refreshKnownApPositions() {
        knownApPositions.clear();
        Set<String> bssids = new LinkedHashSet<>();
        for (SurveyPoint p : points) {
            bssids.addAll(p.rssiByBssid.keySet());
        }
        for (String bssid : bssids) {
            ApPositionEstimator.Estimate estimate = ApPositionEstimator.estimate(points, bssid);
            if (estimate != null) {
                knownApPositions.put(bssid, estimate);
            }
        }
    }

    /** Opens {@link GpsCalibrationDialog} against the current background image, storing the result for both auto-recording and project persistence. */
    private void onGpsCalibrate() {
        if (floorPlanImage == null) {
            showAlert(Messages.get("survey.alert.floorPlanRequired"));
            return;
        }
        GpsCalibrationDialog.show(currentStage(), floorPlanImage, calibrationPoints).ifPresent(result -> {
            geoReference = result.geoReference();
            calibrationPoints = new ArrayList<>(result.calibrationPoints());
            gpsStatusLabel.setText(Messages.get("survey.gps.calibrated", calibrationPoints.size()));
            updateAutoRecordAvailability();
        });
    }

    /**
     * The auto-record toggle's availability depends on which {@link #positionSourceSelector} is
     * chosen: GPS needs a completed {@link GeoReference} calibration first, but Wi-Fi positioning
     * needs no calibration at all (see {@link WifiPositionEstimator}) - it just may have nothing to
     * work with yet if no BSSID has a known position (handled at record-time, not here). The GPS
     * accuracy-threshold field is meaningless in Wi-Fi mode since there's no real accuracy figure,
     * so it's disabled rather than silently ignored.
     */
    private void updateAutoRecordAvailability() {
        boolean wifiMode = POSITION_SOURCE_WIFI.equals(positionSourceSelector.getSelectionModel().getSelectedItem());
        autoRecordToggle.setDisable(!wifiMode && geoReference == null);
        gpsMaxAccuracyMetersField.setDisable(wifiMode);
    }

    /**
     * Starts turning a live position stream into recorded survey points: {@link PathSampler} is
     * (re)configured here (not held as a single long-lived instance) since its distance threshold
     * depends on {@link GeoReference#metersPerNormUnit()} (GPS mode) which is only known once
     * calibrated, and doesn't apply at all in Wi-Fi mode.
     */
    private void startAutoRecord() {
        double minDistanceMeters = parseDoubleOrDefault(gpsMinDistanceMetersField.getText(), 3.0);
        if (POSITION_SOURCE_WIFI.equals(positionSourceSelector.getSelectionModel().getSelectedItem())) {
            double minDistanceNorm = geoReference != null && geoReference.metersPerNormUnit() > 0
                    ? minDistanceMeters / geoReference.metersPerNormUnit() : 0.01;
            // No real accuracy figure exists for a Wi-Fi RSSI-centroid fix (unlike GPS's reported
            // horizontal accuracy), so the accuracy gate is left permanently open here - reliability
            // is instead conveyed via the apCount shown in the status label, not enforced as a hard
            // reject/accept threshold.
            pathSampler = new PathSampler(minDistanceNorm, Double.MAX_VALUE);
            refreshKnownApPositions();
            gpsStatusLabel.setText(Messages.get("survey.wifiPosition.started", knownApPositions.size()));
        } else {
            double maxAccuracyMeters = parseDoubleOrDefault(gpsMaxAccuracyMetersField.getText(), 30.0);
            double metersPerNormUnit = geoReference.metersPerNormUnit();
            double minDistanceNorm = metersPerNormUnit > 0 ? minDistanceMeters / metersPerNormUnit : 0.01;
            pathSampler = new PathSampler(minDistanceNorm, maxAccuracyMeters);
            gpsProbe = new GpsProbe(
                    this::onGpsPosition,
                    status -> Platform.runLater(() -> gpsStatusLabel.setText(Messages.get("survey.gps.error", status))));
            gpsProbe.start();
        }
        autoRecordToggle.setText(Messages.get("survey.toggle.autoRecordOn"));
    }

    private void stopAutoRecord() {
        if (gpsProbe != null) {
            gpsProbe.stop();
            gpsProbe = null;
        }
        pathSampler = null;
        autoRecordToggle.setText(Messages.get("survey.toggle.autoRecordOff"));
        gpsStatusLabel.setText("");
    }

    /** Stops any active GPS auto-record subprocess - call when the app is shutting down (mirrors {@code TracerouteView.shutdown()}). */
    public void shutdownGps() {
        stopAutoRecord();
    }

    /**
     * Called on {@link GpsProbe}'s own background reader thread - marshals to the JavaFX
     * Application thread itself, since {@code GpsProbe}'s contract leaves that to the caller (same
     * convention as {@code WlanPoller}/{@code TraceroutePoller}).
     */
    private void onGpsPosition(GpsProbe.Position position) {
        Platform.runLater(() -> {
            gpsStatusLabel.setText(Messages.get("survey.gps.currentFix", position.horizontalAccuracyMeters()));
            if (geoReference == null || pathSampler == null || floorPlanImage == null || latestSnapshot == null) {
                // Not ready to record yet (e.g. still waiting for the first Wi-Fi scan) - the
                // status label above already reflects the live GPS fix, so just skip this update
                // silently rather than popping the same alert recordPoint()'s manual-click caller
                // would show on every position tick.
                return;
            }
            GeoReference.ImagePoint projected = geoReference.project(position.latitude(), position.longitude());
            if (pathSampler.shouldRecord(projected, position.horizontalAccuracyMeters())) {
                pathSampler.markRecorded(projected);
                recordPoint(projected.xNorm(), projected.yNorm());
            }
        });
    }

    /**
     * Called from {@link #onSnapshot} on every new Wi-Fi scan while Wi-Fi auto-record is active -
     * unlike GPS there's no separate live position stream to subscribe to, so a scan tick doubles
     * as the position-update tick.
     */
    private void onWifiPositionTick(ScanSnapshot snapshot) {
        WifiPositionEstimator.Estimate estimate = WifiPositionEstimator.estimate(knownApPositions, snapshot.accessPoints());
        if (estimate == null) {
            gpsStatusLabel.setText(Messages.get("survey.wifiPosition.noKnownAps"));
            return;
        }
        gpsStatusLabel.setText(Messages.get("survey.wifiPosition.currentFix", estimate.apCount()));
        if (floorPlanImage == null) {
            // Not ready to record yet - same silent-skip convention as onGpsPosition above.
            return;
        }
        GeoReference.ImagePoint candidate = new GeoReference.ImagePoint(estimate.xNorm(), estimate.yNorm());
        if (pathSampler.shouldRecord(candidate, 0)) {
            pathSampler.markRecorded(candidate);
            recordPoint(candidate.xNorm(), candidate.yNorm());
        }
    }

    private static double parseDoubleOrDefault(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private javafx.scene.Node buildLegend() {
        VBox legend = new VBox(4);
        legend.setPadding(new Insets(8));
        legend.getChildren().add(new Label(Messages.get("survey.legend.title")));
        int[] samples = {-45, -55, -65, -75, -90};
        String[] labels = {
                Messages.get("survey.legend.excellent"), Messages.get("survey.legend.good"),
                Messages.get("survey.legend.fair"), Messages.get("survey.legend.poor"),
                Messages.get("survey.legend.outOfRange")
        };
        for (int i = 0; i < samples.length; i++) {
            Rectangle rect = new Rectangle(16, 16, SignalColorScale.colorFor(samples[i]));
            HBox row = new HBox(6, rect, new Label(labels[i]));
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        coverageSummaryLabel.setWrapText(true);
        coverageSummaryLabel.setMaxWidth(180);
        coverageRequirementsSummaryLabel.setWrapText(true);
        coverageRequirementsSummaryLabel.setMaxWidth(180);
        comparisonSummaryLabel.setWrapText(true);
        comparisonSummaryLabel.setMaxWidth(180);
        legend.getChildren().addAll(coverageSummaryLabel, coverageRequirementsSummaryLabel, comparisonSummaryLabel);
        return legend;
    }

    private void onLoadComparison() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.loadComparison"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OpenSiteSurvey Project", "*.json"));
        File file = chooser.showOpenDialog(currentStage());
        if (file == null) {
            return;
        }
        try {
            SurveyProject project = SurveyProjectStore.load(file);
            comparisonPoints = project.points;
            comparisonProjectName = file.getName();
            statusLabel.setText(Messages.get("survey.comparison.loaded", comparisonProjectName, comparisonPoints.size()));
            redraw();
        } catch (Exception e) {
            showAlert(Messages.get("survey.alert.loadFailed", e.getMessage()));
        }
    }

    /** Must be called on the JavaFX Application thread. */
    public void onSnapshot(ScanSnapshot snapshot) {
        this.latestSnapshot = snapshot;
        boolean newTarget = false;
        for (ApSnapshot ap : snapshot.accessPoints()) {
            ssidByBssid.put(ap.bssid(), ap.ssid());
            if (!targetSelector.getItems().contains(ap.bssid())) {
                targetSelector.getItems().add(ap.bssid());
                newTarget = true;
            }
        }
        if (newTarget) {
            targetSelector.setButtonCell(null); // force converter re-render of the button area
        }
        if (autoRecordToggle.isSelected() && pathSampler != null
                && POSITION_SOURCE_WIFI.equals(positionSourceSelector.getSelectionModel().getSelectedItem())) {
            onWifiPositionTick(snapshot);
        }
    }

    private void onLoadFloorPlan() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.selectFloorPlan"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                Messages.get("survey.chooser.imageFiles"), "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        File file = chooser.showOpenDialog(currentStage());
        if (file == null) {
            return;
        }
        if (!confirmDiscardExistingPoints()) {
            return;
        }
        clearComparisonState();
        loadFloorPlanFile(file);
        points.clear();
        apPlacementSuggestions = List.of();
        clearGpsCalibration();
        statusLabel.setText(Messages.get("survey.status.floorPlanLoaded", file.getName()));
        redraw();
    }

    /**
     * Clears any GPS calibration and learned AP positions - called whenever the background image
     * changes, since a stale {@link GeoReference} or {@link #knownApPositions} entry fitted against
     * a *different* image would silently mis-place every new auto-recorded point (same rationale as
     * {@link #clearComparisonState()}).
     */
    private void clearGpsCalibration() {
        stopAutoRecord();
        geoReference = null;
        calibrationPoints = new ArrayList<>();
        knownApPositions.clear();
        updateAutoRecordAvailability();
    }

    /**
     * Clears the loaded comparison baseline and turns comparison mode off - called whenever a
     * new/unrelated project or floor plan is loaded, since a stale baseline from a different
     * survey would otherwise keep producing a spatially meaningless delta heatmap/summary with no
     * warning that the baseline no longer corresponds to what's on screen.
     */
    private void clearComparisonState() {
        comparisonPoints = null;
        comparisonProjectName = null;
        comparisonModeToggle.setSelected(false);
    }

    /**
     * Resets floor-plan state to "nothing loaded" - called before resolving a newly-loaded
     * project's floor plan, so a resolution failure (no embedded image, and its floorPlanPath
     * doesn't exist on this machine) doesn't leave the *previous* project's image displayed
     * underneath the new project's points.
     */
    private void clearFloorPlan() {
        floorPlanImage = null;
        floorPlanImageBytes = null;
        floorPlanPath = null;
    }

    /**
     * "点をクリア" already confirms before discarding recorded points - loading a new floor plan
     * or project silently did the exact same discard with no warning at all, so a user reloading
     * a floor plan (e.g. to fix a wrong image) could lose an entire survey's worth of points with
     * one misplaced click. Only asks when there's actually something to lose.
     */
    private boolean confirmDiscardExistingPoints() {
        if (points.isEmpty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.get("survey.confirm.discardPoints", points.size()));
        alert.setTitle(Messages.get("common.dialog.title.confirm"));
        alert.setHeaderText(null);
        AppTheme.apply(alert);
        return alert.showAndWait().map(r -> r.getButtonData().isDefaultButton()).orElse(false);
    }

    private void loadFloorPlanFile(File file) {
        Image image = new Image(file.toURI().toString());
        this.floorPlanImage = image;
        this.floorPlanPath = file.getAbsolutePath();
        try {
            this.floorPlanImageBytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            this.floorPlanImageBytes = null;
        }
        resizeCanvasToFit();
    }

    /**
     * Sizes the canvas to the largest scale of {@link #floorPlanImage} that still fits inside
     * {@link #canvasHolder}'s current size (letterboxed, aspect-ratio preserved - not stretched
     * to fill), then redraws. Called both right after loading a floor plan and whenever the
     * holder resizes (window resize, SplitPane divider drag), so the survey view keeps making use
     * of however much space is actually available instead of staying pinned at load-time size.
     */
    private void resizeCanvasToFit() {
        if (floorPlanImage == null) {
            return;
        }
        double maxW = canvasHolder.getWidth() > 0 ? canvasHolder.getWidth() : 1000;
        double maxH = canvasHolder.getHeight() > 0 ? canvasHolder.getHeight() : 700;
        double scale = Math.min(maxW / floorPlanImage.getWidth(), maxH / floorPlanImage.getHeight());
        scale = Math.min(scale, 1.0);
        canvas.setWidth(floorPlanImage.getWidth() * scale);
        canvas.setHeight(floorPlanImage.getHeight() * scale);
        redraw();
    }

    private void onClearPoints() {
        if (points.isEmpty()) {
            showAlert(Messages.get("survey.alert.noPointsToDelete"));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                Messages.get("survey.confirm.clearAllPoints", points.size()));
        alert.setTitle(Messages.get("common.dialog.title.confirm"));
        alert.setHeaderText(null);
        AppTheme.apply(alert);
        alert.showAndWait().ifPresent(response -> {
            if (response.getButtonData().isDefaultButton()) {
                points.clear();
                apPlacementSuggestions = List.of();
                statusLabel.setText(Messages.get("survey.status.pointCount", 0));
                redraw();
            }
        });
    }

    private void onSaveProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.saveProject"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OpenSiteSurvey Project", "*.ossproj.json"));
        File file = chooser.showSaveDialog(currentStage());
        if (file == null) {
            return;
        }
        try {
            SurveyProject project = new SurveyProject(floorPlanPath, 0.0, points);
            if (floorPlanImageBytes != null) {
                project.floorPlanImageBase64 = Base64.getEncoder().encodeToString(floorPlanImageBytes);
            }
            project.calibrationPoints = calibrationPoints;
            SurveyProjectStore.save(project, file);
            statusLabel.setText(Messages.get("survey.status.saved", file.getName()));
        } catch (Exception e) {
            showAlert(Messages.get("survey.alert.saveFailed", e.getMessage()));
        }
    }

    private void onLoadProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.loadProject"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OpenSiteSurvey Project", "*.json"));
        File file = chooser.showOpenDialog(currentStage());
        if (file == null) {
            return;
        }
        if (!confirmDiscardExistingPoints()) {
            return;
        }
        clearComparisonState();
        statusLabel.setText(Messages.get("survey.status.loadingProject", file.getName()));
        // JSON parsing plus (for a project with an embedded floor plan) Base64-decoding and
        // JavaFX-Image-decoding a potentially multi-MB image all happen here, off the FX
        // Application thread - constructing an Image from bytes/a URI is safe from any thread (it
        // isn't part of the scene graph), only the final field assignment/canvas redraw below
        // needs Platform.runLater.
        projectIoExecutor.execute(() -> {
            try {
                SurveyProject project = SurveyProjectStore.load(file);
                ResolvedFloorPlan resolved = resolveFloorPlan(project);
                Platform.runLater(() -> {
                    clearFloorPlan();
                    if (resolved.image() != null) {
                        floorPlanImage = resolved.image();
                        floorPlanImageBytes = resolved.bytes();
                        floorPlanPath = resolved.path();
                        resizeCanvasToFit();
                    } else if (resolved.missingPath() != null) {
                        showAlert(Messages.get("survey.alert.floorPlanNotFound", resolved.missingPath()));
                    }
                    points.clear();
                    points.addAll(project.points);
                    apPlacementSuggestions = List.of();
                    stopAutoRecord();
                    calibrationPoints = project.calibrationPoints != null
                            ? new ArrayList<>(project.calibrationPoints) : new ArrayList<>();
                    geoReference = GeoReference.fit(calibrationPoints);
                    refreshKnownApPositions();
                    updateAutoRecordAvailability();
                    statusLabel.setText(Messages.get("survey.status.projectLoaded", file.getName(), points.size()));
                    redraw();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showAlert(Messages.get("survey.alert.loadFailed", e.getMessage())));
            }
        });
    }

    /** Outcome of resolving a loaded project's floor plan - at most one of {@link #image}/{@link #missingPath} is non-null. */
    private record ResolvedFloorPlan(Image image, byte[] bytes, String path, String missingPath) {
    }

    /**
     * Decodes a project's floor plan (embedded Base64 image if present, else the legacy absolute
     * {@code floorPlanPath}) into a ready-to-display {@link Image} - safe to call off the FX
     * Application thread, so {@link #onLoadProject()} can run this on a background thread instead
     * of decoding a potentially large image inline in a button click handler.
     */
    private ResolvedFloorPlan resolveFloorPlan(SurveyProject project) {
        if (project.floorPlanImageBase64 != null && !project.floorPlanImageBase64.isEmpty()) {
            byte[] bytes = Base64.getDecoder().decode(project.floorPlanImageBase64);
            Image image = new Image(new ByteArrayInputStream(bytes));
            return new ResolvedFloorPlan(image, bytes, project.floorPlanPath, null);
        }
        if (project.floorPlanPath != null) {
            // Older project file (saved before the floor plan was embedded) - fall back to
            // resolving the absolute path it was saved with.
            File floorPlanFile = new File(project.floorPlanPath);
            if (!floorPlanFile.exists()) {
                return new ResolvedFloorPlan(null, null, null, project.floorPlanPath);
            }
            Image image = new Image(floorPlanFile.toURI().toString());
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(floorPlanFile.toPath());
            } catch (IOException e) {
                bytes = null;
            }
            return new ResolvedFloorPlan(image, bytes, project.floorPlanPath, null);
        }
        return new ResolvedFloorPlan(null, null, null, null);
    }

    private void exportPoints(boolean csv) {
        if (points.isEmpty()) {
            showAlert(Messages.get("survey.alert.noPointsToExport"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.exportPoints"));
        chooser.getExtensionFilters().add(csv
                ? new FileChooser.ExtensionFilter("CSV", "*.csv")
                : new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showSaveDialog(currentStage());
        if (file == null) {
            return;
        }
        try {
            if (csv) {
                CsvExporter.exportSurveyPoints(points, file);
            } else {
                JsonExporter.exportSurveyPoints(points, file);
            }
            statusLabel.setText(Messages.get("survey.status.pointsExported", file.getName()));
        } catch (Exception e) {
            showAlert(Messages.get("common.export.failed", e.getMessage()));
        }
    }

    /**
     * Exports both the raw survey points and (as a second feature layer, if any BSSID has enough
     * data) {@link ApPositionEstimator} estimates as a GeoPackage - see {@link GeoPackageExporter}
     * for why coordinates come out normalized rather than geo-referenced.
     */
    private void exportGeoPackage() {
        if (points.isEmpty()) {
            showAlert(Messages.get("survey.alert.noPointsToExport"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.exportGeoPackage"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GeoPackage", "*.gpkg"));
        File file = chooser.showSaveDialog(currentStage());
        if (file == null) {
            return;
        }
        try {
            GeoPackageExporter.exportSurveyPoints(points, buildApPositionRows(), file);
            statusLabel.setText(Messages.get("survey.status.pointsExported", file.getName()));
        } catch (Exception e) {
            showAlert(Messages.get("common.export.failed", e.getMessage()));
        }
    }

    /** Must be called on the JavaFX Application thread - see {@link #snapshotApPositionEstimates()} for the cross-thread equivalent. */
    private List<GeoPackageExporter.ApPositionRow> buildApPositionRows() {
        List<GeoPackageExporter.ApPositionRow> apPositions = new ArrayList<>();
        for (ApEstimateInfo info : computeApPositionEstimates()) {
            apPositions.add(new GeoPackageExporter.ApPositionRow(
                    info.bssid(), info.ssid(), info.estimate().sampleCount(),
                    info.estimate().xNorm(), info.estimate().yNorm()));
        }
        return apPositions;
    }

    /**
     * Safe to call from any thread (e.g. an HTTP handler thread in {@link
     * com.opensitesurvey.tool.api.ApiServer}) - marshals the read onto the JavaFX Application
     * thread via {@link FxSync} rather than requiring {@link #points} itself to be
     * synchronized/volatile.
     */
    public List<SurveyPoint> snapshotPoints() {
        return FxSync.callAndWait(() -> List.copyOf(points));
    }

    /** Cross-thread-safe equivalent of {@link #buildApPositionRows()} - see {@link #snapshotPoints()}. */
    public List<GeoPackageExporter.ApPositionRow> snapshotApPositionEstimates() {
        return FxSync.callAndWait(this::buildApPositionRows);
    }

    private void exportReport(boolean html) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("survey.chooser.exportReport"));
        chooser.getExtensionFilters().add(html
                ? new FileChooser.ExtensionFilter("HTML", "*.html")
                : new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File file = chooser.showSaveDialog(currentStage());
        if (file == null) {
            return;
        }
        List<ApSnapshot> aps = latestSnapshot != null ? latestSnapshot.accessPoints() : List.of();
        Image snapshot = floorPlanImage != null ? canvas.snapshot(null, null) : null;
        ReportData data = new ReportData(Instant.now(), interfaceDescriptionSupplier.get(), aps, points, snapshot);
        try {
            if (html) {
                HtmlReportGenerator.generate(data, file);
            } else {
                PdfReportGenerator.generate(data, file);
            }
            statusLabel.setText(Messages.get("survey.status.reportExported", file.getName()));
        } catch (Exception e) {
            showAlert(Messages.get("survey.alert.reportExportFailed", e.getMessage()));
        }
    }

    private Interpolator currentInterpolator() {
        String selected = algorithmSelector.getSelectionModel().getSelectedItem();
        if (ALGO_KRIGING.equals(selected)) {
            return KrigingInterpolator.INSTANCE;
        }
        if (ALGO_NATURAL_NEIGHBOR.equals(selected)) {
            return NaturalNeighborInterpolator.INSTANCE;
        }
        return IdwInterpolator.INSTANCE;
    }

    private void redraw() {
        emptyStateOverlay.setVisible(floorPlanImage == null);
        emptyStateOverlay.setManaged(floorPlanImage == null);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (floorPlanImage != null) {
            gc.drawImage(floorPlanImage, 0, 0, canvas.getWidth(), canvas.getHeight());
        } else {
            gc.setFill(Color.web("#333333"));
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        }

        String target = targetSelector.getSelectionModel().getSelectedItem();
        String targetBssid = (target == null || target.isEmpty()) ? null : target;
        boolean comparing = comparisonModeToggle.isSelected() && comparisonPoints != null;
        Interpolator interpolator = currentInterpolator();

        // Computed at most once per redraw() and shared with drawCoverageHoles() below - both the
        // heatmap image and the coverage-hole overlay must read the exact same interpolated values
        // for the hatching to never visually disagree with the colors it's drawn on top of (and
        // recomputing the interpolation a second time for the same points/target would otherwise
        // be wasted work).
        Double[][] currentValueGrid = null;

        if (!points.isEmpty()) {
            javafx.scene.image.WritableImage heatmap;
            if (comparing) {
                heatmap = HeatmapRenderer.renderDelta(points, comparisonPoints, targetBssid, interpolator);
            } else {
                currentValueGrid = HeatmapRenderer.computeValueGrid(points, targetBssid, interpolator);
                heatmap = HeatmapRenderer.colorize(currentValueGrid);
            }
            gc.setImageSmoothing(true);
            gc.drawImage(heatmap, 0, 0, HeatmapRenderer.GRID_WIDTH, HeatmapRenderer.GRID_HEIGHT,
                    0, 0, canvas.getWidth(), canvas.getHeight());
        }

        if (coverageHoleToggle.isSelected() && !points.isEmpty()) {
            if (currentValueGrid == null) {
                // Comparison mode was showing the delta heatmap above, which doesn't compute the
                // plain absolute-value grid coverage holes need (holes are always about the
                // *current* survey's own absolute coverage, regardless of comparison mode).
                currentValueGrid = HeatmapRenderer.computeValueGrid(points, targetBssid, interpolator);
            }
            drawCoverageHoles(gc, currentValueGrid);
        } else {
            coverageSummaryLabel.setText("");
        }
        updateComparisonSummary(targetBssid, comparing, interpolator);

        for (SurveyPoint p : points) {
            double px = p.xNorm * canvas.getWidth();
            double py = p.yNorm * canvas.getHeight();
            // A dark halo behind the white/black marker so it stays visible regardless of
            // whatever heatmap color happens to sit underneath it (yellow/green markers on a
            // yellow/green heatmap region were hard to spot before this).
            gc.setFill(Color.web("#1e2228"));
            gc.fillOval(px - 6, py - 6, 12, 12);
            gc.setFill(Color.WHITE);
            gc.setStroke(Color.BLACK);
            gc.fillOval(px - 4, py - 4, 8, 8);
            gc.strokeOval(px - 4, py - 4, 8, 8);
        }

        if (estimatedApPositionsToggle.isSelected() && !points.isEmpty()) {
            drawEstimatedApPositions(gc);
        }

        if (!apPlacementSuggestions.isEmpty()) {
            drawApPlacementSuggestions(gc);
        }

        if (roamingAnalysisToggle.isSelected() && !points.isEmpty()) {
            drawRoamingAnalysis(gc);
        }
    }

    /**
     * Draws a yellow triangle marker (distinct from every other marker shape used above) at each
     * {@link RoamingAnalyzer} overlap point, labeled with the SSID and the RSSI gap between the two
     * competing BSSIDs. An unparsable gap threshold falls back to 10dB rather than silently
     * disabling the feature (same convention as {@link #parsedCoverageThreshold()}).
     */
    private void drawRoamingAnalysis(GraphicsContext gc) {
        int maxGapDb;
        try {
            maxGapDb = Integer.parseInt(roamingGapThresholdField.getText().trim());
        } catch (NumberFormatException e) {
            maxGapDb = 10;
        }
        Color markerColor = Color.web("#f7d716");
        for (RoamingAnalyzer.OverlapPoint overlap : RoamingAnalyzer.analyze(points, ssidByBssid, maxGapDb)) {
            double px = overlap.xNorm() * canvas.getWidth();
            double py = overlap.yNorm() * canvas.getHeight();
            double[] xs = {px, px - 7, px + 7};
            double[] ys = {py - 8, py + 6, py + 6};
            gc.setFill(Color.web("#1e2228"));
            gc.fillOval(px - 9, py - 9, 18, 18);
            gc.setFill(markerColor);
            gc.fillPolygon(xs, ys, 3);
            gc.setStroke(Color.BLACK);
            gc.setLineWidth(1);
            gc.strokePolygon(xs, ys, 3);

            gc.setFill(markerColor);
            gc.fillText(Messages.get("survey.roaming.markerLabel", overlap.ssid(), overlap.gapDb()), px + 10, py + 4);
        }
    }

    /**
     * Computes up to 3 {@link ApPlacementAdvisor} suggestions for the currently-displayed heatmap
     * (same points/target/interpolator/threshold the user is already looking at) and stores them
     * for {@link #redraw()} to overlay - a deliberate one-shot action (unlike the continuously
     * redrawn toggles above) since the greedy search, while fast, is still meant as an explicit
     * "search for candidate positions" step rather than free continuous overlay work.
     */
    private void onSuggestApPlacement() {
        if (points.isEmpty()) {
            showAlert(Messages.get("survey.alert.noPointsForSuggestion"));
            return;
        }
        String target = targetSelector.getSelectionModel().getSelectedItem();
        String targetBssid = (target == null || target.isEmpty()) ? null : target;
        Double[][] valueGrid = HeatmapRenderer.computeValueGrid(points, targetBssid, currentInterpolator());
        apPlacementSuggestions = ApPlacementAdvisor.suggest(valueGrid, parsedCoverageThreshold(), 3);
        if (apPlacementSuggestions.isEmpty()) {
            showAlert(Messages.get("survey.placement.noSuggestions"));
        }
        redraw();
    }

    /**
     * Checks the currently-displayed heatmap (same points/target/interpolator the user is already
     * looking at) against {@link CoverageRequirementEvaluator}'s fixed Voice/Video/Data profiles
     * and shows the resulting coverage percentages in {@link #coverageRequirementsSummaryLabel} - a
     * one-shot check rather than a continuous overlay, since the three profiles themselves never
     * change between clicks.
     */
    private void onCheckCoverageRequirements() {
        if (points.isEmpty()) {
            showAlert(Messages.get("survey.alert.noPointsForSuggestion"));
            return;
        }
        String target = targetSelector.getSelectionModel().getSelectedItem();
        String targetBssid = (target == null || target.isEmpty()) ? null : target;
        Double[][] valueGrid = HeatmapRenderer.computeValueGrid(points, targetBssid, currentInterpolator());
        List<CoverageRequirementEvaluator.Result> results =
                CoverageRequirementEvaluator.evaluate(valueGrid, CoverageRequirementEvaluator.DEFAULT_REQUIREMENTS);
        StringBuilder sb = new StringBuilder(Messages.get("survey.coverageRequirements.legend"));
        for (CoverageRequirementEvaluator.Result r : results) {
            sb.append('\n').append(Messages.get("survey.coverageRequirements.line", r.name(), r.minRssiDbm(), r.coveragePercent()));
        }
        coverageRequirementsSummaryLabel.setText(sb.toString());
    }

    /**
     * Draws a "+" cross marker (distinct from the round survey-point markers and the diamond
     * {@link #drawEstimatedApPositions estimated-AP} markers) at each suggested new-AP position,
     * labeled with how many currently-weak cells it would bring above the coverage threshold.
     */
    private void drawApPlacementSuggestions(GraphicsContext gc) {
        Color markerColor = Color.web("#4cc9f0");
        for (ApPlacementAdvisor.Suggestion s : apPlacementSuggestions) {
            double px = s.xNorm() * canvas.getWidth();
            double py = s.yNorm() * canvas.getHeight();
            gc.setFill(Color.web("#1e2228"));
            gc.fillOval(px - 9, py - 9, 18, 18);
            gc.setStroke(markerColor);
            gc.setLineWidth(2);
            gc.strokeLine(px - 7, py, px + 7, py);
            gc.strokeLine(px, py - 7, px, py + 7);
            gc.setFill(markerColor);
            gc.fillText(Messages.get("survey.placement.markerLabel", s.cellsImproved()), px + 10, py + 4);
        }
    }

    /** One BSSID's {@link ApPositionEstimator} estimate, paired with its best-effort SSID label. */
    private record ApEstimateInfo(String bssid, String ssid, ApPositionEstimator.Estimate estimate) {
    }

    /**
     * Computes {@link ApPositionEstimator} estimates for every known BSSID - scanning {@code
     * points} directly for the full set of BSSIDs, not {@link #ssidByBssid}, so this also works
     * for a loaded historical project (surveyed on a different machine/session) and not just
     * BSSIDs the live scanner has itself seen. Shared by the on-canvas marker drawing and the
     * GeoPackage export, so both agree on exactly the same set of estimates.
     */
    private List<ApEstimateInfo> computeApPositionEstimates() {
        Set<String> allBssids = new LinkedHashSet<>();
        for (SurveyPoint p : points) {
            allBssids.addAll(p.rssiByBssid.keySet());
        }
        List<ApEstimateInfo> result = new ArrayList<>();
        for (String bssid : allBssids) {
            ApPositionEstimator.Estimate estimate = ApPositionEstimator.estimate(points, bssid);
            if (estimate != null) {
                result.add(new ApEstimateInfo(bssid, ssidByBssid.get(bssid), estimate));
            }
        }
        return result;
    }

    /**
     * Draws a diamond marker (distinct from the round survey-point markers above) at each
     * estimate's position, labeled with its SSID (or the BSSID itself if no SSID was ever recorded
     * for it - see {@link #computeApPositionEstimates()}).
     */
    private void drawEstimatedApPositions(GraphicsContext gc) {
        Color markerColor = Color.web("#ff9f1c");
        for (ApEstimateInfo info : computeApPositionEstimates()) {
            double px = info.estimate().xNorm() * canvas.getWidth();
            double py = info.estimate().yNorm() * canvas.getHeight();
            double[] xs = {px, px + 7, px, px - 7};
            double[] ys = {py - 8, py, py + 8, py};
            gc.setFill(Color.web("#1e2228"));
            gc.fillOval(px - 9, py - 9, 18, 18);
            gc.setFill(markerColor);
            gc.fillPolygon(xs, ys, 4);
            gc.setStroke(Color.BLACK);
            gc.setLineWidth(1);
            gc.strokePolygon(xs, ys, 4);

            String label = info.ssid() == null ? info.bssid() : (info.ssid().isEmpty() ? "<hidden>" : info.ssid());
            gc.setFill(markerColor);
            gc.fillText(label + " (" + info.estimate().sampleCount() + ")", px + 10, py + 4);
        }
    }

    /**
     * Overlays a diagonal "X" hatch on every heatmap-grid cell (grouped into a coarser stride than
     * {@link HeatmapRenderer}'s own pixel grid, so the marks read as a hazard-stripe pattern rather
     * than a solid block) whose interpolated RSSI falls below {@link #coverageThresholdField}.
     * Reads {@code valueGrid} (computed once by the caller via {@link
     * HeatmapRenderer#computeValueGrid}) rather than independently re-running IDW, so the hatching
     * can never visually disagree with the heatmap image it's drawn on top of. Also updates {@link
     * #coverageSummaryLabel} with the fraction of surveyed area affected. An unparsable threshold
     * falls back to -75dBm (this app's own default RSSI alert threshold) rather than silently
     * disabling the feature.
     */
    private void drawCoverageHoles(GraphicsContext gc, Double[][] valueGrid) {
        double thresholdDbm = parsedCoverageThreshold();
        int stride = 6; // heatmap-grid cells per hatch mark
        double cellW = canvas.getWidth() / HeatmapRenderer.GRID_WIDTH * stride;
        double cellH = canvas.getHeight() / HeatmapRenderer.GRID_HEIGHT * stride;
        gc.setStroke(Color.web("#ffffff"));
        gc.setLineWidth(1.3);
        int coveredCells = 0;
        int holeCells = 0;
        for (int gy = 0; gy < HeatmapRenderer.GRID_HEIGHT; gy += stride) {
            int sampleGy = Math.min(gy + stride / 2, HeatmapRenderer.GRID_HEIGHT - 1);
            for (int gx = 0; gx < HeatmapRenderer.GRID_WIDTH; gx += stride) {
                int sampleGx = Math.min(gx + stride / 2, HeatmapRenderer.GRID_WIDTH - 1);
                Double value = valueGrid[sampleGy][sampleGx];
                if (value == null) {
                    continue;
                }
                coveredCells++;
                if (value < thresholdDbm) {
                    holeCells++;
                    double px = gx * (canvas.getWidth() / HeatmapRenderer.GRID_WIDTH);
                    double py = gy * (canvas.getHeight() / HeatmapRenderer.GRID_HEIGHT);
                    gc.strokeLine(px, py, px + cellW, py + cellH);
                    gc.strokeLine(px + cellW, py, px, py + cellH);
                }
            }
        }
        double coveragePercent = coveredCells == 0 ? 0 : 100.0 * (coveredCells - holeCells) / coveredCells;
        double holePercent = coveredCells == 0 ? 0 : 100.0 * holeCells / coveredCells;
        coverageSummaryLabel.setText(Messages.get("survey.coverage.summary", coveragePercent, holePercent));
    }

    /** Parses {@link #coverageThresholdField}, falling back to -75dBm (this app's default RSSI alert threshold) on unparsable text - shared by {@link #drawCoverageHoles} and {@link #onSuggestApPlacement} so both agree on the same "weak" definition. */
    private double parsedCoverageThreshold() {
        try {
            return Double.parseDouble(coverageThresholdField.getText().trim());
        } catch (NumberFormatException e) {
            return -75.0;
        }
    }

    /** Updates {@link #comparisonSummaryLabel} with avg/best/worst RSSI delta at each current point vs. the loaded baseline. */
    private void updateComparisonSummary(String targetBssid, boolean comparing, Interpolator interpolator) {
        if (!comparing) {
            comparisonSummaryLabel.setText("");
            return;
        }
        double sum = 0;
        int n = 0;
        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        for (SurveyPoint p : points) {
            Integer current = p.rssiFor(targetBssid);
            Double baseline = interpolator.interpolate(p.xNorm, p.yNorm, comparisonPoints, targetBssid);
            if (current == null || baseline == null) {
                continue;
            }
            double delta = current - baseline;
            sum += delta;
            n++;
            best = Math.max(best, delta);
            worst = Math.min(worst, delta);
        }
        if (n == 0) {
            comparisonSummaryLabel.setText(Messages.get("survey.comparison.noOverlap"));
            return;
        }
        comparisonSummaryLabel.setText(Messages.get("survey.comparison.legend") + "\n"
                + Messages.get("survey.comparison.summary", sum / n, best, worst, comparisonProjectName));
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setTitle(Messages.get("common.dialog.title.warning"));
        alert.setHeaderText(null);
        AppTheme.apply(alert);
        alert.showAndWait();
    }

    private Stage currentStage() {
        return (Stage) root.getScene().getWindow();
    }
}

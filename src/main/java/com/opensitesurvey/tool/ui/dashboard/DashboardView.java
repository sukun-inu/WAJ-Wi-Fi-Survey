package com.waj.tool.ui.dashboard;

import com.waj.tool.alert.TrustedApRegistry;
import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;
import com.waj.tool.persistence.CsvExporter;
import com.waj.tool.persistence.JsonExporter;
import com.waj.tool.util.AppTheme;
import com.waj.tool.util.CategoricalColorPalette;
import com.waj.tool.util.MonoTableCells;
import com.waj.tool.util.NoiseEstimator;
import com.waj.tool.util.TooltipSupport;
import com.waj.tool.util.VendorLookup;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.Axis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live dashboard: AP table (with per-row color swatch + show/hide checkbox), an RSSI history
 * chart, and a single combined spectrum view - all overlaying <em>every currently visible AP at
 * once</em> (not just a single selection). The RSSI history chart has a hover crosshair that
 * ranks every AP's reading at the cursor's position strongest-first.
 *
 * <p>The spectrum view merges three things into one plot area, all sharing the same frequency
 * X axis: (1) the live trace - one bell-curve-per-AP RSSI line with on-curve labels; (2) a
 * translucent waterfall (time x frequency x signal-strength heatmap, most recent at the top)
 * painted <em>behind</em> the trace as a history backdrop; (3) an SNR reading via a second,
 * right-side Y axis - since SNR is just RSSI minus the (constant) estimated noise floor, the
 * existing RSSI curve doubles as the SNR curve with no extra series needed, only a second scale.
 * Both are RSSI modelled as a bell curve per AP around its center frequency - not a real RF
 * spectrum reading, see {@code dashboard.spectrum.disclaimer} in the message bundles.
 */
public final class DashboardView {

    private final BorderPane root = new BorderPane();
    private final TableView<ApSnapshot> apTable = new TableView<>();
    private final ObservableList<ApSnapshot> apItems = FXCollections.observableArrayList();

    private final NumberAxis rssiHistoryXAxis = new NumberAxis();
    private final NumberAxis rssiHistoryYAxis = new NumberAxis(-100, -20, 10);
    private final AnnotatedLineChart<Number, Number> rssiHistoryChart =
            new AnnotatedLineChart<>(rssiHistoryXAxis, rssiHistoryYAxis);

    private final ComboBox<String> bandSelector = new ComboBox<>();

    private final NumberAxis spectrumXAxis = new NumberAxis();
    private final NumberAxis spectrumYAxis = new NumberAxis(-100, -20, 10);
    // LineChart, not AreaChart: AreaChart fills each series down to Y=0 by default, but every
    // RSSI value here is negative (below the axis's -20dBm upper bound), so the fill extends
    // upward past the visible plot area and renders as a clipped flat-topped block instead of a
    // bell curve - confirmed by zooming into a real screenshot during testing.
    private final AnnotatedLineChart<Number, Number> spectrumChart =
            new AnnotatedLineChart<>(spectrumXAxis, spectrumYAxis);
    private final List<Node> spectrumAnnotations = new ArrayList<>();

    // Manually-drawn secondary Y axis: SNR(dB) = RSSI(dBm) - ESTIMATED_NOISE_FLOOR_DBM, a fixed
    // linear shift, so the existing RSSI curve already *is* the SNR curve - this pane just mirrors
    // spectrumYAxis's own tick marks (translated to SNR) in a slim strip to its right instead of
    // standing up an entire second chart/axis pair (which is fiddly to pixel-align in JavaFX).
    private final Pane snrAxisPane = new Pane();
    private final Label snrAxisTitle = new Label(Messages.get("dashboard.spectrum.snrAxisTitle"));

    // Waterfall history, painted directly onto spectrumChart's own plot area as a translucent
    // backdrop behind the live trace (mouse-transparent so it never steals the crosshair's mouse
    // events from the plot-background node underneath).
    private final Canvas spectrogramCanvas = new Canvas();

    // Live-measured bounds of spectrumChart's own plot area (i.e. past its Y-axis tick-label
    // gutter) - the waterfall backdrop and the SNR axis pane both reuse these instead of guessing
    // fixed margins, so everything lines up regardless of how wide the dBm labels render.
    private double spectrumPlotOffsetX = 0;
    private double spectrumPlotOffsetY = 0;
    private double spectrumPlotWidth = 0;
    private double spectrumPlotHeight = 0;

    private final Label noiseFloorLabel = new Label();
    private final Label interfaceLabel = new Label(Messages.get("dashboard.label.interfaceDetecting"));
    private final Label lastScanLabel = new Label(Messages.get("dashboard.label.lastScanInitial"));

    private final RssiHistoryStore historyStore = new RssiHistoryStore();
    private final long appStartEpochSecond = Instant.now().getEpochSecond();

    private final CategoricalColorPalette colorPalette;
    private final Map<String, String> ssidByBssid = new HashMap<>();
    private final Map<String, Double> freqMhzByBssid = new HashMap<>();
    private final Map<String, String> bandByBssid = new HashMap<>();
    private final Map<String, BooleanProperty> visibilityByBssid = new HashMap<>();

    private ChartCrosshair rssiCrosshair;
    private ChartCrosshair spectrumCrosshair;
    private Button rssiYZoomResetButton;
    private Button spectrumYZoomResetButton;

    private long lastSpectrogramRenderMillis = 0;

    private static final Map<String, double[]> BAND_RANGE_MHZ = Map.of(
            "2.4GHz", new double[]{2400, 2485, 11},
            "5GHz", new double[]{5150, 5895, 20},
            "6GHz", new double[]{5925, 7125, 20}
    );

    // Fixed (not live-zoomed) reference range the white utilization curves map their 0-100% onto -
    // matches spectrumYAxis's own initial construction bounds, so "half height" always means 50%
    // regardless of how far the user has zoomed the RSSI/SNR axis in the meantime.
    private static final double SPECTRUM_Y_NOMINAL_LOWER = -100;
    private static final double SPECTRUM_Y_NOMINAL_UPPER = -20;

    private static final long SPECTROGRAM_WINDOW_SECONDS = 300;
    private static final int SPECTROGRAM_FREQ_BINS = 150;
    private static final int SPECTROGRAM_TIME_BINS = 90;
    // Bounds how often onSnapshot() may trigger a full waterfall grid recompute, independent of
    // the poll interval - the interval is user-configurable down to 0.1s elsewhere in the app,
    // but a full redraw rescans every visible AP's entire retained history, so recomputing on
    // every single poll tick at that rate would be wasteful. Explicit interactions (band change,
    // visibility toggle, canvas resize) bypass this and redraw immediately.
    private static final long SPECTROGRAM_MIN_REDRAW_INTERVAL_MILLIS = 500;
    private static final DateTimeFormatter LAST_SCAN_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final TrustedApRegistry trustedApRegistry;

    public DashboardView(TrustedApRegistry trustedApRegistry, CategoricalColorPalette colorPalette) {
        this.trustedApRegistry = trustedApRegistry;
        this.colorPalette = colorPalette;
        buildApTable();
        buildRssiHistoryChart();
        buildSpectrumChart();
        buildSpectrogramBackdrop();
        buildSnrAxisPane();
        buildCrosshairs();
        layout();
        wireSpectrumPlotAreaSync();
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    public Label getInterfaceLabel() {
        return interfaceLabel;
    }

    public Label getLastScanLabel() {
        return lastScanLabel;
    }

    private void buildApTable() {
        TableColumn<ApSnapshot, ApSnapshot> colorCol = new TableColumn<>("");
        colorCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        colorCol.setSortable(false);
        colorCol.setPrefWidth(22);
        colorCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ApSnapshot ap, boolean empty) {
                super.updateItem(ap, empty);
                setGraphic(empty || ap == null ? null : new Rectangle(12, 12, colorPalette.colorFor(ap.bssid())));
            }
        });

        TableColumn<ApSnapshot, Boolean> visibleCol = new TableColumn<>(Messages.get("dashboard.column.visible"));
        visibleCol.setCellValueFactory(d -> visibilityProperty(d.getValue().bssid()));
        visibleCol.setCellFactory(CheckBoxTableCell.forTableColumn(visibleCol));
        visibleCol.setEditable(true);
        visibleCol.setSortable(false);
        visibleCol.setPrefWidth(45);

        TableColumn<ApSnapshot, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                d.getValue().ssid().isEmpty() ? "<hidden>" : d.getValue().ssid()));
        ssidCol.setPrefWidth(140);

        TableColumn<ApSnapshot, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().bssid()));
        bssidCol.setPrefWidth(130);

        // From the OUI (first 3 bytes) of the BSSID - see VendorLookup's own javadoc for why this
        // is a bundled static snapshot rather than a live lookup. Blank (not "N/A") for
        // locally-administered/unregistered prefixes, since that's the common case for randomized
        // MACs and isn't itself noteworthy enough to call out per row.
        TableColumn<ApSnapshot, String> vendorCol = new TableColumn<>(Messages.get("dashboard.column.vendor"));
        vendorCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                java.util.Objects.requireNonNullElse(VendorLookup.vendorFor(d.getValue().bssid()), "")));
        vendorCol.setPrefWidth(150);

        TableColumn<ApSnapshot, Number> channelCol = new TableColumn<>("Ch");
        channelCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().channel()));
        channelCol.setPrefWidth(45);
        MonoTableCells.applyTo(channelCol);

        TableColumn<ApSnapshot, String> bandCol = new TableColumn<>("Band");
        bandCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().band()));
        bandCol.setPrefWidth(60);

        TableColumn<ApSnapshot, Number> rssiCol = new TableColumn<>("RSSI(dBm)");
        rssiCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().rssiDbm()));
        rssiCol.setPrefWidth(80);
        MonoTableCells.applyTo(rssiCol);

        TableColumn<ApSnapshot, Number> qualityCol = new TableColumn<>("Quality%");
        qualityCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().linkQuality()));
        qualityCol.setPrefWidth(70);
        MonoTableCells.applyTo(qualityCol);

        // Channel utilization comes from the optional 802.11 BSS Load element - only present if
        // the AP itself chooses to report it, so many rows will legitimately show "N/A" rather
        // than a number; this is a real measured value when present, not an estimate.
        TableColumn<ApSnapshot, String> utilizationCol = new TableColumn<>(Messages.get("dashboard.column.channelUtilization"));
        utilizationCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                d.getValue().channelUtilizationPercent() == null
                        ? "N/A" : d.getValue().channelUtilizationPercent() + "%"));
        utilizationCol.setPrefWidth(90);
        MonoTableCells.applyTo(utilizationCol);

        TableColumn<ApSnapshot, String> phyCol = new TableColumn<>("PHY");
        phyCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().phyType()));
        phyCol.setPrefWidth(150);

        TableColumn<ApSnapshot, String> securityCol = new TableColumn<>("Security");
        securityCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().securityType().label()));
        securityCol.setPrefWidth(110);

        apTable.getColumns().setAll(List.of(colorCol, visibleCol, ssidCol, bssidCol, vendorCol, channelCol, bandCol,
                rssiCol, qualityCol, utilizationCol, phyCol, securityCol));
        apTable.setEditable(true);
        apTable.setItems(apItems);
        apTable.setPlaceholder(new Label(Messages.get("common.status.waitingForScan")));
        apTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> refreshRssiHistoryChart());
        apTable.setRowFactory(tv -> buildTrustContextMenuRow());
    }

    private TableRow<ApSnapshot> buildTrustContextMenuRow() {
        TableRow<ApSnapshot> row = new TableRow<>();
        MenuItem trustItem = new MenuItem();
        trustItem.setOnAction(e -> {
            ApSnapshot ap = row.getItem();
            if (ap == null) {
                return;
            }
            if (trustedApRegistry.isTrusted(ap.bssid())) {
                trustedApRegistry.untrust(ap.bssid());
            } else {
                trustedApRegistry.trust(ap.ssid(), ap.bssid());
            }
        });
        ContextMenu menu = new ContextMenu(trustItem);
        row.setOnContextMenuRequested(e -> {
            ApSnapshot ap = row.getItem();
            if (ap != null) {
                trustItem.setText(trustedApRegistry.isTrusted(ap.bssid())
                        ? Messages.get("dashboard.contextMenu.untrust") : Messages.get("dashboard.contextMenu.trust"));
            }
        });
        row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                        .then((ContextMenu) null)
                        .otherwise(menu));
        return row;
    }

    private BooleanProperty visibilityProperty(String bssid) {
        return visibilityByBssid.computeIfAbsent(bssid, k -> {
            BooleanProperty prop = new SimpleBooleanProperty(true);
            prop.addListener((obs, old, val) -> {
                refreshRssiHistoryChart();
                refreshSpectrumChart(lastSnapshot);
                refreshSpectrogram();
            });
            return prop;
        });
    }

    private boolean isVisible(String bssid) {
        return visibilityProperty(bssid).get();
    }

    private String labelFor(String bssid) {
        String ssid = ssidByBssid.getOrDefault(bssid, "");
        String shortMac = bssid.length() >= 5 ? bssid.substring(bssid.length() - 5) : bssid;
        return (ssid.isEmpty() ? "<hidden>" : ssid) + " (" + shortMac + ")";
    }

    private void buildRssiHistoryChart() {
        rssiHistoryChart.setTitle(Messages.get("dashboard.rssiChart.title"));
        rssiHistoryChart.setCreateSymbols(false);
        rssiHistoryChart.setAnimated(false);
        rssiHistoryXAxis.setLabel(Messages.get("common.axis.elapsedSeconds"));
        rssiHistoryYAxis.setLabel(Messages.get("common.axis.rssiDbm"));
        rssiHistoryYAxis.setAutoRanging(false);
        // Explicit bounds, not auto-ranging: auto-ranging would still span every retained sample's
        // full min/max, and even with stale-BSSID pruning (RssiHistoryStore.record()) a single
        // AP's oldest still-retained sample can sit close to the 5-minute edge - pinning the
        // window to exactly [now-RETENTION, now] guarantees the axis matches this chart's own
        // "直近5分" title instead of drifting wider as the app keeps running.
        rssiHistoryXAxis.setAutoRanging(false);
    }

    private void updateRssiHistoryXAxisBounds() {
        double nowElapsed = Instant.now().getEpochSecond() - appStartEpochSecond;
        rssiHistoryXAxis.setUpperBound(nowElapsed);
        rssiHistoryXAxis.setLowerBound(Math.max(0, nowElapsed - RssiHistoryStore.RETENTION_SECONDS));
    }

    private void buildSpectrumChart() {
        bandSelector.getItems().setAll("2.4GHz", "5GHz", "6GHz");
        bandSelector.getSelectionModel().select("2.4GHz");
        bandSelector.setOnAction(e -> {
            refreshSpectrumChart(lastSnapshot);
            refreshSpectrogram();
        });

        spectrumChart.setTitle(Messages.get("dashboard.spectrum.disclaimer"));
        spectrumChart.setCreateSymbols(false);
        spectrumChart.setAnimated(false);
        spectrumChart.setLegendVisible(false);
        spectrumXAxis.setLabel(Messages.get("common.axis.frequencyMhz"));
        spectrumYAxis.setLabel(Messages.get("common.axis.rssiDbm"));
        // Without this, the X axis silently auto-ranges to whatever the chart's default padding
        // computes (observed: a ~0-2750MHz span instead of the selected band's actual window),
        // which both looks wrong and breaks the waterfall/SNR-axis pixel alignment, since both
        // assume the trace chart's plot area maps exactly to BAND_RANGE_MHZ.
        spectrumXAxis.setAutoRanging(false);
        spectrumYAxis.setAutoRanging(false);
        // The SNR overlay mirrors spectrumYAxis's own tick marks - listening directly to that
        // list (rather than to lowerBound/upperBound + guessing how many runLater passes it
        // takes for ticks to settle after a bound change) guarantees the SNR labels are always
        // computed from the exact same tick set the axis itself just rendered.
        spectrumYAxis.getTickMarks().addListener(
                (javafx.collections.ListChangeListener<Axis.TickMark<Number>>) change -> refreshSnrAxisOverlay());

        noiseFloorLabel.setText(Messages.get("dashboard.spectrum.noiseFloorCaption",
                NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM));
    }

    private void buildSpectrogramBackdrop() {
        spectrogramCanvas.setMouseTransparent(true);
        spectrumChart.addAnnotation(spectrogramCanvas);
        spectrogramCanvas.toBack();
    }

    private void buildSnrAxisPane() {
        snrAxisPane.setPrefWidth(34);
        snrAxisPane.setMinWidth(34);
        snrAxisTitle.getStyleClass().add("snr-axis-title");
    }

    /**
     * Waits for {@code spectrumChart}'s plot-background node (not available until after its
     * first CSS/layout pass) and keeps the {@code spectrumPlot*} fields in sync with it
     * afterwards, so the waterfall backdrop and SNR axis overlay always match the trace chart's
     * own plot area regardless of window resizes or dBm-label width changes.
     */
    private void wireSpectrumPlotAreaSync() {
        Node found = spectrumChart.lookup(".chart-plot-background");
        if (found instanceof Region plotArea) {
            Runnable sync = () -> {
                spectrumPlotOffsetX = plotArea.getBoundsInParent().getMinX();
                spectrumPlotOffsetY = plotArea.getBoundsInParent().getMinY();
                spectrumPlotWidth = plotArea.getWidth();
                spectrumPlotHeight = plotArea.getHeight();
                refreshSpectrogram();
                refreshSnrAxisOverlay();
            };
            plotArea.layoutXProperty().addListener((o, ov, nv) -> sync.run());
            plotArea.layoutYProperty().addListener((o, ov, nv) -> sync.run());
            plotArea.widthProperty().addListener((o, ov, nv) -> sync.run());
            plotArea.heightProperty().addListener((o, ov, nv) -> sync.run());
            sync.run();
        } else {
            Platform.runLater(this::wireSpectrumPlotAreaSync);
        }
    }

    private void buildCrosshairs() {
        rssiYZoomResetButton = new Button(Messages.get("common.button.resetYAxis"));
        rssiCrosshair = new ChartCrosshair(rssiHistoryChart, rssiHistoryChart, rssiHistoryXAxis,
                this::formatTimeAxis, this::rssiEntriesAt);
        rssiCrosshair.installYAxisWheelZoom(rssiHistoryYAxis, rssiYZoomResetButton);
        TooltipSupport.set(rssiYZoomResetButton, Messages.get("tooltip.dashboard.resetYAxis"));
        TooltipSupport.set(rssiCrosshair.getPanel(), Messages.get("tooltip.common.crosshairPanel"));

        spectrumYZoomResetButton = new Button(Messages.get("common.button.resetYAxis"));
        spectrumCrosshair = new ChartCrosshair(spectrumChart, spectrumChart, spectrumXAxis,
                f -> String.format("%.0f MHz", f), this::spectrumEntriesAt);
        spectrumCrosshair.installYAxisWheelZoom(spectrumYAxis, spectrumYZoomResetButton);
        TooltipSupport.set(spectrumYZoomResetButton, Messages.get("tooltip.dashboard.resetYAxis"));
        TooltipSupport.set(spectrumCrosshair.getPanel(), Messages.get("tooltip.common.crosshairPanel"));
    }

    private String formatTimeAxis(double elapsedSeconds) {
        return DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(appStartEpochSecond + Math.round(elapsedSeconds)));
    }

    private List<ChartCrosshair.Entry> rssiEntriesAt(double elapsedSeconds) {
        long targetEpoch = appStartEpochSecond + Math.round(elapsedSeconds);
        List<ChartCrosshair.Entry> result = new ArrayList<>();
        for (String bssid : historyStore.knownBssids()) {
            if (!isVisible(bssid)) {
                continue;
            }
            RssiHistoryStore.Sample sample = historyStore.nearestSample(bssid, targetEpoch, 5);
            if (sample != null) {
                result.add(new ChartCrosshair.Entry(labelFor(bssid), colorPalette.colorFor(bssid), sample.rssiDbm()));
            }
        }
        return result;
    }

    private List<ChartCrosshair.Entry> spectrumEntriesAt(double freqMhz) {
        List<ChartCrosshair.Entry> result = new ArrayList<>();
        if (lastSnapshot == null) {
            return result;
        }
        String band = bandSelector.getSelectionModel().getSelectedItem();
        double[] range = BAND_RANGE_MHZ.get(band);
        if (range == null) {
            return result;
        }
        double sigma = range[2];
        for (ApSnapshot ap : lastSnapshot.accessPoints()) {
            if (!ap.band().equals(band) || !isVisible(ap.bssid())) {
                continue;
            }
            double centerMhz = ap.frequencyKhz() / 1000.0;
            double weight = Math.exp(-Math.pow(freqMhz - centerMhz, 2) / (2 * sigma * sigma));
            if (weight > 0.05) {
                // Surfaces which AP the white utilization curve at this frequency belongs to -
                // otherwise every AP's utilization curve looks identical (white, dashed) and
                // there's no way to tell them apart just by looking at the chart.
                String utilization = ap.channelUtilizationPercent() == null
                        ? Messages.get("dashboard.label.utilizationNA")
                        : Messages.get("dashboard.label.utilizationValue", ap.channelUtilizationPercent());
                result.add(new ChartCrosshair.Entry(labelFor(ap.bssid()), colorPalette.colorFor(ap.bssid()),
                        ap.rssiDbm(), utilization));
            }
        }
        return result;
    }

    private void layout() {
        Button exportCsvButton = new Button(Messages.get("common.button.exportCsv"));
        exportCsvButton.setOnAction(e -> exportApTable(true));
        Button exportJsonButton = new Button(Messages.get("common.button.exportJson"));
        exportJsonButton.setOnAction(e -> exportApTable(false));
        TooltipSupport.set(exportCsvButton, Messages.get("tooltip.dashboard.exportCsv"));
        TooltipSupport.set(exportJsonButton, Messages.get("tooltip.dashboard.exportJson"));
        TooltipSupport.set(apTable, Messages.get("tooltip.dashboard.apTable"));
        TooltipSupport.set(bandSelector, Messages.get("tooltip.dashboard.bandSelector"));
        TooltipSupport.set(noiseFloorLabel, Messages.get("tooltip.dashboard.noiseFloor"));
        TooltipSupport.install(rssiHistoryChart, Messages.get("tooltip.dashboard.rssiChart"));
        TooltipSupport.install(spectrumChart, Messages.get("tooltip.dashboard.spectrumChart"));
        HBox exportBar = new HBox(6, new Label("Access Points"), exportCsvButton, exportJsonButton);
        exportBar.setAlignment(Pos.CENTER_LEFT);

        VBox accessPointsBox = new VBox(6, exportBar, apTable);
        accessPointsBox.setPadding(new Insets(8));
        VBox.setVgrow(apTable, Priority.ALWAYS);
        accessPointsBox.setPrefHeight(230);
        accessPointsBox.setMinHeight(180);

        HBox rssiRow = new HBox(4, rssiHistoryChart, rssiCrosshair.getPanel());
        HBox.setHgrow(rssiHistoryChart, Priority.ALWAYS);
        rssiCrosshair.getPanel().setMinWidth(180);
        rssiCrosshair.getPanel().setPrefWidth(190);
        HBox rssiToolbar = new HBox(6, rssiYZoomResetButton);
        rssiToolbar.setAlignment(Pos.CENTER_LEFT);
        VBox rssiBox = new VBox(2, rssiToolbar, rssiRow);
        rssiBox.getStyleClass().add("card");
        VBox.setVgrow(rssiRow, Priority.ALWAYS);

        VBox snrAxisBox = new VBox(1, snrAxisTitle, snrAxisPane);
        VBox.setVgrow(snrAxisPane, Priority.ALWAYS);

        HBox traceRow = new HBox(2, spectrumChart, snrAxisBox, spectrumCrosshair.getPanel());
        HBox.setHgrow(spectrumChart, Priority.ALWAYS);
        spectrumCrosshair.getPanel().setMinWidth(180);
        spectrumCrosshair.getPanel().setPrefWidth(190);
        HBox spectrumToolbar = new HBox(6, bandSelector, spectrumYZoomResetButton);
        spectrumToolbar.setAlignment(Pos.CENTER_LEFT);

        // Everything - toolbar, trace curve, SNR second axis, and the waterfall backdrop drawn
        // behind the curve - lives inside this one bordered card.
        VBox spectrumBox = new VBox(2, spectrumToolbar, traceRow, noiseFloorLabel);
        spectrumBox.getStyleClass().add("card");
        VBox.setVgrow(traceRow, Priority.ALWAYS);

        // Rotated layout (counter-clockwise once): charts on the top row, AP/SSID list below.
        SplitPane chartsSplit = new SplitPane(rssiBox, spectrumBox);
        chartsSplit.setDividerPositions(0.5);

        SplitPane mainSplit = new SplitPane(chartsSplit, accessPointsBox);
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.78);

        root.setCenter(mainSplit);

        // Double-click a chart to expand it to fill the whole window (AP table and the other
        // chart hidden); double-click the same chart again to restore the normal 3-pane layout.
        rssiHistoryChart.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                toggleChartFullscreen(mainSplit, chartsSplit, rssiBox, 0);
            }
        });
        spectrumChart.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                toggleChartFullscreen(mainSplit, chartsSplit, spectrumBox, 1);
            }
        });
    }

    private void toggleChartFullscreen(SplitPane mainSplit, SplitPane chartsSplit, VBox box, int restoreIndex) {
        if (root.getCenter() == mainSplit) {
            chartsSplit.getItems().remove(box);
            root.setCenter(box);
        } else {
            root.setCenter(mainSplit);
            if (!chartsSplit.getItems().contains(box)) {
                chartsSplit.getItems().add(Math.min(restoreIndex, chartsSplit.getItems().size()), box);
            }
        }
    }

    private ScanSnapshot lastSnapshot;

    /** Must be called on the JavaFX Application thread. */
    public void onSnapshot(ScanSnapshot snapshot) {
        this.lastSnapshot = snapshot;
        String selectedBssid = apTable.getSelectionModel().getSelectedItem() == null
                ? null : apTable.getSelectionModel().getSelectedItem().bssid();

        apItems.setAll(snapshot.accessPoints());
        snapshot.accessPoints().forEach(ap -> {
            historyStore.record(ap, snapshot.timestamp());
            ssidByBssid.put(ap.bssid(), ap.ssid());
            freqMhzByBssid.put(ap.bssid(), ap.frequencyKhz() / 1000.0);
            bandByBssid.put(ap.bssid(), ap.band());
        });

        if (selectedBssid != null) {
            snapshot.accessPoints().stream()
                    .filter(ap -> ap.bssid().equals(selectedBssid))
                    .findFirst()
                    .ifPresent(ap -> apTable.getSelectionModel().select(ap));
        }

        lastScanLabel.setText(Messages.get("dashboard.label.lastScanValue",
                LAST_SCAN_TIME_FORMATTER.format(snapshot.timestamp())));
        refreshRssiHistoryChart();
        refreshSpectrumChart(snapshot);
        maybeRefreshSpectrogram();
    }

    /** One AP's series, tracked alongside its BSSID so post-layout styling (run later) can look up its color. */
    private record RssiSeriesEntry(String bssid, XYChart.Series<Number, Number> series) {
    }

    private void refreshRssiHistoryChart() {
        updateRssiHistoryXAxisBounds();
        rssiHistoryChart.getData().clear();
        String selectedBssid = apTable.getSelectionModel().getSelectedItem() == null
                ? null : apTable.getSelectionModel().getSelectedItem().bssid();

        List<RssiSeriesEntry> entries = new ArrayList<>();
        for (String bssid : historyStore.knownBssids()) {
            if (!isVisible(bssid)) {
                continue;
            }
            List<RssiHistoryStore.Sample> samples = historyStore.historyFor(bssid);
            if (samples.isEmpty()) {
                continue;
            }
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(labelFor(bssid));
            for (RssiHistoryStore.Sample s : samples) {
                series.getData().add(new XYChart.Data<>(s.epochSecond() - appStartEpochSecond, s.rssiDbm()));
            }
            entries.add(new RssiSeriesEntry(bssid, series));
        }
        for (RssiSeriesEntry e : entries) {
            rssiHistoryChart.getData().add(e.series());
        }

        // Series render nodes don't exist until after this layout pass - style them once ready.
        Platform.runLater(() -> {
            for (RssiSeriesEntry e : entries) {
                if (e.series().getNode() != null) {
                    String hex = CategoricalColorPalette.toWeb(colorPalette.colorFor(e.bssid()));
                    double width = e.bssid().equals(selectedBssid) ? 3.0 : 1.5;
                    e.series().getNode().setStyle("-fx-stroke: " + hex + "; -fx-stroke-width: " + width + ";");
                }
            }
        });
    }

    private record SpectrumPeakEntry(String bssid, double centerMhz, double rssiDbm,
                                      XYChart.Series<Number, Number> series) {
    }

    private void refreshSpectrumChart(ScanSnapshot snapshot) {
        for (Node annotation : spectrumAnnotations) {
            spectrumChart.removeAnnotation(annotation);
        }
        spectrumAnnotations.clear();
        spectrumChart.getData().clear();
        if (snapshot == null) {
            return;
        }
        String band = bandSelector.getSelectionModel().getSelectedItem();
        double[] range = BAND_RANGE_MHZ.get(band);
        if (range == null) {
            return;
        }
        double startMhz = range[0];
        double endMhz = range[1];
        double sigma = range[2];
        spectrumXAxis.setLowerBound(startMhz);
        spectrumXAxis.setUpperBound(endMhz);

        List<SpectrumPeakEntry> peaks = new ArrayList<>();
        List<XYChart.Series<Number, Number>> utilizationSeriesList = new ArrayList<>();
        for (ApSnapshot ap : snapshot.accessPoints()) {
            if (!ap.band().equals(band) || !isVisible(ap.bssid())) {
                continue;
            }
            double centerMhz = ap.frequencyKhz() / 1000.0;
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(labelFor(ap.bssid()));
            int steps = 60;
            for (int i = 0; i <= steps; i++) {
                double f = startMhz + (endMhz - startMhz) * i / steps;
                double gaussian = Math.exp(-Math.pow(f - centerMhz, 2) / (2 * sigma * sigma));
                double y = NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM
                        + (ap.rssiDbm() - NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM) * gaussian;
                series.getData().add(new XYChart.Data<>(f, y));
            }
            spectrumChart.getData().add(series);
            peaks.add(new SpectrumPeakEntry(ap.bssid(), centerMhz, ap.rssiDbm(), series));

            // Channel utilization (from the optional BSS Load IE) isn't an RSSI value, but is
            // drawn as the same bell-curve shape at the same center frequency so it reads as
            // "one more curve on this chart" rather than a separate widget - height maps 0-100%
            // onto the axis's fixed nominal range (not its live/zoomed bounds, so the meaning of
            // "50% high" doesn't shift as the user zooms the RSSI/SNR scale).
            if (ap.channelUtilizationPercent() != null) {
                XYChart.Series<Number, Number> utilSeries = new XYChart.Series<>();
                double peakY = SPECTRUM_Y_NOMINAL_LOWER
                        + (SPECTRUM_Y_NOMINAL_UPPER - SPECTRUM_Y_NOMINAL_LOWER) * (ap.channelUtilizationPercent() / 100.0);
                for (int i = 0; i <= steps; i++) {
                    double f = startMhz + (endMhz - startMhz) * i / steps;
                    double gaussian = Math.exp(-Math.pow(f - centerMhz, 2) / (2 * sigma * sigma));
                    double y = SPECTRUM_Y_NOMINAL_LOWER + (peakY - SPECTRUM_Y_NOMINAL_LOWER) * gaussian;
                    utilSeries.getData().add(new XYChart.Data<>(f, y));
                }
                spectrumChart.getData().add(utilSeries);
                utilizationSeriesList.add(utilSeries);
            }
        }

        // Coloring needs the series' render Node, and peak labels need final axis pixel
        // positions - neither exists/settles after only one deferred layout pass (axis bounds
        // were just changed above too, which itself needs a pass to take effect before pixel
        // conversions are correct) - nesting two runLater calls reliably waits out both.
        Platform.runLater(() -> Platform.runLater(() -> {
            for (SpectrumPeakEntry p : peaks) {
                Color color = colorPalette.colorFor(p.bssid());
                String hex = CategoricalColorPalette.toWeb(color);
                if (p.series().getNode() != null) {
                    // Width/shadow come from the shared .spectrum-peak-curve class (keeps the
                    // curve legible against the busy translucent waterfall backdrop behind it) -
                    // only the per-AP stroke color is dynamic, so only that stays inline.
                    p.series().getNode().getStyleClass().add("spectrum-peak-curve");
                    p.series().getNode().setStyle("-fx-stroke: " + hex + ";");
                }
                double px = spectrumXAxis.getDisplayPosition(p.centerMhz());
                double py = spectrumYAxis.getDisplayPosition(p.rssiDbm());
                // Text, not Label: a Label is a Control and never actually rendered here even
                // after setManaged(false)+autosize() workarounds - Controls need a Skin, which
                // apparently never gets installed for a node injected directly into the chart's
                // plot-children Group this way. Text is a Shape (like the crosshair's Line,
                // which always rendered fine) and draws immediately from its own geometry with
                // no Skin involved - confirmed to actually fix it where the Label attempts didn't.
                Text label = new Text(labelFor(p.bssid()));
                label.setFill(color);
                label.getStyleClass().add("chart-annotation-label");
                label.setX(px + 4);
                // Clamp so labels for strong (near the top of the dBm range) peaks don't get
                // pushed above y=0 and clipped by the plot area - a real bug hit during testing,
                // since a -21dBm peak sits only a few pixels below the axis's -20dBm top edge.
                label.setY(Math.max(10, py - 8));
                label.setMouseTransparent(true);
                spectrumChart.addAnnotation(label);
                spectrumAnnotations.add(label);
            }
            for (XYChart.Series<Number, Number> utilSeries : utilizationSeriesList) {
                if (utilSeries.getNode() != null) {
                    utilSeries.getNode().getStyleClass().add("utilization-series");
                }
            }
        }));
    }

    /**
     * Mirrors {@link #spectrumYAxis}'s own tick marks (dBm) into SNR(dB) values in the slim side
     * pane, so the already-plotted RSSI curve can also be read as an SNR curve off the right-hand
     * scale - SNR = dBm - {@link NoiseEstimator#ESTIMATED_NOISE_FLOOR_DBM}, a constant shift.
     */
    private void refreshSnrAxisOverlay() {
        snrAxisPane.getChildren().clear();
        if (spectrumPlotHeight <= 0) {
            return;
        }
        for (Axis.TickMark<Number> tick : spectrumYAxis.getTickMarks()) {
            double dbm = tick.getValue().doubleValue();
            double y = spectrumYAxis.getDisplayPosition(dbm);
            if (y < 0 || y > spectrumPlotHeight) {
                continue;
            }
            double snr = dbm - NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM;
            Text label = new Text(String.format("%.0f", snr));
            label.setFill(Color.web("#c6cdd4"));
            label.getStyleClass().add("snr-axis-tick-label");
            label.setX(2);
            label.setY(y + 4);
            snrAxisPane.getChildren().add(label);
        }
    }

    private void maybeRefreshSpectrogram() {
        long now = System.currentTimeMillis();
        if (now - lastSpectrogramRenderMillis < SPECTROGRAM_MIN_REDRAW_INTERVAL_MILLIS) {
            return;
        }
        lastSpectrogramRenderMillis = now;
        refreshSpectrogram();
    }

    /**
     * Repaints the waterfall backdrop directly onto {@link #spectrumChart}'s own plot area (as a
     * mouse-transparent annotation sized to {@link #spectrumPlotWidth}/{@link #spectrumPlotHeight}
     * and positioned at that area's local origin, so it needs no separate offset - it already
     * shares the trace curve's coordinate space). X = frequency; Y = elapsed time over the last
     * {@link #SPECTROGRAM_WINDOW_SECONDS}s, most recent at the top (new data "falls" down as it
     * ages, the usual waterfall convention). Each cell's color is the strongest modelled RSSI
     * among all visible APs at that time/frequency (same bell-curve-per-AP model the trace uses,
     * evaluated on a time x frequency grid instead of a single sweep), painted at reduced alpha so
     * the live trace curve and its labels stay readable on top.
     */
    private void refreshSpectrogram() {
        if (spectrumPlotWidth <= 0 || spectrumPlotHeight <= 0) {
            return;
        }
        spectrogramCanvas.setWidth(spectrumPlotWidth);
        spectrogramCanvas.setHeight(spectrumPlotHeight);
        GraphicsContext gc = spectrogramCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, spectrumPlotWidth, spectrumPlotHeight);

        String band = bandSelector.getSelectionModel().getSelectedItem();
        double[] range = BAND_RANGE_MHZ.get(band);
        if (range == null) {
            return;
        }
        double freqLo = range[0];
        double freqHi = range[1];
        double sigma = range[2];

        long nowEpoch = Instant.now().getEpochSecond();
        long windowStart = Math.max(appStartEpochSecond, nowEpoch - SPECTROGRAM_WINDOW_SECONDS);
        long span = Math.max(1, nowEpoch - windowStart);

        List<String> bssidsInBand = new ArrayList<>();
        for (String bssid : historyStore.knownBssids()) {
            if (isVisible(bssid) && band.equals(bandByBssid.get(bssid))) {
                bssidsInBand.add(bssid);
            }
        }

        double cellW = spectrumPlotWidth / SPECTROGRAM_FREQ_BINS;
        double cellH = spectrumPlotHeight / SPECTROGRAM_TIME_BINS;

        for (int row = 0; row < SPECTROGRAM_TIME_BINS; row++) {
            // row 0 (top) = now, increasing row = further into the past.
            long t = nowEpoch - Math.round(span * (row + 0.5) / SPECTROGRAM_TIME_BINS);
            List<double[]> apsAtT = new ArrayList<>();
            for (String bssid : bssidsInBand) {
                RssiHistoryStore.Sample s = historyStore.nearestSample(bssid, t, 3);
                Double freq = freqMhzByBssid.get(bssid);
                if (s != null && freq != null) {
                    apsAtT.add(new double[]{freq, s.rssiDbm()});
                }
            }
            double y = row * cellH;
            for (int col = 0; col < SPECTROGRAM_FREQ_BINS; col++) {
                double f = freqLo + (col + 0.5) * (freqHi - freqLo) / SPECTROGRAM_FREQ_BINS;
                double dbm = NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM;
                for (double[] ap : apsAtT) {
                    double weight = Math.exp(-Math.pow(f - ap[0], 2) / (2 * sigma * sigma));
                    double contributed = NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM
                            + (ap[1] - NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM) * weight;
                    dbm = Math.max(dbm, contributed);
                }
                gc.setFill(heatColor(dbm));
                gc.fillRect(col * cellW, y, cellW + 1, cellH + 1);
            }
        }
    }

    /**
     * Noise floor -> dark navy, strong signal -> red, matching a conventional spectrum-analyzer
     * heat scale, rendered at reduced alpha so it reads as a backdrop rather than competing with
     * the live trace curve drawn on top of it.
     */
    private static Color heatColor(double dbm) {
        double t = (dbm - NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM) / (-20 - NoiseEstimator.ESTIMATED_NOISE_FLOOR_DBM);
        t = Math.max(0, Math.min(1, t));
        Color[] stops = {
                Color.web("#0a0a2a"), Color.web("#1f4fd8"), Color.web("#1fbf5c"),
                Color.web("#ffe135"), Color.web("#e6392b")
        };
        double scaled = t * (stops.length - 1);
        int idx = Math.max(0, Math.min(stops.length - 2, (int) Math.floor(scaled)));
        double frac = scaled - idx;
        Color c = stops[idx].interpolate(stops[idx + 1], frac);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.55);
    }

    private void exportApTable(boolean csv) {
        if (apItems.isEmpty()) {
            showAlert(Messages.get("dashboard.export.noData"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("dashboard.export.chooserTitle"));
        chooser.getExtensionFilters().add(csv
                ? new FileChooser.ExtensionFilter("CSV", "*.csv")
                : new FileChooser.ExtensionFilter("JSON", "*.json"));
        Stage stage = (Stage) root.getScene().getWindow();
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            if (csv) {
                CsvExporter.exportApSnapshots(new ArrayList<>(apItems), file);
            } else {
                JsonExporter.exportApSnapshots(new ArrayList<>(apItems), file);
            }
        } catch (Exception e) {
            showAlert(Messages.get("common.export.failed", e.getMessage()));
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setTitle(Messages.get("common.dialog.title.warning"));
        alert.setHeaderText(null);
        AppTheme.apply(alert);
        alert.showAndWait();
    }

}

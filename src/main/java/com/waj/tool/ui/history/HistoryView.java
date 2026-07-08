package com.waj.tool.ui.history;

import com.waj.tool.i18n.Messages;
import com.waj.tool.persistence.ScanLogDatabase;
import com.waj.tool.ui.dashboard.AnnotatedLineChart;
import com.waj.tool.ui.dashboard.ChartCrosshair;
import com.waj.tool.util.AppTheme;
import com.waj.tool.util.CategoricalColorPalette;
import com.waj.tool.util.CsvUtil;
import com.waj.tool.util.MonoTableCells;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-term history browser backed by the SQLite scan log: pick a time range, optionally narrow
 * the raw table to one BSSID, then check any number of BSSIDs in the checklist to overlay their
 * RSSI trends on one chart (same "shared color identity, ranked hover crosshair" design Dashboard's
 * live RSSI chart already uses).
 */
public final class HistoryView {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Above this many simultaneously-overlaid series, CategoricalColorPalette starts repeating
    // hues and ChartCrosshair's side panel gets tall enough to be awkward to scan - not a hard
    // cap (a user may genuinely want more), just a heads-up surfaced in the status label.
    private static final int RECOMMENDED_MAX_SERIES = 10;

    private static final ScanLogDatabase.BssidLabel ALL_BSSIDS = new ScanLogDatabase.BssidLabel("", "");

    private final BorderPane root = new BorderPane();
    private final ScanLogDatabase database;
    private final CategoricalColorPalette colorPalette;

    private final Map<String, Long> rangeMillisByLabel = new LinkedHashMap<>();
    private final ComboBox<String> rangeSelector = new ComboBox<>();
    private final ComboBox<ScanLogDatabase.BssidLabel> bssidFilter = new ComboBox<>();
    private final Label statusLabel = new Label("");

    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis(-100, -20, 10);
    private final AnnotatedLineChart<Number, Number> chart = new AnnotatedLineChart<>(xAxis, yAxis);
    private ChartCrosshair crosshair;
    private Button yZoomResetButton;

    private final TableView<ScanLogDatabase.ScanSampleRow> table = new TableView<>();
    private final ObservableList<ScanLogDatabase.ScanSampleRow> items = FXCollections.observableArrayList();

    // Multi-BSSID overlay checklist: one persistent checked-state property per BSSID (mirrors
    // DashboardView's visibilityByBssid - entries default to false/unchecked here rather than
    // Dashboard's default-true, since a week-long range can easily have far more distinct BSSIDs
    // than are useful to chart at once) plus the ListView's current backing items.
    private final ListView<ScanLogDatabase.BssidLabel> bssidChecklist = new ListView<>();
    private final ObservableList<ScanLogDatabase.BssidLabel> checklistItems = FXCollections.observableArrayList();
    private final Map<String, BooleanProperty> checkedByBssid = new LinkedHashMap<>();

    // Backs entriesAt(): each checked BSSID's plotted rows (oldest-first), keyed by BSSID, plus
    // the shared time origin every series' "elapsed seconds" x-value is computed from - using one
    // shared origin (the search range's own lower bound) rather than each series' own first row
    // is what keeps multiple overlaid series aligned on the same time axis instead of each
    // independently starting at x=0.
    private final Map<String, List<ScanLogDatabase.ScanSampleRow>> chartedRowsByBssid = new LinkedHashMap<>();
    private long chartRangeFromMillis = 0;
    private long chartRangeToMillis = 0;

    public HistoryView(ScanLogDatabase database, CategoricalColorPalette colorPalette) {
        this.database = database;
        this.colorPalette = colorPalette;
        buildControls();
        buildChart();
        buildChecklist();
        buildTable();

        VBox checklistBox = new VBox(4, new Label(Messages.get("history.checklist.label")), bssidChecklist);
        checklistBox.setPadding(new Insets(4));
        checklistBox.setPrefWidth(240);
        checklistBox.setMinWidth(180);
        VBox.setVgrow(bssidChecklist, Priority.ALWAYS);

        ScrollPane crosshairScroll = new ScrollPane(crosshair.getPanel());
        crosshairScroll.setFitToWidth(true);
        crosshairScroll.setPrefWidth(230);

        HBox chartRow = new HBox(4, chart, crosshairScroll);
        HBox.setHgrow(chart, Priority.ALWAYS);
        HBox chartToolbar = new HBox(6, yZoomResetButton);
        chartToolbar.setAlignment(Pos.CENTER_LEFT);
        VBox chartBox = new VBox(2, chartToolbar, chartRow);
        chartBox.getStyleClass().add("card");
        VBox.setVgrow(chartRow, Priority.ALWAYS);

        SplitPane bottomSplit = new SplitPane(checklistBox, table);
        bottomSplit.setDividerPositions(0.2);

        // Rotated layout (counter-clockwise once): chart on top, SSID list / log table on bottom.
        SplitPane mainSplit = new SplitPane(chartBox, bottomSplit);
        mainSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.55);
        root.setCenter(mainSplit);

        if (database == null) {
            statusLabel.setText(Messages.get("history.status.noDatabase"));
        }
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    /** Call once after the tab becomes visible or periodically to refresh the BSSID filter list. */
    public void refreshBssidList() {
        if (database == null) {
            return;
        }
        ScanLogDatabase.BssidLabel current = bssidFilter.getValue();
        List<ScanLogDatabase.BssidLabel> labels = new ArrayList<>();
        labels.add(ALL_BSSIDS);
        labels.addAll(database.distinctBssids());
        bssidFilter.getItems().setAll(labels);
        // Matched by bssid, not equals() - the paired SSID text can legitimately change between
        // refreshes (an AP renaming itself), which would otherwise make the previous selection
        // look unmatched even though it's the same physical BSSID.
        ScanLogDatabase.BssidLabel toSelect = current == null ? ALL_BSSIDS : labels.stream()
                .filter(l -> l.bssid().equals(current.bssid()))
                .findFirst()
                .orElse(ALL_BSSIDS);
        bssidFilter.setValue(toSelect);
    }

    private void buildControls() {
        rangeMillisByLabel.put(Messages.get("history.range.15min"), 15 * 60_000L);
        rangeMillisByLabel.put(Messages.get("history.range.1hour"), 60 * 60_000L);
        rangeMillisByLabel.put(Messages.get("history.range.24hours"), 24 * 60 * 60_000L);
        rangeMillisByLabel.put(Messages.get("history.range.7days"), 7L * 24 * 60 * 60_000L);
        rangeSelector.getItems().setAll(rangeMillisByLabel.keySet());
        rangeSelector.getSelectionModel().select(Messages.get("history.range.15min"));

        bssidFilter.getItems().add(ALL_BSSIDS);
        bssidFilter.setValue(ALL_BSSIDS);
        bssidFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(ScanLogDatabase.BssidLabel label) {
                if (label == null || label.bssid().isEmpty()) {
                    return Messages.get("history.bssidFilter.all");
                }
                String ssid = label.ssid() == null || label.ssid().isEmpty() ? "<hidden>" : label.ssid();
                return ssid + " (" + label.bssid() + ")";
            }

            @Override
            public ScanLogDatabase.BssidLabel fromString(String s) {
                return null;
            }
        });

        Button searchButton = new Button(Messages.get("history.button.search"));
        searchButton.setOnAction(e -> search());
        Button refreshBssidButton = new Button(Messages.get("history.button.refreshBssidList"));
        refreshBssidButton.setOnAction(e -> refreshBssidList());
        Button exportButton = new Button(Messages.get("history.button.exportVisibleCsv"));
        exportButton.setOnAction(e -> exportCsv());

        HBox controls = new HBox(8, new Label(Messages.get("history.label.range")), rangeSelector,
                new Label(Messages.get("history.label.bssid")), bssidFilter,
                searchButton, refreshBssidButton, exportButton, statusLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(8));
        root.setTop(controls);
    }

    private void buildChart() {
        chart.setTitle(Messages.get("history.chart.title"));
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        xAxis.setLabel(Messages.get("history.axis.elapsedSecondsInRange"));
        yAxis.setLabel(Messages.get("common.axis.rssiDbm"));
        yAxis.setAutoRanging(false);

        crosshair = new ChartCrosshair(chart, chart, xAxis,
                x -> Messages.get("history.crosshair.secondsFormat", x), this::entriesAt);
        yZoomResetButton = new Button(Messages.get("common.button.resetYAxis"));
        crosshair.installYAxisWheelZoom(yAxis, yZoomResetButton);
    }

    /**
     * Custom cell (checkbox + color swatch + "SSID (BSSID)" label) rather than a built-in
     * CheckBoxListCell, so the swatch can share the same per-BSSID identity color as every other
     * tab. ListCells are reused as the list scrolls, so the previously-bound checked-property must
     * be unbound before binding the new one on each updateItem() call - binding a fresh property
     * without unbinding the old one first would leave the checkbox listening to multiple BSSIDs'
     * state at once.
     */
    private void buildChecklist() {
        bssidChecklist.setItems(checklistItems);
        bssidChecklist.setPlaceholder(new Label(Messages.get("history.placeholder.noBssidInRange")));
        bssidChecklist.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Rectangle swatch = new Rectangle(10, 10);
            private final Label label = new Label();
            private final HBox row = new HBox(6, checkBox, swatch, label);
            private BooleanProperty boundProperty;

            {
                row.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(ScanLogDatabase.BssidLabel item, boolean empty) {
                super.updateItem(item, empty);
                if (boundProperty != null) {
                    checkBox.selectedProperty().unbindBidirectional(boundProperty);
                    boundProperty = null;
                }
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                swatch.setFill(colorPalette.colorFor(item.bssid()));
                String ssid = item.ssid() == null || item.ssid().isEmpty() ? "<hidden>" : item.ssid();
                label.setText(ssid + " (" + item.bssid() + ")");
                boundProperty = checkedProperty(item.bssid());
                checkBox.selectedProperty().bindBidirectional(boundProperty);
                setGraphic(row);
            }
        });
    }

    private BooleanProperty checkedProperty(String bssid) {
        return checkedByBssid.computeIfAbsent(bssid, k -> {
            BooleanProperty prop = new SimpleBooleanProperty(false);
            prop.addListener((obs, old, val) -> refreshChart());
            return prop;
        });
    }

    private List<ChartCrosshair.Entry> entriesAt(double elapsedSeconds) {
        long targetEpochMillis = chartRangeFromMillis + Math.round(elapsedSeconds * 1000);
        List<ChartCrosshair.Entry> result = new ArrayList<>();
        for (Map.Entry<String, List<ScanLogDatabase.ScanSampleRow>> e : chartedRowsByBssid.entrySet()) {
            String bssid = e.getKey();
            ScanLogDatabase.ScanSampleRow nearest = null;
            long bestDiff = Long.MAX_VALUE;
            for (ScanLogDatabase.ScanSampleRow row : e.getValue()) {
                long diff = Math.abs(row.tsEpochMilli() - targetEpochMillis);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    nearest = row;
                }
            }
            if (nearest != null) {
                String label = (nearest.ssid().isEmpty() ? "<hidden>" : nearest.ssid()) + " (" + bssid + ")";
                result.add(new ChartCrosshair.Entry(label, colorPalette.colorFor(bssid), nearest.rssiDbm()));
            }
        }
        return result;
    }

    private void buildTable() {
        TableColumn<ScanLogDatabase.ScanSampleRow, String> tsCol = new TableColumn<>(Messages.get("common.column.timestamp"));
        tsCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(d.getValue().tsEpochMilli()))));
        tsCol.setPrefWidth(150);
        MonoTableCells.applyTo(tsCol);

        TableColumn<ScanLogDatabase.ScanSampleRow, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().ssid()));
        TableColumn<ScanLogDatabase.ScanSampleRow, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().bssid()));

        TableColumn<ScanLogDatabase.ScanSampleRow, Number> channelCol = new TableColumn<>("Ch");
        channelCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().channel()));
        channelCol.setPrefWidth(45);
        MonoTableCells.applyTo(channelCol);

        TableColumn<ScanLogDatabase.ScanSampleRow, String> bandCol = new TableColumn<>("Band");
        bandCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().band()));
        bandCol.setPrefWidth(60);

        TableColumn<ScanLogDatabase.ScanSampleRow, Number> rssiCol = new TableColumn<>("RSSI(dBm)");
        rssiCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().rssiDbm()));
        MonoTableCells.applyTo(rssiCol);

        TableColumn<ScanLogDatabase.ScanSampleRow, Number> qualityCol = new TableColumn<>("Quality%");
        qualityCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().linkQuality()));
        qualityCol.setPrefWidth(70);
        MonoTableCells.applyTo(qualityCol);

        TableColumn<ScanLogDatabase.ScanSampleRow, String> phyCol = new TableColumn<>("PHY");
        phyCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().phyType()));
        phyCol.setPrefWidth(140);

        // "Security" (English), not the previous "セキュリティ" - matches Dashboard/Security
        // Audit's own column naming for the same concept instead of being the one place in the
        // app that translated it.
        TableColumn<ScanLogDatabase.ScanSampleRow, String> secCol = new TableColumn<>("Security");
        secCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().security()));

        table.getColumns().setAll(List.of(tsCol, ssidCol, bssidCol, channelCol, bandCol, rssiCol, qualityCol, phyCol, secCol));
        table.setItems(items);
        table.setPlaceholder(new Label(Messages.get("history.placeholder.searchPrompt")));
    }

    private void search() {
        if (database == null) {
            return;
        }
        long rangeMillis = rangeMillisByLabel.getOrDefault(rangeSelector.getValue(), 15 * 60_000L);
        long to = System.currentTimeMillis();
        long from = to - rangeMillis;
        chartRangeFromMillis = from;
        chartRangeToMillis = to;

        String bssidFilterValue = bssidFilter.getValue() == null ? "" : bssidFilter.getValue().bssid();
        List<ScanLogDatabase.ScanSampleRow> rows = database.querySamples(from, to, bssidFilterValue);
        items.setAll(rows);

        String countText = Messages.get("history.status.rowCount", rows.size());
        if (rows.size() >= ScanLogDatabase.MAX_QUERY_ROWS) {
            countText += " " + Messages.get("history.status.truncated", ScanLogDatabase.MAX_QUERY_ROWS);
        }
        statusLabel.setText(countText);

        // Populated from the full requested range, independent of the row-capped query above -
        // see ScanLogDatabase.distinctBssidsInRange's own javadoc for why deriving this list from
        // `rows` instead would silently drop BSSIDs that were genuinely present earlier in range.
        checklistItems.setAll(database.distinctBssidsInRange(from, to));

        refreshChart();
    }

    private record SeriesEntry(String bssid, XYChart.Series<Number, Number> series) {
    }

    private void refreshChart() {
        chart.getData().clear();
        chartedRowsByBssid.clear();
        if (database == null) {
            return;
        }
        List<String> checked = checklistItems.stream()
                .map(ScanLogDatabase.BssidLabel::bssid)
                .filter(bssid -> checkedProperty(bssid).get())
                .toList();

        if (checked.isEmpty()) {
            statusLabel.setText(Messages.get("history.status.rowCount", items.size())
                    + "  " + Messages.get("history.status.noBssidChecked"));
            return;
        }
        if (checked.size() > RECOMMENDED_MAX_SERIES) {
            statusLabel.setText(Messages.get("history.status.tooManySeries", checked.size(), RECOMMENDED_MAX_SERIES));
        } else {
            statusLabel.setText(Messages.get("history.status.rowCount", items.size()));
        }

        List<SeriesEntry> entries = new ArrayList<>();
        for (String bssid : checked) {
            List<ScanLogDatabase.ScanSampleRow> ordered =
                    database.querySamples(chartRangeFromMillis, chartRangeToMillis, bssid).reversed();
            chartedRowsByBssid.put(bssid, ordered);
            if (ordered.isEmpty()) {
                continue;
            }
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(bssid);
            for (ScanLogDatabase.ScanSampleRow row : ordered) {
                series.getData().add(new XYChart.Data<>((row.tsEpochMilli() - chartRangeFromMillis) / 1000.0, row.rssiDbm()));
            }
            chart.getData().add(series);
            entries.add(new SeriesEntry(bssid, series));
        }

        Platform.runLater(() -> {
            for (SeriesEntry e : entries) {
                if (e.series().getNode() != null) {
                    String hex = CategoricalColorPalette.toWeb(colorPalette.colorFor(e.bssid()));
                    e.series().getNode().setStyle("-fx-stroke: " + hex + ";");
                }
            }
        });
    }

    private void exportCsv() {
        if (items.isEmpty()) {
            showAlert(Messages.get("history.export.noRows"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("history.chooser.exportCsv"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showSaveDialog((Stage) root.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder("timestamp,ssid,bssid,channel,band,rssi_dbm,link_quality,phy_type,security\n");
            for (ScanLogDatabase.ScanSampleRow r : items) {
                sb.append(Instant.ofEpochMilli(r.tsEpochMilli())).append(',')
                        .append(CsvUtil.escapeField(r.ssid())).append(',').append(CsvUtil.escapeField(r.bssid())).append(',')
                        .append(r.channel()).append(',').append(r.band()).append(',')
                        .append(r.rssiDbm()).append(',').append(r.linkQuality()).append(',')
                        .append(CsvUtil.escapeField(r.phyType())).append(',').append(CsvUtil.escapeField(r.security())).append('\n');
            }
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            statusLabel.setText(Messages.get("history.status.exported", file.getName()));
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

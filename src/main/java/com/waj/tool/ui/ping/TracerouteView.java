package com.waj.tool.ui.ping;

import com.waj.tool.i18n.Messages;
import com.waj.tool.ping.TraceroutePoller;
import com.waj.tool.ping.TracerouteProbe;
import com.waj.tool.ui.dashboard.AnnotatedLineChart;
import com.waj.tool.ui.dashboard.ChartCrosshair;
import com.waj.tool.util.CategoricalColorPalette;
import com.waj.tool.util.MonoTableCells;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PingPlotter-style continuous route monitor: discovers a route's hops once (Windows {@code
 * tracert}), then keeps pinging every hop on a fixed interval, showing a per-hop stats table
 * alongside a shared "all hops overlaid, color per hop, hover-crosshair ranking" latency chart -
 * the same design language as the Dashboard's RSSI history chart, just for RTT instead of RSSI.
 */
public final class TracerouteView {

    private static final long PING_INTERVAL_MILLIS = 1000;

    private final BorderPane root = new BorderPane();
    private final TextField hostField = new TextField();
    private final Button startStopButton = new Button(Messages.get("traceroute.button.start"));
    private final Label statusLabel = new Label(Messages.get("traceroute.status.enterHostPrompt"));

    private final TableView<HopRow> table = new TableView<>();
    private final ObservableList<HopRow> hopRows = FXCollections.observableArrayList();

    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final AnnotatedLineChart<Number, Number> chart = new AnnotatedLineChart<>(xAxis, yAxis);
    private ChartCrosshair crosshair;

    private final HopRttHistory history = new HopRttHistory();
    private final CategoricalColorPalette colorPalette;
    private final long appStartEpochSecond = Instant.now().getEpochSecond();
    private final Map<Integer, HopStats> statsByHop = new HashMap<>();
    private final Map<Integer, String> ipByHop = new HashMap<>();

    private TraceroutePoller poller;
    private boolean running = false;

    /** One row of the hop table - a plain display DTO, rebuilt fresh each refresh. */
    public record HopRow(int hopNumber, String ip, String currentRtt, String minAvgMax, String lossPercent) {
    }

    private static final class HopStats {
        int sent = 0;
        int lost = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int successCount = 0;
        Integer lastRtt = null;
    }

    public TracerouteView(CategoricalColorPalette colorPalette) {
        this.colorPalette = colorPalette;
        buildToolbar();
        buildTable();
        buildChart();

        HBox chartRow = new HBox(4, chart, crosshair.getPanel());
        HBox.setHgrow(chart, Priority.ALWAYS);

        VBox tableBox = new VBox(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        // A plain HBox here previously gave the table a hard-fixed width with no way to resize
        // it against the chart - every other split-view tab in this app (Dashboard, History)
        // uses a SplitPane with a draggable divider instead, so this matches that same pattern.
        SplitPane mainSplit = new SplitPane(tableBox, chartRow);
        mainSplit.setDividerPositions(0.3);

        VBox center = new VBox(mainSplit);
        center.getStyleClass().add("card");
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        root.setCenter(center);
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    /** Stops the background poller - call when the application is shutting down. */
    public void shutdown() {
        if (poller != null) {
            poller.shutdown();
        }
    }

    private void buildToolbar() {
        hostField.setPromptText(Messages.get("traceroute.hostField.prompt"));
        hostField.setPrefWidth(220);
        // hostField is disabled for the whole time monitoring is running (see startMonitoring()/
        // onRouteDiscovered()), so Enter here can only ever mean "start" - no need to branch on
        // `running` the way the button's own handler below does.
        hostField.setOnAction(e -> startMonitoring());

        startStopButton.setOnAction(e -> {
            if (running) {
                stopMonitoring();
            } else {
                startMonitoring();
            }
        });

        HBox toolbar = new HBox(8, new Label(Messages.get("traceroute.label.host")), hostField, startStopButton, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        root.setTop(toolbar);
    }

    private void startMonitoring() {
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        if (host.isEmpty()) {
            statusLabel.setText(Messages.get("traceroute.status.hostRequired"));
            return;
        }
        history.clear();
        statsByHop.clear();
        ipByHop.clear();
        hopRows.clear();
        chart.getData().clear();
        statusLabel.setText(Messages.get("traceroute.status.discovering"));
        running = true;
        startStopButton.setText(Messages.get("traceroute.button.stop"));
        hostField.setDisable(true);

        if (poller == null) {
            poller = new TraceroutePoller(
                    hops -> Platform.runLater(() -> onRouteDiscovered(hops)),
                    results -> Platform.runLater(() -> onPingCycle(results)));
        }
        poller.start(host, PING_INTERVAL_MILLIS);
    }

    private void stopMonitoring() {
        running = false;
        startStopButton.setText(Messages.get("traceroute.button.start"));
        hostField.setDisable(false);
        statusLabel.setText(Messages.get("traceroute.status.stopped"));
        if (poller != null) {
            poller.stop();
        }
    }

    private void onRouteDiscovered(List<TracerouteProbe.Hop> hops) {
        if (hops.isEmpty()) {
            // Without this, running/the button/hostField were left exactly as they were mid-
            // discovery ("stop" shown, host field disabled) even though there's nothing left
            // running to stop - the user had to click "stop" for no reason before they could even
            // try a different host, with no hint that was necessary.
            statusLabel.setText(Messages.get("traceroute.status.discoveryFailed"));
            running = false;
            startStopButton.setText(Messages.get("traceroute.button.start"));
            hostField.setDisable(false);
            return;
        }
        for (TracerouteProbe.Hop hop : hops) {
            ipByHop.put(hop.number(), hop.ip());
            statsByHop.put(hop.number(), new HopStats());
        }
        statusLabel.setText(Messages.get("traceroute.status.monitoring", hops.size(), PING_INTERVAL_MILLIS / 1000.0));
        refreshTable();
    }

    private void onPingCycle(List<TraceroutePoller.HopResult> results) {
        Instant now = Instant.now();
        for (TraceroutePoller.HopResult r : results) {
            HopStats stats = statsByHop.computeIfAbsent(r.hopNumber(), k -> new HopStats());
            stats.sent++;
            stats.lastRtt = r.rttMillis();
            if (r.rttMillis() == null) {
                stats.lost++;
            } else {
                stats.min = Math.min(stats.min, r.rttMillis());
                stats.max = Math.max(stats.max, r.rttMillis());
                stats.sum += r.rttMillis();
                stats.successCount++;
                history.record(r.ip(), r.rttMillis(), now);
            }
        }
        refreshTable();
        refreshChart();
    }

    private void refreshTable() {
        List<HopRow> rows = new ArrayList<>();
        List<Integer> hopNumbers = new ArrayList<>(ipByHop.keySet());
        hopNumbers.sort(Integer::compareTo);
        for (Integer hopNumber : hopNumbers) {
            String ip = ipByHop.get(hopNumber);
            HopStats s = statsByHop.getOrDefault(hopNumber, new HopStats());
            String current = s.lastRtt == null ? "*" : s.lastRtt + " ms";
            String minAvgMax = s.successCount == 0 ? "-"
                    : String.format("%d / %.0f / %d ms", s.min, (double) s.sum / s.successCount, s.max);
            String loss = s.sent == 0 ? "-" : String.format("%.0f%%", 100.0 * s.lost / s.sent);
            rows.add(new HopRow(hopNumber, ip, current, minAvgMax, loss));
        }
        hopRows.setAll(rows);
    }

    private void buildTable() {
        TableColumn<HopRow, HopRow> colorCol = new TableColumn<>("");
        colorCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        colorCol.setSortable(false);
        colorCol.setPrefWidth(22);
        colorCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(HopRow row, boolean empty) {
                super.updateItem(row, empty);
                setGraphic(empty || row == null ? null : new Rectangle(12, 12, colorPalette.colorFor(row.ip())));
            }
        });

        TableColumn<HopRow, Number> hopCol = new TableColumn<>("Hop");
        hopCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().hopNumber()));
        hopCol.setPrefWidth(40);
        MonoTableCells.applyTo(hopCol);

        TableColumn<HopRow, String> ipCol = new TableColumn<>("IP");
        ipCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().ip()));
        ipCol.setPrefWidth(120);
        MonoTableCells.applyTo(ipCol);

        TableColumn<HopRow, String> rttCol = new TableColumn<>(Messages.get("traceroute.column.currentRtt"));
        rttCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().currentRtt()));
        rttCol.setPrefWidth(80);
        MonoTableCells.applyTo(rttCol);

        TableColumn<HopRow, String> statsCol = new TableColumn<>(Messages.get("traceroute.column.minAvgMax"));
        statsCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().minAvgMax()));
        statsCol.setPrefWidth(140);
        MonoTableCells.applyTo(statsCol);

        TableColumn<HopRow, String> lossCol = new TableColumn<>(Messages.get("traceroute.column.lossPercent"));
        lossCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().lossPercent()));
        lossCol.setPrefWidth(60);
        MonoTableCells.applyTo(lossCol);

        table.getColumns().setAll(List.of(colorCol, hopCol, ipCol, rttCol, statsCol, lossCol));
        table.setItems(hopRows);
        table.setPlaceholder(new Label(Messages.get("traceroute.status.enterHostPrompt")));
    }

    private void buildChart() {
        chart.setTitle(Messages.get("traceroute.chart.title"));
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        xAxis.setLabel(Messages.get("common.axis.elapsedSeconds"));
        yAxis.setLabel(Messages.get("traceroute.axis.rttMs"));
        xAxis.setAutoRanging(false);
        yAxis.setAutoRanging(true);

        crosshair = new ChartCrosshair(chart, chart, xAxis, this::formatTimeAxis, this::entriesAt, "ms");
    }

    private String formatTimeAxis(double elapsedSeconds) {
        return Messages.get("traceroute.axis.elapsedSecondsFormat", elapsedSeconds);
    }

    private record HopSeriesEntry(String ip, XYChart.Series<Number, Number> series) {
    }

    private void refreshChart() {
        double nowElapsed = Instant.now().getEpochSecond() - appStartEpochSecond;
        xAxis.setUpperBound(nowElapsed);
        xAxis.setLowerBound(Math.max(0, nowElapsed - HopRttHistory.RETENTION_SECONDS));

        chart.getData().clear();
        List<HopSeriesEntry> entries = new ArrayList<>();
        for (String hopIp : history.knownHopIps()) {
            List<HopRttHistory.Sample> samples = history.historyFor(hopIp);
            if (samples.isEmpty()) {
                continue;
            }
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(hopIp);
            for (HopRttHistory.Sample s : samples) {
                series.getData().add(new XYChart.Data<>(s.epochSecond() - appStartEpochSecond, s.rttMillis()));
            }
            entries.add(new HopSeriesEntry(hopIp, series));
        }
        for (HopSeriesEntry e : entries) {
            chart.getData().add(e.series());
        }

        Platform.runLater(() -> {
            for (HopSeriesEntry e : entries) {
                if (e.series().getNode() != null) {
                    String hex = CategoricalColorPalette.toWeb(colorPalette.colorFor(e.ip()));
                    e.series().getNode().setStyle("-fx-stroke: " + hex + "; -fx-stroke-width: 1.5px;");
                }
            }
        });
    }

    private List<ChartCrosshair.Entry> entriesAt(double elapsedSeconds) {
        long targetEpoch = appStartEpochSecond + Math.round(elapsedSeconds);
        List<ChartCrosshair.Entry> result = new ArrayList<>();
        for (String hopIp : history.knownHopIps()) {
            HopRttHistory.Sample sample = history.nearestSample(hopIp, targetEpoch, 5);
            if (sample != null) {
                result.add(new ChartCrosshair.Entry(hopIp, colorPalette.colorFor(hopIp), sample.rttMillis()));
            }
        }
        return result;
    }
}

package com.opensitesurvey.tool.ui.dashboard;

import com.opensitesurvey.tool.i18n.Messages;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.chart.Chart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shared vertical crosshair + ranked value-list side panel for XYChart-based views. On mouse
 * move over the chart's plot area, converts the cursor's pixel X to a data-space X value, asks
 * the caller for every series' value at that X ({@code entriesAt}), and lists them - color
 * swatch, label, value - sorted strongest-first in a side panel, ranking every visible AP's
 * dBm reading rather than showing just one selected series.
 *
 * <p>Mouse handlers are attached to the chart's {@code .chart-plot-background} node rather than
 * the chart itself, since that node's local coordinate space is what {@code Axis.getValueForDisplay}
 * / {@code getDisplayPosition} expect - the outer chart region also includes the Y-axis tick
 * label gutter, which would otherwise throw off every X conversion by that gutter's width. That
 * node doesn't exist until after the chart's first CSS/layout pass, so lookups are retried via
 * {@code Platform.runLater} until it appears.
 */
public final class ChartCrosshair {

    public record Entry(String label, Color color, double value, String extraInfo) {
        public Entry(String label, Color color, double value) {
            this(label, color, value, null);
        }
    }

    /** Implemented by chart subclasses that expose their protected plot-children list. */
    public interface PlotAnnotatable {
        void addAnnotation(Node node);

        void removeAnnotation(Node node);
    }

    private final VBox panel = new VBox(3);
    private final Label headerLabel = new Label();
    private final VBox entriesBox = new VBox(2);
    private final ScrollPane entriesScroll = new ScrollPane(entriesBox);
    private final Line crosshairLine = new Line();

    private final Chart chart;
    private final NumberAxis xAxis;
    private final Function<Double, String> xFormatter;
    private final Function<Double, List<Entry>> entriesAt;
    private final String unit;

    private double defaultYLower;
    private double defaultYUpper;
    private Region plotArea;

    public ChartCrosshair(Chart chart, PlotAnnotatable annotationTarget, NumberAxis xAxis,
                           Function<Double, String> xFormatter, Function<Double, List<Entry>> entriesAt) {
        this(chart, annotationTarget, xAxis, xFormatter, entriesAt, "dBm");
    }

    public ChartCrosshair(Chart chart, PlotAnnotatable annotationTarget, NumberAxis xAxis,
                           Function<Double, String> xFormatter, Function<Double, List<Entry>> entriesAt,
                           String unit) {
        this.chart = chart;
        this.xAxis = xAxis;
        this.xFormatter = xFormatter;
        this.entriesAt = entriesAt;
        this.unit = unit;

        crosshairLine.getStyleClass().add("crosshair-line");
        crosshairLine.setMouseTransparent(true);
        crosshairLine.setVisible(false);
        annotationTarget.addAnnotation(crosshairLine);

        // A caller (Dashboard, History) typically pins this panel to a fairly narrow fixed width
        // so the chart itself keeps most of the row's space - which used to mean a long entry
        // ("SSID (macsuffix): -36 dBm (チャネル使用率45%)") got silently clipped with an ellipsis,
        // hiding exactly the detail a user just hovered to read. Wrapping entriesBox in its own
        // ScrollPane (fitToWidth/fitToHeight both false, so it never squeezes its content down to
        // the viewport) turns that clipping into a scrollbar instead - nothing is ever hidden, it's
        // just scrolled into view on demand. Only a floor width is set on the outer panel, so it's
        // never awkwardly narrow when a hover position has just one short entry.
        panel.setMinWidth(210);
        panel.getStyleClass().add("crosshair-panel");
        headerLabel.getStyleClass().add("crosshair-header");
        entriesScroll.setFitToWidth(false);
        entriesScroll.setFitToHeight(false);
        entriesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        entriesScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        entriesScroll.getStyleClass().add("crosshair-scroll");
        VBox.setVgrow(entriesScroll, Priority.ALWAYS);
        panel.getChildren().addAll(headerLabel, entriesScroll);
        showPlaceholder();

        whenPlotAreaReady(this::wireMouseHandlers);
    }

    public VBox getPanel() {
        return panel;
    }

    /** Adds cursor-centered mouse-wheel Y-axis zoom, plus a reset button that restores the axis's bounds at call time. */
    public void installYAxisWheelZoom(NumberAxis yAxis, Button resetButton) {
        defaultYLower = yAxis.getLowerBound();
        defaultYUpper = yAxis.getUpperBound();
        resetButton.setOnAction(e -> {
            yAxis.setLowerBound(defaultYLower);
            yAxis.setUpperBound(defaultYUpper);
        });
        whenPlotAreaReady(plotArea -> plotArea.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
            double cursorY = yAxis.getValueForDisplay(e.getY()).doubleValue();
            double newLower = cursorY - (cursorY - yAxis.getLowerBound()) / factor;
            double newUpper = cursorY + (yAxis.getUpperBound() - cursorY) / factor;
            yAxis.setLowerBound(newLower);
            yAxis.setUpperBound(newUpper);
            e.consume();
        }));
    }

    private void wireMouseHandlers(Region plotArea) {
        this.plotArea = plotArea;
        plotArea.setOnMouseMoved(e -> {
            double dataX = xAxis.getValueForDisplay(e.getX()).doubleValue();
            headerLabel.setText(xFormatter.apply(dataX));
            renderEntries(entriesAt.apply(dataX));

            crosshairLine.setStartX(e.getX());
            crosshairLine.setEndX(e.getX());
            crosshairLine.setStartY(0);
            crosshairLine.setEndY(plotArea.getBoundsInLocal().getHeight());
            crosshairLine.setVisible(true);
        });
        // The panel sits right next to the chart (not inside plotArea), so moving the mouse onto
        // it - e.g. to reach entriesScroll's horizontal scrollbar and read a long entry that
        // didn't fit - crosses out of plotArea's own bounds and would otherwise immediately fire
        // this exited handler, wiping the very entries the user just moved over to read. Checking
        // the exit point's screen position against the panel's own current bounds (rather than
        // just always clearing) lets the mouse cross that boundary without losing the display;
        // the panel's own mirrored handler below covers the reverse direction.
        plotArea.setOnMouseExited(e -> {
            if (isPointOverNode(panel, e.getScreenX(), e.getScreenY())) {
                return;
            }
            crosshairLine.setVisible(false);
            showPlaceholder();
        });
        panel.setOnMouseExited(e -> {
            if (isPointOverNode(plotArea, e.getScreenX(), e.getScreenY())) {
                return;
            }
            crosshairLine.setVisible(false);
            showPlaceholder();
        });
    }

    private static boolean isPointOverNode(Node node, double screenX, double screenY) {
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        return bounds != null && bounds.contains(screenX, screenY);
    }

    private void showPlaceholder() {
        headerLabel.setText(Messages.get("common.chart.hoverPrompt"));
        entriesBox.getChildren().clear();
    }

    private void renderEntries(List<Entry> entries) {
        entriesBox.getChildren().clear();
        entries.stream()
                .sorted(Comparator.comparingDouble(Entry::value).reversed())
                .forEach(entry -> {
                    Rectangle swatch = new Rectangle(10, 10, entry.color());
                    String text = String.format("%s: %.0f %s", entry.label(), entry.value(), unit)
                            + (entry.extraInfo() == null ? "" : " (" + entry.extraInfo() + ")");
                    Label label = new Label(text);
                    HBox row = new HBox(6, swatch, label);
                    entriesBox.getChildren().add(row);
                });
    }

    private void whenPlotAreaReady(Consumer<Region> callback) {
        Node found = chart.lookup(".chart-plot-background");
        if (found instanceof Region plotArea) {
            callback.accept(plotArea);
        } else {
            Platform.runLater(() -> whenPlotAreaReady(callback));
        }
    }
}

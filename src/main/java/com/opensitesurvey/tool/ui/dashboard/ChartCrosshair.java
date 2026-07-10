package com.opensitesurvey.tool.ui.dashboard;

import com.opensitesurvey.tool.i18n.Messages;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.Chart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
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
    private final Line crosshairLine = new Line();

    private final Chart chart;
    private final NumberAxis xAxis;
    private final Function<Double, String> xFormatter;
    private final Function<Double, List<Entry>> entriesAt;
    private final String unit;

    private double defaultYLower;
    private double defaultYUpper;

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

        // No fixed prefWidth: a VBox's default computed width is already the widest child's
        // preferred width, so leaving it alone lets the panel grow to fit whatever the current
        // entries need (e.g. the longer "... (使用率100%)" suffix) without truncating, and shrink
        // back down when they don't. Only a floor is set, so it's never awkwardly narrow when a
        // hover position has just one short entry.
        panel.setMinWidth(210);
        panel.getStyleClass().add("crosshair-panel");
        headerLabel.getStyleClass().add("crosshair-header");
        panel.getChildren().addAll(headerLabel, entriesBox);
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
        plotArea.setOnMouseExited(e -> {
            crosshairLine.setVisible(false);
            showPlaceholder();
        });
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

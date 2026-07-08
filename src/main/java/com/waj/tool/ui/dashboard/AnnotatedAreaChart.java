package com.waj.tool.ui.dashboard;

import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.Axis;

/**
 * Exposes {@link AreaChart}'s protected plot-children list so callers can overlay annotation
 * nodes (crosshair lines, direct peak labels) that stay correctly positioned in the chart's own
 * data coordinate space - the standard JavaFX technique for chart annotations, since XYChart
 * doesn't offer a public API for this.
 */
public final class AnnotatedAreaChart<X, Y> extends AreaChart<X, Y> implements ChartCrosshair.PlotAnnotatable {

    public AnnotatedAreaChart(Axis<X> xAxis, Axis<Y> yAxis) {
        super(xAxis, yAxis);
    }

    @Override
    public void addAnnotation(Node node) {
        getPlotChildren().add(node);
    }

    @Override
    public void removeAnnotation(Node node) {
        getPlotChildren().remove(node);
    }
}

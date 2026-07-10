package com.opensitesurvey.tool.ui.dashboard;

import javafx.scene.Node;
import javafx.scene.chart.Axis;
import javafx.scene.chart.StackedBarChart;

/**
 * Exposes {@link StackedBarChart}'s protected plot-children list so callers can overlay annotation
 * nodes (e.g. a "recommended channel" label above one bar) that stay correctly positioned in the
 * chart's own data coordinate space - the standard JavaFX technique for chart annotations, used
 * the same way by {@link AnnotatedLineChart}/{@link AnnotatedAreaChart}.
 */
public final class AnnotatedStackedBarChart<X, Y> extends StackedBarChart<X, Y> implements ChartCrosshair.PlotAnnotatable {

    public AnnotatedStackedBarChart(Axis<X> xAxis, Axis<Y> yAxis) {
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

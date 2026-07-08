package com.waj.tool.ui.survey;

import com.waj.tool.model.SurveyPoint;
import com.waj.tool.util.SignalColorScale;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Renders an IDW-interpolated RSSI heatmap into a small (coarse-grid) {@link WritableImage}.
 * The caller upscales it with smoothing onto the floor-plan canvas rather than recomputing IDW
 * per screen pixel every frame.
 */
public final class HeatmapRenderer {

    public static final int GRID_WIDTH = 96;
    public static final int GRID_HEIGHT = 72;

    private HeatmapRenderer() {
    }

    public static WritableImage render(List<SurveyPoint> points, String targetBssid) {
        WritableImage image = new WritableImage(GRID_WIDTH, GRID_HEIGHT);
        PixelWriter writer = image.getPixelWriter();
        for (int gy = 0; gy < GRID_HEIGHT; gy++) {
            double y = (gy + 0.5) / GRID_HEIGHT;
            for (int gx = 0; gx < GRID_WIDTH; gx++) {
                double x = (gx + 0.5) / GRID_WIDTH;
                Double value = IdwInterpolator.interpolate(x, y, points, targetBssid);
                Color color = value == null
                        ? Color.TRANSPARENT
                        : SignalColorScale.colorFor(value).deriveColor(0, 1, 1, 0.55);
                writer.setColor(gx, gy, color);
            }
        }
        return image;
    }
}

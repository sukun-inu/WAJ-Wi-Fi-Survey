package com.waj.tool.ui.survey;

import com.waj.tool.model.SurveyPoint;

import java.util.List;

/** Inverse Distance Weighting interpolation over survey points, in normalized (0..1) coordinates. */
public final class IdwInterpolator {

    private static final double POWER = 2.0;
    private static final double EPSILON = 1e-9;

    private IdwInterpolator() {
    }

    /** @return the interpolated dBm value at (x, y), or {@code null} if no point carries a value for this target. */
    public static Double interpolate(double x, double y, List<SurveyPoint> points, String targetBssid) {
        double weightedSum = 0;
        double weightSum = 0;
        boolean any = false;
        for (SurveyPoint p : points) {
            Integer value = p.rssiFor(targetBssid);
            if (value == null) {
                continue;
            }
            any = true;
            double dx = p.xNorm - x;
            double dy = p.yNorm - y;
            double distSq = dx * dx + dy * dy;
            if (distSq < EPSILON) {
                return value.doubleValue();
            }
            double weight = 1.0 / Math.pow(distSq, POWER / 2.0);
            weightedSum += weight * value;
            weightSum += weight;
        }
        if (!any || weightSum == 0) {
            return null;
        }
        return weightedSum / weightSum;
    }
}

package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;

import java.util.List;

/**
 * Strategy interface for estimating an RSSI value at an arbitrary point on the floor plan from a
 * sparse set of real survey-point measurements. Implemented by {@link IdwInterpolator} (the
 * original, default algorithm) and {@link KrigingInterpolator}.
 */
public interface Interpolator {

    /**
     * @param x           normalized (0..1) floor-plan X coordinate.
     * @param y           normalized (0..1) floor-plan Y coordinate.
     * @param points      every recorded survey point (not just ones with data for {@code targetBssid}).
     * @param targetBssid the BSSID to estimate, or {@code null} for "strongest AP visible at this point".
     * @return the interpolated dBm value, or {@code null} if no point carries a value for this target.
     */
    Double interpolate(double x, double y, List<SurveyPoint> points, String targetBssid);
}

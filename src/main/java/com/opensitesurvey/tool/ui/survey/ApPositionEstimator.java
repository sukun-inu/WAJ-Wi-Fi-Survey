package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;

import java.util.List;

/**
 * Heuristic AP-position estimate from survey points: an RSSI-weighted centroid of every point
 * that detected the target BSSID, in the same normalized (0..1) floor-plan coordinate space as
 * {@link SurveyPoint}.
 *
 * <p>This is deliberately <b>not</b> true multilateration/trilateration - this app has no
 * real-world distance/scale calibration today ({@code SurveyProject.metersPerPixel} exists as a
 * field but is always persisted as {@code 0.0} and never read back anywhere), so converting RSSI
 * to a real-world distance estimate would have no meaningful unit to convert into. Weighting each
 * point's contribution by its own signal strength instead - a stronger reading pulls the estimate
 * toward itself more, the same "closer counts far more" intuition {@link IdwInterpolator}'s own
 * inverse-square distance weighting encodes - is a simple, well-known simplified localization
 * technique. It degrades gracefully with noisy/sparse data, unlike a true nonlinear
 * least-squares multilateration solve, which needs at least 3 well-distributed measurements and is
 * easy to make numerically unstable with real (noisy) RSSI-to-distance path-loss models.
 */
public final class ApPositionEstimator {

    /** @param sampleCount how many survey points contributed - shown alongside the estimate so a
     *                     user can judge its reliability (2 points is a much weaker basis than 10). */
    public record Estimate(double xNorm, double yNorm, int sampleCount) {
    }

    private ApPositionEstimator() {
    }

    /**
     * @return the estimated position, or {@code null} if {@code bssid} is missing/blank or fewer
     *         than 2 survey points detected it (one point alone gives no basis to pull the
     *         estimate in any particular direction away from that single reading).
     */
    public static Estimate estimate(List<SurveyPoint> points, String bssid) {
        if (bssid == null || bssid.isEmpty()) {
            return null;
        }
        double weightedX = 0;
        double weightedY = 0;
        double weightSum = 0;
        int count = 0;
        for (SurveyPoint p : points) {
            Integer rssi = p.rssiByBssid.get(bssid);
            if (rssi == null) {
                continue;
            }
            count++;
            // dBm -> linear power ratio (mW), the same conversion the dB scale is defined from -
            // this makes a strong reading (e.g. -30dBm) outweigh a weak one (e.g. -90dBm) by
            // orders of magnitude, matching how much more confidently a strong reading implies
            // "the AP is near here" than a weak one does.
            double weight = Math.pow(10.0, rssi / 10.0);
            weightedX += weight * p.xNorm;
            weightedY += weight * p.yNorm;
            weightSum += weight;
        }
        if (count < 2 || weightSum <= 0) {
            return null;
        }
        return new Estimate(weightedX / weightSum, weightedY / weightSum, count);
    }
}

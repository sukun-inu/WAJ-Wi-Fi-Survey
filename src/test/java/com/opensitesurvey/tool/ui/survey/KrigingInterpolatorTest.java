package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KrigingInterpolatorTest {

    private static SurveyPoint point(double x, double y, int rssi) {
        return new SurveyPoint(x, y, Map.of("AA:AA:AA:AA:AA:AA", rssi), Instant.now());
    }

    @Test
    void exactMatchAtSamplePointReturnsItsValue() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.8, 0.8, -80), point(0.5, 0.1, -60));
        Double value = KrigingInterpolator.INSTANCE.interpolate(0.2, 0.2, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-40.0, value);
    }

    @Test
    void singlePointAlwaysReturnsItsValueRegardlessOfQueryLocation() {
        List<SurveyPoint> points = List.of(point(0.5, 0.5, -55));
        assertEquals(-55.0, KrigingInterpolator.INSTANCE.interpolate(0.0, 0.0, points, "AA:AA:AA:AA:AA:AA"));
        assertEquals(-55.0, KrigingInterpolator.INSTANCE.interpolate(0.9, 0.9, points, "AA:AA:AA:AA:AA:AA"));
    }

    @Test
    void midpointBetweenSymmetricSamplesAveragesThem() {
        List<SurveyPoint> points = List.of(point(0.0, 0.5, -40), point(1.0, 0.5, -60));
        Double value = KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-50.0, value, 0.01);
    }

    @Test
    void closerSampleDominatesTheEstimate() {
        List<SurveyPoint> points = List.of(point(0.0, 0.5, -30), point(1.0, 0.5, -90));
        Double nearFirst = KrigingInterpolator.INSTANCE.interpolate(0.1, 0.5, points, "AA:AA:AA:AA:AA:AA");
        Double nearSecond = KrigingInterpolator.INSTANCE.interpolate(0.9, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertTrue(nearFirst > -60);
        assertTrue(nearSecond < -60);
    }

    @Test
    void unknownTargetReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.6, 0.6, -50));
        assertNull(KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "FF:FF:FF:FF:FF:FF"));
    }

    @Test
    void noPointsReturnsNull() {
        assertNull(KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, List.of(), "AA:AA:AA:AA:AA:AA"));
    }

    /**
     * Two points at the exact same normalized position give the ordinary-kriging system two
     * identical rows (their distance - and therefore variogram value - to every other point is
     * identical), making it singular. The interpolator must fall back to IDW rather than divide by
     * zero or return a NaN/garbage estimate.
     */
    @Test
    void duplicatePositionFallsBackToIdwInsteadOfFailing() {
        List<SurveyPoint> points = List.of(
                point(0.3, 0.3, -40),
                point(0.3, 0.3, -60),
                point(0.8, 0.8, -70));
        Double kriging = KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        Double idw = IdwInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(idw, kriging);
    }

    @Test
    void addingAPointInvalidatesTheCachedSolveForTheSameListInstance() {
        List<SurveyPoint> points = new ArrayList<>();
        points.add(point(0.0, 0.5, -40));
        points.add(point(1.0, 0.5, -40));
        Double beforeAdd = KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-40.0, beforeAdd, 0.01);

        // A third point, much stronger, right at the query location - if the cached (stale)
        // 2-point solve were reused, this would still report roughly -40 instead of the new point's
        // own value dominating at its exact location.
        points.add(point(0.5, 0.5, -20));
        Double afterAdd = KrigingInterpolator.INSTANCE.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-20.0, afterAdd, 0.01);
    }
}

package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdwInterpolatorTest {

    private static SurveyPoint point(double x, double y, int rssi) {
        return new SurveyPoint(x, y, Map.of("AA:AA:AA:AA:AA:AA", rssi), Instant.now());
    }

    @Test
    void exactMatchAtSamplePointReturnsItsValue() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.8, 0.8, -80));
        Double value = IdwInterpolator.interpolate(0.2, 0.2, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-40.0, value);
    }

    @Test
    void midpointBetweenEqualDistanceSamplesAveragesThem() {
        List<SurveyPoint> points = List.of(point(0.0, 0.5, -40), point(1.0, 0.5, -60));
        Double value = IdwInterpolator.interpolate(0.5, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(-50.0, value, 0.001);
    }

    @Test
    void closerSampleDominatesTheEstimate() {
        List<SurveyPoint> points = List.of(point(0.0, 0.5, -30), point(1.0, 0.5, -90));
        Double nearFirst = IdwInterpolator.interpolate(0.1, 0.5, points, "AA:AA:AA:AA:AA:AA");
        Double nearSecond = IdwInterpolator.interpolate(0.9, 0.5, points, "AA:AA:AA:AA:AA:AA");
        assertEquals(true, nearFirst > -60);
        assertEquals(true, nearSecond < -60);
    }

    @Test
    void unknownTargetReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40));
        assertNull(IdwInterpolator.interpolate(0.5, 0.5, points, "FF:FF:FF:FF:FF:FF"));
    }

    @Test
    void noPointsReturnsNull() {
        assertNull(IdwInterpolator.interpolate(0.5, 0.5, List.of(), "AA:AA:AA:AA:AA:AA"));
    }
}

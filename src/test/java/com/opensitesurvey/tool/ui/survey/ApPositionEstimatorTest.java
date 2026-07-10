package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApPositionEstimatorTest {

    private static SurveyPoint point(double x, double y, int rssi) {
        return new SurveyPoint(x, y, Map.of("AA:AA:AA:AA:AA:AA", rssi), Instant.now());
    }

    @Test
    void nullBssidReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.8, 0.8, -50));
        assertNull(ApPositionEstimator.estimate(points, null));
    }

    @Test
    void blankBssidReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.8, 0.8, -50));
        assertNull(ApPositionEstimator.estimate(points, ""));
    }

    @Test
    void fewerThanTwoContributingPointsReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40));
        assertNull(ApPositionEstimator.estimate(points, "AA:AA:AA:AA:AA:AA"));
    }

    @Test
    void noContributingPointsReturnsNull() {
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -40), point(0.8, 0.8, -50));
        assertNull(ApPositionEstimator.estimate(points, "FF:FF:FF:FF:FF:FF"));
    }

    @Test
    void equalSignalStrengthAtSymmetricPointsAveragesToTheMidpoint() {
        List<SurveyPoint> points = List.of(point(0.0, 0.0, -50), point(1.0, 1.0, -50));
        ApPositionEstimator.Estimate estimate = ApPositionEstimator.estimate(points, "AA:AA:AA:AA:AA:AA");
        assertEquals(0.5, estimate.xNorm(), 0.001);
        assertEquals(0.5, estimate.yNorm(), 0.001);
        assertEquals(2, estimate.sampleCount());
    }

    @Test
    void strongerReadingPullsTheEstimateTowardItself() {
        List<SurveyPoint> points = List.of(point(0.0, 0.5, -30), point(1.0, 0.5, -90));
        ApPositionEstimator.Estimate estimate = ApPositionEstimator.estimate(points, "AA:AA:AA:AA:AA:AA");
        assertTrue(estimate.xNorm() < 0.5, "estimate should be pulled toward the much stronger -30dBm reading at x=0");
    }

    @Test
    void ignoresPointsWithNoDataForTheTargetBssid() {
        SurveyPoint noData = new SurveyPoint(0.9, 0.9, Map.of("FF:FF:FF:FF:FF:FF", -20), Instant.now());
        List<SurveyPoint> points = List.of(point(0.2, 0.2, -50), point(0.4, 0.4, -50), noData);
        ApPositionEstimator.Estimate estimate = ApPositionEstimator.estimate(points, "AA:AA:AA:AA:AA:AA");
        assertEquals(2, estimate.sampleCount());
        assertEquals(0.3, estimate.xNorm(), 0.001);
        assertEquals(0.3, estimate.yNorm(), 0.001);
    }
}

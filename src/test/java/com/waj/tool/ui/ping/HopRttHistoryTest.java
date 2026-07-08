package com.waj.tool.ui.ping;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopRttHistoryTest {

    @Test
    void knownHopIpsReflectsEveryRecordedHop() {
        HopRttHistory history = new HopRttHistory();
        history.record("10.0.0.1", 5, Instant.ofEpochSecond(1000));
        history.record("10.0.0.2", 12, Instant.ofEpochSecond(1000));

        Set<String> known = history.knownHopIps();
        assertEquals(2, known.size());
        assertTrue(known.contains("10.0.0.1"));
        assertTrue(known.contains("10.0.0.2"));
    }

    @Test
    void timeoutDoesNotRecordASample() {
        HopRttHistory history = new HopRttHistory();
        history.record("10.0.0.1", null, Instant.ofEpochSecond(1000));
        assertTrue(history.knownHopIps().isEmpty());
    }

    @Test
    void nearestSampleFindsClosestWithinTolerance() {
        HopRttHistory history = new HopRttHistory();
        history.record("10.0.0.1", 5, Instant.ofEpochSecond(1000));
        history.record("10.0.0.1", 8, Instant.ofEpochSecond(1010));
        history.record("10.0.0.1", 11, Instant.ofEpochSecond(1020));

        HopRttHistory.Sample nearest = history.nearestSample("10.0.0.1", 1012, 5);
        assertEquals(8, nearest.rttMillis());
    }

    @Test
    void aHopThatStopsRespondingIsFullyPrunedNotJustItsOldSamples() {
        HopRttHistory history = new HopRttHistory();
        history.record("10.0.0.1", 5, Instant.ofEpochSecond(1000));
        history.record("10.0.0.2", 12, Instant.ofEpochSecond(1000));
        history.record("10.0.0.2", 12, Instant.ofEpochSecond(1000 + HopRttHistory.RETENTION_SECONDS + 1));

        Set<String> known = history.knownHopIps();
        assertTrue(known.contains("10.0.0.2"));
        assertTrue(!known.contains("10.0.0.1"));
        assertTrue(history.historyFor("10.0.0.1").isEmpty());
    }

    @Test
    void nearestSampleReturnsNullOutsideTolerance() {
        HopRttHistory history = new HopRttHistory();
        history.record("10.0.0.1", 5, Instant.ofEpochSecond(1000));
        assertNull(history.nearestSample("10.0.0.1", 2000, 5));
    }
}

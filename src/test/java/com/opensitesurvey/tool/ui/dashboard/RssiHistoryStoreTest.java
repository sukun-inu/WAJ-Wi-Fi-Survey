package com.waj.tool.ui.dashboard;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.security.SecurityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssiHistoryStoreTest {

    private static ApSnapshot ap(String bssid, int rssi) {
        return new ApSnapshot("S1", bssid, 1, 2412000, "2.4GHz", rssi, 90, "n/a",
                true, SecurityType.WPA2, null, Instant.now());
    }

    @Test
    void knownBssidsReflectsEveryRecordedAp() {
        RssiHistoryStore store = new RssiHistoryStore();
        store.record(ap("AA:AA:AA:AA:AA:01", -40), Instant.ofEpochSecond(1000));
        store.record(ap("BB:BB:BB:BB:BB:02", -50), Instant.ofEpochSecond(1000));

        Set<String> known = store.knownBssids();
        assertEquals(2, known.size());
        assertTrue(known.contains("AA:AA:AA:AA:AA:01"));
        assertTrue(known.contains("BB:BB:BB:BB:BB:02"));
    }

    @Test
    void nearestSampleFindsClosestWithinTolerance() {
        RssiHistoryStore store = new RssiHistoryStore();
        store.record(ap("AA:AA:AA:AA:AA:01", -40), Instant.ofEpochSecond(1000));
        store.record(ap("AA:AA:AA:AA:AA:01", -45), Instant.ofEpochSecond(1010));
        store.record(ap("AA:AA:AA:AA:AA:01", -50), Instant.ofEpochSecond(1020));

        RssiHistoryStore.Sample nearest = store.nearestSample("AA:AA:AA:AA:AA:01", 1012, 5);
        assertEquals(-45, nearest.rssiDbm());
    }

    @Test
    void nearestSampleReturnsNullOutsideTolerance() {
        RssiHistoryStore store = new RssiHistoryStore();
        store.record(ap("AA:AA:AA:AA:AA:01", -40), Instant.ofEpochSecond(1000));

        assertNull(store.nearestSample("AA:AA:AA:AA:AA:01", 2000, 5));
    }

    @Test
    void nearestSampleReturnsNullForUnknownBssid() {
        RssiHistoryStore store = new RssiHistoryStore();
        assertNull(store.nearestSample("FF:FF:FF:FF:FF:FF", 1000, 5));
    }

    @Test
    void anApThatStopsAppearingIsFullyPrunedNotJustItsOldSamples() {
        RssiHistoryStore store = new RssiHistoryStore();
        store.record(ap("AA:AA:AA:AA:AA:01", -40), Instant.ofEpochSecond(1000));
        // AA never appears again; BB keeps getting recorded well past AA's retention window -
        // AA's entry must be dropped entirely, not just left sitting with its stale sample.
        store.record(ap("BB:BB:BB:BB:BB:02", -50), Instant.ofEpochSecond(1000));
        store.record(ap("BB:BB:BB:BB:BB:02", -50), Instant.ofEpochSecond(1000 + RssiHistoryStore.RETENTION_SECONDS + 1));

        Set<String> known = store.knownBssids();
        assertTrue(known.contains("BB:BB:BB:BB:BB:02"));
        assertTrue(!known.contains("AA:AA:AA:AA:AA:01"));
        assertTrue(store.historyFor("AA:AA:AA:AA:AA:01").isEmpty());
    }
}

package com.opensitesurvey.tool.ui.dashboard;

import com.opensitesurvey.tool.model.ApSnapshot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps a rolling per-BSSID RSSI history (last {@link #RETENTION_SECONDS}) for the trend chart. */
final class RssiHistoryStore {

    record Sample(long epochSecond, int rssiDbm) {
    }

    // Package-private (not private) so DashboardView can bound its X axis to exactly this same
    // window instead of duplicating the magic number and risking the two drifting apart.
    static final long RETENTION_SECONDS = 300;

    private final Map<String, Deque<Sample>> historyByBssid = new HashMap<>();

    void record(ApSnapshot ap, Instant now) {
        Deque<Sample> dq = historyByBssid.computeIfAbsent(ap.bssid(), k -> new ArrayDeque<>());
        dq.addLast(new Sample(now.getEpochSecond(), ap.rssiDbm()));
        long cutoff = now.getEpochSecond() - RETENTION_SECONDS;
        while (!dq.isEmpty() && dq.peekFirst().epochSecond() < cutoff) {
            dq.pollFirst();
        }
        // An AP that stops appearing never gets record() called again under its own BSSID, so
        // its deque would otherwise sit here unpruned forever - sweep every entry's most recent
        // sample on every call (piggybacking on whichever AP's poll tick triggered it) so a
        // long-gone AP doesn't keep dragging the chart's auto-ranging X axis back to whenever it
        // was last seen, hours ago, squeezing the currently-relevant curves into a sliver.
        historyByBssid.entrySet().removeIf(entry -> {
            Sample last = entry.getValue().peekLast();
            return last == null || last.epochSecond() < cutoff;
        });
    }

    List<Sample> historyFor(String bssid) {
        return new ArrayList<>(historyByBssid.getOrDefault(bssid, new ArrayDeque<>()));
    }

    /** Every BSSID with at least one retained sample - used to draw all APs, not just the currently selected one. */
    Set<String> knownBssids() {
        return new java.util.HashSet<>(historyByBssid.keySet());
    }

    /** The sample closest to {@code targetEpochSecond}, or {@code null} if none is within {@code toleranceSeconds}. */
    Sample nearestSample(String bssid, long targetEpochSecond, long toleranceSeconds) {
        Deque<Sample> dq = historyByBssid.get(bssid);
        if (dq == null || dq.isEmpty()) {
            return null;
        }
        Sample best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Sample s : dq) {
            long diff = Math.abs(s.epochSecond() - targetEpochSecond);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return (best != null && bestDiff <= toleranceSeconds) ? best : null;
    }
}

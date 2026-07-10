package com.opensitesurvey.tool.ui.ping;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolling per-hop RTT history (last {@link #RETENTION_SECONDS}) for the traceroute latency chart.
 * Mirrors {@code com.opensitesurvey.tool.ui.dashboard.RssiHistoryStore}'s corrected pruning behavior: a hop
 * that stops responding entirely has its whole entry dropped (not just its old samples), so it
 * can't drag the chart's bounded X axis back to whenever it was last seen.
 */
final class HopRttHistory {

    record Sample(long epochSecond, int rttMillis) {
    }

    static final long RETENTION_SECONDS = 300;

    private final Map<String, Deque<Sample>> historyByHopIp = new HashMap<>();

    /** No-op for a timed-out ping ({@code rttMillis == null}) - nothing to plot, and a single miss shouldn't drop the hop. */
    void record(String hopIp, Integer rttMillis, Instant now) {
        if (rttMillis == null) {
            return;
        }
        Deque<Sample> dq = historyByHopIp.computeIfAbsent(hopIp, k -> new ArrayDeque<>());
        dq.addLast(new Sample(now.getEpochSecond(), rttMillis));
        long cutoff = now.getEpochSecond() - RETENTION_SECONDS;
        while (!dq.isEmpty() && dq.peekFirst().epochSecond() < cutoff) {
            dq.pollFirst();
        }
        historyByHopIp.entrySet().removeIf(entry -> {
            Sample last = entry.getValue().peekLast();
            return last == null || last.epochSecond() < cutoff;
        });
    }

    List<Sample> historyFor(String hopIp) {
        return new ArrayList<>(historyByHopIp.getOrDefault(hopIp, new ArrayDeque<>()));
    }

    Set<String> knownHopIps() {
        return new HashSet<>(historyByHopIp.keySet());
    }

    /** The sample closest to {@code targetEpochSecond}, or {@code null} if none is within {@code toleranceSeconds}. */
    Sample nearestSample(String hopIp, long targetEpochSecond, long toleranceSeconds) {
        Deque<Sample> dq = historyByHopIp.get(hopIp);
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

    void clear() {
        historyByHopIp.clear();
    }
}

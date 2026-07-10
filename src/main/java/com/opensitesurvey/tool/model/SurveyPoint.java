package com.waj.tool.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One recorded floor-plan click: normalized (0..1) position plus the full RSSI snapshot
 * (every BSSID visible at that moment), so the target AP can be changed later and the
 * heatmap recomputed retroactively without re-surveying.
 */
public class SurveyPoint {

    public double xNorm;
    public double yNorm;
    public Map<String, Integer> rssiByBssid = new LinkedHashMap<>();
    public long epochSecond;
    /** Host pinged when this point was recorded, or {@code null}/empty if no ping target was configured. */
    public String pingHost;
    /** Round-trip time in milliseconds, or {@code null} if no ping target was set or the ping timed out. */
    public Integer pingRttMs;

    public SurveyPoint() {
    }

    public SurveyPoint(double xNorm, double yNorm, Map<String, Integer> rssiByBssid, Instant timestamp) {
        this.xNorm = xNorm;
        this.yNorm = yNorm;
        this.rssiByBssid = rssiByBssid;
        this.epochSecond = timestamp.getEpochSecond();
    }

    /** @param bssid target BSSID, or {@code null} for "strongest AP visible at this point" mode. */
    public Integer rssiFor(String bssid) {
        if (bssid == null) {
            return rssiByBssid.values().stream().max(Integer::compareTo).orElse(null);
        }
        return rssiByBssid.get(bssid);
    }
}

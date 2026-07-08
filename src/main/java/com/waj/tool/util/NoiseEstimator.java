package com.waj.tool.util;

/**
 * Wlanapi.dll exposes no noise-floor field ({@code WLAN_BSS_ENTRY} has RSSI and link quality
 * only) - a generic client NIC cannot measure it without vendor-specific OIDs. This is a fixed,
 * clearly-labelled estimate, not a measurement: a typical indoor 2.4/5GHz noise floor.
 */
public final class NoiseEstimator {

    public static final int ESTIMATED_NOISE_FLOOR_DBM = -95;

    private NoiseEstimator() {
    }

    public static int estimatedSnrDb(int rssiDbm) {
        return rssiDbm - ESTIMATED_NOISE_FLOOR_DBM;
    }
}

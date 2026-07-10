package com.opensitesurvey.tool.util;

/** Maps {@code DOT11_PHY_TYPE} enum values (windot11.h) to human-readable Wi-Fi generation labels. */
public final class PhyTypeUtil {

    private PhyTypeUtil() {
    }

    public static String label(int dot11PhyType) {
        return switch (dot11PhyType) {
            case 0 -> "unknown";
            case 1 -> "FHSS";
            case 2 -> "DSSS";
            case 3 -> "IR";
            case 4 -> "OFDM (802.11a)";
            case 5 -> "HRDSSS (802.11b)";
            case 6 -> "ERP (802.11g)";
            case 7 -> "HT (802.11n / Wi-Fi 4)";
            case 8 -> "VHT (802.11ac / Wi-Fi 5)";
            case 9 -> "DMG (802.11ad)";
            case 10 -> "HE (802.11ax / Wi-Fi 6)";
            case 11 -> "EHT (802.11be / Wi-Fi 7)";
            default -> "phy(" + dot11PhyType + ")";
        };
    }
}

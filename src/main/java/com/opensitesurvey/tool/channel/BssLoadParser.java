package com.opensitesurvey.tool.channel;

/**
 * Parses the optional 802.11 BSS Load information element (tag 11) out of a beacon/probe
 * response's raw IE blob. Unlike RSSI (which this app can always measure locally), channel
 * utilization is something only the AP itself knows and chooses to report - many consumer APs
 * omit this element entirely, in which case {@link #parse(byte[])} returns {@code null}.
 */
public final class BssLoadParser {

    private static final int EID_BSS_LOAD = 11;

    public record BssLoad(int stationCount, int channelUtilizationPercent) {
    }

    private BssLoadParser() {
    }

    /**
     * BSS Load element body (802.11 spec): Station Count (2 bytes LE), Channel Utilization
     * (1 byte, 0-255 representing the fraction of time the AP sensed the channel busy via CCA),
     * Available Admission Capacity (2 bytes LE, not used here).
     *
     * @return the parsed element, or {@code null} if the AP didn't include one.
     */
    public static BssLoad parse(byte[] ie) {
        if (ie == null) {
            return null;
        }
        int pos = 0;
        while (pos + 2 <= ie.length) {
            int eid = ie[pos] & 0xFF;
            int len = ie[pos + 1] & 0xFF;
            int contentStart = pos + 2;
            if (contentStart + len > ie.length) {
                break; // truncated/corrupt tail - stop parsing defensively
            }
            if (eid == EID_BSS_LOAD && len >= 3) {
                int stationCount = readU16LE(ie, contentStart);
                int channelUtilizationRaw = ie[contentStart + 2] & 0xFF;
                int channelUtilizationPercent = Math.round(channelUtilizationRaw * 100f / 255f);
                return new BssLoad(stationCount, channelUtilizationPercent);
            }
            pos = contentStart + len;
        }
        return null;
    }

    private static int readU16LE(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }
}

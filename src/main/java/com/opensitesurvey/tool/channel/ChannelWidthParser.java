package com.waj.tool.channel;

/**
 * Parses the optional HT Operation (tag 61) and VHT Operation (tag 192) information elements out
 * of a beacon/probe response's raw IE blob, to determine how many 20MHz-equivalent channel slots
 * an AP actually occupies - a 40/80/160MHz-wide AP overlaps far more of the band than its single
 * reported primary channel suggests, which the previous exact-primary-channel-match overlap model
 * in {@link ChannelPlanner} didn't account for.
 *
 * <p>Deliberately does not parse the HE Operation element (tag 255/extension 36) for 6GHz width -
 * that element's "6 GHz Operation Information" subfield is optional and variably present, and
 * 6GHz channels are already treated as non-overlapping PSCs by {@link ChannelPlanner} regardless
 * of width, so a 6GHz AP always falls back to the 20MHz/primary-channel-only result here.
 */
public final class ChannelWidthParser {

    private static final int EID_HT_OPERATION = 61;
    private static final int EID_VHT_OPERATION = 192;

    /**
     * @param widthMhz               20, 40, 80, or 160.
     * @param effectiveCenterChannel the channel number to measure overlap distance from - equal
     *                               to the primary channel for a plain 20MHz AP, but the midpoint
     *                               (HT40) or VHT-reported segment-0 center (80/160MHz) otherwise.
     */
    public record WidthInfo(int widthMhz, int effectiveCenterChannel) {
    }

    private ChannelWidthParser() {
    }

    public static WidthInfo parse(byte[] ie, int primaryChannel, String band) {
        if (ie == null || "6GHz".equals(band)) {
            return new WidthInfo(20, primaryChannel);
        }

        WidthInfo result = new WidthInfo(20, primaryChannel);

        int pos = 0;
        while (pos + 2 <= ie.length) {
            int eid = ie[pos] & 0xFF;
            int len = ie[pos + 1] & 0xFF;
            int contentStart = pos + 2;
            if (contentStart + len > ie.length) {
                break; // truncated/corrupt tail - stop parsing defensively
            }
            if (eid == EID_HT_OPERATION && len >= 2) {
                result = parseHt(ie, contentStart, primaryChannel);
            } else if (eid == EID_VHT_OPERATION && len >= 3) {
                WidthInfo vht = parseVht(ie, contentStart);
                if (vht != null) {
                    result = vht; // VHT Operation is authoritative over HT's 40MHz-only view when present
                }
            }
            pos = contentStart + len;
        }
        return result;
    }

    /**
     * HT Operation element body (802.11 9.4.2.57): byte 0 is the primary channel (redundant with
     * the BSS entry's own channel, not re-read here); byte 1's low 2 bits are the Secondary
     * Channel Offset (1=above, 3=below, 0/2=none/reserved) and bit 2 is the STA Channel Width Set
     * flag - both must indicate a real secondary channel for this to count as 40MHz.
     */
    private static WidthInfo parseHt(byte[] ie, int contentStart, int primaryChannel) {
        int htInfo1 = ie[contentStart + 1] & 0xFF;
        int secondaryOffset = htInfo1 & 0x03;
        boolean anyWidth = (htInfo1 & 0x04) != 0;
        if (!anyWidth || (secondaryOffset != 1 && secondaryOffset != 3)) {
            return new WidthInfo(20, primaryChannel);
        }
        // Secondary channel sits 4 channel-number-units (20MHz, using this app's existing
        // 4-units-per-20MHz convention - see ChannelPlanner's class javadoc) above/below the
        // primary; the 40MHz block's center is halfway between the two.
        int direction = secondaryOffset == 1 ? 1 : -1;
        return new WidthInfo(40, primaryChannel + direction * 2);
    }

    /**
     * VHT Operation element body (802.11 9.4.2.159): byte 0 is Channel Width (0 = defer to HT's
     * 20/40MHz result, 1 = 80MHz, 2 = 160MHz, 3 = 80+80MHz - treated the same as 160MHz here since
     * this is already a heuristic overlap model, not an exact spectrum simulation), byte 1 is
     * Channel Center Frequency Segment 0 - a real 802.11 channel number directly comparable to
     * every other channel number this app already works with, no unit conversion needed.
     *
     * @return {@code null} if Channel Width is 0 (defer to the HT Operation result instead).
     */
    private static WidthInfo parseVht(byte[] ie, int contentStart) {
        int channelWidth = ie[contentStart] & 0xFF;
        if (channelWidth == 0) {
            return null;
        }
        int segment0 = ie[contentStart + 1] & 0xFF;
        if (segment0 == 0) {
            return null; // malformed/absent center segment - nothing usable to fall back on
        }
        int widthMhz = channelWidth == 1 ? 80 : 160;
        return new WidthInfo(widthMhz, segment0);
    }
}

package com.waj.tool.util;

/** Frequency (kHz, as reported by Wlanapi.dll) &lt;-&gt; Wi-Fi channel number conversions. */
public final class ChannelUtil {

    private ChannelUtil() {
    }

    public static int frequencyKhzToChannel(int freqKhz) {
        int freqMhz = freqKhz / 1000;
        if (freqMhz == 2484) {
            return 14;
        }
        if (freqMhz >= 2412 && freqMhz <= 2472) {
            return (freqMhz - 2407) / 5;
        }
        if (freqMhz >= 5000 && freqMhz < 5895) {
            return (freqMhz - 5000) / 5;
        }
        if (freqMhz >= 5955 && freqMhz <= 7115) {
            return (freqMhz - 5950) / 5;
        }
        return 0;
    }

    /**
     * Inverse of {@link #frequencyKhzToChannel(int)} - a channel number's center frequency in
     * MHz, for positioning it on a frequency-domain chart axis. Round-trips exactly against
     * {@code frequencyKhzToChannel} for every real channel in each band.
     */
    public static double channelToFrequencyMhz(String band, int channel) {
        return switch (band) {
            case "2.4GHz" -> channel == 14 ? 2484 : channel * 5 + 2407;
            case "5GHz" -> channel * 5 + 5000;
            default -> channel * 5 + 5950; // 6GHz
        };
    }

    public static String band(int freqKhz) {
        int freqMhz = freqKhz / 1000;
        if (freqMhz < 3000) {
            return "2.4GHz";
        }
        // Matches frequencyKhzToChannel()'s own 5GHz/6GHz cutoff (5955MHz) exactly - real channel
        // plans never place a center frequency in the 5895-5954MHz gap between them, but keeping
        // both methods' boundaries aligned avoids the internally-contradictory "band=6GHz but
        // channel=0" a mismatched cutoff would otherwise produce for any frequency in that gap.
        if (freqMhz < 5955) {
            return "5GHz";
        }
        return "6GHz";
    }
}

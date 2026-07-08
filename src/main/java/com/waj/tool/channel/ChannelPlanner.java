package com.waj.tool.channel;

import com.waj.tool.model.ApSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Heuristic channel-congestion scoring and recommendation - not a rigorous RF propagation model,
 * but the same spirit of "sum of nearby signal strength weighted by channel overlap" used by
 * consumer Wi-Fi analyzer tools. For 2.4GHz, adjacent channels within 4 of each other are treated
 * as overlapping with a triangular falloff (channels are only 5MHz apart but each occupies about
 * 22MHz). For 5/6GHz, channels are treated as independent (only an exact-channel match counts),
 * since those bands are normally deployed non-overlapping.
 *
 * <p>When an AP reports real channel utilization (802.11 BSS Load element, {@link
 * ApSnapshot#channelUtilizationPercent()}), that measured value is blended 50/50 with the
 * RSSI-derived estimate - APs that don't report it (the field is {@code null}) fall back to the
 * original RSSI-only estimate unchanged, so recommendations degrade gracefully on networks with
 * older/non-reporting hardware instead of silently treating "unknown" as "zero load".
 */
public final class ChannelPlanner {

    private static final int[] CANDIDATES_24 = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    private static final int[] RECOMMENDABLE_24 = {1, 6, 11};
    // 120, 124, 128 (part of J56/W56) were previously missing here - a real gap, not an
    // intentional exclusion like the DFS-avoidance one below: every other J56 channel
    // (100-116, 132-140) was already present, so the diagram would have shown an unexplained
    // hole in the middle of that group for no regulatory reason.
    private static final int[] CANDIDATES_5 = {36, 40, 44, 48, 52, 56, 60, 64,
            100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165};
    // Deliberately excludes every DFS-required channel (52-140/J53+J56) - this heuristic tool
    // can't detect radar, so it only ever suggests a channel that's safe to use without DFS.
    private static final int[] RECOMMENDABLE_5 = {36, 40, 44, 48, 149, 153, 157, 161, 165};
    // Japan (MIC) permits Wi-Fi only in the lower 5925-6425MHz slice of 6GHz (UNII-5) - the
    // previous {1,5,...,45} list stopped at ~6115MHz, well short of that, apparently reusing a
    // 2.4GHz-shaped channel count rather than the real 6GHz PSC (Preferred Scanning Channel)
    // list. 1,5,9,...,93 (4-channel steps) are every PSC in that permitted range; unlike 5GHz,
    // 6GHz has no DFS-restricted subset for client devices, so every candidate here is also
    // recommendable (see recommendableFor()'s default branch).
    private static final int[] CANDIDATES_6 = {1, 5, 9, 13, 17, 21, 25, 29, 33, 37, 41, 45,
            49, 53, 57, 61, 65, 69, 73, 77, 81, 85, 89, 93};

    /**
     * A 5GHz sub-band group as designated under Japanese radio law (J52/J53/J56, commonly also
     * called W52/W53/W56) - used to render Channel Planning as separate per-group panels instead
     * of one combined 5GHz chart, matching how these groups are conventionally documented.
     */
    public record SubBand(String label, int[] channels, double startMhz, double endMhz) {
    }

    public static final List<SubBand> SUBBANDS_5GHZ = List.of(
            new SubBand("J52", new int[]{36, 40, 44, 48}, 5150, 5250),
            new SubBand("J53", new int[]{52, 56, 60, 64}, 5250, 5350),
            new SubBand("J56", new int[]{100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140}, 5470, 5730)
    );

    private ChannelPlanner() {
    }

    /**
     * @param allScores               every scored channel for the band, for charting.
     * @param perChannelContributions each channel's score broken down by contributing AP, in the
     *                                same order/units as {@code allScores} (the two are computed
     *                                from the same pass, so they can never drift apart).
     */
    public record Recommendation(int channel, double score, Map<Integer, Double> allScores,
                                  Map<Integer, List<ApContribution>> perChannelContributions) {

        /** One AP's share of a single channel's congestion score. */
        public record ApContribution(String bssid, String ssid, double weight, double contribution,
                                      Integer utilizationPercent) {
        }
    }

    private record ScoreResult(double total, List<Recommendation.ApContribution> contributions) {
    }

    public static Recommendation recommend(List<ApSnapshot> aps, String band) {
        int[] candidates = candidatesFor(band);
        int[] recommendable = recommendableFor(band);

        Map<Integer, Double> scores = new TreeMap<>();
        Map<Integer, List<Recommendation.ApContribution>> contributions = new TreeMap<>();
        for (int c : candidates) {
            putScore(aps, band, c, scores, contributions);
        }
        for (ApSnapshot ap : aps) {
            if (ap.band().equals(band) && !scores.containsKey(ap.channel())) {
                putScore(aps, band, ap.channel(), scores, contributions);
            }
        }

        // recommendable is always a subset of candidates for every band (see the arrays above),
        // so every channel touched below was already scored in the first loop.
        int bestChannel = recommendable[0];
        double bestScore = scores.get(bestChannel);
        for (int c : recommendable) {
            double s = scores.get(c);
            if (s < bestScore) {
                bestScore = s;
                bestChannel = c;
            }
        }
        return new Recommendation(bestChannel, bestScore, scores, contributions);
    }

    private static void putScore(List<ApSnapshot> aps, String band, int channel,
                                  Map<Integer, Double> scores,
                                  Map<Integer, List<Recommendation.ApContribution>> contributions) {
        ScoreResult result = scoreDetailed(aps, band, channel);
        scores.put(channel, result.total());
        contributions.put(channel, result.contributions());
    }

    private static ScoreResult scoreDetailed(List<ApSnapshot> aps, String band, int channel) {
        double total = 0;
        List<Recommendation.ApContribution> contributions = new ArrayList<>();
        for (ApSnapshot ap : aps) {
            if (!ap.band().equals(band)) {
                continue;
            }
            double weight = band.equals("2.4GHz")
                    ? Math.max(0, 1.0 - Math.abs(ap.channel() - channel) / 4.0)
                    : (ap.channel() == channel ? 1.0 : 0.0);
            if (weight <= 0) {
                continue;
            }
            double strength = Math.max(0, ap.rssiDbm() + 100); // -100dBm -> 0, -30dBm -> 70
            Integer utilization = ap.channelUtilizationPercent();
            double combined = utilization != null ? 0.5 * strength + 0.5 * utilization : strength;
            double contribution = weight * combined;
            total += contribution;
            contributions.add(new Recommendation.ApContribution(
                    ap.bssid(), ap.ssid(), weight, contribution, utilization));
        }
        return new ScoreResult(total, contributions);
    }

    private static int[] candidatesFor(String band) {
        return switch (band) {
            case "2.4GHz" -> CANDIDATES_24;
            case "5GHz" -> CANDIDATES_5;
            default -> CANDIDATES_6;
        };
    }

    private static int[] recommendableFor(String band) {
        return switch (band) {
            case "2.4GHz" -> RECOMMENDABLE_24;
            case "5GHz" -> RECOMMENDABLE_5;
            default -> CANDIDATES_6;
        };
    }
}

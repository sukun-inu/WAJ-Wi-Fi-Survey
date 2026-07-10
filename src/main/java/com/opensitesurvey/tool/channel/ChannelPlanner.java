package com.waj.tool.channel;

import com.waj.tool.model.ApSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Heuristic channel-congestion scoring and recommendation - not a rigorous RF propagation model,
 * but the same spirit of "sum of nearby signal strength weighted by channel overlap" used by
 * consumer Wi-Fi analyzer tools. For 2.4GHz, adjacent channels are treated as overlapping with a
 * triangular falloff (channels are only 5MHz apart but each occupies about 22MHz). For 5/6GHz,
 * a plain 20MHz AP only overlaps its exact channel, since those bands are normally deployed
 * non-overlapping - but an AP actually operating a wider HT40/VHT80/VHT160 channel (see {@link
 * ChannelWidthParser}) is scored as overlapping every candidate channel within its real occupied
 * width, not just its single reported primary channel.
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

        /**
         * One AP's share of a single channel's congestion score.
         *
         * @param homeChannel the AP's own reported primary channel - NOT necessarily the channel
         *                    this contribution is scored against (a wide HT40/VHT80/160 AP scores
         *                    non-zero, weight &lt; 1.0 contributions against every channel it
         *                    overlaps, but only ever "lives" on one). Callers that want to draw an
         *                    AP's own occupancy curve, as opposed to reading its congestion
         *                    contribution, should match on this rather than on {@code weight}.
         * @param widthMhz    the AP's own detected channel width (20/40/80/160), for callers that
         *                    want to draw that occupancy curve at its real width instead of a
         *                    fixed per-band default.
         */
        public record ApContribution(String bssid, String ssid, int homeChannel, double weight, double contribution,
                                      Integer utilizationPercent, int widthMhz) {
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
            // Distance is measured from the AP's *effective* center (its primary channel for a
            // plain 20MHz AP, or the HT40/VHT80/VHT160 block's actual center otherwise - see
            // ChannelWidthParser). A channel genuinely inside that AP's own declared occupied
            // width gets full weight - an 80MHz AP occupies all four of its 20MHz sub-channels
            // equally, there's no physical reason its own edge channel should count for less than
            // its middle one - while a channel beyond that footprint falls back to the original
            // distance-based falloff (modelling adjacent-channel leakage into spectrum the AP
            // isn't actually using). halfWidthUnits is 2 for the plain-20MHz case, which combined
            // with the `channelWidthMhz() > 20` guard means this never fires for a 20MHz AP - the
            // formula reduces to exactly the original fixed-radius falloff for both bands.
            double widthFactor = ap.channelWidthMhz() / 20.0;
            double halfWidthUnits = widthFactor * 2.0;
            double distance = Math.abs(ap.effectiveCenterChannel() - channel);
            double falloffRadius = (band.equals("2.4GHz") ? 4.0 : 2.0) * widthFactor;
            double weight = (ap.channelWidthMhz() > 20 && distance <= halfWidthUnits)
                    ? 1.0
                    : Math.max(0, 1.0 - distance / falloffRadius);
            if (weight <= 0) {
                continue;
            }
            double strength = Math.max(0, ap.rssiDbm() + 100); // -100dBm -> 0, -30dBm -> 70
            Integer utilization = ap.channelUtilizationPercent();
            double combined = utilization != null ? 0.5 * strength + 0.5 * utilization : strength;
            double contribution = weight * combined;
            total += contribution;
            contributions.add(new Recommendation.ApContribution(
                    ap.bssid(), ap.ssid(), ap.channel(), weight, contribution, utilization, ap.channelWidthMhz()));
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

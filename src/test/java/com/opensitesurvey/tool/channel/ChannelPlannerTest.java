package com.opensitesurvey.tool.channel;

import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.security.SecurityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelPlannerTest {

    private static ApSnapshot ap(int channel, String band, int rssi) {
        return new ApSnapshot("test", "AA:BB:CC:DD:EE:FF", channel, 0, band, rssi, 90, "n/a",
                true, SecurityType.WPA2, null, Instant.now());
    }

    private static ApSnapshot wideAp(int channel, String band, int rssi, int widthMhz, int effectiveCenterChannel) {
        return new ApSnapshot("test", "AA:BB:CC:DD:EE:FF", channel, 0, band, rssi, 90, "n/a",
                true, SecurityType.WPA2, null, Instant.now(), widthMhz, effectiveCenterChannel);
    }

    @Test
    void recommendsLeastCongestedStandardChannelIn24Ghz() {
        List<ApSnapshot> aps = List.of(
                ap(1, "2.4GHz", -30),
                ap(1, "2.4GHz", -35),
                ap(6, "2.4GHz", -40)
        );
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(aps, "2.4GHz");
        assertEquals(11, rec.channel());
    }

    @Test
    void adjacentChannelInterferenceReducesAsDistanceGrows() {
        List<ApSnapshot> aps = List.of(ap(6, "2.4GHz", -40));
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(aps, "2.4GHz");
        double scoreAt6 = rec.allScores().get(6);  // distance 0
        double scoreAt4 = rec.allScores().get(4);  // distance 2
        double scoreAt2 = rec.allScores().get(2);  // distance 4 (no overlap in this model)
        assertTrue(scoreAt6 > scoreAt4);
        assertTrue(scoreAt4 > scoreAt2);
        assertEquals(0.0, scoreAt2);
    }

    @Test
    void fiveGhzTreatsChannelsAsIndependent() {
        List<ApSnapshot> aps = List.of(ap(36, "5GHz", -30));
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(aps, "5GHz");
        assertEquals(0.0, rec.allScores().get(40));
        assertTrue(rec.allScores().get(36) > 0);
    }

    @Test
    void emptyScanRecommendsFirstStandardChannelWithZeroScore() {
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(List.of(), "2.4GHz");
        assertEquals(1, rec.channel());
        assertEquals(0.0, rec.score());
    }

    @Test
    void channelUtilizationIncreasesScoreWhenReported() {
        ApSnapshot withUtilization = new ApSnapshot("test", "AA:BB:CC:DD:EE:FF", 6, 0, "2.4GHz", -40, 90,
                "n/a", true, SecurityType.WPA2, 80, Instant.now());
        ApSnapshot withoutUtilization = ap(6, "2.4GHz", -40);

        double scoreWith = ChannelPlanner.recommend(List.of(withUtilization), "2.4GHz").allScores().get(6);
        double scoreWithout = ChannelPlanner.recommend(List.of(withoutUtilization), "2.4GHz").allScores().get(6);

        assertTrue(scoreWith > scoreWithout);
    }

    @Test
    void perChannelContributionsSumToTheSameTotalAsAllScores() {
        List<ApSnapshot> aps = List.of(ap(1, "2.4GHz", -30), ap(1, "2.4GHz", -35), ap(6, "2.4GHz", -40));
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(aps, "2.4GHz");
        for (Integer channel : rec.allScores().keySet()) {
            double summed = rec.perChannelContributions().get(channel).stream()
                    .mapToDouble(ChannelPlanner.Recommendation.ApContribution::contribution)
                    .sum();
            assertEquals(rec.allScores().get(channel), summed, 0.0001);
        }
    }

    @Test
    void fiveGhzCandidatesIncludeTheFullJ56RangeWithNoGap() {
        // 120/124/128 were previously missing from the candidate list - every channel from 100 to
        // 140 in steps of 4 must be scored, or the J56 diagram would show an unexplained hole.
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(List.of(), "5GHz");
        for (int ch = 100; ch <= 140; ch += 4) {
            assertTrue(rec.allScores().containsKey(ch), "missing channel " + ch);
        }
    }

    @Test
    void sixGhzCandidatesCoverJapansPermittedRange() {
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(List.of(), "6GHz");
        assertTrue(rec.allScores().containsKey(1));
        assertTrue(rec.allScores().containsKey(93));
        assertTrue(rec.allScores().size() >= 24);
    }

    @Test
    void wideChannelApScoresFullWeightAcrossItsWholeOccupiedWidth() {
        // VHT80 AP: primary channel 36, VHT segment-0 center 42 (spans 36-48) - every one of those
        // four 20MHz channels is genuinely inside this AP's occupied 80MHz block, so none of them
        // should be discounted relative to the others (previously: 36/48 scored 0.25x, 40/44 0.75x,
        // an unphysical edge-vs-middle artifact of centering the falloff on effectiveCenterChannel).
        ApSnapshot wideAp = wideAp(36, "5GHz", -30, 80, 42);
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(List.of(wideAp), "5GHz");
        double scoreAt36 = rec.allScores().get(36);
        assertEquals(70.0, scoreAt36, 0.0001); // -30dBm -> strength 70, weight 1.0
        assertEquals(scoreAt36, rec.allScores().get(40), 0.0001);
        assertEquals(scoreAt36, rec.allScores().get(44), 0.0001);
        assertEquals(scoreAt36, rec.allScores().get(48), 0.0001);
        // A channel well outside the 80MHz footprint gets no score from this AP at all.
        assertEquals(0.0, rec.allScores().get(149));
    }

    @Test
    void wideChannelApStillScoresFullWeightAtItsOwnReportedChannel() {
        // Regression guard for ChannelCongestionRule, which reads rec.allScores().get(ap.channel())
        // assuming an AP always contributes its full strength-based score at its own reported
        // channel. Before the fix above, a lone strong VHT80 AP scored only 17.5 there (weight
        // 0.25), falling below AppConfig's default channelCongestionThreshold of 50.0 and silently
        // no longer firing a WARNING that the exact same physical AP/RSSI used to trigger.
        ApSnapshot wideAp = wideAp(36, "5GHz", -30, 80, 42);
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(List.of(wideAp), "5GHz");
        assertTrue(rec.allScores().get(wideAp.channel()) >= 50.0);
    }

    @Test
    void subBandsCoverExactlyTheDocumentedJChannels() {
        ChannelPlanner.SubBand j52 = ChannelPlanner.SUBBANDS_5GHZ.get(0);
        ChannelPlanner.SubBand j53 = ChannelPlanner.SUBBANDS_5GHZ.get(1);
        ChannelPlanner.SubBand j56 = ChannelPlanner.SUBBANDS_5GHZ.get(2);
        assertEquals("J52", j52.label());
        assertArrayEquals(new int[]{36, 40, 44, 48}, j52.channels());
        assertEquals("J53", j53.label());
        assertArrayEquals(new int[]{52, 56, 60, 64}, j53.channels());
        assertEquals("J56", j56.label());
        assertArrayEquals(new int[]{100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140}, j56.channels());
    }
}

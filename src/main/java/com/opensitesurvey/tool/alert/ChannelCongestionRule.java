package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.channel.ChannelPlanner;
import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.ScanSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fires a WARNING when a channel currently in use crosses the configured congestion score threshold. */
public final class ChannelCongestionRule implements AlertRule {

    private static final String[] BANDS = {"2.4GHz", "5GHz", "6GHz"};

    @Override
    public List<Alert> evaluate(ScanSnapshot snapshot, AlertContext context) {
        List<Alert> alerts = new ArrayList<>();
        if (!context.config.channelCongestionAlertEnabled) {
            // Forget which channels were flagged rather than leaving it frozen: otherwise a
            // channel that becomes congested for a *different* reason while this rule is disabled
            // won't get a fresh WARNING once re-enabled, since the stale key already "matches".
            context.congestedChannelKeys.clear();
            return alerts;
        }
        Set<String> currentlyCongested = new HashSet<>();
        for (String band : BANDS) {
            List<ApSnapshot> inBand = snapshot.accessPoints().stream().filter(a -> a.band().equals(band)).toList();
            if (inBand.isEmpty()) {
                continue;
            }
            Set<Integer> channelsInUse = new HashSet<>();
            for (ApSnapshot ap : inBand) {
                channelsInUse.add(ap.channel());
            }
            ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(inBand, band);
            for (int channel : channelsInUse) {
                double score = rec.allScores().getOrDefault(channel, 0.0);
                String key = band + ":" + channel;
                if (score >= context.config.channelCongestionThreshold) {
                    currentlyCongested.add(key);
                    if (!context.congestedChannelKeys.contains(key)) {
                        alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.WARNING,
                                Messages.get("alert.category.channelCongestion"),
                                Messages.get("alert.message.channelCongestion",
                                        band, channel, score, context.config.channelCongestionThreshold),
                                null));
                    }
                }
            }
        }
        context.congestedChannelKeys.clear();
        context.congestedChannelKeys.addAll(currentlyCongested);
        return alerts;
    }
}

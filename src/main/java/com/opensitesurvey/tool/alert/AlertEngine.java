package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.model.ScanSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Runs every registered {@link AlertRule} against each incoming snapshot. Not thread-safe - confine to one thread. */
public final class AlertEngine {

    private final List<AlertRule> rules;
    private final AlertContext context;

    public AlertEngine(AlertContext context) {
        this.context = context;
        this.rules = List.of(new RssiThresholdRule(), new UnknownApRule(), new NewSsidRule(), new ChannelCongestionRule());
    }

    public List<Alert> onSnapshot(ScanSnapshot snapshot) {
        List<Alert> fired = new ArrayList<>();
        for (AlertRule rule : rules) {
            fired.addAll(rule.evaluate(snapshot, context));
        }
        return fired;
    }
}

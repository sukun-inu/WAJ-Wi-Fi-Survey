package com.waj.tool.alert;

import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fires a WARNING when an AP's RSSI drops below the configured threshold, and an INFO when it recovers. */
public final class RssiThresholdRule implements AlertRule {

    @Override
    public List<Alert> evaluate(ScanSnapshot snapshot, AlertContext context) {
        List<Alert> alerts = new ArrayList<>();
        if (!context.config.rssiAlertEnabled) {
            // Forget which APs were below threshold rather than leaving it frozen: otherwise an
            // AP that recovers while this rule is disabled produces a spurious "RSSI回復" alert
            // the moment the rule is re-enabled, for a recovery the user was never told about.
            context.belowThresholdBssids.clear();
            return alerts;
        }
        Set<String> currentlyBelow = new HashSet<>();
        for (ApSnapshot ap : snapshot.accessPoints()) {
            if (ap.rssiDbm() < context.config.rssiThresholdDbm) {
                currentlyBelow.add(ap.bssid());
                if (!context.belowThresholdBssids.contains(ap.bssid())) {
                    alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.WARNING,
                            Messages.get("alert.category.rssiDropped"),
                            Messages.get("alert.message.rssiDropped",
                                    ap.ssid().isEmpty() ? "<hidden>" : ap.ssid(), ap.bssid(),
                                    ap.rssiDbm(), context.config.rssiThresholdDbm),
                            ap.bssid()));
                }
            }
        }
        for (String bssid : context.belowThresholdBssids) {
            if (!currentlyBelow.contains(bssid)) {
                alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.INFO,
                        Messages.get("alert.category.rssiRecovered"),
                        Messages.get("alert.message.rssiRecovered", bssid), bssid));
            }
        }
        context.belowThresholdBssids.clear();
        context.belowThresholdBssids.addAll(currentlyBelow);
        return alerts;
    }
}

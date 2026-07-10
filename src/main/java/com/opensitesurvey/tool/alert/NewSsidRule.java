package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.ScanSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Fires an INFO alert the first time a new SSID appears - except during the very first snapshot
 * processed, which establishes the baseline (otherwise every AP already in the environment would
 * fire a "new SSID" alert on startup).
 */
public final class NewSsidRule implements AlertRule {

    private boolean baselineEstablished = false;

    @Override
    public List<Alert> evaluate(ScanSnapshot snapshot, AlertContext context) {
        List<Alert> alerts = new ArrayList<>();
        // seenSsids is updated unconditionally, even while the rule is disabled - otherwise an
        // SSID that first appears while disabled is never recorded, and re-enabling the rule
        // later would then misfire a "new SSID" alert for an AP that has actually been present
        // the whole time. Only the alert *emission* is gated on the enabled flag.
        boolean enabled = context.config.newSsidAlertEnabled;
        for (ApSnapshot ap : snapshot.accessPoints()) {
            if (ap.ssid().isEmpty()) {
                continue;
            }
            boolean isNew = context.seenSsids.add(ap.ssid());
            if (enabled && isNew && baselineEstablished) {
                alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.INFO,
                        Messages.get("alert.category.newSsid"),
                        Messages.get("alert.message.newSsid", ap.ssid()), ap.bssid()));
            }
        }
        baselineEstablished = true;
        return alerts;
    }
}

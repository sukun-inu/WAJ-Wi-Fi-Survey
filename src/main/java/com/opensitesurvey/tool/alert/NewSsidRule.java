package com.waj.tool.alert;

import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;

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
        if (context.config.newSsidAlertEnabled) {
            for (ApSnapshot ap : snapshot.accessPoints()) {
                if (ap.ssid().isEmpty()) {
                    continue;
                }
                boolean isNew = context.seenSsids.add(ap.ssid());
                if (isNew && baselineEstablished) {
                    alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.INFO,
                            Messages.get("alert.category.newSsid"),
                            Messages.get("alert.message.newSsid", ap.ssid()), ap.bssid()));
                }
            }
        }
        baselineEstablished = true;
        return alerts;
    }
}

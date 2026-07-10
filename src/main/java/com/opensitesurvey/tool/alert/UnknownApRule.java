package com.waj.tool.alert;

import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple rogue-AP / evil-twin heuristic: fires CRITICAL when an SSID the user has marked as
 * trusted appears from a BSSID that isn't in the trusted set. Requires the user to have
 * registered at least one trusted AP for a given SSID (via the AP table's context menu); does
 * nothing for SSIDs that were never explicitly trusted.
 */
public final class UnknownApRule implements AlertRule {

    @Override
    public List<Alert> evaluate(ScanSnapshot snapshot, AlertContext context) {
        List<Alert> alerts = new ArrayList<>();
        if (!context.config.rogueApAlertEnabled) {
            return alerts;
        }
        for (ApSnapshot ap : snapshot.accessPoints()) {
            if (ap.ssid().isEmpty()) {
                continue;
            }
            boolean ssidIsTrusted = context.trustedApRegistry.hasTrustedSsid(ap.ssid());
            boolean thisBssidTrusted = context.trustedApRegistry.isTrusted(ap.bssid());
            if (ssidIsTrusted && !thisBssidTrusted && context.alertedUnknownBssids.add(ap.bssid())) {
                alerts.add(new Alert(snapshot.timestamp(), AlertSeverity.CRITICAL,
                        Messages.get("alert.category.unknownAp"),
                        Messages.get("alert.message.unknownAp", ap.ssid(), ap.bssid()),
                        ap.bssid()));
            }
        }
        return alerts;
    }
}

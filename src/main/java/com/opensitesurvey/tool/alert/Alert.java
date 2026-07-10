package com.opensitesurvey.tool.alert;

import java.time.Instant;

/** A single fired alert (RSSI drop, unknown/rogue AP, new SSID, channel congestion, etc). */
public record Alert(Instant timestamp, AlertSeverity severity, String category, String message, String relatedBssid) {
}

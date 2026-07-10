package com.opensitesurvey.tool.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted application settings (thresholds, trusted APs, ping target, logging cadence).
 * Plain mutable POJO for straightforward Jackson (de)serialization.
 */
public class AppConfig {

    /** {ssid, bssid} pair the user has explicitly marked as a known-good access point. */
    public static class TrustedAp {
        public String ssid;
        public String bssid;

        public TrustedAp() {
        }

        public TrustedAp(String ssid, String bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
        }
    }

    // volatile: SettingsDialog writes these on the JavaFX Application thread (Save button) while
    // the WLAN poller's background thread reads them once per scan cycle via AlertRule.evaluate()
    // - without a happens-before edge, the poller could keep observing stale values (or, for the
    // non-atomic double, theoretically a torn read) for an unbounded time after Save.
    public volatile int rssiThresholdDbm = -75;
    /** Compared directly against {@code ChannelPlanner} scores (roughly 0-100+ per nearby AP). */
    public volatile double channelCongestionThreshold = 50.0;

    public volatile boolean rssiAlertEnabled = true;
    public volatile boolean rogueApAlertEnabled = true;
    public volatile boolean newSsidAlertEnabled = true;
    public volatile boolean channelCongestionAlertEnabled = true;
    public volatile boolean windowsNotificationsEnabled = true;

    public List<TrustedAp> trustedAps = new ArrayList<>();

    public String defaultPingHost = "";

    /** "ja" or "en" - see {@code com.opensitesurvey.tool.i18n.Messages}. Takes effect on next app launch. */
    public String language = "ja";

    /** How often a snapshot is written to the long-term SQLite log, independent of the UI poll interval. */
    public long scanLogIntervalMillis = 2000;
}

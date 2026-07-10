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

    public int rssiThresholdDbm = -75;
    /** Compared directly against {@code ChannelPlanner} scores (roughly 0-100+ per nearby AP). */
    public double channelCongestionThreshold = 50.0;

    public boolean rssiAlertEnabled = true;
    public boolean rogueApAlertEnabled = true;
    public boolean newSsidAlertEnabled = true;
    public boolean channelCongestionAlertEnabled = true;
    public boolean windowsNotificationsEnabled = true;

    public List<TrustedAp> trustedAps = new ArrayList<>();

    public String defaultPingHost = "";

    /** "ja" or "en" - see {@code com.opensitesurvey.tool.i18n.Messages}. Takes effect on next app launch. */
    public String language = "ja";

    /** How often a snapshot is written to the long-term SQLite log, independent of the UI poll interval. */
    public long scanLogIntervalMillis = 2000;
}

package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.persistence.AppConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over {@link AppConfig#trustedAps}: the set of {ssid, bssid} pairs the user has
 * explicitly marked as known-good, used by {@link UnknownApRule} to flag an unregistered BSSID
 * showing up under a trusted SSID (a simple rogue-AP / evil-twin heuristic).
 *
 * <p>Reads happen on the WLAN poller's background thread (once per scan cycle, via {@link
 * UnknownApRule}), while writes happen on the JavaFX Application thread (context-menu trust/
 * untrust actions, the settings dialog). {@code config.trustedAps} is a plain {@code ArrayList} -
 * every method here synchronizes on this instance so a trust/untrust mutation can never race a
 * concurrent read's iteration (which would otherwise risk a {@code ConcurrentModificationException}
 * that aborts that poll cycle's alert evaluation and UI refresh entirely).
 */
public final class TrustedApRegistry {

    private final AppConfig config;

    public TrustedApRegistry(AppConfig config) {
        this.config = config;
    }

    public synchronized boolean isTrusted(String bssid) {
        return config.trustedAps.stream().anyMatch(t -> t.bssid.equalsIgnoreCase(bssid));
    }

    public synchronized boolean hasTrustedSsid(String ssid) {
        return ssid != null && !ssid.isEmpty()
                && config.trustedAps.stream().anyMatch(t -> t.ssid.equals(ssid));
    }

    public synchronized void trust(String ssid, String bssid) {
        if (!isTrusted(bssid)) {
            config.trustedAps.add(new AppConfig.TrustedAp(ssid, bssid));
        }
    }

    public synchronized void untrust(String bssid) {
        config.trustedAps.removeIf(t -> t.bssid.equalsIgnoreCase(bssid));
    }

    public synchronized List<AppConfig.TrustedAp> all() {
        return new ArrayList<>(config.trustedAps);
    }
}

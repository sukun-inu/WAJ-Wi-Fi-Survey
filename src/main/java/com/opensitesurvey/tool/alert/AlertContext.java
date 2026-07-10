package com.waj.tool.alert;

import com.waj.tool.persistence.AppConfig;

import java.util.HashSet;
import java.util.Set;

/** Shared, mutable state threaded through every {@link AlertRule} evaluation. Not thread-safe. */
public final class AlertContext {

    public final AppConfig config;
    public final TrustedApRegistry trustedApRegistry;

    final Set<String> seenSsids = new HashSet<>();
    final Set<String> belowThresholdBssids = new HashSet<>();
    final Set<String> alertedUnknownBssids = new HashSet<>();
    final Set<String> congestedChannelKeys = new HashSet<>();

    public AlertContext(AppConfig config) {
        this.config = config;
        this.trustedApRegistry = new TrustedApRegistry(config);
    }
}

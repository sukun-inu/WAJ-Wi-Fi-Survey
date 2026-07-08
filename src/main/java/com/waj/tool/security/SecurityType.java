package com.waj.tool.security;

import com.waj.tool.i18n.Messages;

/**
 * Wi-Fi authentication/encryption classification derived from 802.11 information elements. Labels
 * are resolved once, at enum class-init time (the first reference to this class after {@code
 * App.start()} has already called {@code Messages.setLocale()}) - consistent with the rest of the
 * app's "language switch takes effect on next launch" design, since a Java enum's constants are a
 * JVM-lifetime singleton and can't be re-labeled later without rebuilding the enum itself.
 */
public enum SecurityType {
    OPEN(Messages.get("security.type.open"), RiskLevel.HIGH),
    WEP(Messages.get("security.type.wep"), RiskLevel.HIGH),
    WPA(Messages.get("security.type.wpa"), RiskLevel.MEDIUM),
    WPA2(Messages.get("security.type.wpa2"), RiskLevel.LOW),
    WPA2_WPA3_MIXED(Messages.get("security.type.wpa2wpa3mixed"), RiskLevel.LOW),
    WPA3(Messages.get("security.type.wpa3"), RiskLevel.LOW),
    UNKNOWN(Messages.get("security.type.unknown"), RiskLevel.MEDIUM);

    public enum RiskLevel {
        HIGH, MEDIUM, LOW
    }

    private final String label;
    private final RiskLevel riskLevel;

    SecurityType(String label, RiskLevel riskLevel) {
        this.label = label;
        this.riskLevel = riskLevel;
    }

    public String label() {
        return label;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    @Override
    public String toString() {
        return label;
    }
}

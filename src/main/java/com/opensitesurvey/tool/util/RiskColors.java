package com.opensitesurvey.tool.util;

import com.opensitesurvey.tool.alert.AlertSeverity;
import com.opensitesurvey.tool.security.SecurityType;
import javafx.scene.paint.Color;

/**
 * Shared background/text color pairs for risk and severity tiers, so Security Audit's
 * {@link SecurityType.RiskLevel} coloring and Alerts' {@link AlertSeverity} coloring aren't each
 * hand-rolling the same hex values in separate, silently-duplicated switch statements. This is a
 * slightly higher-level util than its siblings ({@link CategoricalColorPalette}, {@link
 * SignalColorScale} depend on nothing outside {@code javafx.scene.paint}) since it necessarily
 * knows about domain enums from {@code security} and {@code alert} - kept in {@code util} anyway
 * since it's the same kind of "shared color mapping" concern as those two.
 *
 * <p>The two methods are intentionally separate rather than one shared 3-tier enum: HIGH/CRITICAL
 * and MEDIUM/WARNING happen to use identical colors today, but LOW (Security Audit's "this is
 * fine" green) and INFO (Alerts' "neutral/informational" blue) mean different things at the calm
 * end of their respective scales.
 */
public final class RiskColors {

    public record Style(Color background, Color textFill) {
        public String toCss() {
            return "-fx-background-color: " + ColorUtil.toWeb(background) + "; -fx-text-fill: " + ColorUtil.toWeb(textFill) + ";";
        }
    }

    // Text colors were audited against the WCAG 2.1 AA contrast minimum (4.5:1 for this table/badge
    // text's actual size) this session: white-on-#e74c3c measured only ~3.8:1 (fails), as did
    // white-on-#2ecc71 (~2.1:1, badly) and white-on-#3498db (~3.2:1) - HIGH's background is darkened
    // to a slightly deeper red (still the same "Flat UI" palette family as the rest of this app's
    // colors) to reach ~5.4:1 with white text; LOW/INFO keep their original background hue but swap
    // to the app's own dark text color (matching MEDIUM's already-passing black-on-yellow pattern),
    // reaching ~7.6:1 and ~5.1:1 respectively.
    private static final Style HIGH = new Style(Color.web("#c0392b"), Color.WHITE);
    private static final Style MEDIUM = new Style(Color.web("#f1c40f"), Color.BLACK);
    private static final Style LOW = new Style(Color.web("#2ecc71"), Color.web("#1e2228"));
    private static final Style INFO = new Style(Color.web("#3498db"), Color.web("#1e2228"));

    private RiskColors() {
    }

    public static Style forSecurityRisk(SecurityType.RiskLevel level) {
        return switch (level) {
            case HIGH -> HIGH;
            case MEDIUM -> MEDIUM;
            case LOW -> LOW;
        };
    }

    public static Style forAlertSeverity(AlertSeverity severity) {
        return switch (severity) {
            case CRITICAL -> HIGH;
            case WARNING -> MEDIUM;
            case INFO -> INFO;
        };
    }
}

package com.waj.tool.report;

import com.waj.tool.i18n.Messages;
import com.waj.tool.model.SurveyPoint;
import com.waj.tool.security.SecurityType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Shared localized report text helpers. */
final class ReportText {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private ReportText() {
    }

    static String securityNote(SecurityType type) {
        return switch (type) {
            case OPEN -> Messages.get("report.securityNote.open");
            case WEP -> Messages.get("report.securityNote.wep");
            case WPA -> Messages.get("report.securityNote.wpa");
            case UNKNOWN -> Messages.get("report.securityNote.unknown");
            default -> Messages.get("report.securityNote.ok");
        };
    }

    static String formatPingSummary(ReportMetrics.PingStats stats) {
        if (stats.configuredPoints() == 0) {
            return Messages.get("report.message.pingNotConfigured");
        }
        if (stats.successCount() == 0) {
            return Messages.get("report.message.pingAllTimeout", stats.configuredPoints());
        }
        return Messages.get(
                "report.message.pingSummary",
                stats.successCount(),
                stats.configuredPoints(),
                stats.avgMs(),
                stats.maxMs());
    }

    static String formatInstant(Instant instant) {
        return instant == null ? "-" : TIMESTAMP_FORMAT.format(instant);
    }

    static String formatEpochSeconds(long epochSecond) {
        return formatInstant(Instant.ofEpochSecond(epochSecond));
    }

    static String formatSurveyPointPing(SurveyPoint point) {
        if (point.pingHost == null || point.pingHost.isBlank()) {
            return "-";
        }
        return point.pingHost + ": " + (point.pingRttMs == null ? "timeout" : point.pingRttMs + "ms");
    }
}

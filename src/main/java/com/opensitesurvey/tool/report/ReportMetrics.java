package com.waj.tool.report;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.SurveyPoint;
import com.waj.tool.security.SecurityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Shared report calculations used by both PDF and HTML output paths. */
final class ReportMetrics {

    static final List<String> BANDS = List.of("2.4GHz", "5GHz", "6GHz");

    private ReportMetrics() {
    }

    record ExecutiveSummary(
            int accessPointCount,
            int surveyPointCount,
            String bands,
            double averageRssi,
            long highRiskAccessPoints,
            long mediumRiskAccessPoints,
            PingStats pingStats
    ) {
    }

    record PingStats(int configuredPoints, int successCount, int timeoutCount,
                     Integer minMs, Integer maxMs, Double avgMs) {
    }

    static List<ApSnapshot> accessPoints(ReportData data) {
        return data.accessPoints() == null ? List.of() : data.accessPoints();
    }

    static List<SurveyPoint> surveyPoints(ReportData data) {
        return data.surveyPoints() == null ? List.of() : data.surveyPoints();
    }

    static List<ApSnapshot> sortedAccessPoints(List<ApSnapshot> aps) {
        List<ApSnapshot> sorted = new ArrayList<>(aps);
        sorted.sort(Comparator
                .comparingInt((ApSnapshot ap) -> bandOrder(ap.band()))
                .thenComparingInt(ApSnapshot::channel)
                .thenComparing(Comparator.comparingInt(ApSnapshot::rssiDbm).reversed()));
        return sorted;
    }

    static ExecutiveSummary executiveSummary(ReportData data) {
        List<ApSnapshot> aps = accessPoints(data);
        List<SurveyPoint> points = surveyPoints(data);
        long high = aps.stream()
                .filter(a -> a.securityType().riskLevel() == SecurityType.RiskLevel.HIGH)
                .count();
        long medium = aps.stream()
                .filter(a -> a.securityType().riskLevel() == SecurityType.RiskLevel.MEDIUM)
                .count();
        double averageRssi = aps.stream()
                .mapToInt(ApSnapshot::rssiDbm)
                .average()
                .orElse(Double.NaN);
        String bands = aps.stream()
                .map(ApSnapshot::band)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
        return new ExecutiveSummary(
                aps.size(),
                points.size(),
                bands,
                averageRssi,
                high,
                medium,
                pingStats(points));
    }

    static PingStats pingStats(List<SurveyPoint> points) {
        int configured = 0;
        int success = 0;
        int timeout = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;

        for (SurveyPoint point : points) {
            if (point.pingHost == null || point.pingHost.isBlank()) {
                continue;
            }
            configured++;
            if (point.pingRttMs == null) {
                timeout++;
                continue;
            }
            success++;
            min = Math.min(min, point.pingRttMs);
            max = Math.max(max, point.pingRttMs);
            sum += point.pingRttMs;
        }

        return success == 0
                ? new PingStats(configured, 0, timeout, null, null, null)
                : new PingStats(configured, success, timeout, min, max, sum / (double) success);
    }

    static String formatAverageRssi(double averageRssi) {
        return Double.isNaN(averageRssi) ? "-" : String.format(Locale.ROOT, "%.1f dBm", averageRssi);
    }

    static String formatPingRttTriplet(PingStats stats) {
        return stats.successCount() == 0
                ? "-"
                : String.format(Locale.ROOT, "%d / %.1f / %d ms", stats.minMs(), stats.avgMs(), stats.maxMs());
    }

    private static int bandOrder(String band) {
        return switch (band) {
            case "2.4GHz" -> 1;
            case "5GHz" -> 2;
            case "6GHz" -> 3;
            default -> 99;
        };
    }
}

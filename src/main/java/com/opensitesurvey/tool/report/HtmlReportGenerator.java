package com.waj.tool.report;

import com.waj.tool.channel.ChannelPlanner;
import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.SurveyPoint;
import com.waj.tool.security.SecurityType;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds a self-contained HTML site-survey report (no external resources - images are inlined as base64). */
public final class HtmlReportGenerator {

    private HtmlReportGenerator() {
    }

    public static void generate(ReportData data, File file) throws IOException {
        List<ApSnapshot> aps = ReportMetrics.accessPoints(data);
        List<SurveyPoint> points = ReportMetrics.surveyPoints(data);
        String reportTitle = Messages.get("report.title");
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'><title>").append(escape(reportTitle)).append("</title><style>")
                .append("@page{size:A4 landscape;margin:12mm;}")
                .append("body{font-family:'BIZ UDPGothic','BIZ UDGothic','BIZ UDゴシック','Yu Gothic UI','Segoe UI',sans-serif;margin:24px;color:#222;}")
                .append("h1{font-size:20px;margin:0;} h2{font-size:16px;border-bottom:2px solid #333;padding-bottom:4px;margin-top:32px;}")
                .append(".meta{margin-top:8px;}")
                .append(".summary{margin-top:14px;display:grid;grid-template-columns:220px 1fr;gap:6px 12px;max-width:840px;}")
                .append(".summary .k{font-weight:700;background:#f3f4f6;padding:4px 8px;border:1px solid #d8dde3;}")
                .append(".summary .v{padding:4px 8px;border:1px solid #d8dde3;}")
                .append("table{border-collapse:collapse;width:100%;margin-top:8px;}")
                .append("th,td{border:1px solid #ccc;padding:5px 7px;font-size:11.5px;text-align:left;vertical-align:top;}")
                .append("th{background:#f0f0f0;}")
                .append(".risk-high{background:#e74c3c;color:white;} .risk-medium{background:#f1c40f;} .risk-low{background:#2ecc71;color:white;}")
                .append(".mono{font-family:'Consolas','Cascadia Mono','BIZ UDGothic','BIZ UDゴシック',monospace;white-space:nowrap;}")
                .append("img.floorplan{max-width:100%;border:1px solid #999;margin-top:8px;}")
                .append("@media print{body{margin:0;}h2,h3{break-after:avoid;}tr{break-inside:avoid;}table{page-break-inside:auto;}}")
                .append("</style></head><body>");

        html.append("<h1>").append(escape(reportTitle)).append("</h1>");
        html.append("<p class='meta'>").append(Messages.get("report.generatedAtLabel")).append(" ")
                .append(ReportText.formatInstant(data.generatedAt())).append("<br>")
                .append(escape(data.interfaceDescription())).append("</p>");

        appendExecutiveSummary(html, data);

        if (data.floorPlanSnapshot() != null) {
            html.append("<h2>").append(Messages.get("report.section.floorPlanHeatmap")).append("</h2>");
            html.append("<p>").append(escape(Messages.get("report.message.floorPlanSnapshot"))).append("</p>");
            html.append("<img class='floorplan' src='data:image/png;base64,")
                    .append(toBase64Png(data.floorPlanSnapshot()))
                    .append("'/>");
        }

        html.append("<h2>").append(Messages.get("report.section.apList")).append("</h2><table><tr>")
                .append(th(Messages.get("report.column.index")))
                .append(th(Messages.get("report.column.ssid")))
                .append(th(Messages.get("report.column.bssid")))
                .append(th(Messages.get("report.column.bandChannel")))
                .append(th(Messages.get("report.column.frequency")))
                .append(th(Messages.get("report.column.rssi")))
                .append(th(Messages.get("report.column.quality")))
                .append(th(Messages.get("report.column.phy")))
                .append(th(Messages.get("report.column.security")))
                .append(th(Messages.get("report.column.utilization")))
                .append("</tr>");
        if (aps.isEmpty()) {
            html.append("<tr><td colspan='10'>").append(escape(Messages.get("report.message.noAccessPoints"))).append("</td></tr>");
        }
        int apIndex = 1;
        for (ApSnapshot ap : ReportMetrics.sortedAccessPoints(aps)) {
            html.append("<tr><td class='mono'>").append(apIndex++).append("</td><td>")
                    .append(escape(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid())).append("</td><td class='mono'>")
                    .append(escape(ap.bssid())).append("</td><td class='mono'>").append(ap.band()).append(" / ").append(ap.channel())
                    .append("</td><td class='mono'>").append(String.format(Locale.ROOT, "%.0fMHz", ap.frequencyKhz() / 1000.0))
                    .append("</td><td class='mono'>").append(ap.rssiDbm()).append("dBm</td><td class='mono'>").append(ap.linkQuality()).append("%</td><td>")
                    .append(escape(ap.phyType())).append("</td><td class='")
                    .append(riskClass(ap.securityType())).append("'>").append(ap.securityType().label()).append("</td><td class='mono'>")
                    .append(ap.channelUtilizationPercent() == null ? "N/A" : ap.channelUtilizationPercent() + "%")
                    .append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h2>").append(Messages.get("report.section.securitySummary")).append("</h2>");
        Map<SecurityType, Long> bySec = new EnumMap<>(SecurityType.class);
        for (ApSnapshot ap : aps) {
            bySec.merge(ap.securityType(), 1L, Long::sum);
        }
        html.append("<table><tr><th>").append(Messages.get("report.column.type")).append("</th><th>")
                .append(Messages.get("report.column.count")).append("</th><th>")
                .append(Messages.get("report.column.risk")).append("</th></tr>");
        for (SecurityType type : SecurityType.values()) {
            html.append("<tr><td class='").append(riskClass(type)).append("'>").append(escape(type.label()))
                    .append("</td><td class='mono'>").append(bySec.getOrDefault(type, 0L))
                    .append("</td><td class='mono'>").append(type.riskLevel().name()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h3 style='margin-top:12px;'>").append(Messages.get("report.section.findings")).append("</h3><table><tr>")
                .append(th(Messages.get("report.column.ssid")))
                .append(th(Messages.get("report.column.bssid")))
                .append(th(Messages.get("report.column.type")))
                .append(th(Messages.get("report.column.band")))
                .append(th(Messages.get("report.column.notes")))
                .append("</tr>");
        boolean hasFinding = false;
        for (ApSnapshot ap : aps) {
            if (ap.securityType().riskLevel() == SecurityType.RiskLevel.LOW) {
                continue;
            }
            hasFinding = true;
            html.append("<tr><td>").append(escape(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid())).append("</td><td class='mono'>")
                    .append(escape(ap.bssid())).append("</td><td class='").append(riskClass(ap.securityType())).append("'>")
                    .append(ap.securityType().label()).append("</td><td class='mono'>").append(ap.band()).append("</td><td>")
                    .append(escape(ReportText.securityNote(ap.securityType()))).append("</td></tr>");
        }
        if (!hasFinding) {
            html.append("<tr><td colspan='5'>").append(Messages.get("report.message.noFindings")).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h2>").append(Messages.get("report.section.channelRecommendation")).append("</h2><table><tr><th>")
                .append(Messages.get("report.column.band")).append("</th><th>").append(Messages.get("report.column.recommendedChannel"))
                .append("</th><th>").append(Messages.get("report.column.congestionScore")).append("</th><th>")
                .append(Messages.get("report.column.observedChannels")).append("</th></tr>");
        Map<String, ChannelPlanner.Recommendation> recByBand = new LinkedHashMap<>();
        if (aps.isEmpty()) {
            html.append("<tr><td colspan='4'>").append(escape(Messages.get("report.message.noChannelData"))).append("</td></tr>");
        } else {
            for (String band : ReportMetrics.BANDS) {
                List<ApSnapshot> inBand = aps.stream().filter(a -> a.band().equals(band)).toList();
                if (inBand.isEmpty()) {
                    continue;
                }
                ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(inBand, band);
                recByBand.put(band, rec);
                String observed = inBand.stream().map(ApSnapshot::channel).distinct().sorted().map(String::valueOf)
                        .collect(Collectors.joining(", "));
                html.append("<tr><td>").append(band).append("</td><td class='mono'>").append(rec.channel()).append("</td><td class='mono'>")
                        .append(String.format(Locale.ROOT, "%.1f", rec.score())).append("</td><td class='mono'>")
                        .append(observed).append("</td></tr>");
            }
        }
        html.append("</table>");

        if (!recByBand.isEmpty()) {
            html.append("<h3 style='margin-top:12px;'>").append(Messages.get("report.section.channelScoreDetails")).append("</h3>")
                    .append("<table><tr>")
                    .append(th(Messages.get("report.column.band")))
                    .append(th(Messages.get("report.column.channel")))
                    .append(th(Messages.get("report.column.congestionScore")))
                    .append(th(Messages.get("report.column.aps")))
                    .append(th(Messages.get("report.column.topContributor")))
                    .append("</tr>");
            for (Map.Entry<String, ChannelPlanner.Recommendation> e : recByBand.entrySet()) {
                String band = e.getKey();
                ChannelPlanner.Recommendation rec = e.getValue();
                List<Integer> inUse = aps.stream()
                        .filter(ap -> ap.band().equals(band))
                        .map(ApSnapshot::channel)
                        .distinct()
                        .sorted((a, b) -> Double.compare(rec.allScores().getOrDefault(b, 0.0), rec.allScores().getOrDefault(a, 0.0)))
                        .toList();
                for (Integer ch : inUse) {
                    long apCount = aps.stream().filter(ap -> ap.band().equals(band) && ap.channel() == ch).count();
                    ChannelPlanner.Recommendation.ApContribution top = rec.perChannelContributions().getOrDefault(ch, List.of())
                            .stream().max(java.util.Comparator.comparingDouble(ChannelPlanner.Recommendation.ApContribution::contribution))
                            .orElse(null);
                    String contributor = top == null ? "-"
                            : escape((top.ssid().isEmpty() ? "<hidden>" : top.ssid()) + " (" + shortBssid(top.bssid()) + ") "
                            + String.format(Locale.ROOT, "%.1f pts", top.contribution()));
                    html.append("<tr><td>").append(band).append("</td><td class='mono'>").append(ch).append("</td><td class='mono'>")
                            .append(String.format(Locale.ROOT, "%.1f", rec.allScores().getOrDefault(ch, 0.0))).append("</td><td class='mono'>")
                            .append(apCount).append("</td><td>").append(contributor).append("</td></tr>");
                }
            }
            html.append("</table>");
        }

        if (!points.isEmpty()) {
            appendSurveyPointSummary(html, points);
            html.append("<h2>").append(Messages.get("report.section.surveyPoints")).append("</h2><table><tr>")
                    .append(th(Messages.get("report.column.index")))
                    .append(th(Messages.get("report.column.timestamp")))
                    .append(th(Messages.get("report.column.coordinates")))
                    .append(th(Messages.get("report.column.visibleAps")))
                    .append(th(Messages.get("report.column.strongestRssi")))
                    .append(th(Messages.get("report.column.ping")))
                    .append("</tr>");
            int i = 1;
            for (SurveyPoint p : points) {
                int visible = p.rssiByBssid == null ? 0 : p.rssiByBssid.size();
                Integer strongest = p.rssiByBssid == null ? null : p.rssiByBssid.values().stream().max(Integer::compareTo).orElse(null);
                html.append("<tr><td class='mono'>").append(i++).append("</td><td class='mono'>")
                        .append(ReportText.formatEpochSeconds(p.epochSecond)).append("</td><td class='mono'>")
                        .append(String.format(Locale.ROOT, "(%.2f, %.2f)", p.xNorm, p.yNorm)).append("</td><td class='mono'>")
                        .append(visible).append("</td><td class='mono'>")
                        .append(strongest == null ? "-" : strongest + "dBm").append("</td><td>")
                        .append(escape(ReportText.formatSurveyPointPing(p))).append("</td></tr>");
            }
            html.append("</table>");
        } else {
            html.append("<h2>").append(Messages.get("report.section.surveyPoints")).append("</h2>")
                    .append("<p>").append(escape(Messages.get("report.message.noSurveyPoints"))).append("</p>");
        }

        html.append("<p style='margin-top:32px;color:#888;font-size:11px;'>")
                .append(Messages.get("report.disclaimer"))
                .append("</p>");
        html.append("</body></html>");

        Files.writeString(file.toPath(), html.toString(), StandardCharsets.UTF_8);
    }

    private static void appendExecutiveSummary(StringBuilder html, ReportData data) {
        ReportMetrics.ExecutiveSummary metrics = ReportMetrics.executiveSummary(data);
        html.append("<h2>").append(Messages.get("report.section.executiveSummary")).append("</h2>")
                .append("<div class='summary'>")
                .append("<div class='k'>").append(Messages.get("report.field.detectedAps")).append("</div><div class='v mono'>").append(metrics.accessPointCount()).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.surveyPoints")).append("</div><div class='v mono'>").append(metrics.surveyPointCount()).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.bands")).append("</div><div class='v mono'>").append(escape(metrics.bands().isBlank() ? "-" : metrics.bands())).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.averageRssi")).append("</div><div class='v mono'>")
                .append(ReportMetrics.formatAverageRssi(metrics.averageRssi())).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.highMediumRiskAps")).append("</div><div class='v mono'>")
                .append(metrics.highRiskAccessPoints()).append(" / ").append(metrics.mediumRiskAccessPoints()).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.pingSuccess")).append("</div><div class='v mono'>")
                .append(escape(ReportText.formatPingSummary(metrics.pingStats()))).append("</div>")
                .append("</div>");
    }

    private static void appendSurveyPointSummary(StringBuilder html, List<SurveyPoint> points) {
        ReportMetrics.PingStats pingStats = ReportMetrics.pingStats(points);
        html.append("<h3 style='margin-top:12px;'>").append(Messages.get("report.section.surveyPointMetrics")).append("</h3>")
                .append("<div class='summary'>")
                .append("<div class='k'>").append(Messages.get("report.field.pingConfiguredPoints")).append("</div><div class='v mono'>").append(pingStats.configuredPoints()).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.pingSuccessTimeout")).append("</div><div class='v mono'>")
                .append(pingStats.successCount()).append(" / ").append(pingStats.timeoutCount()).append("</div>")
                .append("<div class='k'>").append(Messages.get("report.field.pingRttMinAvgMax")).append("</div><div class='v mono'>")
                .append(ReportMetrics.formatPingRttTriplet(pingStats)).append("</div>")
                .append("</div>");
    }

    private static String riskClass(SecurityType type) {
        return switch (type.riskLevel()) {
            case HIGH -> "risk-high";
            case MEDIUM -> "risk-medium";
            case LOW -> "risk-low";
        };
    }

    private static String th(String text) {
        return "<th>" + escape(text) + "</th>";
    }

    private static String shortBssid(String bssid) {
        if (bssid == null || bssid.length() < 5) {
            return bssid == null ? "" : bssid;
        }
        return bssid.substring(bssid.length() - 5);
    }

    private static String toBase64Png(javafx.scene.image.Image image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

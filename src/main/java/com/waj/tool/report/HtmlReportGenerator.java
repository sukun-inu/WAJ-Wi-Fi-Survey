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
import java.util.List;
import java.util.Map;

/** Builds a self-contained HTML site-survey report (no external resources - images are inlined as base64). */
public final class HtmlReportGenerator {

    private static final List<String> BANDS = List.of("2.4GHz", "5GHz", "6GHz");

    private HtmlReportGenerator() {
    }

    public static void generate(ReportData data, File file) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'><title>Wi-Fi Site Survey Report</title><style>")
                .append("body{font-family:'Segoe UI',sans-serif;margin:24px;color:#222;}")
                .append("h1{font-size:20px;} h2{font-size:16px;border-bottom:2px solid #333;padding-bottom:4px;margin-top:32px;}")
                .append("table{border-collapse:collapse;width:100%;margin-top:8px;}")
                .append("th,td{border:1px solid #ccc;padding:6px 8px;font-size:12px;text-align:left;}")
                .append("th{background:#f0f0f0;}")
                .append(".risk-high{background:#e74c3c;color:white;} .risk-medium{background:#f1c40f;} .risk-low{background:#2ecc71;color:white;}")
                .append("img.floorplan{max-width:100%;border:1px solid #999;margin-top:8px;}")
                .append("</style></head><body>");

        html.append("<h1>").append(Messages.get("report.title")).append("</h1>");
        html.append("<p>").append(Messages.get("report.generatedAtLabel")).append(" ").append(data.generatedAt()).append("<br>")
                .append(escape(data.interfaceDescription())).append("</p>");

        if (data.floorPlanSnapshot() != null) {
            html.append("<h2>").append(Messages.get("report.section.floorPlanHeatmap")).append("</h2>");
            html.append("<img class='floorplan' src='data:image/png;base64,")
                    .append(toBase64Png(data.floorPlanSnapshot()))
                    .append("'/>");
        }

        html.append("<h2>").append(Messages.get("report.section.apList")).append("</h2><table><tr><th>SSID</th><th>BSSID</th><th>")
                .append(Messages.get("report.column.channel")).append("</th><th>Band</th><th>RSSI</th><th>")
                .append(Messages.get("report.column.security")).append("</th></tr>");
        for (ApSnapshot ap : data.accessPoints()) {
            html.append("<tr><td>").append(escape(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid())).append("</td><td>")
                    .append(escape(ap.bssid())).append("</td><td>").append(ap.channel()).append("</td><td>")
                    .append(ap.band()).append("</td><td>").append(ap.rssiDbm()).append("</td><td class='")
                    .append(riskClass(ap.securityType())).append("'>").append(ap.securityType().label()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h2>").append(Messages.get("report.section.securitySummary")).append("</h2>");
        Map<SecurityType, Long> bySec = new EnumMap<>(SecurityType.class);
        for (ApSnapshot ap : data.accessPoints()) {
            bySec.merge(ap.securityType(), 1L, Long::sum);
        }
        html.append("<table><tr><th>").append(Messages.get("report.column.type")).append("</th><th>")
                .append(Messages.get("report.column.count")).append("</th></tr>");
        for (Map.Entry<SecurityType, Long> e : bySec.entrySet()) {
            html.append("<tr><td class='").append(riskClass(e.getKey())).append("'>").append(e.getKey().label())
                    .append("</td><td>").append(e.getValue()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("<h2>").append(Messages.get("report.section.channelRecommendation")).append("</h2><table><tr><th>")
                .append(Messages.get("report.column.band")).append("</th><th>").append(Messages.get("report.column.recommendedChannel"))
                .append("</th><th>").append(Messages.get("report.column.congestionScore")).append("</th></tr>");
        for (String band : BANDS) {
            List<ApSnapshot> inBand = data.accessPoints().stream().filter(a -> a.band().equals(band)).toList();
            if (inBand.isEmpty()) {
                continue;
            }
            ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(inBand, band);
            html.append("<tr><td>").append(band).append("</td><td>").append(rec.channel()).append("</td><td>")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", rec.score())).append("</td></tr>");
        }
        html.append("</table>");

        if (!data.surveyPoints().isEmpty()) {
            html.append("<h2>").append(Messages.get("report.section.surveyPoints")).append("</h2><table><tr><th>#</th><th>")
                    .append(Messages.get("report.column.coordinates")).append("</th><th>Ping</th></tr>");
            int i = 1;
            for (SurveyPoint p : data.surveyPoints()) {
                String ping = p.pingRttMs != null ? escape(p.pingHost) + ": " + p.pingRttMs + "ms" : "-";
                html.append("<tr><td>").append(i++).append("</td><td>")
                        .append(String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", p.xNorm, p.yNorm)).append("</td><td>")
                        .append(ping).append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("<p style='margin-top:32px;color:#888;font-size:11px;'>")
                .append(Messages.get("report.disclaimer"))
                .append("</p>");
        html.append("</body></html>");

        Files.writeString(file.toPath(), html.toString(), StandardCharsets.UTF_8);
    }

    private static String riskClass(SecurityType type) {
        return switch (type.riskLevel()) {
            case HIGH -> "risk-high";
            case MEDIUM -> "risk-medium";
            case LOW -> "risk-low";
        };
    }

    private static String toBase64Png(javafx.scene.image.Image image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

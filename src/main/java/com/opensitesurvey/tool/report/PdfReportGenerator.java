package com.waj.tool.report;

import com.waj.tool.channel.ChannelPlanner;
import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.SurveyPoint;
import com.waj.tool.security.SecurityType;
import javafx.embed.swing.SwingFXUtils;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a detailed printable PDF site-survey report using OpenPDF.
 *
 * <p>Windows font strategy:
 * <ul>
 *   <li>Prefer BIZ UD Gothic (document text) and Consolas (numeric/code-like fields).</li>
 *   <li>If unavailable, fall back to the CJK-safe PDF base font (HeiseiKakuGo-W5).</li>
 * </ul>
 */
public final class PdfReportGenerator {

    private static final List<String> BIZ_UD_REGULAR_FILES = List.of(
            "BIZ-UDGothicR.ttc",
            "BIZ-UDPGothicR.ttc",
            "BIZUDGothic-Regular.ttf",
            "BIZUDPGothic-Regular.ttf",
            "YuGothR.ttc",
            "msgothic.ttc"
    );
    private static final List<String> BIZ_UD_BOLD_FILES = List.of(
            "BIZ-UDGothicB.ttc",
            "BIZ-UDPGothicB.ttc",
            "BIZUDGothic-Bold.ttf",
            "BIZUDPGothic-Bold.ttf",
            "YuGothB.ttc"
    );
    private static final List<String> CONSOLAS_FILES = List.of(
            "consola.ttf",
            "consolab.ttf"
    );

    private PdfReportGenerator() {
    }

    private record FontPack(Font title, Font heading, Font body, Font bodyBold, Font mono, Font disclaimer) {
    }

    public static void generate(ReportData data, File file) throws Exception {
        FontPack fonts = loadFonts();
        Document document = new Document(PageSize.A4.rotate(), 28, 28, 30, 28);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            PdfWriter.getInstance(document, fos);
            document.open();

            addTitleBlock(document, data, fonts);
            addExecutiveSummary(document, data, fonts);
            addFloorPlanSection(document, data, fonts);
            addAccessPointSection(document, data, fonts);
            addSecuritySection(document, data, fonts);
            addChannelSection(document, data, fonts);
            addSurveyPointSection(document, data, fonts);

            document.add(new Paragraph(
                    Messages.get("report.disclaimer"),
                    fonts.disclaimer()));

            // Must close the Document (flushes xref/trailer) before the try-with-resources
            // closes the underlying FileOutputStream, otherwise this throws "Stream Closed".
            document.close();
        }
    }

    private static void addTitleBlock(Document document, ReportData data, FontPack fonts) throws Exception {
        Paragraph title = new Paragraph(Messages.get("report.title"), fonts.title());
        title.setSpacingAfter(6);
        document.add(title);
        document.add(new Paragraph(Messages.get("report.generatedAtLabel") + " " + ReportText.formatInstant(data.generatedAt()), fonts.body()));
        document.add(new Paragraph(safe(data.interfaceDescription()), fonts.body()));
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addExecutiveSummary(Document document, ReportData data, FontPack fonts) throws Exception {
        addSectionHeading(document, Messages.get("report.section.executiveSummary"), fonts);
        PdfPTable summary = new PdfPTable(2);
        styleTable(summary, 0);
        summary.setWidths(new float[]{30, 70});

        ReportMetrics.ExecutiveSummary metrics = ReportMetrics.executiveSummary(data);
        addSummaryRow(summary, Messages.get("report.field.detectedAps"), String.valueOf(metrics.accessPointCount()), fonts);
        addSummaryRow(summary, Messages.get("report.field.surveyPoints"), String.valueOf(metrics.surveyPointCount()), fonts);
        addSummaryRow(summary, Messages.get("report.field.bands"), metrics.bands().isBlank() ? "-" : metrics.bands(), fonts);
        addSummaryRow(summary, Messages.get("report.field.averageRssi"), ReportMetrics.formatAverageRssi(metrics.averageRssi()), fonts);
        addSummaryRow(summary, Messages.get("report.field.highMediumRiskAps"),
                metrics.highRiskAccessPoints() + " / " + metrics.mediumRiskAccessPoints(), fonts);
        addSummaryRow(summary, Messages.get("report.field.pingSuccess"), ReportText.formatPingSummary(metrics.pingStats()), fonts);

        document.add(summary);
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addFloorPlanSection(Document document, ReportData data, FontPack fonts) throws Exception {
        if (data.floorPlanSnapshot() == null) {
            return;
        }
        document.newPage();
        addSectionHeading(document, Messages.get("report.section.floorPlanHeatmap"), fonts);
        document.add(new Paragraph(Messages.get("report.message.floorPlanSnapshot"), fonts.body()));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(SwingFXUtils.fromFXImage(data.floorPlanSnapshot(), null), "png", baos);
        org.openpdf.text.Image img = org.openpdf.text.Image.getInstance(baos.toByteArray());
        img.scaleToFit(760, 440);
        img.setAlignment(Element.ALIGN_CENTER);
        document.add(img);
        document.add(new Paragraph(" ", fonts.body()));
        document.newPage();
    }

    private static void addAccessPointSection(Document document, ReportData data, FontPack fonts) throws Exception {
        List<ApSnapshot> aps = ReportMetrics.accessPoints(data);
        addSectionHeading(document, Messages.get("report.section.apList"), fonts);
        if (aps.isEmpty()) {
            document.add(new Paragraph(Messages.get("report.message.noAccessPoints"), fonts.body()));
            document.add(new Paragraph(" ", fonts.body()));
            return;
        }

        PdfPTable apTable = new PdfPTable(10);
        styleTable(apTable, 1);
        apTable.setWidths(new float[]{3, 14, 17, 8, 7, 8, 8, 13, 12, 8});
        for (String h : new String[]{
                Messages.get("report.column.index"),
                Messages.get("report.column.ssid"),
                Messages.get("report.column.bssid"),
                Messages.get("report.column.bandChannel"),
                Messages.get("report.column.frequency"),
                Messages.get("report.column.rssi"),
                Messages.get("report.column.quality"),
                Messages.get("report.column.phy"),
                Messages.get("report.column.security"),
                Messages.get("report.column.utilization")
        }) {
            apTable.addCell(headerCell(h, fonts.bodyBold()));
        }

        int index = 1;
        for (ApSnapshot ap : ReportMetrics.sortedAccessPoints(aps)) {
            apTable.addCell(cell(String.valueOf(index++), fonts.mono()));
            apTable.addCell(cell(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid(), fonts.body()));
            apTable.addCell(cell(ap.bssid(), fonts.mono()));
            apTable.addCell(cell(ap.band() + " / " + ap.channel(), fonts.mono()));
            apTable.addCell(cell(String.format(Locale.ROOT, "%.0fMHz", ap.frequencyKhz() / 1000.0), fonts.mono()));
            apTable.addCell(cell(ap.rssiDbm() + "dBm", fonts.mono()));
            apTable.addCell(cell(ap.linkQuality() + "%", fonts.mono()));
            apTable.addCell(cell(ap.phyType(), fonts.body()));
            PdfPCell secCell = cell(ap.securityType().label(), fonts.body());
            secCell.setBackgroundColor(riskColor(ap.securityType()));
            apTable.addCell(secCell);
            apTable.addCell(cell(ap.channelUtilizationPercent() == null ? "N/A" : ap.channelUtilizationPercent() + "%", fonts.mono()));
        }
        document.add(apTable);
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addSecuritySection(Document document, ReportData data, FontPack fonts) throws Exception {
        List<ApSnapshot> aps = ReportMetrics.accessPoints(data);
        addSectionHeading(document, Messages.get("report.section.securitySummary"), fonts);

        Map<SecurityType, Long> byType = new EnumMap<>(SecurityType.class);
        for (ApSnapshot ap : aps) {
            byType.merge(ap.securityType(), 1L, Long::sum);
        }
        PdfPTable summaryTable = new PdfPTable(3);
        styleTable(summaryTable, 1);
        summaryTable.setWidths(new float[]{46, 20, 34});
        summaryTable.addCell(headerCell(Messages.get("report.column.type"), fonts.bodyBold()));
        summaryTable.addCell(headerCell(Messages.get("report.column.count"), fonts.bodyBold()));
        summaryTable.addCell(headerCell(Messages.get("report.column.risk"), fonts.bodyBold()));
        for (SecurityType type : SecurityType.values()) {
            long count = byType.getOrDefault(type, 0L);
            PdfPCell typeCell = cell(type.label(), fonts.body());
            typeCell.setBackgroundColor(riskColor(type));
            summaryTable.addCell(typeCell);
            summaryTable.addCell(cell(String.valueOf(count), fonts.mono()));
            summaryTable.addCell(cell(type.riskLevel().name(), fonts.mono()));
        }
        document.add(summaryTable);

        List<ApSnapshot> findings = aps.stream()
                .filter(ap -> ap.securityType().riskLevel() != SecurityType.RiskLevel.LOW)
                .toList();
        document.add(new Paragraph(Messages.get("report.section.findings") + ":", fonts.bodyBold()));
        if (findings.isEmpty()) {
            document.add(new Paragraph(Messages.get("report.message.noFindings"), fonts.body()));
            document.add(new Paragraph(" ", fonts.body()));
            return;
        }

        PdfPTable findingsTable = new PdfPTable(5);
        styleTable(findingsTable, 1);
        findingsTable.setWidths(new float[]{20, 24, 20, 10, 26});
        for (String h : new String[]{
                Messages.get("report.column.ssid"),
                Messages.get("report.column.bssid"),
                Messages.get("report.column.type"),
                Messages.get("report.column.band"),
                Messages.get("report.column.notes")
        }) {
            findingsTable.addCell(headerCell(h, fonts.bodyBold()));
        }
        for (ApSnapshot ap : findings) {
            findingsTable.addCell(cell(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid(), fonts.body()));
            findingsTable.addCell(cell(ap.bssid(), fonts.mono()));
            PdfPCell typeCell = cell(ap.securityType().label(), fonts.body());
            typeCell.setBackgroundColor(riskColor(ap.securityType()));
            findingsTable.addCell(typeCell);
            findingsTable.addCell(cell(ap.band(), fonts.mono()));
            findingsTable.addCell(cell(ReportText.securityNote(ap.securityType()), fonts.body()));
        }
        document.add(findingsTable);
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addChannelSection(Document document, ReportData data, FontPack fonts) throws Exception {
        List<ApSnapshot> aps = ReportMetrics.accessPoints(data);
        addSectionHeading(document, Messages.get("report.section.channelRecommendation"), fonts);
        if (aps.isEmpty()) {
            document.add(new Paragraph(Messages.get("report.message.noChannelData"), fonts.body()));
            document.add(new Paragraph(" ", fonts.body()));
            return;
        }

        PdfPTable recTable = new PdfPTable(4);
        styleTable(recTable, 1);
        recTable.setWidths(new float[]{20, 28, 22, 30});
        recTable.addCell(headerCell(Messages.get("report.column.band"), fonts.bodyBold()));
        recTable.addCell(headerCell(Messages.get("report.column.recommendedChannel"), fonts.bodyBold()));
        recTable.addCell(headerCell(Messages.get("report.column.congestionScore"), fonts.bodyBold()));
        recTable.addCell(headerCell(Messages.get("report.column.observedChannels"), fonts.bodyBold()));

        Map<String, ChannelPlanner.Recommendation> recByBand = new LinkedHashMap<>();
        for (String band : ReportMetrics.BANDS) {
            List<ApSnapshot> inBand = aps.stream().filter(a -> a.band().equals(band)).toList();
            if (inBand.isEmpty()) {
                continue;
            }
            ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(inBand, band);
            recByBand.put(band, rec);

            String observed = inBand.stream()
                    .map(ApSnapshot::channel)
                    .distinct()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));

            recTable.addCell(cell(band, fonts.body()));
            recTable.addCell(cell(String.valueOf(rec.channel()), fonts.mono()));
            recTable.addCell(cell(String.format(Locale.ROOT, "%.1f", rec.score()), fonts.mono()));
            recTable.addCell(cell(observed, fonts.mono()));
        }
        document.add(recTable);

        document.add(new Paragraph(Messages.get("report.section.channelScoreDetails") + ":", fonts.bodyBold()));
        PdfPTable detailTable = new PdfPTable(5);
        styleTable(detailTable, 1);
        detailTable.setWidths(new float[]{14, 10, 14, 10, 52});
        for (String h : new String[]{
                Messages.get("report.column.band"),
                Messages.get("report.column.channel"),
                Messages.get("report.column.congestionScore"),
                Messages.get("report.column.aps"),
                Messages.get("report.column.topContributor")
        }) {
            detailTable.addCell(headerCell(h, fonts.bodyBold()));
        }
        for (Map.Entry<String, ChannelPlanner.Recommendation> e : recByBand.entrySet()) {
            String band = e.getKey();
            ChannelPlanner.Recommendation rec = e.getValue();
            Set<Integer> inUse = aps.stream()
                    .filter(ap -> ap.band().equals(band))
                    .map(ApSnapshot::channel)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));

            List<Integer> channels = inUse.stream()
                    .sorted(Comparator.comparingDouble((Integer ch) -> rec.allScores().getOrDefault(ch, 0.0)).reversed())
                    .toList();
            for (Integer channel : channels) {
                List<ChannelPlanner.Recommendation.ApContribution> contributions =
                        rec.perChannelContributions().getOrDefault(channel, List.of());
                ChannelPlanner.Recommendation.ApContribution top = contributions.stream()
                        .max(Comparator.comparingDouble(ChannelPlanner.Recommendation.ApContribution::contribution))
                        .orElse(null);
                long apsOnChannel = aps.stream().filter(ap -> ap.band().equals(band) && ap.channel() == channel).count();
                String contributor = top == null ? "-"
                        : String.format(
                                "%s (%s) %.1f pts",
                                top.ssid().isEmpty() ? "<hidden>" : top.ssid(),
                                shortBssid(top.bssid()),
                                top.contribution());

                detailTable.addCell(cell(band, fonts.body()));
                detailTable.addCell(cell(String.valueOf(channel), fonts.mono()));
                detailTable.addCell(cell(String.format(Locale.ROOT, "%.1f", rec.allScores().getOrDefault(channel, 0.0)), fonts.mono()));
                detailTable.addCell(cell(String.valueOf(apsOnChannel), fonts.mono()));
                detailTable.addCell(cell(contributor, fonts.body()));
            }
        }
        document.add(detailTable);
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addSurveyPointSection(Document document, ReportData data, FontPack fonts) throws Exception {
        List<SurveyPoint> points = ReportMetrics.surveyPoints(data);
        addSectionHeading(document, Messages.get("report.section.surveyPoints"), fonts);
        if (points.isEmpty()) {
            document.add(new Paragraph(Messages.get("report.message.noSurveyPoints"), fonts.body()));
            document.add(new Paragraph(" ", fonts.body()));
            return;
        }

        ReportMetrics.PingStats pingStats = ReportMetrics.pingStats(points);
        PdfPTable summary = new PdfPTable(2);
        styleTable(summary, 0);
        summary.setWidths(new float[]{30, 70});
        addSummaryRow(summary, Messages.get("report.field.totalPoints"), String.valueOf(points.size()), fonts);
        addSummaryRow(summary, Messages.get("report.field.pingConfiguredPoints"), String.valueOf(pingStats.configuredPoints()), fonts);
        addSummaryRow(summary, Messages.get("report.field.pingSuccessTimeout"), pingStats.successCount() + " / " + pingStats.timeoutCount(), fonts);
        addSummaryRow(summary, Messages.get("report.field.pingRttMinAvgMax"), ReportMetrics.formatPingRttTriplet(pingStats), fonts);
        document.add(summary);

        PdfPTable detail = new PdfPTable(6);
        styleTable(detail, 1);
        detail.setWidths(new float[]{4, 19, 16, 12, 14, 35});
        for (String h : new String[]{
                Messages.get("report.column.index"),
                Messages.get("report.column.timestamp"),
                Messages.get("report.column.coordinates"),
                Messages.get("report.column.visibleAps"),
                Messages.get("report.column.strongestRssi"),
                Messages.get("report.column.ping")
        }) {
            detail.addCell(headerCell(h, fonts.bodyBold()));
        }

        int index = 1;
        for (SurveyPoint p : points) {
            int visibleCount = p.rssiByBssid == null ? 0 : p.rssiByBssid.size();
            Integer strongest = p.rssiByBssid == null ? null : p.rssiByBssid.values().stream().max(Integer::compareTo).orElse(null);
            detail.addCell(cell(String.valueOf(index++), fonts.mono()));
            detail.addCell(cell(ReportText.formatEpochSeconds(p.epochSecond), fonts.mono()));
            detail.addCell(cell(String.format(Locale.ROOT, "(%.3f, %.3f)", p.xNorm, p.yNorm), fonts.mono()));
            detail.addCell(cell(String.valueOf(visibleCount), fonts.mono()));
            detail.addCell(cell(strongest == null ? "-" : strongest + "dBm", fonts.mono()));
            detail.addCell(cell(ReportText.formatSurveyPointPing(p), fonts.body()));
        }
        document.add(detail);
        document.add(new Paragraph(" ", fonts.body()));
    }

    private static void addSummaryRow(PdfPTable table, String key, String value, FontPack fonts) {
        PdfPCell keyCell = cell(key, fonts.bodyBold());
        keyCell.setBackgroundColor(new Color(0xF2, 0xF2, 0xF2));
        table.addCell(keyCell);
        table.addCell(cell(value, fonts.mono()));
    }

    private static void addSectionHeading(Document document, String text, FontPack fonts) throws Exception {
        Paragraph heading = new Paragraph(text, fonts.heading());
        heading.setSpacingBefore(8);
        heading.setSpacingAfter(5);
        document.add(heading);
    }

    private static void styleTable(PdfPTable table, int headerRows) {
        table.setWidthPercentage(100);
        table.setSpacingBefore(3);
        table.setSpacingAfter(8);
        table.setSplitLate(false);
        table.setSplitRows(true);
        if (headerRows > 0) {
            table.setHeaderRows(headerRows);
        }
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(0xE0, 0xE0, 0xE0));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(4);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(text), font));
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(3);
        return cell;
    }

    private static Color riskColor(SecurityType type) {
        return switch (type.riskLevel()) {
            case HIGH -> new Color(0xE7, 0x4C, 0x3C);
            case MEDIUM -> new Color(0xF1, 0xC4, 0x0F);
            case LOW -> new Color(0x2E, 0xCC, 0x71);
        };
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String shortBssid(String bssid) {
        if (bssid == null || bssid.length() < 5) {
            return safe(bssid);
        }
        return bssid.substring(bssid.length() - 5);
    }

    private static FontPack loadFonts() throws Exception {
        BaseFont bodyBase = loadWindowsFont(BIZ_UD_REGULAR_FILES);
        BaseFont boldBase = loadWindowsFont(BIZ_UD_BOLD_FILES);
        BaseFont monoBase = loadWindowsFont(CONSOLAS_FILES);

        // Guaranteed Japanese-safe fallback (predefined CJK font name recognized by PDF viewers).
        BaseFont fallbackCjk = BaseFont.createFont("HeiseiKakuGo-W5", "UniJIS-UCS2-H", BaseFont.NOT_EMBEDDED);

        if (bodyBase == null) {
            bodyBase = fallbackCjk;
        }
        if (boldBase == null) {
            boldBase = bodyBase;
        }
        if (monoBase == null) {
            monoBase = bodyBase;
        }

        Font title = new Font(boldBase, 18, Font.BOLD);
        Font heading = new Font(boldBase, 11, Font.BOLD);
        Font body = new Font(bodyBase, 8.5f);
        Font bodyBold = new Font(boldBase, 8.5f, Font.BOLD);
        Font mono = new Font(monoBase, 8f);
        Font disclaimer = new Font(bodyBase, 7.5f, Font.ITALIC, Color.GRAY);
        return new FontPack(title, heading, body, bodyBold, mono, disclaimer);
    }

    private static BaseFont loadWindowsFont(List<String> fileNames) {
        for (String path : candidateFontPaths(fileNames)) {
            try {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // Try next candidate.
            }
        }
        return null;
    }

    private static List<String> candidateFontPaths(List<String> fileNames) {
        Set<String> roots = new LinkedHashSet<>();
        String winDir = System.getenv("WINDIR");
        if (winDir != null && !winDir.isBlank()) {
            roots.add(new File(winDir, "Fonts").getAbsolutePath());
        }
        roots.add("C:\\Windows\\Fonts");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(new File(localAppData, "Microsoft\\Windows\\Fonts").getAbsolutePath());
        }

        List<String> candidates = new ArrayList<>();
        for (String root : roots) {
            for (String fileName : fileNames) {
                File f = new File(root, fileName);
                if (f.isFile()) {
                    String path = f.getAbsolutePath();
                    if (fileName.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                        candidates.add(path + ",0");
                        candidates.add(path + ",1");
                    }
                    candidates.add(path);
                }
            }
        }
        return candidates;
    }
}

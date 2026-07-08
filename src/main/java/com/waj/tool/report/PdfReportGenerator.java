package com.waj.tool.report;

import com.waj.tool.channel.ChannelPlanner;
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
import java.util.List;

/** Builds a printable PDF site-survey report using OpenPDF (LGPL/MPL, no AGPL concerns). */
public final class PdfReportGenerator {

    private static final List<String> BANDS = List.of("2.4GHz", "5GHz", "6GHz");

    private PdfReportGenerator() {
    }

    public static void generate(ReportData data, File file) throws Exception {
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            PdfWriter.getInstance(document, fos);
            document.open();

            // Helvetica (and every other core-14 PDF font) has no Japanese glyphs, so a Japanese
            // SSID or interface description would previously render as blank/missing text - this
            // predefined CJK font is a standard Adobe-registered name the PDF viewer substitutes
            // an appropriate Japanese Gothic font for (NOT_EMBEDDED: no font file is bundled into
            // the PDF, just a reference to it, which is how every PDF viewer already displays
            // this font family - the CMap/metrics needed to reference it are bundled in openpdf
            // itself, no extra dependency required).
            BaseFont cjkBase = BaseFont.createFont("HeiseiKakuGo-W5", "UniJIS-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(cjkBase, 18, Font.BOLD);
            Font headingFont = new Font(cjkBase, 13, Font.BOLD);
            Font normalFont = new Font(cjkBase, 9);
            Font headerFont = new Font(cjkBase, 9, Font.BOLD);
            Font disclaimerFont = new Font(cjkBase, 7, Font.ITALIC, Color.GRAY);

            document.add(new Paragraph("Wi-Fi Site Survey Report", titleFont));
            document.add(new Paragraph("Generated: " + data.generatedAt(), normalFont));
            document.add(new Paragraph(data.interfaceDescription(), normalFont));

            if (data.floorPlanSnapshot() != null) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("Floor Plan / Heatmap", headingFont));
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(SwingFXUtils.fromFXImage(data.floorPlanSnapshot(), null), "png", baos);
                org.openpdf.text.Image img = org.openpdf.text.Image.getInstance(baos.toByteArray());
                img.scaleToFit(480, 360);
                document.add(img);
            }

            document.add(new Paragraph(" "));
            document.add(new Paragraph("Access Points", headingFont));
            PdfPTable apTable = new PdfPTable(6);
            apTable.setWidthPercentage(100);
            for (String h : new String[]{"SSID", "BSSID", "Ch", "Band", "RSSI", "Security"}) {
                apTable.addCell(headerCell(h, headerFont));
            }
            for (ApSnapshot ap : data.accessPoints()) {
                apTable.addCell(cell(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid(), normalFont));
                apTable.addCell(cell(ap.bssid(), normalFont));
                apTable.addCell(cell(String.valueOf(ap.channel()), normalFont));
                apTable.addCell(cell(ap.band(), normalFont));
                apTable.addCell(cell(ap.rssiDbm() + "dBm", normalFont));
                PdfPCell secCell = cell(ap.securityType().label(), normalFont);
                secCell.setBackgroundColor(riskColor(ap.securityType()));
                apTable.addCell(secCell);
            }
            document.add(apTable);

            document.add(new Paragraph(" "));
            document.add(new Paragraph("Channel Planning Recommendation", headingFont));
            PdfPTable chTable = new PdfPTable(3);
            chTable.setWidthPercentage(100);
            for (String h : new String[]{"Band", "Recommended Channel", "Congestion Score"}) {
                chTable.addCell(headerCell(h, headerFont));
            }
            for (String band : BANDS) {
                List<ApSnapshot> inBand = data.accessPoints().stream().filter(a -> a.band().equals(band)).toList();
                if (inBand.isEmpty()) {
                    continue;
                }
                ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(inBand, band);
                chTable.addCell(cell(band, normalFont));
                chTable.addCell(cell(String.valueOf(rec.channel()), normalFont));
                chTable.addCell(cell(String.format(java.util.Locale.ROOT, "%.1f", rec.score()), normalFont));
            }
            document.add(chTable);

            if (!data.surveyPoints().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("Survey Points", headingFont));
                PdfPTable spTable = new PdfPTable(3);
                spTable.setWidthPercentage(100);
                for (String h : new String[]{"#", "Position (normalized)", "Ping"}) {
                    spTable.addCell(headerCell(h, headerFont));
                }
                int i = 1;
                for (SurveyPoint p : data.surveyPoints()) {
                    spTable.addCell(cell(String.valueOf(i++), normalFont));
                    spTable.addCell(cell(String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", p.xNorm, p.yNorm), normalFont));
                    spTable.addCell(cell(p.pingRttMs != null ? p.pingHost + ": " + p.pingRttMs + "ms" : "-", normalFont));
                }
                document.add(spTable);
            }

            document.add(new Paragraph(" "));
            document.add(new Paragraph(
                    "Signal distribution and channel congestion are RSSI-based estimates, not measured RF spectrum data.",
                    disclaimerFont));

            // Must close the Document (flushes xref/trailer) before the try-with-resources
            // closes the underlying FileOutputStream, otherwise this throws "Stream Closed".
            document.close();
        }
    }

    private static PdfPCell headerCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(0xE0, 0xE0, 0xE0));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        return new PdfPCell(new Phrase(text, font));
    }

    private static Color riskColor(SecurityType type) {
        return switch (type.riskLevel()) {
            case HIGH -> new Color(0xE7, 0x4C, 0x3C);
            case MEDIUM -> new Color(0xF1, 0xC4, 0x0F);
            case LOW -> new Color(0x2E, 0xCC, 0x71);
        };
    }
}

package com.opensitesurvey.tool.report;

import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.security.SecurityType;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportGeneratorTest {

    @Test
    void generatesAValidPdfUsingBizUdOrCjkFallbackForJapaneseText(@TempDir Path tempDir) throws Exception {
        // The PDF generator now prefers Windows BIZ UD fonts when available, and falls back to a
        // CJK-safe built-in base font otherwise. Verify structurally that a composite CJK-capable
        // font mapping is present and one of the expected font families is referenced.
        ApSnapshot ap = new ApSnapshot("日本語テストSSID", "AA:BB:CC:DD:EE:FF", 36, 5180000, "5GHz",
                -50, 90, "802.11ac", true, SecurityType.WPA2, null, Instant.now());
        ReportData data = new ReportData(Instant.now(), "テストインターフェース",
                List.of(ap), List.of(), null);

        File out = tempDir.resolve("report.pdf").toFile();
        PdfReportGenerator.generate(data, out);

        assertTrue(out.length() > 0, "PDF file should not be empty");

        // Read as Latin-1 (byte-transparent for any 8-bit value) so the object-name literals in
        // the PDF's own internal structure (font dictionaries, etc.) can be matched as plain text
        // regardless of surrounding compressed/binary stream data elsewhere in the file.
        String raw = Files.readString(out.toPath(), StandardCharsets.ISO_8859_1);
        assertTrue(raw.contains("/Type0"),
                "PDF should declare a Type0 (composite/CID-keyed) font for the CJK text");
        assertTrue(
                raw.contains("BIZ-UDGothic") || raw.contains("HeiseiKakuGo-W5"),
                "PDF should reference either a BIZ UD font or the CJK fallback font");
        assertTrue(raw.contains("Consolas") || raw.contains("BIZ-UDGothic") || raw.contains("HeiseiKakuGo-W5"),
                "PDF should reference a monospace-capable font for numeric fields, or a safe fallback");
    }

    @Test
    void floorPlanHeatmapUsesDedicatedPageBeforeSecuritySummary(@TempDir Path tempDir) throws Exception {
        Messages.setLocale(Locale.ENGLISH);
        try {
            ApSnapshot ap = new ApSnapshot("Test", "AA:BB:CC:DD:EE:FF", 36, 5180000, "5GHz",
                    -50, 90, "802.11ac", true, SecurityType.WPA2, null, Instant.now());
            ReportData data = new ReportData(Instant.parse("2026-01-02T03:04:05Z"), "Test Interface",
                    List.of(ap), List.of(), testImage());

            File out = tempDir.resolve("report-with-heatmap.pdf").toFile();
            PdfReportGenerator.generate(data, out);

            try (PdfReader reader = new PdfReader(out.getAbsolutePath())) {
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                assertTrue(reader.getNumberOfPages() >= 3,
                        "Heatmap should be isolated from tabular report sections on its own page");
                String heatmapPage = extractor.getTextFromPage(2);
                String followingPage = extractor.getTextFromPage(3);

                assertTrue(heatmapPage.contains("Floor Plan / Heatmap"));
                assertFalse(heatmapPage.contains("Security Audit Summary"),
                        "Security summary should not share the heatmap page");
                assertTrue(followingPage.contains("Detected Access Points")
                                || followingPage.contains("Security Audit Summary"),
                        "Tabular report sections should resume after the heatmap page");
            }
        } finally {
            Messages.setLocale(Locale.JAPANESE);
        }
    }

    private static WritableImage testImage() {
        WritableImage image = new WritableImage(320, 180);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.getPixelWriter().setColor(x, y, x < image.getWidth() / 2 ? Color.LIGHTBLUE : Color.ORANGE);
            }
        }
        return image;
    }
}

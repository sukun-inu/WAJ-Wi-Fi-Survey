package com.waj.tool.report;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.security.SecurityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportGeneratorTest {

    @Test
    void generatesAValidPdfUsingACjkCapableFontForJapaneseText(@TempDir Path tempDir) throws Exception {
        // Helvetica (the previous default) has no Japanese glyphs, so a Japanese SSID/interface
        // description would have rendered as blank/missing text. Verified structurally (does the
        // PDF actually reference a CJK Type0 font?) rather than via PdfTextExtractor: predefined,
        // non-embedded CID fonts like this one have no reverse ToUnicode CMap in openpdf, so text
        // extraction returns nothing for *any* text in this font (confirmed empirically, including
        // for plain ASCII content in the same font) - that's an unrelated extraction limitation,
        // not a sign the font isn't correctly referenced for on-screen/printed rendering, which is
        // what a PDF viewer actually uses to substitute a matching Japanese Gothic typeface for.
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
        assertTrue(raw.contains("HeiseiKakuGo-W5"),
                "PDF should reference the CJK base font by name");
        assertTrue(raw.contains("UniJIS-UCS2-H"),
                "PDF should reference the CJK encoding/CMap by name");
        assertTrue(raw.contains("/Type0"),
                "PDF should declare a Type0 (composite/CID-keyed) font for the CJK text");
    }
}

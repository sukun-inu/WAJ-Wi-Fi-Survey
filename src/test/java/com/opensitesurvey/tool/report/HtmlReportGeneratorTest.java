package com.waj.tool.report;

import com.waj.tool.i18n.Messages;
import com.waj.tool.model.SurveyPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportGeneratorTest {

    @Test
    void emptyReportExplainsMissingData(@TempDir Path tempDir) throws Exception {
        Messages.setLocale(Locale.ENGLISH);
        try {
            ReportData data = new ReportData(Instant.parse("2026-01-02T03:04:05Z"),
                    "Test Interface", null, null, null);
            Path out = tempDir.resolve("empty-report.html");

            HtmlReportGenerator.generate(data, out.toFile());

            String html = Files.readString(out, StandardCharsets.UTF_8);
            assertTrue(html.contains("Executive Summary"));
            assertTrue(html.contains("No access points were captured at export time."));
            assertTrue(html.contains("No AP data is available for channel analysis."));
            assertTrue(html.contains("No survey points were recorded."));
        } finally {
            Messages.setLocale(Locale.JAPANESE);
        }
    }

    @Test
    void surveyPointPingTimeoutIsRendered(@TempDir Path tempDir) throws Exception {
        Messages.setLocale(Locale.ENGLISH);
        try {
            Map<String, Integer> rssi = new LinkedHashMap<>();
            rssi.put("AA:BB:CC:DD:EE:FF", -58);
            SurveyPoint point = new SurveyPoint(0.25, 0.75, rssi, Instant.parse("2026-01-02T03:04:05Z"));
            point.pingHost = "8.8.8.8";
            point.pingRttMs = null;
            ReportData data = new ReportData(Instant.parse("2026-01-02T03:04:05Z"),
                    "Test Interface", List.of(), List.of(point), null);
            Path out = tempDir.resolve("survey-report.html");

            HtmlReportGenerator.generate(data, out.toFile());

            String html = Files.readString(out, StandardCharsets.UTF_8);
            assertTrue(html.contains("8.8.8.8: timeout"));
            assertFalse(html.contains("No survey points were recorded."));
        } finally {
            Messages.setLocale(Locale.JAPANESE);
        }
    }
}

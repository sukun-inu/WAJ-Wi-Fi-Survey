package com.waj.tool.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.SurveyPoint;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON export for AP snapshots and survey points. Builds plain {@code Map}/{@code List}
 * structures with timestamps pre-formatted as ISO-8601 strings rather than adding the
 * jackson-datatype-jsr310 module just to serialize {@link Instant} fields.
 */
public final class JsonExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private JsonExporter() {
    }

    public static void exportApSnapshots(List<ApSnapshot> aps, File file) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApSnapshot ap : aps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", ap.timestamp().toString());
            row.put("ssid", ap.ssid());
            row.put("bssid", ap.bssid());
            row.put("channel", ap.channel());
            row.put("band", ap.band());
            row.put("rssiDbm", ap.rssiDbm());
            row.put("linkQuality", ap.linkQuality());
            row.put("phyType", ap.phyType());
            row.put("securityType", ap.securityType().name());
            row.put("privacyEnabled", ap.privacyEnabled());
            rows.add(row);
        }
        MAPPER.writeValue(file, rows);
    }

    public static void exportSurveyPoints(List<SurveyPoint> points, File file) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SurveyPoint p : points) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", Instant.ofEpochSecond(p.epochSecond).toString());
            row.put("xNorm", p.xNorm);
            row.put("yNorm", p.yNorm);
            row.put("rssiByBssid", p.rssiByBssid);
            row.put("pingHost", p.pingHost);
            row.put("pingRttMs", p.pingRttMs);
            rows.add(row);
        }
        MAPPER.writeValue(file, rows);
    }
}

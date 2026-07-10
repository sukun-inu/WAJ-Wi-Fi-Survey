package com.waj.tool.persistence;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.SurveyPoint;
import com.waj.tool.util.CsvUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Hand-rolled CSV writer for AP snapshots and survey points - the schema is flat enough that a CSV library isn't worth the dependency. */
public final class CsvExporter {

    private CsvExporter() {
    }

    public static void exportApSnapshots(List<ApSnapshot> aps, File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,ssid,bssid,channel,band,rssi_dbm,link_quality,phy_type,security,privacy\n");
        for (ApSnapshot ap : aps) {
            sb.append(CsvUtil.escapeField(ap.timestamp().toString())).append(',')
                    .append(CsvUtil.escapeField(ap.ssid())).append(',')
                    .append(CsvUtil.escapeField(ap.bssid())).append(',')
                    .append(ap.channel()).append(',')
                    .append(CsvUtil.escapeField(ap.band())).append(',')
                    .append(ap.rssiDbm()).append(',')
                    .append(ap.linkQuality()).append(',')
                    .append(CsvUtil.escapeField(ap.phyType())).append(',')
                    .append(CsvUtil.escapeField(ap.securityType().label())).append(',')
                    .append(ap.privacyEnabled()).append('\n');
        }
        Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    public static void exportSurveyPoints(List<SurveyPoint> points, File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,x_norm,y_norm,bssid,rssi_dbm,ping_host,ping_rtt_ms\n");
        for (SurveyPoint p : points) {
            Instant ts = Instant.ofEpochSecond(p.epochSecond);
            for (Map.Entry<String, Integer> e : p.rssiByBssid.entrySet()) {
                sb.append(CsvUtil.escapeField(ts.toString())).append(',')
                        .append(p.xNorm).append(',')
                        .append(p.yNorm).append(',')
                        .append(CsvUtil.escapeField(e.getKey())).append(',')
                        .append(e.getValue()).append(',')
                        .append(CsvUtil.escapeField(p.pingHost)).append(',')
                        .append(p.pingRttMs == null ? "" : p.pingRttMs).append('\n');
            }
        }
        Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }
}

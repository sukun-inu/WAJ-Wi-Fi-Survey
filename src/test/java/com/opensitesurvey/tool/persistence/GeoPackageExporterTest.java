package com.opensitesurvey.tool.persistence;

import com.opensitesurvey.tool.model.SurveyPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoPackageExporterTest {

    private static SurveyPoint point(double x, double y, int rssi) {
        return new SurveyPoint(x, y, Map.of("AA:AA:AA:AA:AA:AA", rssi), Instant.now());
    }

    /** Decodes the GeoPackageBinary blob {@link GeoPackageExporter} writes, for round-trip verification. */
    private static double[] decodePoint(byte[] blob) {
        ByteBuffer buf = ByteBuffer.wrap(blob);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8); // skip the 8-byte GeoPackage header (no envelope in this app's encoding)
        buf.get(); // WKB byte order marker
        buf.getInt(); // WKB geometry type
        double x = buf.getDouble();
        double y = buf.getDouble();
        return new double[]{x, y};
    }

    @Test
    void exportsSurveyPointsWithValidGeoPackageMetadata(@TempDir Path tempDir) throws Exception {
        SurveyPoint p1 = point(0.2, 0.3, -40);
        p1.pingHost = "8.8.8.8";
        p1.pingRttMs = 15;
        SurveyPoint p2 = point(0.7, 0.8, -60);
        List<SurveyPoint> points = List.of(p1, p2);

        File out = tempDir.resolve("survey.gpkg").toFile();
        GeoPackageExporter.exportSurveyPoints(points, List.of(), out);

        assertTrue(out.length() > 0, "GeoPackage file should not be empty");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + out.getAbsolutePath())) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT srs_id FROM gpkg_spatial_ref_sys ORDER BY srs_id")) {
                assertTrue(rs.next());
                assertEquals(-1, rs.getInt(1));
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
                assertFalse(rs.next());
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT data_type, srs_id FROM gpkg_contents WHERE table_name = 'survey_points'")) {
                assertTrue(rs.next());
                assertEquals("features", rs.getString(1));
                assertEquals(0, rs.getInt(2));
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT geometry_type_name FROM gpkg_geometry_columns WHERE table_name = 'survey_points'")) {
                assertTrue(rs.next());
                assertEquals("POINT", rs.getString(1));
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT geom, ping_host, ping_rtt_ms, rssi_json FROM survey_points ORDER BY fid")) {
                assertTrue(rs.next());
                double[] xy = decodePoint(rs.getBytes("geom"));
                assertEquals(0.2, xy[0], 1e-9);
                assertEquals(0.3, xy[1], 1e-9);
                assertEquals("8.8.8.8", rs.getString("ping_host"));
                assertEquals(15, rs.getInt("ping_rtt_ms"));
                assertTrue(rs.getString("rssi_json").contains("AA:AA:AA:AA:AA:AA"));

                assertTrue(rs.next());
                xy = decodePoint(rs.getBytes("geom"));
                assertEquals(0.7, xy[0], 1e-9);
                assertEquals(0.8, xy[1], 1e-9);

                assertFalse(rs.next());
            }

            // No AP-position estimates were passed in, so that feature layer must not exist at all
            // rather than being created empty.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='estimated_ap_positions'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void exportsEstimatedApPositionsLayerWhenProvided(@TempDir Path tempDir) throws Exception {
        List<SurveyPoint> points = List.of(point(0.1, 0.1, -50));
        List<GeoPackageExporter.ApPositionRow> apPositions = List.of(
                new GeoPackageExporter.ApPositionRow("AA:AA:AA:AA:AA:AA", "MySSID", 5, 0.4, 0.6));

        File out = tempDir.resolve("survey-with-aps.gpkg").toFile();
        GeoPackageExporter.exportSurveyPoints(points, apPositions, out);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + out.getAbsolutePath())) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT geom, bssid, ssid, sample_count FROM estimated_ap_positions")) {
                assertTrue(rs.next());
                double[] xy = decodePoint(rs.getBytes("geom"));
                assertEquals(0.4, xy[0], 1e-9);
                assertEquals(0.6, xy[1], 1e-9);
                assertEquals("AA:AA:AA:AA:AA:AA", rs.getString("bssid"));
                assertEquals("MySSID", rs.getString("ssid"));
                assertEquals(5, rs.getInt("sample_count"));
                assertFalse(rs.next());
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT data_type FROM gpkg_contents WHERE table_name = 'estimated_ap_positions'")) {
                assertTrue(rs.next());
                assertEquals("features", rs.getString(1));
            }
        }
    }

    @Test
    void overwritesAnExistingFileCleanly(@TempDir Path tempDir) throws Exception {
        File out = tempDir.resolve("survey.gpkg").toFile();
        GeoPackageExporter.exportSurveyPoints(List.of(point(0.1, 0.1, -50)), List.of(), out);
        // Re-exporting at the same path must not fail with "table already exists" or similar.
        GeoPackageExporter.exportSurveyPoints(List.of(point(0.2, 0.2, -55), point(0.3, 0.3, -60)), List.of(), out);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + out.getAbsolutePath());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM survey_points")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        }
    }
}

package com.opensitesurvey.tool.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensitesurvey.tool.model.SurveyPoint;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Exports survey points (and, optionally, a caller-supplied set of heuristic AP position
 * estimates - see {@code com.opensitesurvey.tool.ui.survey.ApPositionEstimator}) as a minimal,
 * spec-compliant GeoPackage - an OGC-standardized, SQLite-based vector format readable by QGIS and
 * most other GIS tools - hand-rolled directly against {@code sqlite-jdbc} (already a project
 * dependency) since there is no GeoTools/JTS-style GIS library here to build on (see pom.xml).
 * Deliberately takes the AP-position estimates as a plain parameter rather than depending on {@code
 * ApPositionEstimator} directly, since that class lives in the {@code ui.survey} package, which
 * itself already depends on this {@code persistence} package (for {@code SurveyProjectStore} etc.)
 * - importing the other way would create a package cycle.
 *
 * <p>Every exported coordinate is the same normalized (0..1) floor-plan-relative fraction {@link
 * SurveyPoint} itself uses, registered under GeoPackage's reserved "srs_id 0" (Undefined Cartesian)
 * spatial reference system - this app has no real-world distance/scale calibration today (see
 * {@code SurveyProject.metersPerPixel}, always persisted as {@code 0.0} and never read back), so
 * there is no meaningful geographic or real-world-metric coordinate to export instead. A GIS tool
 * opening this file will show the survey layout in the same proportions as the floor plan image,
 * just not geo-located to any real-world position.
 */
public final class GeoPackageExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int UNDEFINED_CARTESIAN_SRS_ID = 0;

    private GeoPackageExporter() {
    }

    /** One AP's estimated position (normalized 0..1 floor-plan coordinates), staged for export. */
    public record ApPositionRow(String bssid, String ssid, int sampleCount, double xNorm, double yNorm) {
    }

    public static void exportSurveyPoints(List<SurveyPoint> points, List<ApPositionRow> apPositions, File file)
            throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not overwrite existing file: " + file);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath())) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA application_id = 1196444487"); // big-endian 'GPKG'
                    st.execute("PRAGMA user_version = 10300"); // GeoPackage 1.3.0
                }
                createMetadataTables(conn);
                createSurveyPointsTable(conn, points);
                createEstimatedApPositionsTable(conn, apPositions);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to write GeoPackage: " + e.getMessage(), e);
        }
    }

    private static void createMetadataTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE gpkg_spatial_ref_sys (
                        srs_name TEXT NOT NULL,
                        srs_id INTEGER NOT NULL PRIMARY KEY,
                        organization TEXT NOT NULL,
                        organization_coordsys_id INTEGER NOT NULL,
                        definition TEXT NOT NULL,
                        description TEXT
                    )""");
            st.execute("""
                    INSERT INTO gpkg_spatial_ref_sys
                        (srs_name, srs_id, organization, organization_coordsys_id, definition, description)
                    VALUES
                        ('Undefined Cartesian SRS', 0, 'NONE', 0, 'undefined',
                         'OpenSiteSurvey floor-plan-relative normalized (0..1) coordinates - not geo-referenced'),
                        ('Undefined geographic SRS', -1, 'NONE', -1, 'undefined', NULL)
                    """);
            st.execute("""
                    CREATE TABLE gpkg_contents (
                        table_name TEXT NOT NULL PRIMARY KEY,
                        data_type TEXT NOT NULL,
                        identifier TEXT UNIQUE,
                        description TEXT,
                        last_change TEXT NOT NULL,
                        min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE,
                        srs_id INTEGER,
                        CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
                    )""");
            st.execute("""
                    CREATE TABLE gpkg_geometry_columns (
                        table_name TEXT NOT NULL,
                        column_name TEXT NOT NULL,
                        geometry_type_name TEXT NOT NULL,
                        srs_id INTEGER NOT NULL,
                        z TINYINT NOT NULL,
                        m TINYINT NOT NULL,
                        CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name),
                        CONSTRAINT uk_gc_table_name UNIQUE (table_name),
                        CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
                        CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
                    )""");
        }
    }

    private static void registerFeatureTable(Connection conn, String tableName, String description,
                                              double minX, double minY, double maxX, double maxY) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO gpkg_contents
                    (table_name, data_type, identifier, description, last_change, min_x, min_y, max_x, max_y, srs_id)
                VALUES (?, 'features', ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, tableName);
            ps.setString(2, tableName);
            ps.setString(3, description);
            ps.setString(4, Instant.now().toString());
            ps.setDouble(5, minX);
            ps.setDouble(6, minY);
            ps.setDouble(7, maxX);
            ps.setDouble(8, maxY);
            ps.setInt(9, UNDEFINED_CARTESIAN_SRS_ID);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, srs_id, z, m)
                VALUES (?, 'geom', 'POINT', ?, 0, 0)""")) {
            ps.setString(1, tableName);
            ps.setInt(2, UNDEFINED_CARTESIAN_SRS_ID);
            ps.executeUpdate();
        }
    }

    private static void createSurveyPointsTable(Connection conn, List<SurveyPoint> points) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE survey_points (
                        fid INTEGER PRIMARY KEY AUTOINCREMENT,
                        geom BLOB,
                        recorded_at TEXT,
                        ping_host TEXT,
                        ping_rtt_ms INTEGER,
                        rssi_json TEXT
                    )""");
        }
        registerFeatureTable(conn, "survey_points", "OpenSiteSurvey measurement points",
                bound(points, p -> p.xNorm, true), bound(points, p -> p.yNorm, true),
                bound(points, p -> p.xNorm, false), bound(points, p -> p.yNorm, false));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO survey_points (geom, recorded_at, ping_host, ping_rtt_ms, rssi_json) VALUES (?, ?, ?, ?, ?)")) {
            for (SurveyPoint p : points) {
                ps.setBytes(1, encodePointGeometry(p.xNorm, p.yNorm));
                ps.setString(2, Instant.ofEpochSecond(p.epochSecond).toString());
                ps.setString(3, p.pingHost);
                if (p.pingRttMs == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setInt(4, p.pingRttMs);
                }
                ps.setString(5, toJson(p.rssiByBssid));
                ps.executeUpdate();
            }
        }
    }

    /**
     * A second feature layer of the caller-supplied heuristic per-BSSID position estimates,
     * skipped entirely (no empty table left behind) if the list is empty.
     */
    private static void createEstimatedApPositionsTable(Connection conn, List<ApPositionRow> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE estimated_ap_positions (
                        fid INTEGER PRIMARY KEY AUTOINCREMENT,
                        geom BLOB,
                        bssid TEXT,
                        ssid TEXT,
                        sample_count INTEGER
                    )""");
        }
        registerFeatureTable(conn, "estimated_ap_positions",
                "Heuristic RSSI-weighted-centroid AP position estimates (see ApPositionEstimator) - "
                        + "not true trilateration, treat as a rough guide only",
                rowBound(rows, r -> r.xNorm, true), rowBound(rows, r -> r.yNorm, true),
                rowBound(rows, r -> r.xNorm, false), rowBound(rows, r -> r.yNorm, false));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO estimated_ap_positions (geom, bssid, ssid, sample_count) VALUES (?, ?, ?, ?)")) {
            for (ApPositionRow r : rows) {
                ps.setBytes(1, encodePointGeometry(r.xNorm(), r.yNorm()));
                ps.setString(2, r.bssid());
                ps.setString(3, r.ssid());
                ps.setInt(4, r.sampleCount());
                ps.executeUpdate();
            }
        }
    }

    private interface DoubleAccessor<T> {
        double get(T t);
    }

    private static double bound(List<SurveyPoint> points, DoubleAccessor<SurveyPoint> accessor, boolean min) {
        return points.stream().mapToDouble(accessor::get).reduce(min ? Math::min : Math::max).orElse(0);
    }

    private static double rowBound(List<ApPositionRow> rows, DoubleAccessor<ApPositionRow> accessor, boolean min) {
        return rows.stream().mapToDouble(accessor::get).reduce(min ? Math::min : Math::max).orElse(0);
    }

    private static String toJson(Map<String, Integer> rssiByBssid) throws SQLException {
        try {
            return MAPPER.writeValueAsString(rssiByBssid);
        } catch (Exception e) {
            throw new SQLException("Failed to encode rssiByBssid as JSON", e);
        }
    }

    /**
     * Encodes a single POINT geometry as a GeoPackageBinary blob (OGC GeoPackage 1.3 clause
     * 2.1.3): an 8-byte GeoPackage-specific header (magic "GP", version, flags, srs_id - no
     * envelope, since one isn't required and every consumer must handle its absence) followed by
     * the standard little-endian ISO WKB encoding of a 2D Point.
     */
    private static byte[] encodePointGeometry(double x, double y) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 21);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'G');
        buf.put((byte) 'P');
        buf.put((byte) 0); // version 0
        buf.put((byte) 0x01); // flags: bit0=1 (little-endian), envelope indicator=000 (none), empty=0
        buf.putInt(UNDEFINED_CARTESIAN_SRS_ID);
        buf.put((byte) 1); // WKB byte order marker: little-endian
        buf.putInt(1); // WKB geometry type: 1 = Point
        buf.putDouble(x);
        buf.putDouble(y);
        return buf.array();
    }
}

package com.waj.tool.persistence;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;
import com.waj.tool.util.CsvUtil;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Long-term SQLite log of scan samples, survey points, and fired alerts - independent of the
 * in-memory 5-minute ring buffers used for live charts.
 *
 * <p>The single underlying {@link Connection} is written from the WLAN poller's background
 * thread ({@code insertScanSamples}, on every logged scan cycle) and read from the JavaFX
 * Application thread (the History tab's search/refresh actions) - two different threads sharing
 * one JDBC connection, which most drivers (including the SQLite one this app uses) do not
 * support safely without external synchronization. Every public method here is {@code
 * synchronized} on this instance so a read and a write can never execute concurrently.
 */
public final class ScanLogDatabase implements AutoCloseable {

    /** Row cap for {@link #querySamples}, exposed so callers can detect/report truncation. */
    public static final int MAX_QUERY_ROWS = 5000;

    public record ScanSampleRow(long tsEpochMilli, String bssid, String ssid, int channel, String band,
                                 int rssiDbm, int linkQuality, String phyType, String security) {
    }

    /** A BSSID paired with the most recent SSID logged for it, for human-readable display. */
    public record BssidLabel(String bssid, String ssid) {
    }

    private final Connection connection;
    private final String jdbcUrl;

    private ScanLogDatabase(Connection connection, String jdbcUrl) {
        this.connection = connection;
        this.jdbcUrl = jdbcUrl;
    }

    public static ScanLogDatabase open(File dbFile) throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Connection conn = DriverManager.getConnection(jdbcUrl);
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS scan_samples (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "ts_epoch_ms INTEGER NOT NULL," +
                        "bssid TEXT NOT NULL," +
                        "ssid TEXT," +
                        "channel INTEGER," +
                        "band TEXT," +
                        "rssi INTEGER," +
                        "link_quality INTEGER," +
                        "phy_type TEXT," +
                        "security TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_scan_samples_ts ON scan_samples(ts_epoch_ms)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_scan_samples_bssid ON scan_samples(bssid)");
                st.execute("CREATE TABLE IF NOT EXISTS survey_points_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "ts_epoch_ms INTEGER NOT NULL," +
                        "project_name TEXT," +
                        "x_norm REAL," +
                        "y_norm REAL," +
                        "target_bssid TEXT," +
                        "rssi INTEGER," +
                        "ping_host TEXT," +
                        "ping_rtt_ms INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS alert_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "ts_epoch_ms INTEGER NOT NULL," +
                        "severity TEXT," +
                        "category TEXT," +
                        "message TEXT," +
                        "related_bssid TEXT)");
            }
        } catch (SQLException e) {
            // Schema init failed (corrupt/locked file, disk error) - close the connection we just
            // opened before rethrowing, otherwise it leaks for the app's lifetime since the
            // caller never receives a ScanLogDatabase instance to close later.
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return new ScanLogDatabase(conn, jdbcUrl);
    }

    public synchronized void insertScanSamples(ScanSnapshot snapshot) {
        String sql = "INSERT INTO scan_samples(ts_epoch_ms,bssid,ssid,channel,band,rssi,link_quality,phy_type,security) " +
                "VALUES(?,?,?,?,?,?,?,?,?)";
        long ts = snapshot.timestamp().toEpochMilli();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ApSnapshot ap : snapshot.accessPoints()) {
                ps.setLong(1, ts);
                ps.setString(2, ap.bssid());
                ps.setString(3, ap.ssid());
                ps.setInt(4, ap.channel());
                ps.setString(5, ap.band());
                ps.setInt(6, ap.rssiDbm());
                ps.setInt(7, ap.linkQuality());
                ps.setString(8, ap.phyType());
                ps.setString(9, ap.securityType().name());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("scan_samples insert failed", e);
        }
    }

    public synchronized void insertSurveyPoint(String projectName, long tsEpochMilli, double xNorm, double yNorm,
                                   String targetBssid, Integer rssi, String pingHost, Integer pingRttMs) {
        String sql = "INSERT INTO survey_points_log(ts_epoch_ms,project_name,x_norm,y_norm,target_bssid,rssi,ping_host,ping_rtt_ms) " +
                "VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tsEpochMilli);
            ps.setString(2, projectName);
            ps.setDouble(3, xNorm);
            ps.setDouble(4, yNorm);
            ps.setString(5, targetBssid);
            if (rssi != null) ps.setInt(6, rssi); else ps.setNull(6, java.sql.Types.INTEGER);
            ps.setString(7, pingHost);
            if (pingRttMs != null) ps.setInt(8, pingRttMs); else ps.setNull(8, java.sql.Types.INTEGER);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("survey_points_log insert failed", e);
        }
    }

    public synchronized void insertAlert(long tsEpochMilli, String severity, String category, String message, String relatedBssid) {
        String sql = "INSERT INTO alert_log(ts_epoch_ms,severity,category,message,related_bssid) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tsEpochMilli);
            ps.setString(2, severity);
            ps.setString(3, category);
            ps.setString(4, message);
            ps.setString(5, relatedBssid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("alert_log insert failed", e);
        }
    }

    /** Raw samples in [fromEpochMilli, toEpochMilli], optionally filtered to one BSSID, newest first. */
    public synchronized List<ScanSampleRow> querySamples(long fromEpochMilli, long toEpochMilli, String bssidFilter) {
        String sql = "SELECT ts_epoch_ms,bssid,ssid,channel,band,rssi,link_quality,phy_type,security FROM scan_samples " +
                "WHERE ts_epoch_ms BETWEEN ? AND ?" +
                (bssidFilter != null && !bssidFilter.isEmpty() ? " AND bssid = ?" : "") +
                " ORDER BY ts_epoch_ms DESC LIMIT " + MAX_QUERY_ROWS;
        List<ScanSampleRow> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromEpochMilli);
            ps.setLong(2, toEpochMilli);
            if (bssidFilter != null && !bssidFilter.isEmpty()) {
                ps.setString(3, bssidFilter);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ScanSampleRow(
                            rs.getLong("ts_epoch_ms"), rs.getString("bssid"), rs.getString("ssid"),
                            rs.getInt("channel"), rs.getString("band"), rs.getInt("rssi"),
                            rs.getInt("link_quality"), rs.getString("phy_type"), rs.getString("security")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("scan_samples query failed", e);
        }
        return result;
    }

    /**
     * Distinct BSSIDs seen at least once (all time), each paired with the most recently logged
     * SSID for it, for populating the single-BSSID filter dropdown with a human-readable "SSID
     * (BSSID)" label instead of a bare MAC address. {@code MAX(id)} (not {@code MAX(ts_epoch_ms)})
     * picks the latest row per BSSID - cheap off the existing primary key, and safe since rows are
     * always inserted in chronological order (one batch insert per scan cycle).
     */
    public synchronized List<BssidLabel> distinctBssids() {
        String sql = "SELECT bssid, ssid FROM scan_samples " +
                "WHERE id IN (SELECT MAX(id) FROM scan_samples GROUP BY bssid) ORDER BY bssid";
        return queryBssidLabels(sql, null, null);
    }

    /**
     * Same as {@link #distinctBssids()} but scoped to BSSIDs that appeared within
     * [fromEpochMilli, toEpochMilli] - used to populate the History chart's multi-BSSID overlay
     * checklist. Deliberately NOT derived from an already-fetched {@link #querySamples} result
     * set: that query's {@code LIMIT 5000} is a cap across all BSSIDs combined, so with several
     * concurrently-active APs it can cover only the last few minutes of a much longer requested
     * range - a checklist built from it would silently omit BSSIDs that were genuinely present
     * earlier in the range. This query has no row limit (it only returns one row per distinct
     * BSSID, not per sample), so it always reflects the full requested range.
     */
    public synchronized List<BssidLabel> distinctBssidsInRange(long fromEpochMilli, long toEpochMilli) {
        String sql = "SELECT bssid, ssid FROM scan_samples " +
                "WHERE id IN (SELECT MAX(id) FROM scan_samples WHERE ts_epoch_ms BETWEEN ? AND ? GROUP BY bssid) " +
                "ORDER BY bssid";
        return queryBssidLabels(sql, fromEpochMilli, toEpochMilli);
    }

    /**
     * Streams every row in [fromEpochMilli, toEpochMilli] (optionally filtered to one BSSID)
     * straight to a CSV file, bypassing {@link #MAX_QUERY_ROWS} - {@link #querySamples} caps at
     * 5000 rows to keep the History table/chart responsive, but a full-range audit export (e.g. a
     * 7-day range across many APs) can legitimately be far larger, so this reads the {@link
     * ResultSet} row-by-row rather than materializing it as a {@code List<ScanSampleRow>} first.
     *
     * <p>Deliberately NOT {@code synchronized} on this instance, and deliberately opens its own
     * dedicated {@link Connection} instead of using {@link #connection} - every other method here
     * is synchronized because they all share one {@code Connection} object, which most JDBC
     * drivers (including this app's SQLite one) don't allow concurrent use of from multiple
     * threads. A large streamed export can run for seconds to minutes; holding the shared instance
     * lock for that whole duration would block the WLAN poller's own {@link #insertScanSamples}
     * calls (and any other History action on the JavaFX thread) for just as long. A second,
     * independent connection to the same (WAL-mode) database file lets this read proceed
     * concurrently with the poller's writes instead.
     */
    public long exportSamplesToCsv(long fromEpochMilli, long toEpochMilli, String bssidFilter, File file)
            throws IOException {
        String sql = "SELECT ts_epoch_ms,bssid,ssid,channel,band,rssi,link_quality,phy_type,security FROM scan_samples " +
                "WHERE ts_epoch_ms BETWEEN ? AND ?" +
                (bssidFilter != null && !bssidFilter.isEmpty() ? " AND bssid = ?" : "") +
                " ORDER BY ts_epoch_ms DESC";
        try (Connection exportConnection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = exportConnection.prepareStatement(sql);
             Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            ps.setLong(1, fromEpochMilli);
            ps.setLong(2, toEpochMilli);
            if (bssidFilter != null && !bssidFilter.isEmpty()) {
                ps.setString(3, bssidFilter);
            }
            writer.write("timestamp,ssid,bssid,channel,band,rssi_dbm,link_quality,phy_type,security\n");
            long rowCount = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    writer.write(Instant.ofEpochMilli(rs.getLong("ts_epoch_ms")).toString());
                    writer.write(',');
                    writer.write(CsvUtil.escapeField(rs.getString("ssid")));
                    writer.write(',');
                    writer.write(CsvUtil.escapeField(rs.getString("bssid")));
                    writer.write(',');
                    writer.write(Integer.toString(rs.getInt("channel")));
                    writer.write(',');
                    writer.write(CsvUtil.escapeField(rs.getString("band")));
                    writer.write(',');
                    writer.write(Integer.toString(rs.getInt("rssi")));
                    writer.write(',');
                    writer.write(Integer.toString(rs.getInt("link_quality")));
                    writer.write(',');
                    writer.write(CsvUtil.escapeField(rs.getString("phy_type")));
                    writer.write(',');
                    writer.write(CsvUtil.escapeField(rs.getString("security")));
                    writer.write('\n');
                    rowCount++;
                }
            }
            return rowCount;
        } catch (SQLException e) {
            throw new IOException("scan_samples export failed", e);
        }
    }

    private List<BssidLabel> queryBssidLabels(String sql, Long fromEpochMilli, Long toEpochMilli) {
        List<BssidLabel> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (fromEpochMilli != null) {
                ps.setLong(1, fromEpochMilli);
                ps.setLong(2, toEpochMilli);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BssidLabel(rs.getString("bssid"), rs.getString("ssid")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("distinct bssid query failed", e);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort close
        }
    }
}

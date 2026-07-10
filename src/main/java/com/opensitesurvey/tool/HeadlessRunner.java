package com.waj.tool;

import com.waj.tool.alert.Alert;
import com.waj.tool.alert.AlertContext;
import com.waj.tool.alert.AlertEngine;
import com.waj.tool.alert.NotificationService;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;
import com.waj.tool.persistence.AppConfig;
import com.waj.tool.persistence.AppConfigStore;
import com.waj.tool.persistence.AppPaths;
import com.waj.tool.persistence.ScanLogDatabase;
import com.waj.tool.wlan.WlanPoller;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Console-only scan loop for unattended/server use: no JavaFX {@code Stage}, no window - just a
 * periodic Wi-Fi scan logged to the same SQLite long-term log the GUI's History tab reads (see
 * {@link ScanLogDatabase}), plus a one-line-per-AP summary on stdout, running until interrupted
 * (Ctrl+C, or a service manager sending a termination signal).
 *
 * <p>Entered via a {@code --headless} command-line flag (see {@code App.main}/{@code
 * Launcher.main}) rather than being its own separate jar/main-class, so the exact same packaged
 * exe works for both a normal interactive install and a background/monitoring-only deployment on
 * a machine with no one watching the screen.
 */
public final class HeadlessRunner {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private HeadlessRunner() {
    }

    /**
     * Parses {@code --headless} / {@code --interval <ms>} out of the process's command-line
     * arguments and, if headless mode was requested, runs it to completion (blocking until
     * Ctrl+C). Shared by both {@code App.main} (dev-mode {@code javafx:run} entry point) and
     * {@code Launcher.main} (packaged jar/exe entry point) so the flag parsing lives in one place.
     *
     * @return {@code true} if headless mode was requested - the caller must not also launch the
     *         JavaFX UI in that case.
     */
    public static boolean tryRun(String[] args) {
        boolean headless = false;
        long intervalMillis = 2_000;
        for (int i = 0; i < args.length; i++) {
            if ("--headless".equals(args[i])) {
                headless = true;
            } else if ("--interval".equals(args[i]) && i + 1 < args.length) {
                try {
                    intervalMillis = Long.parseLong(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    // keep the default rather than failing a background/service deployment over a typo
                }
            }
        }
        if (headless) {
            run(intervalMillis);
        }
        return headless;
    }

    public static void run(long pollIntervalMillis) {
        System.out.println("WAJ Wi-Fi Survey - headless scan mode (interval=" + pollIntervalMillis + "ms, Ctrl+C to stop)");

        ScanLogDatabase scanLogDatabase;
        try {
            scanLogDatabase = ScanLogDatabase.open(AppPaths.scanLogDbFile());
            System.out.println("Logging to: " + AppPaths.scanLogDbFile().getAbsolutePath());
        } catch (Exception e) {
            scanLogDatabase = null;
            System.out.println("Warning: could not open the long-term log database (" + e.getMessage()
                    + ") - continuing with console output only.");
        }
        ScanLogDatabase database = scanLogDatabase;

        // Same alert pipeline the GUI uses (RSSI threshold, rogue-AP, new-SSID, channel-congestion
        // rules) plus Windows tray notifications - both are plain java.awt/JDK APIs with no JavaFX
        // dependency, so they work identically here. Without this, headless mode - explicitly
        // meant for unattended/server monitoring - would silently have no alerting at all.
        AppConfig appConfig = AppConfigStore.load();
        AlertContext alertContext = new AlertContext(appConfig);
        AlertEngine alertEngine = new AlertEngine(alertContext);
        NotificationService notificationService = new NotificationService();

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        WlanPoller poller = new WlanPoller(
                pollIntervalMillis,
                snapshot -> {
                    if (database != null) {
                        try {
                            database.insertScanSamples(snapshot);
                        } catch (Exception ignored) {
                            // best-effort logging; a DB hiccup must not take down the poller
                        }
                    }
                    List<Alert> firedAlerts = alertEngine.onSnapshot(snapshot);
                    for (Alert fired : firedAlerts) {
                        notificationService.notify(fired, appConfig.windowsNotificationsEnabled);
                        System.out.println("[" + fired.severity() + "] " + fired.category() + ": " + fired.message());
                    }
                    printSummary(snapshot);
                },
                interfaces -> {
                    if (interfaces.isEmpty()) {
                        System.out.println("No wireless interfaces found.");
                    }
                },
                iface -> System.out.println("Using interface: " + iface.description()),
                accessDenied -> System.out.println("Access denied: " + accessDenied.getMessage()
                        + " (Windows Settings > Privacy & security > Location may need to allow desktop apps to access location)"),
                error -> System.out.println("Error: " + error.getMessage())
        );

        // A shutdown hook (not a try/finally around await()) is what actually runs on Ctrl+C -
        // SIGINT there doesn't unwind the call stack, it triggers the JVM's normal shutdown
        // sequence directly. poller.stop() blocks briefly (see its own javadoc) waiting for the
        // in-flight poll cycle to finish and the native WLAN handle to close, which is safe to do
        // here since the JVM won't exit until every shutdown hook returns.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping...");
            poller.stop();
            if (database != null) {
                database.close();
            }
            shutdownLatch.countDown();
        }, "headless-shutdown"));

        poller.start();
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printSummary(ScanSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(TIMESTAMP_FORMATTER.format(snapshot.timestamp())).append("] ")
                .append(snapshot.accessPoints().size()).append(" AP(s)");
        for (ApSnapshot ap : snapshot.accessPoints()) {
            sb.append("\n  ").append(String.format("%-24s %-18s ch%-3d %-6s %4ddBm %s",
                    truncate(ap.ssid().isEmpty() ? "<hidden>" : ap.ssid(), 24), ap.bssid(),
                    ap.channel(), ap.band(), ap.rssiDbm(), ap.securityType().label()));
        }
        System.out.println(sb);
    }

    private static String truncate(String s, int maxLength) {
        return s.length() <= maxLength ? s : s.substring(0, maxLength - 1) + "…";
    }
}

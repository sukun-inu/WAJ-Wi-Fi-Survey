package com.waj.tool.wlan;

import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background poller: reads the driver's cached BSS list on a fixed interval (set once at
 * construction) and requests a fresh over-the-air scan on that same cadence, floored at
 * {@link #MIN_SCAN_INTERVAL_MILLIS} - forcing a real scan every poll tick would hammer the driver
 * and spam the location-consent/in-use indicator for no benefit, since an over-the-air scan itself
 * takes real seconds regardless of how often it's requested. All callbacks fire on a private
 * single background thread - callers must marshal to the JavaFX thread themselves (e.g. wrap each
 * consumer with {@code Platform.runLater}).
 *
 * <p>A single Wi-Fi adapter can't scan other channels and carry an active connection's data at
 * the same time - triggering {@code WlanScan} forces the radio to briefly leave its associated
 * channel, which measurably hurts throughput on whatever SSID it's currently connected to. So
 * whenever the polled adapter reports itself as actually connected (not just "present"), the scan
 * floor backs off much further to {@link #CONNECTED_MIN_SCAN_INTERVAL_MILLIS} - cached-BSS-list
 * reads (which don't touch the radio) still happen at the requested poll interval regardless, so
 * the UI keeps updating promptly; only the disruptive over-the-air scan itself is throttled.
 */
public final class WlanPoller {

    private static final long MIN_SCAN_INTERVAL_MILLIS = 2_000; // floor regardless of poll interval
    private static final long CONNECTED_MIN_SCAN_INTERVAL_MILLIS = 15_000; // floor while actively connected
    private static final int WLAN_INTERFACE_STATE_CONNECTED = 1;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wlan-poller");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<ScanSnapshot> onSnapshot;
    private final Consumer<List<WlanInterface>> onInterfacesListed;
    private final Consumer<WlanInterface> onActiveInterfaceChanged;
    private final Consumer<WlanAccessDeniedException> onAccessDenied;
    private final Consumer<Exception> onError;

    private WlanClient client;
    private volatile WlanInterface activeInterface;
    private long lastScanMillis = 0;
    private volatile long pollIntervalMillis;
    private ScheduledFuture<?> scheduledTask;
    private volatile boolean stopped = false;

    public WlanPoller(long initialPollIntervalMillis,
                       Consumer<ScanSnapshot> onSnapshot,
                       Consumer<List<WlanInterface>> onInterfacesListed,
                       Consumer<WlanInterface> onActiveInterfaceChanged,
                       Consumer<WlanAccessDeniedException> onAccessDenied,
                       Consumer<Exception> onError) {
        this.pollIntervalMillis = initialPollIntervalMillis;
        this.onSnapshot = onSnapshot;
        this.onInterfacesListed = onInterfacesListed;
        this.onActiveInterfaceChanged = onActiveInterfaceChanged;
        this.onAccessDenied = onAccessDenied;
        this.onError = onError;
    }

    public synchronized void start() {
        scheduledTask = executor.scheduleWithFixedDelay(
                this::pollCycle, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Switches which adapter is polled. Safe to call from any thread. */
    public void selectInterface(WlanInterface iface) {
        executor.execute(() -> this.activeInterface = iface);
    }

    /** Re-enumerates wireless adapters (e.g. after plugging in a USB Wi-Fi dongle). */
    public void refreshInterfaces() {
        executor.execute(() -> {
            try {
                if (client == null) {
                    client = WlanClient.open();
                }
                List<WlanInterface> found = client.listInterfaces();
                onInterfacesListed.accept(found);
            } catch (WlanAccessDeniedException e) {
                onAccessDenied.accept(e);
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    /**
     * Idempotent (callers - see {@code App}'s {@code setOnCloseRequest} and {@code Application.stop()}
     * override - can both legitimately fire on a normal shutdown). Closing {@link #client} is
     * submitted to run on {@code executor}'s own thread rather than done here directly: {@code
     * client} is only ever written from that thread and reading it from the caller's thread
     * without synchronization risked observing a stale {@code null} (leaking the native WLAN
     * handle), and closing it from a different thread than an in-flight {@code pollCycle()} would
     * violate {@link WlanClient}'s documented "confine access to one thread" contract. A plain
     * {@code shutdown()} (not {@code shutdownNow()}) is used so this queued close task is
     * guaranteed to actually run - {@code shutdownNow()} would otherwise be free to discard it
     * before it starts.
     */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        executor.execute(() -> {
            if (client != null) {
                client.close();
                client = null;
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollCycle() {
        try {
            if (client == null) {
                client = WlanClient.open();
            }
            if (activeInterface == null) {
                List<WlanInterface> found = client.listInterfaces();
                onInterfacesListed.accept(found);
                if (found.isEmpty()) {
                    return;
                }
                activeInterface = found.get(0);
                onActiveInterfaceChanged.accept(activeInterface);
            }

            boolean connected = isCurrentlyConnected(client, activeInterface);
            long floorMillis = connected ? CONNECTED_MIN_SCAN_INTERVAL_MILLIS : MIN_SCAN_INTERVAL_MILLIS;

            long now = System.currentTimeMillis();
            long scanIntervalMillis = Math.max(pollIntervalMillis, floorMillis);
            if (now - lastScanMillis >= scanIntervalMillis) {
                client.scan(activeInterface.guid());
                lastScanMillis = now;
            }

            List<ApSnapshot> aps = client.getBssList(activeInterface.guid());
            onSnapshot.accept(new ScanSnapshot(Instant.now(), aps));
        } catch (WlanAccessDeniedException e) {
            onAccessDenied.accept(e);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * {@code activeInterface} is only ever assigned once (first discovery) or on an explicit user
     * action - it doesn't track the adapter's live connection state - so this re-enumerates
     * interfaces (a cheap, local {@code WlanEnumInterfaces} query with no radio activity, unlike
     * {@code WlanScan}) each cycle to read the current {@code stateCode} for the polled adapter.
     */
    private static boolean isCurrentlyConnected(WlanClient client, WlanInterface iface) {
        return client.listInterfaces().stream()
                .filter(i -> i.guid().equals(iface.guid()))
                .findFirst()
                .map(i -> i.stateCode() == WLAN_INTERFACE_STATE_CONNECTED)
                .orElse(false);
    }
}

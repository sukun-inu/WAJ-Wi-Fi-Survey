package com.opensitesurvey.tool.ping;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Background PingPlotter-style monitor: discovers a route's hops once via {@link
 * TracerouteProbe}, then continuously pings every discovered hop (not just the final
 * destination) on a fixed interval, reporting each cycle's per-hop result via callback. All
 * callbacks fire on a private background thread - callers must marshal to the JavaFX thread
 * themselves (e.g. wrap each consumer with {@code Platform.runLater}), same convention as {@code
 * WlanPoller}.
 */
public final class TraceroutePoller {

    public record HopResult(int hopNumber, String ip, Integer rttMillis) {
    }

    private static final int MAX_HOPS = 30;
    private static final int DISCOVERY_PER_HOP_TIMEOUT_MILLIS = 1000;
    private static final int PING_TIMEOUT_MILLIS = 1000;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "traceroute-poller");
        t.setDaemon(true);
        return t;
    });

    // Each hop's ping shells out to its own ping.exe process and waits on it independently, so
    // there's no reason to serialize them - pinging N hops one after another (the original
    // implementation) made every cycle take roughly N times as long as a single ping, which is
    // exactly why results felt sluggish with a multi-hop route. Pinging all hops concurrently
    // makes one cycle take about as long as the single slowest hop, not the sum of all of them.
    private final ExecutorService pingWorkers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "traceroute-ping-worker");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<List<TracerouteProbe.Hop>> onRouteDiscovered;
    private final Consumer<List<HopResult>> onPingCycle;

    private volatile List<TracerouteProbe.Hop> hops = List.of();
    private Future<?> discoveryTask;
    // Set as soon as discoverRoute() spawns tracert.exe - stop() destroys it directly rather than
    // relying on discoveryTask.cancel(true), since a blocked Process#getInputStream() read (which
    // is where discoverRoute() spends nearly all its time) does not respond to Thread.interrupt();
    // destroying the process is what actually unblocks that read (EOF), letting a stop()+start()
    // for a new host take effect immediately instead of waiting out the old discovery's own
    // (up to tens-of-seconds) internal timeout.
    private volatile Process discoveryProcess;
    private ScheduledFuture<?> pingTask;

    public TraceroutePoller(Consumer<List<TracerouteProbe.Hop>> onRouteDiscovered,
                             Consumer<List<HopResult>> onPingCycle) {
        this.onRouteDiscovered = onRouteDiscovered;
        this.onPingCycle = onPingCycle;
    }

    /** Starts (or restarts, if already monitoring another host) route discovery + continuous per-hop pinging. */
    public synchronized void start(String host, long pingIntervalMillis) {
        stop();
        hops = List.of();
        discoveryTask = executor.submit(() -> {
            List<TracerouteProbe.Hop> discovered = TracerouteProbe.discoverRoute(
                    host, MAX_HOPS, DISCOVERY_PER_HOP_TIMEOUT_MILLIS, p -> discoveryProcess = p);
            hops = discovered;
            onRouteDiscovered.accept(discovered);
            // Chained rather than scheduled with a guessed initial delay: discovery can take
            // anywhere from ~1s (a short, responsive route) to tens of seconds (many timed-out
            // hops), so starting the repeating ping cycle only once discovery actually finishes
            // avoids either a needlessly long wait or firing before hops are known.
            synchronized (TraceroutePoller.this) {
                pingTask = executor.scheduleWithFixedDelay(this::pingCycle, 0, pingIntervalMillis, TimeUnit.MILLISECONDS);
            }
        });
    }

    public synchronized void stop() {
        if (discoveryTask != null) {
            discoveryTask.cancel(true);
            discoveryTask = null;
        }
        Process process = discoveryProcess;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        discoveryProcess = null;
        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
        pingWorkers.shutdownNow();
    }

    private void pingCycle() {
        List<TracerouteProbe.Hop> currentHops = hops;
        if (currentHops.isEmpty()) {
            return;
        }
        // Dispatch every hop's ping concurrently, then join in the same order the hops were
        // submitted - join() on an already-completed (or completing-out-of-order) future just
        // returns its value, so the result list stays in hop order regardless of which one
        // actually finished first.
        List<CompletableFuture<HopResult>> futures = currentHops.stream()
                .map(hop -> CompletableFuture.supplyAsync(
                        () -> new HopResult(hop.number(), hop.ip(), PingProbe.ping(hop.ip(), PING_TIMEOUT_MILLIS).orElse(null)),
                        pingWorkers))
                .collect(Collectors.toList());
        List<HopResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        onPingCycle.accept(results);
    }
}

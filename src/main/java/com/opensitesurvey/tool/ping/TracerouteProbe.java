package com.opensitesurvey.tool.ping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the Windows {@code tracert} command once to discover a route's hops (hop number + IP),
 * reusing the same shell-out + MS932 decoding approach as {@link PingProbe} - confirmed empirically
 * (via a throwaway {@code ProcessBuilder} test piping through {@code MS932}, the same way
 * {@link PingProbe}'s encoding was verified) that Windows localizes {@code tracert}'s console
 * output on this machine (ja_JP) the same way it does {@code ping}'s.
 *
 * <p>This class only *discovers* the hop list - ongoing per-hop latency monitoring is done by
 * repeatedly calling {@link PingProbe#ping(String, int)} against each discovered hop's IP (see
 * {@code TraceroutePoller}), since {@code tracert} itself only ever runs a single 3-probes-per-hop
 * pass, not continuous monitoring - there is no "continuous tracert" mode to shell out to.
 */
public final class TracerouteProbe {

    private static final Charset CONSOLE_CHARSET = Charset.forName("MS932");

    // Hop number, then (anything), then the first IPv4-looking dotted-quad on the line - that's
    // always the actual hop address whether written bare ("27.86.120.109") or in
    // "hostname [ip]" form ("sjkBBAR001-1.bb.kddi.ne.jp [111.87.243.233]"), since a hostname's own
    // dots never form 4 consecutive all-digit groups - confirmed against real tracert output.
    private static final Pattern HOP_LINE =
            Pattern.compile("^\\s*(\\d+)\\s+.*?(\\d{1,3}(?:\\.\\d{1,3}){3})");

    public record Hop(int number, String ip) {
    }

    private TracerouteProbe() {
    }

    /**
     * @return discovered hops in order; a hop that fully timed out on all 3 probes (no IP ever
     *         printed for it) is simply absent from the result, since there's no address to
     *         subsequently ping for it anyway.
     */
    public static List<Hop> discoverRoute(String host, int maxHops, int perHopTimeoutMillis) {
        List<Hop> hops = new ArrayList<>();
        if (host == null || host.isBlank()) {
            return hops;
        }
        Process process = null;
        try {
            // -d: skip the reverse DNS lookup tracert otherwise performs for every hop before
            // printing its line - by far the biggest source of "traceroute is slow" complaints,
            // since it's a synchronous query per hop that can each take seconds on a slow/
            // unresponsive DNS server. Unneeded here regardless: parseHopLine() only ever reads
            // the IP address, whether or not a hostname prefix is present in the line.
            process = new ProcessBuilder("tracert", "-d", "-h", String.valueOf(maxHops),
                    "-w", String.valueOf(perHopTimeoutMillis), host.trim())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), CONSOLE_CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseHopLine(line).ifPresent(hops::add);
                }
            }
            // Worst case every hop times out on all 3 probes before the final destination
            // responds - bound the wait generously rather than blocking forever on a stuck trace.
            long overallTimeoutMillis = (long) maxHops * (perHopTimeoutMillis + 200L) + 5_000L;
            if (!process.waitFor(overallTimeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            // best-effort discovery; a failed/partial trace just yields whatever hops were parsed
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return hops;
    }

    static java.util.Optional<Hop> parseHopLine(String line) {
        Matcher m = HOP_LINE.matcher(line);
        if (m.find()) {
            return java.util.Optional.of(new Hop(Integer.parseInt(m.group(1)), m.group(2)));
        }
        return java.util.Optional.empty();
    }
}

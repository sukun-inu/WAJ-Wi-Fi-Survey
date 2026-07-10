package com.opensitesurvey.tool.ping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the Windows {@code ping} command and parses the round-trip time from its output.
 * {@code InetAddress.isReachable()} is deliberately not used - on Windows it's unreliable without
 * administrator rights (falls back to a TCP probe on port 7 that most hosts don't answer).
 *
 * <p>Windows localizes ping's console output - this machine (ja_JP) prints "時間 =15ms" (with a
 * space before "=") rather than "time=15ms" - confirmed empirically by piping a real {@code
 * ProcessBuilder}-spawned ping through this exact code path, since it differs from what some
 * shells display. The pattern therefore tolerates optional whitespace around "=". The output is
 * also NOT UTF-8: since JDK 18 (JEP 400) {@code Charset.defaultCharset()} is UTF-8 regardless of
 * platform, but {@code ping.exe} itself still writes bytes in the Windows Japanese code page
 * (CP932/MS932) - decoding with the JVM default charset silently corrupts every Japanese
 * character into '?' replacement chars and breaks matching, so MS932 is used explicitly.
 */
public final class PingProbe {

    private static final Charset CONSOLE_CHARSET = Charset.forName("MS932");
    private static final Pattern RTT_PATTERN = Pattern.compile("(?:time|時間)\\s*[=<]\\s*(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);

    private PingProbe() {
    }

    /** @return round-trip time in ms, or empty if the host didn't respond within the timeout. */
    public static Optional<Integer> ping(String host, int timeoutMillis) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        Process process = null;
        try {
            process = new ProcessBuilder("ping", "-n", "1", "-w", String.valueOf(timeoutMillis), host.trim())
                    .redirectErrorStream(true)
                    .start();
            // ping.exe's own -w flag only bounds the ICMP echo wait, not e.g. a slow/unresponsive
            // DNS resolution phase for a hostname target - if the process is still alive past our
            // own timeout, force-kill it so the blocking readLine() loop below is guaranteed to
            // unblock (EOF, since destroying the process closes its stdout pipe) instead of
            // hanging this thread indefinitely on a process that never prints another line.
            Process watchedProcess = process;
            Thread watchdog = new Thread(() -> {
                try {
                    if (!watchedProcess.waitFor(timeoutMillis + 2000L, TimeUnit.MILLISECONDS)) {
                        watchedProcess.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    // best-effort watchdog only
                }
            }, "ping-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), CONSOLE_CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutMillis + 2000L, TimeUnit.MILLISECONDS);
            if (!finished) {
                return Optional.empty();
            }
            return parseRttMillis(output.toString());
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static Optional<Integer> parseRttMillis(String pingOutput) {
        Matcher m = RTT_PATTERN.matcher(pingOutput);
        if (m.find()) {
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        return Optional.empty();
    }
}

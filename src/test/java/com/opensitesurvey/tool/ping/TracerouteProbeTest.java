package com.opensitesurvey.tool.ping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracerouteProbeTest {

    // Real captured/decoded tracert output lines (Japanese Windows, MS932-decoded) from a live
    // run against 8.8.8.8, used verbatim rather than guessed at.
    @Test
    void parsesBareIpHopLine() {
        Optional<TracerouteProbe.Hop> hop = TracerouteProbe.parseHopLine("  4    12 ms    12 ms    13 ms  27.86.120.109 ");
        assertTrue(hop.isPresent());
        assertEquals(4, hop.get().number());
        assertEquals("27.86.120.109", hop.get().ip());
    }

    @Test
    void parsesHostnameBracketedIpHopLine() {
        Optional<TracerouteProbe.Hop> hop = TracerouteProbe.parseHopLine(
                "  3    10 ms    10 ms    10 ms  sjkBBAR001-1.bb.kddi.ne.jp [111.87.243.233] ");
        assertTrue(hop.isPresent());
        assertEquals(3, hop.get().number());
        assertEquals("111.87.243.233", hop.get().ip());
    }

    @Test
    void parsesSubMillisecondRttHopLine() {
        Optional<TracerouteProbe.Hop> hop = TracerouteProbe.parseHopLine("  1    <1 ms    <1 ms     1 ms  10.75.66.1 ");
        assertTrue(hop.isPresent());
        assertEquals(1, hop.get().number());
        assertEquals("10.75.66.1", hop.get().ip());
    }

    @Test
    void ignoresNonHopLines() {
        assertFalse(TracerouteProbe.parseHopLine("").isPresent());
        assertFalse(TracerouteProbe.parseHopLine("dns.google [8.8.8.8] へのルートをトレースします").isPresent());
        assertFalse(TracerouteProbe.parseHopLine("トレースを完了しました。").isPresent());
    }
}

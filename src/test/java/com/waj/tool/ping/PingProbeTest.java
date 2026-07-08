package com.waj.tool.ping;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PingProbeTest {

    @Test
    void parsesEnglishWindowsOutput() {
        String output = "Pinging 8.8.8.8 with 32 bytes of data:\n"
                + "Reply from 8.8.8.8: bytes=32 time=15ms TTL=57\n"
                + "\nPing statistics for 8.8.8.8:\n";
        assertEquals(Optional.of(15), PingProbe.parseRttMillis(output));
    }

    @Test
    void parsesJapaneseWindowsOutput() {
        String output = "8.8.8.8 に ping を送信しています 32 バイトのデータ:\n"
                + "8.8.8.8 からの応答: バイト数=32 時間=15ms TTL=57\n";
        assertEquals(Optional.of(15), PingProbe.parseRttMillis(output));
    }

    @Test
    void parsesJapaneseWindowsOutputWithSpaceBeforeEquals() {
        // Confirmed empirically on this machine (build 26200, ja_JP): real ping.exe output has a
        // space before "=" - "時間 =13ms" - unlike the no-space form some references show.
        String output = "8.8.8.8 からの応答: バイト数 =32 時間 =13ms TTL=116\n";
        assertEquals(Optional.of(13), PingProbe.parseRttMillis(output));
    }

    @Test
    void parsesSubMillisecondRtt() {
        String output = "Reply from 127.0.0.1: bytes=32 time<1ms TTL=128\n";
        assertEquals(Optional.of(1), PingProbe.parseRttMillis(output));
    }

    @Test
    void timeoutOutputYieldsEmpty() {
        String output = "Request timed out.\n";
        assertTrue(PingProbe.parseRttMillis(output).isEmpty());
    }

    @Test
    void japaneseTimeoutOutputYieldsEmpty() {
        String output = "要求がタイムアウトしました。\n";
        assertTrue(PingProbe.parseRttMillis(output).isEmpty());
    }
}

package com.waj.tool.wlan;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Dot11SsidTest {

    @Test
    void decodesPlainAsciiSsid() {
        Dot11Ssid ssid = new Dot11Ssid();
        byte[] bytes = "S-AP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, ssid.ucSSID, 0, bytes.length);
        ssid.uSSIDLength = bytes.length;
        assertEquals("S-AP", ssid.asString());
    }

    @Test
    void decodesValidUtf8Ssid() {
        Dot11Ssid ssid = new Dot11Ssid();
        byte[] bytes = "日本語SSID".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, ssid.ucSSID, 0, bytes.length);
        ssid.uSSIDLength = bytes.length;
        assertEquals("日本語SSID", ssid.asString());
    }

    @Test
    void fallsBackToShiftJisForNonUtf8Bytes() {
        // A legacy/CJK access point broadcasting raw Shift-JIS bytes is not valid UTF-8 - decoding
        // straight to UTF-8 (the old behavior) would silently replace every byte with U+FFFD
        // instead of recovering the real text.
        Dot11Ssid ssid = new Dot11Ssid();
        byte[] bytes = "テスト".getBytes(Charset.forName("MS932"));
        System.arraycopy(bytes, 0, ssid.ucSSID, 0, bytes.length);
        ssid.uSSIDLength = bytes.length;
        assertEquals("テスト", ssid.asString());
    }
}

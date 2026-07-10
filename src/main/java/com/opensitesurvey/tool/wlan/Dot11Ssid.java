package com.opensitesurvey.tool.wlan;

import com.sun.jna.Structure;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Maps to the native {@code DOT11_SSID} struct (Wlantypes.h). */
public class Dot11Ssid extends Structure {

    private static final Charset LEGACY_CHARSET = Charset.forName("MS932");

    public int uSSIDLength;
    public byte[] ucSSID = new byte[32];

    public Dot11Ssid() {
        super();
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("uSSIDLength", "ucSSID");
    }

    /**
     * Raw 802.11 SSIDs are arbitrary bytes, not guaranteed UTF-8 - legacy/CJK access points
     * commonly broadcast Shift-JIS. Decoding straight to UTF-8 (as before) silently replaces every
     * invalid byte with U+FFFD instead of failing, so a non-UTF-8 SSID would render as garbled
     * text with no indication anything was wrong. Try strict UTF-8 first (correct for the common
     * case and for pure-ASCII SSIDs), and only fall back to the same MS932 decoding this app
     * already relies on for Windows' localized ping/tracert console output if that fails.
     */
    public String asString() {
        int len = Math.max(0, Math.min(uSSIDLength, ucSSID.length));
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(ucSSID, 0, len))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(ucSSID, 0, len, LEGACY_CHARSET);
        }
    }
}

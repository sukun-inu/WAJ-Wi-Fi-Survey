package com.opensitesurvey.tool.wlan;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid.GUID;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Maps to the native {@code WLAN_INTERFACE_INFO} struct (wlanapi.h).
 *
 * <p>{@code strInterfaceDescription} is a fixed {@code WCHAR[256]} (512 bytes). It is declared
 * here as a raw {@code byte[512]} rather than {@code char[256]} - JNA's default (non-Unicode)
 * {@code char} field maps to a single-byte native {@code char}, which would under-size the
 * field and corrupt every subsequent offset in the enclosing array. The bytes are decoded
 * manually as UTF-16LE up to the NUL terminator instead.
 */
public class WlanInterfaceInfo extends Structure {

    public GUID interfaceGuid;
    public byte[] strInterfaceDescriptionRaw = new byte[512];
    public int isState;

    public WlanInterfaceInfo() {
        super();
    }

    public WlanInterfaceInfo(Pointer sharedMemory) {
        super(sharedMemory);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("interfaceGuid", "strInterfaceDescriptionRaw", "isState");
    }

    public String description() {
        int nul = 0;
        while (nul + 1 < strInterfaceDescriptionRaw.length
                && !(strInterfaceDescriptionRaw[nul] == 0 && strInterfaceDescriptionRaw[nul + 1] == 0)) {
            nul += 2;
        }
        return new String(strInterfaceDescriptionRaw, 0, nul, StandardCharsets.UTF_16LE);
    }
}

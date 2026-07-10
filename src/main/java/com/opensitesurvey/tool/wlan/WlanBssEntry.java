package com.waj.tool.wlan;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/**
 * Maps to the native {@code WLAN_BSS_ENTRY} struct (wlanapi.h).
 *
 * <p>Field order below must exactly match the native declaration order - JNA computes each
 * field's offset (with natural alignment/padding) from {@link #getFieldOrder()}, not from
 * source-declaration order alone.
 */
public class WlanBssEntry extends Structure {

    public Dot11Ssid dot11Ssid;
    public int uPhyId;
    public byte[] dot11Bssid = new byte[6];
    public int dot11BssType;
    public int dot11BssPhyType;
    public int lRssi;
    public int uLinkQuality;
    public byte bInRegDomain;
    public short usBeaconPeriod;
    public long ullTimestamp;
    public long ullHostTimestamp;
    public short usCapabilityInformation;
    public int ulChCenterFrequency;
    public WlanRateSet wlanRateSet;
    public int ulIeOffset;
    public int ulIeSize;

    public WlanBssEntry() {
        super();
    }

    public WlanBssEntry(Pointer sharedMemory) {
        super(sharedMemory);
    }

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList(
                "dot11Ssid", "uPhyId", "dot11Bssid", "dot11BssType", "dot11BssPhyType",
                "lRssi", "uLinkQuality", "bInRegDomain", "usBeaconPeriod",
                "ullTimestamp", "ullHostTimestamp", "usCapabilityInformation",
                "ulChCenterFrequency", "wlanRateSet", "ulIeOffset", "ulIeSize");
    }

    public String bssidString() {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < dot11Bssid.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", dot11Bssid[i]));
        }
        return sb.toString();
    }

    /** Bit 4 (Privacy) of the 802.11 Capability Information field. */
    public boolean privacyEnabled() {
        return (usCapabilityInformation & 0x0010) != 0;
    }
}

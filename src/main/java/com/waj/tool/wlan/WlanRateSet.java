package com.waj.tool.wlan;

import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

/** Maps to the native {@code WLAN_RATE_SET} struct (wlanapi.h). Embedded by value inside {@link WlanBssEntry}. */
public class WlanRateSet extends Structure {

    public int uRateSetLength;
    public short[] usRateSet = new short[126];

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("uRateSetLength", "usRateSet");
    }
}

package com.waj.tool.model;

import com.waj.tool.channel.BssLoadParser;
import com.waj.tool.channel.ChannelWidthParser;
import com.waj.tool.security.SecurityClassifier;
import com.waj.tool.security.SecurityType;
import com.waj.tool.util.ChannelUtil;
import com.waj.tool.util.PhyTypeUtil;
import com.waj.tool.wlan.WlanBssEntry;

import java.time.Instant;

/** A single access point observation taken from one {@code WlanGetNetworkBssList} call. */
public record ApSnapshot(
        String ssid,
        String bssid,
        int channel,
        int frequencyKhz,
        String band,
        int rssiDbm,
        int linkQuality,
        String phyType,
        boolean privacyEnabled,
        SecurityType securityType,
        Integer channelUtilizationPercent,
        Instant timestamp,
        int channelWidthMhz,
        int effectiveCenterChannel
) {

    /**
     * Legacy constructor for callers (mostly tests) built before channel-width detection existed -
     * defaults to a plain 20MHz channel centered on {@code channel}, i.e. the same behavior this
     * app always had.
     */
    public ApSnapshot(String ssid, String bssid, int channel, int frequencyKhz, String band, int rssiDbm,
                       int linkQuality, String phyType, boolean privacyEnabled, SecurityType securityType,
                       Integer channelUtilizationPercent, Instant timestamp) {
        this(ssid, bssid, channel, frequencyKhz, band, rssiDbm, linkQuality, phyType, privacyEnabled,
                securityType, channelUtilizationPercent, timestamp, 20, channel);
    }

    /**
     * @param ie raw information-element blob copied from {@code WLAN_BSS_ENTRY.ulIeOffset}/
     *           {@code ulIeSize} - must be copied out of native memory by the caller before this
     *           is invoked (see {@code WlanClient.getBssList()}), since that memory is freed via
     *           {@code WlanFreeMemory} shortly after each scan call returns.
     */
    public static ApSnapshot from(WlanBssEntry entry, byte[] ie, Instant timestamp) {
        BssLoadParser.BssLoad bssLoad = BssLoadParser.parse(ie);
        int channel = ChannelUtil.frequencyKhzToChannel(entry.ulChCenterFrequency);
        String band = ChannelUtil.band(entry.ulChCenterFrequency);
        ChannelWidthParser.WidthInfo width = ChannelWidthParser.parse(ie, channel, band);
        return new ApSnapshot(
                entry.dot11Ssid.asString(),
                entry.bssidString(),
                channel,
                entry.ulChCenterFrequency,
                band,
                entry.lRssi,
                entry.uLinkQuality,
                PhyTypeUtil.label(entry.dot11BssPhyType),
                entry.privacyEnabled(),
                SecurityClassifier.classify(ie, entry.privacyEnabled()),
                bssLoad == null ? null : bssLoad.channelUtilizationPercent(),
                timestamp,
                width.widthMhz(),
                width.effectiveCenterChannel()
        );
    }
}

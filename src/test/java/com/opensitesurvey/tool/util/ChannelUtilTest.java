package com.opensitesurvey.tool.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelUtilTest {

    @Test
    void convertsKnown2_4GhzFrequencies() {
        assertEquals(1, ChannelUtil.frequencyKhzToChannel(2412000));
        assertEquals(6, ChannelUtil.frequencyKhzToChannel(2437000));
        assertEquals(13, ChannelUtil.frequencyKhzToChannel(2472000));
        assertEquals(14, ChannelUtil.frequencyKhzToChannel(2484000));
    }

    @Test
    void convertsKnown5GhzFrequencies() {
        assertEquals(36, ChannelUtil.frequencyKhzToChannel(5180000));
        assertEquals(100, ChannelUtil.frequencyKhzToChannel(5500000));
    }

    @Test
    void convertsKnown6GhzFrequencies() {
        assertEquals(1, ChannelUtil.frequencyKhzToChannel(5955000));
    }

    @Test
    void classifiesBandByFrequency() {
        assertEquals("2.4GHz", ChannelUtil.band(2412000));
        assertEquals("5GHz", ChannelUtil.band(5180000));
        assertEquals("6GHz", ChannelUtil.band(5955000));
    }

    @Test
    void channelToFrequencyRoundTripsWithFrequencyToChannel() {
        assertEquals(2412, ChannelUtil.channelToFrequencyMhz("2.4GHz", 1));
        assertEquals(2472, ChannelUtil.channelToFrequencyMhz("2.4GHz", 13));
        assertEquals(2484, ChannelUtil.channelToFrequencyMhz("2.4GHz", 14));
        assertEquals(5180, ChannelUtil.channelToFrequencyMhz("5GHz", 36));
        assertEquals(5825, ChannelUtil.channelToFrequencyMhz("5GHz", 165));
        assertEquals(5955, ChannelUtil.channelToFrequencyMhz("6GHz", 1));
        assertEquals(6415, ChannelUtil.channelToFrequencyMhz("6GHz", 93));

        for (int ch = 1; ch <= 13; ch++) {
            double mhz = ChannelUtil.channelToFrequencyMhz("2.4GHz", ch);
            assertEquals(ch, ChannelUtil.frequencyKhzToChannel((int) (mhz * 1000)));
        }
        for (int ch : new int[]{36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165}) {
            double mhz = ChannelUtil.channelToFrequencyMhz("5GHz", ch);
            assertEquals(ch, ChannelUtil.frequencyKhzToChannel((int) (mhz * 1000)));
        }
        for (int ch = 1; ch <= 93; ch += 4) {
            double mhz = ChannelUtil.channelToFrequencyMhz("6GHz", ch);
            assertEquals(ch, ChannelUtil.frequencyKhzToChannel((int) (mhz * 1000)));
        }
    }
}

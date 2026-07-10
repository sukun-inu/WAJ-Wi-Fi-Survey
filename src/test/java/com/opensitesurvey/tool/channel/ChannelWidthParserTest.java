package com.waj.tool.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelWidthParserTest {

    @Test
    void defaultsTo20MhzWhenNoWideChannelElementPresent() {
        ChannelWidthParser.WidthInfo info = ChannelWidthParser.parse(new byte[0], 6, "2.4GHz");
        assertEquals(20, info.widthMhz());
        assertEquals(6, info.effectiveCenterChannel());

        assertEquals(20, ChannelWidthParser.parse(null, 36, "5GHz").widthMhz());
    }

    @Test
    void neverWidensA6GhzApRegardlessOfIeContent() {
        // A VHT80 element that would otherwise widen a 5GHz AP - 6GHz always short-circuits to 20MHz.
        byte[] vht80 = {(byte) 192, 3, 1, 42, 0};
        ChannelWidthParser.WidthInfo info = ChannelWidthParser.parse(vht80, 5, "6GHz");
        assertEquals(20, info.widthMhz());
        assertEquals(5, info.effectiveCenterChannel());
    }

    @Test
    void parsesHt40AboveAndBelowFromSecondaryChannelOffset() {
        // HT Operation (tag 61): [primary channel, HT Info Subset 1]; bit2 = STA Channel Width Set,
        // bits0-1 = Secondary Channel Offset (1 = above, 3 = below).
        byte[] above = {61, 2, 1, 0b0000_0101};
        ChannelWidthParser.WidthInfo aboveInfo = ChannelWidthParser.parse(above, 1, "2.4GHz");
        assertEquals(40, aboveInfo.widthMhz());
        assertEquals(3, aboveInfo.effectiveCenterChannel()); // primary(1) + 2

        byte[] below = {61, 2, 6, 0b0000_0111};
        ChannelWidthParser.WidthInfo belowInfo = ChannelWidthParser.parse(below, 6, "2.4GHz");
        assertEquals(40, belowInfo.widthMhz());
        assertEquals(4, belowInfo.effectiveCenterChannel()); // primary(6) - 2
    }

    @Test
    void ignoresHtElementWhenSecondaryOffsetIsNoneOrWidthFlagUnset() {
        byte[] noSecondary = {61, 2, 6, 0b0000_0100}; // width flag set, offset = 0 (none)
        assertEquals(20, ChannelWidthParser.parse(noSecondary, 6, "2.4GHz").widthMhz());

        byte[] widthFlagUnset = {61, 2, 6, 0b0000_0001}; // offset = above, but width flag not set
        assertEquals(20, ChannelWidthParser.parse(widthFlagUnset, 6, "2.4GHz").widthMhz());
    }

    @Test
    void parsesVht80And160FromChannelCenterFrequencySegment0() {
        byte[] vht80 = {(byte) 192, 3, 1, 42, 0};
        ChannelWidthParser.WidthInfo info80 = ChannelWidthParser.parse(vht80, 36, "5GHz");
        assertEquals(80, info80.widthMhz());
        assertEquals(42, info80.effectiveCenterChannel());

        byte[] vht160 = {(byte) 192, 3, 2, 50, 0};
        ChannelWidthParser.WidthInfo info160 = ChannelWidthParser.parse(vht160, 36, "5GHz");
        assertEquals(160, info160.widthMhz());
        assertEquals(50, info160.effectiveCenterChannel());
    }

    @Test
    void vhtChannelWidthZeroDefersToHtOperationResult() {
        byte[] ht40 = {61, 2, 36, 0b0000_0101};
        byte[] vhtDefer = {(byte) 192, 3, 0, 0, 0};
        byte[] ie = new byte[ht40.length + vhtDefer.length];
        System.arraycopy(ht40, 0, ie, 0, ht40.length);
        System.arraycopy(vhtDefer, 0, ie, ht40.length, vhtDefer.length);

        ChannelWidthParser.WidthInfo info = ChannelWidthParser.parse(ie, 36, "5GHz");
        assertEquals(40, info.widthMhz());
        assertEquals(38, info.effectiveCenterChannel());
    }
}

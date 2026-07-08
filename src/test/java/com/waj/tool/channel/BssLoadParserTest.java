package com.waj.tool.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BssLoadParserTest {

    @Test
    void returnsNullWhenElementAbsent() {
        assertNull(BssLoadParser.parse(new byte[0]));
        assertNull(BssLoadParser.parse(null));
        assertNull(BssLoadParser.parse(new byte[]{48, 2, 1, 0}));
    }

    @Test
    void parsesStationCountAndChannelUtilization() {
        // Station count = 5 (LE), channel utilization raw = 128 (~50%), admission capacity = 0 (LE).
        byte[] ie = {11, 5, 5, 0, (byte) 128, 0, 0};
        BssLoadParser.BssLoad load = BssLoadParser.parse(ie);
        assertEquals(5, load.stationCount());
        assertEquals(50, load.channelUtilizationPercent());
    }

    @Test
    void findsBssLoadAfterPrecedingElements() {
        byte[] rsnLike = {48, 2, 0, 0};
        byte[] bssLoad = {11, 5, 10, 0, (byte) 255, 0, 0};
        byte[] ie = new byte[rsnLike.length + bssLoad.length];
        System.arraycopy(rsnLike, 0, ie, 0, rsnLike.length);
        System.arraycopy(bssLoad, 0, ie, rsnLike.length, bssLoad.length);

        BssLoadParser.BssLoad load = BssLoadParser.parse(ie);
        assertEquals(10, load.stationCount());
        assertEquals(100, load.channelUtilizationPercent());
    }
}

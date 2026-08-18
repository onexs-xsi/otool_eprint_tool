package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanDeviceNameParserTest {
    @Test
    public void parsesDualSppBleSuffixAndDpiTable() {
        assertEquals(DothanDeviceNameParser.Channel.DUAL,
                DothanDeviceNameParser.parse("X-00000000").channel);
        assertEquals(DothanDeviceNameParser.Channel.SPP,
                DothanDeviceNameParser.parse("X-02000000").channel);
        assertEquals(DothanDeviceNameParser.Channel.BLE,
                DothanDeviceNameParser.parse("X-04000000").channel);
        assertEquals(203, DothanDeviceNameParser.parse("X-04000000").dpi);
        org.junit.Assert.assertArrayEquals(new int[]{305, 180, 203, 300, 600},
                DothanDeviceNameParser.DPI_TABLE);
    }

    @Test
    public void parsesKnownP1SuffixAndRejectsUnrelatedName() {
        DothanDeviceNameParser.Result p1 = DothanDeviceNameParser.parse("P1-0009200000");
        assertTrue(p1.valid);
        assertEquals(DothanDeviceNameParser.Channel.DUAL, p1.channel);
        assertEquals(203, p1.dpi);
        assertFalse(DothanDeviceNameParser.parse("random").valid);
    }
}

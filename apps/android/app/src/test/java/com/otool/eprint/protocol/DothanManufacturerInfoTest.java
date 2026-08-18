package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanManufacturerInfoTest {
    @Test
    public void parsesMinimalManufacturerInfoBlock() {
        byte[] payload = new byte[30];
        payload[1] = 0x20; // consumableVersion high bits, consumableType 0
        payload[2] = 'P';
        payload[3] = '1';
        payload[6] = 18;   // little-endian flag
        payload[7] = 22;   // one 22-byte record (minimum required by parser)
        payload[8] = 0;    // busyFlag
        payload[9] = 0;    // readResult
        payload[10] = 1;   // chipType
        payload[11] = 2;   // mark
        payload[13] = 0x20; // consumableType 1 / ribbonColor 0

        DothanManufacturerInfo info = new DothanManufacturerInfo();
        assertTrue(info.parse(payload));
        assertEquals("P1", info.product);
        assertEquals(1, info.consumables.size());
        assertEquals(0x20, info.consumableVersion);
        assertEquals(0, info.consumableType);
    }
}

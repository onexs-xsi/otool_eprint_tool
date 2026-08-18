package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanNfcInfoTest {
    @Test
    public void parsesPayloadOffsetsLikeOriginalImplementation() {
        byte[] payload = new byte[26];
        payload[0] = (byte) 0xAA;
        payload[1] = (byte) (payload.length - 3);
        payload[payload.length - 1] = (byte) 0xBB;
        payload[10] = 7;
        payload[7] = 1;
        payload[6] = 2;
        payload[5] = 3;
        payload[4] = 4;
        payload[3] = 5;
        payload[2] = 6;
        payload[15] = 20;
        payload[11] = 0;
        payload[12] = (byte) 203;
        payload[13] = 0x01;
        payload[14] = (byte) 0x80;

        DothanNfcInfo info = new DothanNfcInfo();
        assertTrue(info.parse(payload));
        assertEquals(7, info.deviceType);
        assertEquals("01:02:03:04:05:06", info.macAddress);
        assertEquals(20, info.addressType);
        assertEquals(203, info.dpi);
        assertEquals(384, info.widthDots);
    }
}

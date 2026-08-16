package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public final class DothanFrameCodecTest {
    @Test
    public void encodesAndDecodesShortFrameWithComplementChecksum() {
        byte[] encoded = DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0);
        assertEquals(0x1F, encoded[0] & 0xFF);
        assertEquals(0x77, encoded[1] & 0xFF);
        assertEquals(2, encoded[2] & 0xFF);
        DothanFrame decoded = DothanFrameCodec.decode(encoded);
        assertEquals(0x77, decoded.type);
        assertArrayEquals(new byte[]{0, 0}, decoded.payload);
    }

    @Test
    public void supportsTwoBytePayloadLength() {
        byte[] payload = new byte[240];
        payload[239] = 0x55;
        DothanFrame decoded = DothanFrameCodec.decode(DothanFrameCodec.encode(0x29, payload));
        assertEquals(240, decoded.payload.length);
        assertEquals(0x55, decoded.payload[239]);
    }

    @Test
    public void rejectsChecksumCorruption() {
        byte[] encoded = DothanFrameCodec.encode(0x43, (byte) 15);
        encoded[3] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> DothanFrameCodec.decode(encoded));
    }

    @Test
    public void streamParserHandlesFragmentedAndCoalescedNotifications() {
        byte[] first = DothanFrameCodec.encode(0x77, (byte) 2);
        byte[] second = DothanFrameCodec.encode(0x70, (byte) 0);
        byte[] both = DothanFrameCodec.join(first, second);
        DothanFrameStreamParser parser = new DothanFrameStreamParser();
        assertEquals(0, parser.feed(java.util.Arrays.copyOfRange(both, 0, 3)).size());
        List<DothanFrame> result = parser.feed(java.util.Arrays.copyOfRange(both, 3, both.length));
        assertEquals(2, result.size());
        assertEquals(0x77, result.get(0).type);
        assertEquals(0x70, result.get(1).type);
    }
}

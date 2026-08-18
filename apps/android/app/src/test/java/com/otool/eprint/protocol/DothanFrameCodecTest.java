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
    public void readsTwoAndThreeByteVariableIntegers() {
        assertEquals(300, DothanFrameCodec.readVariableInteger(
                new byte[]{(byte) 0xC1, 0x2C}, 0));
        assertEquals(30_000, DothanFrameCodec.readVariableInteger(
                new byte[]{(byte) 0xC0, 0x75, 0x30}, 0));
    }

    @Test
    public void rejectsChecksumCorruption() {
        byte[] encoded = DothanFrameCodec.encode(0x43, (byte) 15);
        encoded[3] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> DothanFrameCodec.decode(encoded));
    }

    @Test
    public void acceptsAlternateHeadersAndExplicitNoChecksumMarker() {
        byte[] aa = DothanFrameCodec.encode(0x43, (byte) 14);
        aa[0] = (byte) 0xAA;
        assertEquals(0x43, DothanFrameCodec.decode(aa).type);
        byte[] bb = DothanFrameCodec.encode(0x44, (byte) 0);
        bb[0] = (byte) 0xBB;
        bb[3] ^= 1;
        bb[bb.length - 1] = (byte) 0x88;
        assertEquals(1, DothanFrameCodec.decode(bb).payload[0] & 0xFF);
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

    @Test
    public void streamParserHandlesAsciiTextCommands() {
        DothanFrameStreamParser parser = new DothanFrameStreamParser();
        List<DothanFrame> result = parser.feed("PRINT 1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, result.size());
        assertEquals(true, result.get(0).isText());
        assertEquals("PRINT 1", result.get(0).textCommand);
    }

    @Test
    public void streamParserDropsStaleIncompleteFragment() {
        DothanFrameStreamParser parser = new DothanFrameStreamParser();
        byte[] incomplete = DothanFrameCodec.encode(0x43, (byte) 14);
        assertEquals(0, parser.feed(java.util.Arrays.copyOf(incomplete, 3), 1_000).size());
        List<DothanFrame> result = parser.feed(
                DothanFrameCodec.encode(0x44, (byte) 0), 3_100);
        assertEquals(1, result.size());
        assertEquals(0x44, result.get(0).type);
    }

    @Test
    public void decodeRetainsRawFrameByte23() {
        byte[] payload = new byte[24];
        payload[20] = 7;
        DothanFrame decoded = DothanFrameCodec.decode(DothanFrameCodec.encode(0x84, payload));
        assertEquals(7, decoded.rawByte23);
    }

    @Test
    public void rawByte23RequiresAtLeastTwentyFiveFrameBytes() {
        byte[] payload = new byte[20];
        DothanFrame decoded = DothanFrameCodec.decode(DothanFrameCodec.encode(0x84, payload));
        assertEquals(-1, decoded.rawByte23);
    }
}

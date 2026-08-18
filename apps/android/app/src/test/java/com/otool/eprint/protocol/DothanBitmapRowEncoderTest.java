package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanBitmapRowEncoderTest {
    @Test
    public void byteRleEncodesConstantRun() {
        byte[] row = {0, 0, 0, 0};
        assertArrayEquals(new byte[]{(byte) 0xC4, 0},
                DothanBitmapRowEncoder.encodeByteRuns(row));
    }

    @Test
    public void byteRleEscapesSingleControlValue() {
        assertArrayEquals(new byte[]{(byte) 0xC1, (byte) 0xC1},
                DothanBitmapRowEncoder.encodeByteRuns(new byte[]{(byte) 0xC1}));
    }

    @Test
    public void rleCommandsAreValidDothanFrames() {
        byte[] row = new byte[16];
        row[0] = (byte) 0xF0;
        DothanBitmapRowEncoder.RowCommand byteCommand =
                DothanBitmapRowEncoder.encode(row, null, 128, DothanCompressionMode.BYTE_RLE);
        DothanFrame decoded = DothanFrameCodec.decode(byteCommand.bytes);
        assertEquals(0x29, decoded.type);
        assertTrue(decoded.payload.length > 0);

        DothanBitmapRowEncoder.RowCommand bitCommand =
                DothanBitmapRowEncoder.encode(row, null, 128, DothanCompressionMode.BIT_RLE_5);
        assertEquals(0x2C, bitCommand.type);
        decoded = DothanFrameCodec.decode(bitCommand.bytes);
        assertTrue(decoded.payload.length > 0);
    }

    @Test
    public void uncompressedModeKeepsVerifiedRawRow() {
        byte[] row = new byte[8];
        row[2] = (byte) 0x80;
        DothanBitmapRowEncoder.RowCommand command =
                DothanBitmapRowEncoder.encode(row, null, 64, DothanCompressionMode.UNCOMPRESSED);
        assertEquals(0x2B, command.type);
        assertArrayEquals(new byte[]{0x1F, 0x2B, 2, 1, (byte) 0x80}, command.bytes);
    }

    @Test
    public void explicitCompressionFallsBackWhenItExpandsTheRow() {
        DothanBitmapRowEncoder.RowCommand byteRle = DothanBitmapRowEncoder.encode(
                new byte[]{(byte) 0xC1, (byte) 0xC2}, null, 16,
                DothanCompressionMode.BYTE_RLE);
        assertEquals(0x2B, byteRle.type);
        DothanBitmapRowEncoder.RowCommand bitRle = DothanBitmapRowEncoder.encode(
                new byte[]{(byte) 0xAA}, null, 8,
                DothanCompressionMode.BIT_RLE_5);
        assertEquals(0x2B, bitRle.type);
    }
}

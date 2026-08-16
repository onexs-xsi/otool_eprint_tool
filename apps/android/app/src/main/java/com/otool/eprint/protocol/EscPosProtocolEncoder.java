package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

import java.io.ByteArrayOutputStream;

public final class EscPosProtocolEncoder implements ProtocolEncoder {
    private static final int BAND_ROWS = 256;

    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm, PrinterProfile profile) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(raster.widthBytes * raster.heightDots + 64);
        write(output, 0x1B, 0x40);       // Initialize
        write(output, 0x1B, 0x61, 0x01); // Center
        for (int row = 0; row < raster.heightDots; row += BAND_ROWS) {
            int rows = Math.min(BAND_ROWS, raster.heightDots - row);
            write(output, 0x1D, 0x76, 0x30, 0x00);
            write16(output, raster.widthBytes);
            write16(output, rows);
            raster.writeRows(output, row, rows);
        }
        write(output, 0x1B, 0x61, 0x00);
        if (profile.autoFeed && profile.feedLines > 0) {
            write(output, 0x1B, 0x64, profile.feedLines);
        }
        return output.toByteArray();
    }

    private static void write16(ByteArrayOutputStream output, int value) {
        write(output, value & 0xFF, (value >>> 8) & 0xFF);
    }

    private static void write(ByteArrayOutputStream output, int... values) {
        for (int value : values) output.write(value);
    }
}

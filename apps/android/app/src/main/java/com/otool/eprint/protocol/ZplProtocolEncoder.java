package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class ZplProtocolEncoder implements ProtocolEncoder {
    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm, PrinterProfile profile) {
        byte[] data = raster.copyData();
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length * 2 + 120);
        ascii(output, "^XA^PW" + raster.widthDots + "^LL" + raster.heightDots + "^LH0,0^FO0,0^GFA,"
                + data.length + "," + data.length + "," + raster.widthBytes + ",");
        for (byte value : data) {
            int unsigned = value & 0xFF;
            output.write(HEX[unsigned >>> 4]);
            output.write(HEX[unsigned & 0x0F]);
        }
        ascii(output, "^FS^XZ\r\n");
        return output.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes, 0, bytes.length);
    }
}

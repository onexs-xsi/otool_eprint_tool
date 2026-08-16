package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class CpclProtocolEncoder implements ProtocolEncoder {
    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm, PrinterProfile profile) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(raster.widthBytes * raster.heightDots + 120);
        ascii(output, "! 0 " + profile.dpi + " " + profile.dpi + " " + raster.heightDots + " 1\r\n");
        ascii(output, "PW " + raster.widthDots + "\r\n");
        ascii(output, "CG " + raster.widthBytes + " " + raster.heightDots + " 0 0 ");
        byte[] data = raster.copyData();
        output.write(data, 0, data.length);
        ascii(output, "\r\nFORM\r\nPRINT\r\n");
        return output.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes, 0, bytes.length);
    }
}

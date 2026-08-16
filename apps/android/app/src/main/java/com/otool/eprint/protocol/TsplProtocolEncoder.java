package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.PaperType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class TsplProtocolEncoder implements ProtocolEncoder {
    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm, PrinterProfile profile) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(raster.widthBytes * raster.heightDots + 160);
        ascii(output, String.format(Locale.US, "SIZE %.1f mm,%.1f mm\r\n", widthMm, heightMm));
        ascii(output, "DENSITY " + profile.printDensity + "\r\n");
        ascii(output, "SPEED " + profile.printSpeed + "\r\n");
        float gapMm = profile.gapLengthTenthsMm / 10f;
        if (profile.paperType == PaperType.RECEIPT) {
            ascii(output, "GAP 0 mm,0 mm\r\n");
        } else if (profile.paperType == PaperType.CARDSTOCK) {
            ascii(output, String.format(Locale.US, "BLINE %.1f mm,0 mm\r\n", gapMm));
        } else {
            ascii(output, String.format(Locale.US, "GAP %.1f mm,0 mm\r\n", gapMm));
        }
        ascii(output, "DIRECTION 1\r\nREFERENCE 0,0\r\nCLS\r\n");
        ascii(output, "BITMAP 0,0," + raster.widthBytes + "," + raster.heightDots + ",0,");
        byte[] data = raster.copyData();
        output.write(data, 0, data.length);
        ascii(output, "\r\nPRINT 1,1\r\n");
        return output.toByteArray();
    }

    private static void ascii(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes, 0, bytes.length);
    }
}

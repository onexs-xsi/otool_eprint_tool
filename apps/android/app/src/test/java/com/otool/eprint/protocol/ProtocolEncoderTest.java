package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.PaperType;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class ProtocolEncoderTest {
    private final MonochromeRaster raster = new MonochromeRaster(8, 2,
            new byte[]{(byte) 0x81, 0x42});

    @Test
    public void escPosUsesRasterHeaderAndKeepsRows() {
        PrinterProfile profile = DeviceCatalog.find("escpos_58_203");
        byte[] result = new EscPosProtocolEncoder().encode(raster, 1f, 1f, profile);
        assertArrayEquals(new byte[]{0x1B, 0x40, 0x1B, 0x61, 0x01,
                        0x1D, 0x76, 0x30, 0x00, 0x01, 0x00, 0x02, 0x00,
                        (byte) 0x81, 0x42},
                java.util.Arrays.copyOf(result, 15));
    }

    @Test
    public void tsplUsesBitmapWidthInBytes() {
        PrinterProfile base = DeviceCatalog.find("tspl_4in_203");
        PrinterProfile profile = base.withDeviceSettings(base.manufacturer, base.deviceType,
                12, 3, PaperType.CARDSTOCK, 35, true, false, 0);
        byte[] result = new TsplProtocolEncoder().encode(raster, 40f, 30f, profile);
        String printable = new String(result, StandardCharsets.ISO_8859_1);
        assertTrue(printable.contains("SIZE 40.0 mm,30.0 mm\r\n"));
        assertTrue(printable.contains("DENSITY 12\r\n"));
        assertTrue(printable.contains("SPEED 3\r\n"));
        assertTrue(printable.contains("BLINE 3.5 mm,0 mm\r\n"));
        assertTrue(printable.contains("BITMAP 0,0,1,2,0,"));
        assertTrue(printable.endsWith("\r\nPRINT 1,1\r\n"));
    }

    @Test
    public void cpclUsesBinaryCompressedGraphicsCommand() {
        PrinterProfile profile = DeviceCatalog.find("cpcl_3in_203");
        byte[] result = new CpclProtocolEncoder().encode(raster, 1f, 1f, profile);
        String printable = new String(result, StandardCharsets.ISO_8859_1);
        assertTrue(printable.startsWith("! 0 203 203 2 1\r\nPW 8\r\nCG 1 2 0 0 "));
        assertTrue(printable.endsWith("\r\nFORM\r\nPRINT\r\n"));
    }

    @Test
    public void zplUsesDocumentedAsciiHexGraphicField() {
        PrinterProfile profile = DeviceCatalog.find("zpl_4in_203");
        String result = new String(new ZplProtocolEncoder().encode(raster, 1f, 1f, profile),
                StandardCharsets.US_ASCII);
        assertEquals("^XA^PW8^LL2^LH0,0^FO0,0^GFA,2,2,1,8142^FS^XZ\r\n", result);
    }
}

package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.PaperType;
import com.otool.eprint.device.PrinterProfile;

import org.junit.Test;

public final class DothanProtocolEncoderTest {
    @Test
    public void emitsCompleteP1CommandsForSmallRaster() {
        MonochromeRaster raster = new MonochromeRaster(16, 2,
                new byte[]{(byte) 0x80, 0, 0, 0});
        PrinterProfile profile = DeviceCatalog.find("p1_private_203");
        byte[] stream = new DothanProtocolEncoder().encode(raster, 2f, 2f, profile);
        int offset = 0;
        int commands = 0;
        while (offset < stream.length) {
            int length = DothanCommandStream.nextCommandLength(stream, offset);
            assertTrue("invalid command at " + offset, length > 0);
            offset += length;
            commands++;
        }
        assertEquals(stream.length, offset);
        assertTrue(commands >= 9);
    }

    @Test
    public void flowChunkNeverSplitsCommand() {
        MonochromeRaster raster = new MonochromeRaster(16, 1,
                new byte[]{(byte) 0xFF, (byte) 0xFF});
        byte[] stream = new DothanProtocolEncoder().encode(raster, 2f, 1f,
                DeviceCatalog.find("p1_private_203"));
        int first = DothanCommandStream.nextCommandLength(stream, 0);
        assertEquals(0, DothanCommandStream.chunkLength(stream, 0, first - 1));
        assertEquals(first, DothanCommandStream.chunkLength(stream, 0, first));
    }

    @Test
    public void mapsOfficialP1DisplaySettingsToObservedWireValues() {
        assertEquals(14, DothanProtocolEncoder.wireLevel(15));
        assertEquals(0, DothanProtocolEncoder.wireLevel(1));
        assertEquals(0, DothanProtocolEncoder.gapType(PaperType.RECEIPT));
        assertEquals(2, DothanProtocolEncoder.gapType(PaperType.GAP_LABEL));
        assertEquals(3, DothanProtocolEncoder.gapType(PaperType.CARDSTOCK));
        assertEquals(4, DothanProtocolEncoder.gapType(PaperType.TRANSPARENT));
        assertArrayEquals(new byte[]{(byte) 0xC1, 0x2C},
                DothanProtocolEncoder.distanceHundredthsMm(30));
    }
}

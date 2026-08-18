package com.otool.eprint.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DothanRasterTransformTest {
    @Test
    public void appliesMarginsAtExactDotOffsets() {
        MonochromeRaster source = new MonochromeRaster(4, 2,
                new byte[]{(byte) 0x80, 0x20});
        MonochromeRaster result = DothanRasterTransform.apply(source,
                new DothanJobOptions(1, 1, 2, 1, 1,
                        DothanJobOptions.ALIGN_LEFT, 0, false, false));
        assertEquals(7, result.widthDots);
        assertEquals(4, result.heightDots);
        assertArrayEquals(new byte[]{0, 0x40, 0x10, 0}, result.copyData());
    }

    @Test
    public void appliesFlipRotateAndAntiColor() {
        MonochromeRaster source = new MonochromeRaster(4, 2,
                new byte[]{(byte) 0x80, 0x20});
        assertArrayEquals(new byte[]{0x10, 0x40}, DothanRasterTransform.apply(source,
                new DothanJobOptions(1, 0, 0, 0, 0,
                        DothanJobOptions.ALIGN_LEFT, 0, true, false)).copyData());
        assertArrayEquals(new byte[]{0x40, 0x10}, DothanRasterTransform.apply(source,
                new DothanJobOptions(1, 0, 0, 0, 0,
                        DothanJobOptions.ALIGN_LEFT, 180, false, false)).copyData());
        assertArrayEquals(new byte[]{0x70, (byte) 0xD0}, DothanRasterTransform.apply(source,
                new DothanJobOptions(1, 0, 0, 0, 0,
                        DothanJobOptions.ALIGN_LEFT, 0, false, true)).copyData());
    }

    @Test
    public void genericInversionDoesNotSetPaddingBits() {
        MonochromeRaster source = new MonochromeRaster(5, 1, new byte[]{(byte) 0x88});
        assertArrayEquals(new byte[]{0x70}, source.inverted().copyData());
    }
}

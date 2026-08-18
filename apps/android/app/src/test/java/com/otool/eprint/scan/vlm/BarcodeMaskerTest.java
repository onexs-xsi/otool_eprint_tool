package com.otool.eprint.scan.vlm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;

import org.junit.Test;

import java.util.List;

public final class BarcodeMaskerTest {
    @Test
    public void qrPointsExpandToPaddedSquareWithinImage() {
        BarcodeMasker.MaskBounds bounds = BarcodeMasker.boundsFor(new ResultPoint[]{
                new ResultPoint(100f, 80f), new ResultPoint(180f, 80f),
                new ResultPoint(100f, 160f)
        }, BarcodeFormat.QR_CODE, 300, 220);
        assertNotNull(bounds);
        assertEquals(bounds.width(), bounds.height(), 0.01f);
        assertTrue(bounds.left < 100f);
        assertTrue(bounds.top < 80f);
        assertTrue(bounds.right > 180f);
        assertTrue(bounds.bottom > 160f);
    }

    @Test
    public void horizontalBarcodeGetsFullHeightEstimateAndClamping() {
        BarcodeMasker.MaskBounds bounds = BarcodeMasker.boundsFor(new ResultPoint[]{
                new ResultPoint(5f, 20f), new ResultPoint(195f, 20f)
        }, BarcodeFormat.CODE_128, 200, 100);
        assertNotNull(bounds);
        assertEquals(0f, bounds.left, 0.01f);
        assertEquals(200f, bounds.right, 0.01f);
        assertTrue(bounds.height() > 45f);
        assertEquals(0f, bounds.top, 0.01f);
    }

    @Test
    public void stripeLocatorFindsUndecodableHorizontalBarcodePattern() {
        int width = 240;
        int height = 140;
        int[] luminance = new int[width * height];
        java.util.Arrays.fill(luminance, 245);
        for (int y = 45; y < 85; y++) {
            for (int x = 40; x < 200; x++) {
                luminance[y * width + x] = ((x - 40) / 3) % 2 == 0 ? 10 : 245;
            }
        }

        List<BarcodeMasker.MaskBounds> regions =
                BarcodeMasker.findStripeRegions(luminance, width, height);

        assertEquals(1, regions.size());
        BarcodeMasker.MaskBounds region = regions.get(0);
        assertTrue(region.left < 40f);
        assertTrue(region.right > 200f);
        assertTrue(region.top < 45f);
        assertTrue(region.bottom > 85f);
    }

    @Test
    public void stripeLocatorDoesNotMaskBlankImage() {
        int[] luminance = new int[160 * 100];
        java.util.Arrays.fill(luminance, 255);

        assertTrue(BarcodeMasker.findStripeRegions(luminance, 160, 100).isEmpty());
    }

    @Test
    public void matrixLocatorFindsDenseSquareWithoutDecodingPayload() {
        int width = 180;
        int height = 140;
        int[] luminance = new int[width * height];
        java.util.Arrays.fill(luminance, 250);
        for (int y = 40; y < 100; y++) {
            for (int x = 60; x < 120; x++) {
                luminance[y * width + x] = ((x / 3) + (y / 3)) % 2 == 0 ? 8 : 250;
            }
        }

        List<BarcodeMasker.MaskBounds> regions =
                BarcodeMasker.findMatrixRegions(luminance, width, height);

        assertEquals(1, regions.size());
        BarcodeMasker.MaskBounds region = regions.get(0);
        assertTrue(region.left < 60f);
        assertTrue(region.right > 120f);
        assertTrue(region.top < 40f);
        assertTrue(region.bottom > 100f);
    }
}

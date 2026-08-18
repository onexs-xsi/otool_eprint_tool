package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class LabelImageAnalyzerTest {
    private static LabelImageAnalyzer.GrayImage image(int width, int height, int value) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, value);
        return new LabelImageAnalyzer.GrayImage(width, height, pixels);
    }

    @Test
    public void otsuSeparatesBimodalImage() {
        LabelImageAnalyzer.GrayImage img = image(32, 32, 30);
        for (int y = 0; y < 32; y++) {
            for (int x = 16; x < 32; x++) {
                img.pixels[y * 32 + x] = 220;
            }
        }
        int threshold = LabelImageAnalyzer.otsuThreshold(img);
        assertTrue("阈值应落在两峰之间: " + threshold, threshold >= 30 && threshold < 220);
    }

    @Test
    public void findsCenteredBrightRectangle() {
        LabelImageAnalyzer.GrayImage img = image(40, 30, 20);
        // 中央 21x15 的亮块模拟贴纸。
        for (int y = 8; y < 23; y++) {
            for (int x = 10; x < 31; x++) {
                img.pixels[y * 40 + x] = 240;
            }
        }
        LabelImageAnalyzer.LabelRegion region =
                LabelImageAnalyzer.findLargestBrightRegion(img, 100);
        assertTrue(region.found);
        assertEquals(10, region.left);
        assertEquals(8, region.top);
        assertEquals(31, region.right);
        assertEquals(23, region.bottom);
        assertEquals(21 * 15, region.pixelCount);
        assertEquals(21 * 15 / 1200f, region.coverage, 0.001f);
        assertEquals(0f, region.offsetX, 0.02f);
        assertEquals(0f, region.offsetY, 0.02f);
        assertEquals(0f, region.angleDegrees, 1f);
    }

    @Test
    public void recoversRotationAngleOfTiltedBar() {
        int size = 80;
        LabelImageAnalyzer.GrayImage img = image(size, size, 20);
        double theta = Math.toRadians(15);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - size / 2.0;
                double dy = y - size / 2.0;
                double u = dx * cos + dy * sin;
                double v = -dx * sin + dy * cos;
                if (Math.abs(u) <= 25 && Math.abs(v) <= 3) {
                    img.pixels[y * size + x] = 240;
                }
            }
        }
        LabelImageAnalyzer.LabelRegion region =
                LabelImageAnalyzer.findLargestBrightRegion(img, 100);
        assertTrue(region.found);
        assertEquals(15f, region.angleDegrees, 2f);
        assertTrue("旋正后宽度应接近长轴: " + region.rotatedWidth, region.rotatedWidth >= 45);
        assertTrue("旋正后高度应接近短轴: " + region.rotatedHeight, region.rotatedHeight <= 15);
    }

    @Test
    public void darkImageHasNoBrightRegion() {
        LabelImageAnalyzer.GrayImage img = image(20, 20, 30);
        int threshold = LabelImageAnalyzer.otsuThreshold(img);
        LabelImageAnalyzer.LabelRegion region =
                LabelImageAnalyzer.findLargestBrightRegion(img, threshold);
        assertFalse(region.found);
        assertEquals(0f, region.coverage, 0f);
    }

    @Test
    public void downscaleHalvesDimensionsAndKeepsValues() {
        LabelImageAnalyzer.GrayImage img = image(640, 480, 123);
        LabelImageAnalyzer.GrayImage small = LabelImageAnalyzer.downscale(img, 320);
        assertEquals(320, small.width);
        assertEquals(240, small.height);
        assertEquals(123, small.pixels[0]);
        // 不超限时原样返回。
        assertEquals(small, LabelImageAnalyzer.downscale(small, 320));
    }

    @Test
    public void laplacianVarianceDetectsBlur() {
        LabelImageAnalyzer.GrayImage flat = image(32, 32, 128);
        assertEquals(0.0, LabelImageAnalyzer.laplacianVariance(flat), 0.0001);

        LabelImageAnalyzer.GrayImage checker = image(32, 32, 0);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                checker.pixels[y * 32 + x] = ((x + y) & 1) == 0 ? 0 : 255;
            }
        }
        assertTrue(LabelImageAnalyzer.laplacianVariance(checker) > 1000);
    }

    @Test
    public void guideCropRectClampsToBounds() {
        int[] rect = LabelImageAnalyzer.guideCropRect(1000, 500, 0.1f, 0.2f, 0.9f, 0.8f);
        assertEquals(100, rect[0]);
        assertEquals(100, rect[1]);
        assertEquals(900, rect[2]);
        assertEquals(400, rect[3]);
        // 越界钳制。
        int[] clamped = LabelImageAnalyzer.guideCropRect(100, 100, -0.5f, -0.5f, 1.5f, 1.5f);
        assertEquals(0, clamped[0]);
        assertEquals(0, clamped[1]);
        assertEquals(100, clamped[2]);
        assertEquals(100, clamped[3]);
    }
}

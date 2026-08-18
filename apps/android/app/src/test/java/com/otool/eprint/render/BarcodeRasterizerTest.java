package com.otool.eprint.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class BarcodeRasterizerTest {
    @Test
    public void code128ProducesNonEmptyMatrix() {
        boolean[][] matrix = BarcodeRasterizer.matrix("1234567890", "CODE_128");
        // ZXing 的一维码矩阵只有一行；UI/打印渲染必须把这一行纵向拉到元素高度。
        assertEquals(1, matrix.length);
        assertTrue(matrix[0].length > 20);
        boolean anyDark = false;
        for (boolean[] row : matrix) for (boolean cell : row) anyDark |= cell;
        assertTrue(anyDark);
    }

    @Test
    public void qrMatrixIsSquareAndCached() {
        boolean[][] first = BarcodeRasterizer.matrix("EPRINT", "");
        boolean[][] second = BarcodeRasterizer.matrix("EPRINT", "");
        assertEquals(first.length, first[0].length);
        assertSame(first, second);
    }

    @Test
    public void ean13RejectsLetters() {
        try {
            BarcodeRasterizer.matrix("ABC", "EAN_13");
            fail("EAN_13 不应接受字母");
        } catch (IllegalArgumentException expected) {
            // 预期抛出
        }
        assertFalse(BarcodeRasterizer.isValid("ABC", "EAN_13"));
        assertTrue(BarcodeRasterizer.isValid("5901234123457", "EAN_13"));
    }

    @Test
    public void unknownFormatFallsBackToQr() {
        boolean[][] matrix = BarcodeRasterizer.matrix("EPRINT", "NOT_A_FORMAT");
        assertEquals(matrix.length, matrix[0].length);
    }

    @Test
    public void qrCorrectionLevelsUseSeparateCachedMatrices() {
        boolean[][] low = BarcodeRasterizer.matrix("ECC-TEST-1234567890", "QR_CODE", "L");
        boolean[][] high = BarcodeRasterizer.matrix("ECC-TEST-1234567890", "QR_CODE", "H");
        assertEquals(low.length, low[0].length);
        assertEquals(high.length, high[0].length);
        assertNotSame(low, high);
        assertEquals("M", BarcodeRasterizer.normalizeQrErrorCorrection("invalid"));
    }

    @Test
    public void allExposedOneDimensionalFormatsEncodeRepresentativeContent() {
        String[] samples = {
                "ABC123", "ABC-123", "12345678", "5901234123457", "96385074",
                "036000291452", "0421000", "0-306-40615-2", "A1234B", "ABC123",
                "abc123", "12345678901231"
        };
        assertEquals(BarcodeRasterizer.BARCODE_FORMATS.length, samples.length);
        for (int index = 0; index < samples.length; index++) {
            boolean[][] matrix = BarcodeRasterizer.matrix(samples[index],
                    BarcodeRasterizer.BARCODE_FORMATS[index], "M", 2);
            assertEquals(1, matrix.length);
            assertTrue(BarcodeRasterizer.BARCODE_FORMATS[index], matrix[0].length > 10);
        }
    }

    @Test
    public void allExposedTwoDimensionalFormatsEncodeAndQuietZoneAffectsCache() {
        for (String format : BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS) {
            boolean[][] matrix = BarcodeRasterizer.matrix("EPRINT-2D", format, "M", 2);
            assertTrue(format, matrix.length > 1);
            assertTrue(format, matrix[0].length > 1);
        }
        boolean[][] noMargin = BarcodeRasterizer.matrix("QUIET-ZONE", "QR_CODE", "M", 0);
        boolean[][] fourModules = BarcodeRasterizer.matrix("QUIET-ZONE", "QR_CODE", "M", 4);
        assertNotSame(noMargin, fourModules);
        assertTrue(fourModules.length > noMargin.length);
    }
}

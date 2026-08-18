package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScanInputModeTest {
    @Test
    public void orderIsLabelQrBarcode() {
        assertEquals(3, ScanInputMode.count());
        assertEquals(ScanInputMode.LABEL, ScanInputMode.fromIndex(0));
        assertEquals(ScanInputMode.QR, ScanInputMode.fromIndex(1));
        assertEquals(ScanInputMode.BARCODE, ScanInputMode.fromIndex(2));
        assertEquals("标签", ScanInputMode.LABEL.label);
        assertEquals("二维码", ScanInputMode.QR.label);
        assertEquals("条形码", ScanInputMode.BARCODE.label);
    }

    @Test
    public void fromIndexClampsOutOfRange() {
        assertEquals(ScanInputMode.LABEL, ScanInputMode.fromIndex(-1));
        assertEquals(ScanInputMode.BARCODE, ScanInputMode.fromIndex(99));
    }

    @Test
    public void forFractionDispatchesThirds() {
        assertEquals(ScanInputMode.LABEL, ScanInputMode.forFraction(0f));
        assertEquals(ScanInputMode.LABEL, ScanInputMode.forFraction(0.32f));
        assertEquals(ScanInputMode.QR, ScanInputMode.forFraction(0.34f));
        assertEquals(ScanInputMode.QR, ScanInputMode.forFraction(0.5f));
        assertEquals(ScanInputMode.QR, ScanInputMode.forFraction(0.66f));
        assertEquals(ScanInputMode.BARCODE, ScanInputMode.forFraction(0.67f));
        assertEquals(ScanInputMode.BARCODE, ScanInputMode.forFraction(1f));
    }

    @Test
    public void forFractionClampsExtremes() {
        assertEquals(ScanInputMode.LABEL, ScanInputMode.forFraction(-0.5f));
        assertEquals(ScanInputMode.BARCODE, ScanInputMode.forFraction(2.5f));
    }
}

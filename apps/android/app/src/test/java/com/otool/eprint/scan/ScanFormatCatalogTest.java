package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;

import org.junit.Test;

public final class ScanFormatCatalogTest {
    @Test
    public void qrModeOnlyAcceptsQrCode() {
        assertEquals(1, ScanFormatCatalog.formats(false).size());
        assertEquals(BarcodeFormat.QR_CODE, ScanFormatCatalog.formats(false).get(0));
        assertFalse(ScanFormatCatalog.formats(false).contains(BarcodeFormat.CODE_128));
    }

    @Test
    public void barcodeModeAcceptsCommonOneDimensionalFormatsButNotQr() {
        assertTrue(ScanFormatCatalog.formats(true).contains(BarcodeFormat.CODE_128));
        assertTrue(ScanFormatCatalog.formats(true).contains(BarcodeFormat.EAN_13));
        assertTrue(ScanFormatCatalog.formats(true).contains(BarcodeFormat.ITF));
        assertFalse(ScanFormatCatalog.formats(true).contains(BarcodeFormat.QR_CODE));
        assertEquals(ScanFormatCatalog.formats(true).size(),
                ScanFormatCatalog.formatNames(true).size());
    }
}

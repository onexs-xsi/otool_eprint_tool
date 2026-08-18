package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;

import org.junit.Test;

public final class ScanFormatCatalogTest {
    @Test
    public void qrModeOnlyAcceptsQrCode() {
        assertEquals(1, ScanFormatCatalog.formats(ScanInputMode.QR).size());
        assertEquals(BarcodeFormat.QR_CODE, ScanFormatCatalog.formats(ScanInputMode.QR).get(0));
        assertFalse(ScanFormatCatalog.formats(ScanInputMode.QR).contains(BarcodeFormat.CODE_128));
    }

    @Test
    public void barcodeModeAcceptsCommonOneDimensionalFormatsButNotQr() {
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.BARCODE).contains(BarcodeFormat.CODE_128));
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.BARCODE).contains(BarcodeFormat.EAN_13));
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.BARCODE).contains(BarcodeFormat.ITF));
        assertFalse(ScanFormatCatalog.formats(ScanInputMode.BARCODE).contains(BarcodeFormat.QR_CODE));
        assertEquals(ScanFormatCatalog.formats(ScanInputMode.BARCODE).size(),
                ScanFormatCatalog.formatNames(ScanInputMode.BARCODE).size());
    }

    @Test
    public void labelModeUsesNoDecoderFormats() {
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.LABEL).isEmpty());
        assertTrue(ScanFormatCatalog.formatNames(ScanInputMode.LABEL).isEmpty());
    }
}

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

    @Test
    public void configuredPagesExposeOnlySelectedFormatGroups() {
        RecognitionSettings d = RecognitionSettings.defaults();
        RecognitionSettings settings = new RecognitionSettings(
                d.labelEnabled, d.ppOcrEnabled, d.vlmEnabled, d.cropToLabel,
                d.perspectiveRectify, d.reduceGlare, d.spaceRecognizedUnits,
                d.maskCodesBeforeVlm, d.experimentalWeakCodeMask,
                d.showProcessingPreview, d.dictionaryStrongWeight, d.fusionWeightLevel,
                d.minimumLabelCoveragePercent, d.blurVarianceThreshold,
                d.qrEnabled, d.qrContinuous, d.qrTryHarder, d.qrAlsoInverted,
                true, d.qrSuppressDuplicates,
                d.barcodeEnabled, d.barcodeContinuous, d.barcodeTryHarder,
                d.barcodeAlsoInverted, false, true, false,
                d.barcodeSuppressDuplicates);
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.QR, settings)
                .contains(BarcodeFormat.DATA_MATRIX));
        assertTrue(ScanFormatCatalog.formats(ScanInputMode.BARCODE, settings)
                .contains(BarcodeFormat.CODE_128));
        assertFalse(ScanFormatCatalog.formats(ScanInputMode.BARCODE, settings)
                .contains(BarcodeFormat.EAN_13));
        assertFalse(ScanFormatCatalog.formats(ScanInputMode.BARCODE, settings)
                .contains(BarcodeFormat.ITF));
    }
}

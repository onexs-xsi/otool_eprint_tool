package com.otool.eprint.scan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecognitionSettingsTest {
    @Test
    public void defaultsEnableThreePagesAndKeepWeakMaskConservative() {
        RecognitionSettings settings = RecognitionSettings.defaults();
        assertTrue(settings.isModeEnabled(ScanInputMode.LABEL));
        assertTrue(settings.isModeEnabled(ScanInputMode.QR));
        assertTrue(settings.isModeEnabled(ScanInputMode.BARCODE));
        assertTrue(settings.ppOcrEnabled);
        assertTrue(settings.vlmEnabled);
        assertTrue(settings.cropToLabel);
        assertTrue(settings.preferGlmOnConflict);
        assertFalse(settings.maskCodesBeforeVlm);
        assertFalse(settings.experimentalWeakCodeMask);
    }
}

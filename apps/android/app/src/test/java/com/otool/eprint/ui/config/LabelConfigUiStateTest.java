package com.otool.eprint.ui.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LabelConfigUiStateTest {
    @Test
    public void fusionRequiresBothRecognitionEngines() {
        assertTrue(LabelConfigUiState.canTuneFusion(true, true, true));
        assertFalse(LabelConfigUiState.canTuneFusion(false, true, true));
        assertFalse(LabelConfigUiState.canTuneFusion(true, false, true));
        assertFalse(LabelConfigUiState.canTuneFusion(true, true, false));
    }

    @Test
    public void weakMaskRequiresLabelVlmAndPrimaryMask() {
        assertTrue(LabelConfigUiState.canUseWeakMask(true, true, true));
        assertFalse(LabelConfigUiState.canUseWeakMask(false, true, true));
        assertFalse(LabelConfigUiState.canUseWeakMask(true, false, true));
        assertFalse(LabelConfigUiState.canUseWeakMask(true, true, false));
    }

    @Test
    public void qwenMissingOnlyIsDisabledWhileCorrectionAuditIsEnabled() {
        assertTrue(LabelConfigUiState.canLimitQwenToMissing(true, false));
        assertFalse(LabelConfigUiState.canLimitQwenToMissing(true, true));
        assertFalse(LabelConfigUiState.canLimitQwenToMissing(false, false));
    }
}

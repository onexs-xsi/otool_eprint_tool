package com.otool.eprint.scan.opencv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LabelGlareReducerTest {
    @Test
    public void onlyProcessesMateriallyUnevenOrClippedLighting() {
        assertFalse(LabelGlareReducer.shouldApply(0.010, 0.05));
        assertTrue(LabelGlareReducer.shouldApply(0.030, 0.02));
        assertTrue(LabelGlareReducer.shouldApply(0.018, 0.20));
    }

    @Test
    public void requiresMeasuredImprovementBeforeReplacingSource() {
        assertTrue(LabelGlareReducer.isImprovement(0.050, 0.040));
        assertFalse(LabelGlareReducer.isImprovement(0.050, 0.049));
        assertFalse(LabelGlareReducer.isImprovement(0.0, 0.0));
    }

    @Test
    public void illuminationKernelIsOddAndBounded() {
        assertTrue((LabelGlareReducer.illuminationKernel(396, 331) & 1) == 1);
        assertTrue(LabelGlareReducer.illuminationKernel(396, 331) >= 31);
        assertTrue(LabelGlareReducer.illuminationKernel(2000, 1500) <= 151);
    }
}

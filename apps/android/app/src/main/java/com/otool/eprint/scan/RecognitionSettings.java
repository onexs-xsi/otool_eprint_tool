package com.otool.eprint.scan;

/** 三种识别页面的统一、可持久化配置。所有字段都直接参与实际扫描流程。 */
public final class RecognitionSettings {
    public final boolean labelEnabled;
    public final boolean ppOcrEnabled;
    public final boolean vlmEnabled;
    public final boolean cropToLabel;
    public final boolean maskCodesBeforeVlm;
    public final boolean experimentalWeakCodeMask;
    public final boolean showProcessingPreview;
    public final boolean preferGlmOnConflict;
    public final int minimumLabelCoveragePercent;
    public final int blurVarianceThreshold;

    public final boolean qrEnabled;
    public final boolean qrContinuous;
    public final boolean qrTryHarder;
    public final boolean qrAlsoInverted;
    public final boolean qrExtendedFormats;
    public final boolean qrSuppressDuplicates;

    public final boolean barcodeEnabled;
    public final boolean barcodeContinuous;
    public final boolean barcodeTryHarder;
    public final boolean barcodeAlsoInverted;
    public final boolean barcodeRetailFormats;
    public final boolean barcodeIndustrialFormats;
    public final boolean barcodeLogisticsFormats;
    public final boolean barcodeSuppressDuplicates;

    public RecognitionSettings(
            boolean labelEnabled, boolean ppOcrEnabled, boolean vlmEnabled,
            boolean cropToLabel, boolean maskCodesBeforeVlm,
            boolean experimentalWeakCodeMask, boolean showProcessingPreview,
            boolean preferGlmOnConflict, int minimumLabelCoveragePercent,
            int blurVarianceThreshold, boolean qrEnabled, boolean qrContinuous,
            boolean qrTryHarder, boolean qrAlsoInverted, boolean qrExtendedFormats,
            boolean qrSuppressDuplicates, boolean barcodeEnabled,
            boolean barcodeContinuous, boolean barcodeTryHarder,
            boolean barcodeAlsoInverted, boolean barcodeRetailFormats,
            boolean barcodeIndustrialFormats, boolean barcodeLogisticsFormats,
            boolean barcodeSuppressDuplicates) {
        this.labelEnabled = labelEnabled;
        this.ppOcrEnabled = ppOcrEnabled;
        this.vlmEnabled = vlmEnabled;
        this.cropToLabel = cropToLabel;
        this.maskCodesBeforeVlm = maskCodesBeforeVlm;
        this.experimentalWeakCodeMask = experimentalWeakCodeMask;
        this.showProcessingPreview = showProcessingPreview;
        this.preferGlmOnConflict = preferGlmOnConflict;
        this.minimumLabelCoveragePercent = clamp(minimumLabelCoveragePercent, 10, 90);
        this.blurVarianceThreshold = clamp(blurVarianceThreshold, 10, 500);
        this.qrEnabled = qrEnabled;
        this.qrContinuous = qrContinuous;
        this.qrTryHarder = qrTryHarder;
        this.qrAlsoInverted = qrAlsoInverted;
        this.qrExtendedFormats = qrExtendedFormats;
        this.qrSuppressDuplicates = qrSuppressDuplicates;
        this.barcodeEnabled = barcodeEnabled;
        this.barcodeContinuous = barcodeContinuous;
        this.barcodeTryHarder = barcodeTryHarder;
        this.barcodeAlsoInverted = barcodeAlsoInverted;
        this.barcodeRetailFormats = barcodeRetailFormats;
        this.barcodeIndustrialFormats = barcodeIndustrialFormats;
        this.barcodeLogisticsFormats = barcodeLogisticsFormats;
        this.barcodeSuppressDuplicates = barcodeSuppressDuplicates;
    }

    public static RecognitionSettings defaults() {
        return new RecognitionSettings(
                true, true, true, true, false, false, true, true, 20, 60,
                true, true, true, true, false, true,
                true, true, true, true, true, true, true, true);
    }

    public boolean isModeEnabled(ScanInputMode mode) {
        if (mode == ScanInputMode.LABEL) return labelEnabled;
        if (mode == ScanInputMode.QR) return qrEnabled;
        return barcodeEnabled;
    }

    public boolean isContinuous(ScanInputMode mode) {
        return mode == ScanInputMode.QR ? qrContinuous : barcodeContinuous;
    }

    public boolean tryHarder(ScanInputMode mode) {
        return mode == ScanInputMode.QR ? qrTryHarder : barcodeTryHarder;
    }

    public boolean alsoInverted(ScanInputMode mode) {
        return mode == ScanInputMode.QR ? qrAlsoInverted : barcodeAlsoInverted;
    }

    public boolean suppressDuplicates(ScanInputMode mode) {
        return mode == ScanInputMode.QR ? qrSuppressDuplicates : barcodeSuppressDuplicates;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

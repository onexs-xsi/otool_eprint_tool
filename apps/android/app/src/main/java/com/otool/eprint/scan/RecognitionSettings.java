package com.otool.eprint.scan;

/** 三种识别页面的统一、可持久化配置。所有字段都直接参与实际扫描流程。 */
public final class RecognitionSettings {
    public final boolean labelEnabled;
    public final boolean ppOcrEnabled;
    public final boolean vlmEnabled;
    public final boolean cropToLabel;
    public final boolean perspectiveRectify;
    public final boolean reduceGlare;
    public final boolean spaceRecognizedUnits;
    public final boolean maskCodesBeforeVlm;
    public final boolean experimentalWeakCodeMask;
    public final boolean showProcessingPreview;
    /** 立创目录命中时，让品类/品牌/封装证据越过普通双通道 OCR 权重。 */
    public final boolean dictionaryStrongWeight;
    /**
     * PP-OCR / GLM-OCR 融合权重档位（1~5）：
     * 1=PP-OCR 为主（GLM 只补漏），2=PP 语义权重大，3=均衡按置信度，
     * 4=GLM 语义权重大，5=GLM 为主（PP 只补漏）。
     */
    public final int fusionWeightLevel;
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
            boolean cropToLabel, boolean perspectiveRectify, boolean reduceGlare,
            boolean spaceRecognizedUnits, boolean maskCodesBeforeVlm,
            boolean experimentalWeakCodeMask, boolean showProcessingPreview,
            boolean dictionaryStrongWeight, int fusionWeightLevel,
            int minimumLabelCoveragePercent,
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
        this.perspectiveRectify = perspectiveRectify;
        this.reduceGlare = reduceGlare;
        this.spaceRecognizedUnits = spaceRecognizedUnits;
        this.maskCodesBeforeVlm = maskCodesBeforeVlm;
        this.experimentalWeakCodeMask = experimentalWeakCodeMask;
        this.showProcessingPreview = showProcessingPreview;
        this.dictionaryStrongWeight = dictionaryStrongWeight;
        this.fusionWeightLevel = clamp(fusionWeightLevel, 1, 5);
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
        // 融合档位默认 2（PP 权重大）。v1.5.28 起 GLM 通道改为转写+规则解析，与 PP-OCR
        // 同源同分（满分都是 100），5 档"GLM 为主"会让 GLM 的转写误读（如 HRE→HREL）
        // 无条件覆盖 PP 的正确结果；2 档下 GLM 只补漏、置信度翻倍才翻盘（实测见
        // docs/ocr_optimize_0819.md 回归 2）。
        return new RecognitionSettings(
                true, true, true, true, true, true, true, false, false, true, true, 2, 20, 60,
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

package com.otool.eprint.scan;

import android.content.Context;
import android.content.SharedPreferences;

/** 识别配置持久化；覆盖安装和进程重启后继续生效。 */
public final class RecognitionSettingsStore {
    private static final String PREFERENCES = "eprint_recognition_settings_v1";

    private RecognitionSettingsStore() {}

    public static RecognitionSettings load(Context context) {
        RecognitionSettings defaults = RecognitionSettings.defaults();
        SharedPreferences values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        // 旧布尔键迁移：label_glm_priority=true → 5（GLM 为主），false → 3（均衡）。
        int fusionWeight = values.getInt("label_fusion_weight",
                values.getBoolean("label_glm_priority", true) ? 5 : 3);
        return new RecognitionSettings(
                values.getBoolean("label_enabled", defaults.labelEnabled),
                values.getBoolean("label_pp_ocr", defaults.ppOcrEnabled),
                values.getBoolean("label_vlm", defaults.vlmEnabled),
                values.getBoolean("label_crop", defaults.cropToLabel),
                values.getBoolean("label_perspective_rectify", defaults.perspectiveRectify),
                values.getBoolean("label_reduce_glare", defaults.reduceGlare),
                values.getBoolean("label_space_units", defaults.spaceRecognizedUnits),
                values.getBoolean("label_mask_codes", defaults.maskCodesBeforeVlm),
                values.getBoolean("label_weak_code_mask", defaults.experimentalWeakCodeMask),
                values.getBoolean("label_show_stages", defaults.showProcessingPreview),
                fusionWeight,
                values.getInt("label_min_coverage", defaults.minimumLabelCoveragePercent),
                values.getInt("label_blur_threshold", defaults.blurVarianceThreshold),
                values.getBoolean("qr_enabled", defaults.qrEnabled),
                values.getBoolean("qr_continuous", defaults.qrContinuous),
                values.getBoolean("qr_try_harder", defaults.qrTryHarder),
                values.getBoolean("qr_inverted", defaults.qrAlsoInverted),
                values.getBoolean("qr_extended", defaults.qrExtendedFormats),
                values.getBoolean("qr_deduplicate", defaults.qrSuppressDuplicates),
                values.getBoolean("barcode_enabled", defaults.barcodeEnabled),
                values.getBoolean("barcode_continuous", defaults.barcodeContinuous),
                values.getBoolean("barcode_try_harder", defaults.barcodeTryHarder),
                values.getBoolean("barcode_inverted", defaults.barcodeAlsoInverted),
                values.getBoolean("barcode_retail", defaults.barcodeRetailFormats),
                values.getBoolean("barcode_industrial", defaults.barcodeIndustrialFormats),
                values.getBoolean("barcode_logistics", defaults.barcodeLogisticsFormats),
                values.getBoolean("barcode_deduplicate", defaults.barcodeSuppressDuplicates));
    }

    public static void save(Context context, RecognitionSettings settings) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("label_enabled", settings.labelEnabled)
                .putBoolean("label_pp_ocr", settings.ppOcrEnabled)
                .putBoolean("label_vlm", settings.vlmEnabled)
                .putBoolean("label_crop", settings.cropToLabel)
                .putBoolean("label_perspective_rectify", settings.perspectiveRectify)
                .putBoolean("label_reduce_glare", settings.reduceGlare)
                .putBoolean("label_space_units", settings.spaceRecognizedUnits)
                .putBoolean("label_mask_codes", settings.maskCodesBeforeVlm)
                .putBoolean("label_weak_code_mask", settings.experimentalWeakCodeMask)
                .putBoolean("label_show_stages", settings.showProcessingPreview)
                .putInt("label_fusion_weight", settings.fusionWeightLevel)
                .putInt("label_min_coverage", settings.minimumLabelCoveragePercent)
                .putInt("label_blur_threshold", settings.blurVarianceThreshold)
                .putBoolean("qr_enabled", settings.qrEnabled)
                .putBoolean("qr_continuous", settings.qrContinuous)
                .putBoolean("qr_try_harder", settings.qrTryHarder)
                .putBoolean("qr_inverted", settings.qrAlsoInverted)
                .putBoolean("qr_extended", settings.qrExtendedFormats)
                .putBoolean("qr_deduplicate", settings.qrSuppressDuplicates)
                .putBoolean("barcode_enabled", settings.barcodeEnabled)
                .putBoolean("barcode_continuous", settings.barcodeContinuous)
                .putBoolean("barcode_try_harder", settings.barcodeTryHarder)
                .putBoolean("barcode_inverted", settings.barcodeAlsoInverted)
                .putBoolean("barcode_retail", settings.barcodeRetailFormats)
                .putBoolean("barcode_industrial", settings.barcodeIndustrialFormats)
                .putBoolean("barcode_logistics", settings.barcodeLogisticsFormats)
                .putBoolean("barcode_deduplicate", settings.barcodeSuppressDuplicates)
                .apply();
    }
}

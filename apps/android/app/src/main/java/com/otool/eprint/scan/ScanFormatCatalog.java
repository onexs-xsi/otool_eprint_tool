package com.otool.eprint.scan;

import com.google.zxing.BarcodeFormat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared offline decoder formats for the embedded and fallback camera scanners. */
public final class ScanFormatCatalog {
    private static final List<BarcodeFormat> QR_FORMATS = Collections.unmodifiableList(
            Collections.singletonList(BarcodeFormat.QR_CODE));

    private ScanFormatCatalog() {}

    /** 标签档不使用 ZXing 格式目录（解码已暂停），返回空列表。 */
    public static List<BarcodeFormat> formats(ScanInputMode mode) {
        return formats(mode, RecognitionSettings.defaults());
    }

    /** 根据对应配置页精确生成格式集合；关闭的格式组不会传给 ZXing。 */
    public static List<BarcodeFormat> formats(ScanInputMode mode,
                                              RecognitionSettings settings) {
        if (mode == ScanInputMode.LABEL || !settings.isModeEnabled(mode)) {
            return Collections.emptyList();
        }
        if (mode == ScanInputMode.QR) {
            if (!settings.qrExtendedFormats) return QR_FORMATS;
            return Collections.unmodifiableList(Arrays.asList(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC, BarcodeFormat.PDF_417));
        }
        java.util.ArrayList<BarcodeFormat> formats = new java.util.ArrayList<>();
        if (settings.barcodeIndustrialFormats) {
            formats.add(BarcodeFormat.CODE_128);
            formats.add(BarcodeFormat.CODE_39);
            formats.add(BarcodeFormat.CODE_93);
        }
        if (settings.barcodeRetailFormats) {
            formats.add(BarcodeFormat.EAN_13);
            formats.add(BarcodeFormat.EAN_8);
            formats.add(BarcodeFormat.UPC_A);
            formats.add(BarcodeFormat.UPC_E);
        }
        if (settings.barcodeLogisticsFormats) {
            formats.add(BarcodeFormat.ITF);
            formats.add(BarcodeFormat.CODABAR);
        }
        return Collections.unmodifiableList(formats);
    }

    public static List<String> formatNames(ScanInputMode mode) {
        List<BarcodeFormat> formats = formats(mode);
        String[] names = new String[formats.size()];
        for (int index = 0; index < formats.size(); index++) {
            names[index] = formats.get(index).name();
        }
        return Collections.unmodifiableList(Arrays.asList(names));
    }

    public static List<String> formatNames(ScanInputMode mode,
                                           RecognitionSettings settings) {
        List<BarcodeFormat> formats = formats(mode, settings);
        String[] names = new String[formats.size()];
        for (int index = 0; index < formats.size(); index++) {
            names[index] = formats.get(index).name();
        }
        return Collections.unmodifiableList(Arrays.asList(names));
    }
}

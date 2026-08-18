package com.otool.eprint.scan;

import com.google.zxing.BarcodeFormat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared offline decoder formats for the embedded and fallback camera scanners. */
public final class ScanFormatCatalog {
    private static final List<BarcodeFormat> QR_FORMATS = Collections.unmodifiableList(
            Collections.singletonList(BarcodeFormat.QR_CODE));

    private static final List<BarcodeFormat> BARCODE_FORMATS = Collections.unmodifiableList(
            Arrays.asList(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.CODE_93,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.UPC_E,
                    BarcodeFormat.ITF,
                    BarcodeFormat.CODABAR));

    private ScanFormatCatalog() {}

    /** 标签档不使用 ZXing 格式目录（解码已暂停），返回空列表。 */
    public static List<BarcodeFormat> formats(ScanInputMode mode) {
        if (mode == ScanInputMode.BARCODE) return BARCODE_FORMATS;
        if (mode == ScanInputMode.QR) return QR_FORMATS;
        return Collections.emptyList();
    }

    public static List<String> formatNames(ScanInputMode mode) {
        List<BarcodeFormat> formats = formats(mode);
        String[] names = new String[formats.size()];
        for (int index = 0; index < formats.size(); index++) {
            names[index] = formats.get(index).name();
        }
        return Collections.unmodifiableList(Arrays.asList(names));
    }
}

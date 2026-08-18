package com.otool.eprint.render;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 用 zxing 离线生成一维码/二维码点阵，按内容+码制缓存避免每帧重算（纯 Java 缓存，可在 JVM 测试）。 */
public final class BarcodeRasterizer {
    private static final int CACHE_CAPACITY = 24;
    private static final Map<String, boolean[][]> CACHE = new LinkedHashMap<String, boolean[][]>(
            CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, boolean[][]> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    private BarcodeRasterizer() {}

    /** 返回 [row][col] 点阵；内容不符合码制时抛 IllegalArgumentException。 */
    public static synchronized boolean[][] matrix(String content, String formatName) {
        return matrix(content, formatName, "M", 2);
    }

    /** 二维码可指定 L/M/Q/H 纠错级别；一维码会忽略该参数。 */
    public static synchronized boolean[][] matrix(String content, String formatName,
                                                   String qrErrorCorrection) {
        return matrix(content, formatName, qrErrorCorrection, 2);
    }

    /** 可指定静区码元数；支持 QR/PDF417/DataMatrix 和常用一维码别名。 */
    public static synchronized boolean[][] matrix(String content, String formatName,
                                                   String qrErrorCorrection,
                                                   int quietZoneModules) {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("条码内容为空");
        }
        String formatId = normalizeFormatId(formatName);
        BarcodeFormat format = toFormat(formatId);
        String encodedContent = normalizeContent(content, formatId);
        String correction = normalizeQrErrorCorrection(qrErrorCorrection);
        int margin = Math.max(0, Math.min(16, quietZoneModules));
        String key = formatId + '|' + correction + '|' + margin + '|' + encodedContent;
        boolean[][] cached = CACHE.get(key);
        if (cached != null) return cached;
        boolean[][] matrix = encode(encodedContent, format, correction, margin);
        CACHE.put(key, matrix);
        return matrix;
    }

    public static boolean isValid(String content, String formatName) {
        try {
            matrix(content, formatName);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean[][] encode(String content, BarcodeFormat format, String correction,
                                      int quietZoneModules) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, quietZoneModules);
            if (format == BarcodeFormat.QR_CODE) {
                hints.put(EncodeHintType.ERROR_CORRECTION,
                        ErrorCorrectionLevel.valueOf(correction));
            }
            BitMatrix bits = new MultiFormatWriter().encode(content, format, 0, 0, hints);
            boolean[][] matrix = new boolean[bits.getHeight()][bits.getWidth()];
            for (int row = 0; row < bits.getHeight(); row++) {
                for (int col = 0; col < bits.getWidth(); col++) {
                    matrix[row][col] = bits.get(col, row);
                }
            }
            return matrix;
        } catch (WriterException invalid) {
            throw new IllegalArgumentException("内容不符合码制 " + format.name() + "：" + content);
        }
    }

    public static String normalizeQrErrorCorrection(String value) {
        return "L".equals(value) || "Q".equals(value) || "H".equals(value) ? value : "M";
    }

    public static BarcodeFormat toFormat(String formatName) {
        if (formatName == null || formatName.isEmpty()) return BarcodeFormat.QR_CODE;
        if ("ISBN".equals(formatName)) return BarcodeFormat.EAN_13;
        if ("ITF_14".equals(formatName) || "ITF_25".equals(formatName)) {
            return BarcodeFormat.ITF;
        }
        if ("EXTENDED_CODE_39".equals(formatName)) return BarcodeFormat.CODE_39;
        try {
            return BarcodeFormat.valueOf(formatName);
        } catch (IllegalArgumentException unknown) {
            return BarcodeFormat.QR_CODE;
        }
    }

    private static String normalizeFormatId(String value) {
        if (value == null || value.isEmpty()) return "QR_CODE";
        return isBarcodeFormat(value) || isTwoDimensionalFormat(value) ? value : "QR_CODE";
    }

    private static String normalizeContent(String content, String formatId) {
        if ("ITF_14".equals(formatId)) {
            if (!content.matches("\\d{14}")) {
                throw new IllegalArgumentException("ITF14 必须是 14 位数字");
            }
            return content;
        }
        if ("ITF_25".equals(formatId) && !content.matches("(?:\\d{2})+")) {
            throw new IllegalArgumentException("ITF25 必须是偶数位数字");
        }
        if (!"ISBN".equals(formatId)) return content;
        String digits = content.replace("-", "").replace(" ", "");
        if (digits.matches("(?:978|979)\\d{10}")) return digits;
        if (!digits.matches("\\d{9}[\\dXx]")) {
            throw new IllegalArgumentException("ISBN 请输入 10 位或 978/979 开头的 13 位号码");
        }
        String firstTwelve = "978" + digits.substring(0, 9);
        int sum = 0;
        for (int index = 0; index < firstTwelve.length(); index++) {
            int digit = firstTwelve.charAt(index) - '0';
            sum += digit * (index % 2 == 0 ? 1 : 3);
        }
        return firstTwelve + ((10 - sum % 10) % 10);
    }

    public static boolean isBarcodeFormat(String value) {
        if ("ITF".equals(value)) return true; // 兼容 v1.5.13 及更早文档。
        for (String format : BARCODE_FORMATS) if (format.equals(value)) return true;
        return false;
    }

    public static boolean isTwoDimensionalFormat(String value) {
        for (String format : TWO_DIMENSIONAL_FORMATS) if (format.equals(value)) return true;
        return false;
    }

    /** 一维码可选码制（均由 ZXing 3.5.3 实际编码，别名映射见 toFormat）。 */
    public static final String[] BARCODE_FORMATS = {
            "CODE_128", "CODE_39", "ITF_25", "EAN_13", "EAN_8", "UPC_A",
            "UPC_E", "ISBN", "CODABAR", "CODE_93", "EXTENDED_CODE_39", "ITF_14"
    };

    public static final String[] BARCODE_FORMAT_LABELS = {
            "CODE128", "CODE39", "ITF25", "EAN13", "EAN8", "UPCA",
            "UPCE", "ISBN", "CODABAR", "CODE93", "扩展CODE39", "ITF14"
    };

    public static final String[] TWO_DIMENSIONAL_FORMATS = {
            "QR_CODE", "PDF_417", "DATA_MATRIX"
    };

    public static final String[] TWO_DIMENSIONAL_FORMAT_LABELS = {
            "QRCode", "PDF417", "DataMatrix"
    };
}

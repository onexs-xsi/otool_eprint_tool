package com.otool.eprint.scan.vlm;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.qrcode.detector.MultiDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * VLM 输入裁剪：把不承载字段文本的码区（QR/一维码）从画面中裁掉。
 * 视觉 token 预算固定（image_size 616 → 484 token），高密度码纹既浪费预算又干扰
 * 阅读顺序；裁掉后小字号字段行的有效分辨率显著提升——真机实测 "厚膜电阻 0Ω 333.333mW"
 * 从误读 "3mW" 恢复为完全正确（docs/template_ux_0819.md 第二轮 §333）。
 * 检不出码区或裁剪比例异常时原样返回，不猜测。
 */
public final class VlmInputCropper {
    private static final String TAG = "VlmInputCropper";

    private VlmInputCropper() {}

    /** 裁掉码区并适度放大；无码区或裁剪过度时返回原图（不复制）。 */
    public static Bitmap cropToTextRegion(Bitmap frame) {
        if (frame == null) return null;
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width < 200 || height < 200) return frame;

        float left = 0f;
        float top = 0f;
        float right = width;
        float bottom = height;
        boolean found = false;

        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, 0, 0, width, height);
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

        List<Result> results = new ArrayList<>();
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        try {
            Result[] multi = new GenericMultipleBarcodeReader(reader).decodeMultiple(binary, hints);
            if (multi != null) Collections.addAll(results, multi);
        } catch (NotFoundException none) {
            try {
                results.add(reader.decode(binary, hints));
            } catch (NotFoundException ignored) {
                // 无解码结果时继续尝试 QR finder pattern 定位。
            } finally {
                reader.reset();
            }
        }
        for (Result result : results) {
            float[] bounds = boundsOf(result.getResultPoints());
            if (bounds == null) continue;
            boolean matrix = result.getBarcodeFormat() == BarcodeFormat.QR_CODE
                    || result.getBarcodeFormat() == BarcodeFormat.DATA_MATRIX
                    || result.getBarcodeFormat() == BarcodeFormat.AZTEC;
            if (matrix) {
                // QR 在某半边 → 裁掉该半边码列（供应商标签版式：文字列在 QR 另一侧）
                if (bounds[0] > width * 0.45f) {
                    right = Math.min(right, bounds[0] + 4);
                    found = true;
                } else if (bounds[2] < width * 0.55f) {
                    left = Math.max(left, bounds[2] - 4);
                    found = true;
                }
            } else {
                // 一维码所在横带不含字段文本：下半部码 → 裁底部，上半部码 → 裁顶部
                float centerY = (bounds[1] + bounds[3]) / 2f;
                if (centerY > height * 0.5f) {
                    bottom = Math.min(bottom, bounds[1] - 2);
                } else {
                    top = Math.max(top, bounds[3] + 2);
                }
                found = true;
            }
        }
        // 解码失败但 finder pattern 可定位的 QR 同样参与裁剪
        try {
            DetectorResult[] detected = new MultiDetector(binary.getBlackMatrix())
                    .detectMulti(hints);
            for (DetectorResult qr : detected) {
                float[] bounds = boundsOf(qr.getPoints());
                if (bounds == null) continue;
                if (bounds[0] > width * 0.45f) {
                    right = Math.min(right, bounds[0] + 4);
                    found = true;
                } else if (bounds[2] < width * 0.55f) {
                    left = Math.max(left, bounds[2] - 4);
                    found = true;
                }
            }
        } catch (NotFoundException ignored) {
            // 无 QR finder，按已有结果继续。
        }

        if (!found) return frame;
        int cropLeft = Math.max(0, Math.round(left));
        int cropTop = Math.max(0, Math.round(top));
        int cropWidth = Math.min(width - cropLeft, Math.round(right - left));
        int cropHeight = Math.min(height - cropTop, Math.round(bottom - top));
        if (cropWidth < width * 0.35f || cropHeight < height * 0.35f
                || cropWidth < 200 || cropHeight < 200) {
            Log.d(TAG, "裁剪比例异常，保留原图: " + cropWidth + "x" + cropHeight
                    + " / " + width + "x" + height);
            return frame;
        }
        Bitmap cropped = Bitmap.createBitmap(frame, cropLeft, cropTop, cropWidth, cropHeight);
        // 适度放大：让小字号行在固定视觉预算下拿到更多像素（实测 1.5~2x 安全）
        int longest = Math.max(cropWidth, cropHeight);
        if (longest < 1000) {
            float scale = Math.min(2.0f, 1000f / longest);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped,
                    Math.max(1, Math.round(cropWidth * scale)),
                    Math.max(1, Math.round(cropHeight * scale)), true);
            if (scaled != cropped) cropped.recycle();
            cropped = scaled;
        }
        Log.i(TAG, "码区裁剪: " + width + "x" + height + " -> "
                + cropped.getWidth() + "x" + cropped.getHeight());
        return cropped;
    }

    /** 结果点集的轴对齐包围盒：[left, top, right, bottom]；点数不足返回 null。 */
    private static float[] boundsOf(ResultPoint[] points) {
        if (points == null || points.length < 2) return null;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (ResultPoint point : points) {
            if (point == null) continue;
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
        }
        if (minX == Float.MAX_VALUE) return null;
        return new float[]{minX, minY, maxX, maxY};
    }
}

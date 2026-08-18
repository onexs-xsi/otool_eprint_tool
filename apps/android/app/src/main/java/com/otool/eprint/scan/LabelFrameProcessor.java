package com.otool.eprint.scan;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.TextureView;

/**
 * 标签抓帧处理流水线（android.graphics 适配层）：
 * 引导框 ROI 裁剪 → 降采样 → 灰度 → Otsu → 最大连通亮区评估 → 必要时二次裁剪+旋正。
 * 核心算法全部在 {@link LabelImageAnalyzer}（纯 JVM，可单测），这里只做 Bitmap 进出。
 */
public final class LabelFrameProcessor {
    /** 抓帧限宽：OCR 精度足够，耗时与内存可控。 */
    public static final int CAPTURE_MAX_WIDTH = 1280;
    /** 轮廓分析用的降采样限宽。 */
    public static final int ANALYSIS_MAX_WIDTH = 320;
    /** 拉普拉斯方差低于该值视为画面偏糊（经验阈值，真机调参）。 */
    public static final double BLUR_VARIANCE_MIN = 60;
    /** 贴纸亮区占引导框比例低于该值时认为定位不可信，保留整框。 */
    public static final float MIN_COVERAGE = 0.20f;
    /** 倾角小于该值（度）时不做旋正，避免无谓重采样。 */
    public static final float DESKEW_MIN_ANGLE_DEGREES = 1.5f;
    /** 质心归一化偏移超过该值时做二次裁剪居中。 */
    public static final float RECENTER_MIN_OFFSET = 0.08f;

    public static final class ProcessedFrame {
        public final Bitmap bitmap;
        public final LabelImageAnalyzer.LabelRegion region;
        public final double blurVariance;
        public final boolean blurry;

        ProcessedFrame(Bitmap bitmap, LabelImageAnalyzer.LabelRegion region,
                       double blurVariance, boolean blurry) {
            this.bitmap = bitmap;
            this.region = region;
            this.blurVariance = blurVariance;
            this.blurry = blurry;
        }
    }

    private LabelFrameProcessor() {}

    /** 从 TextureView 抓取当前预览帧，限宽 {@link #CAPTURE_MAX_WIDTH}。 */
    public static Bitmap capturePreviewFrame(TextureView textureView) {
        if (textureView == null || !textureView.isAvailable()) return null;
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        int width = Math.min(CAPTURE_MAX_WIDTH, viewWidth);
        int height = Math.max(1, Math.round(viewHeight * (float) width / viewWidth));
        return textureView.getBitmap(width, height);
    }

    /**
     * 按归一化引导框裁剪抓帧，并做贴纸轮廓自校正（可失败降级为引导框整图）。
     * guide 为相对整帧的归一化矩形（0~1）。
     */
    public static ProcessedFrame process(Bitmap frame, RectF guide) {
        int[] crop = LabelImageAnalyzer.guideCropRect(frame.getWidth(), frame.getHeight(),
                guide.left, guide.top, guide.right, guide.bottom);
        Bitmap roi = Bitmap.createBitmap(frame, crop[0], crop[1],
                crop[2] - crop[0], crop[3] - crop[1]);

        LabelImageAnalyzer.GrayImage gray = toGrayImage(roi);
        LabelImageAnalyzer.GrayImage small =
                LabelImageAnalyzer.downscale(gray, ANALYSIS_MAX_WIDTH);
        int threshold = LabelImageAnalyzer.otsuThreshold(small);
        LabelImageAnalyzer.LabelRegion region =
                LabelImageAnalyzer.findLargestBrightRegion(small, threshold);

        Bitmap output = roi;
        if (region.found && region.coverage >= MIN_COVERAGE) {
            boolean needsDeskew = Math.abs(region.angleDegrees) >= DESKEW_MIN_ANGLE_DEGREES;
            boolean needsRecenter = Math.abs(region.offsetX) >= RECENTER_MIN_OFFSET
                    || Math.abs(region.offsetY) >= RECENTER_MIN_OFFSET;
            if (needsDeskew) {
                // 整图级旋正改善检测召回；不做四点透视（手持俯拍形变小）。
                Matrix matrix = new Matrix();
                matrix.postRotate(-region.angleDegrees,
                        roi.getWidth() / 2f, roi.getHeight() / 2f);
                output = Bitmap.createBitmap(roi, 0, 0, roi.getWidth(), roi.getHeight(),
                        matrix, true);
            }
            if (needsRecenter) {
                // 占比/偏移与分辨率无关，直接按 ROI 尺寸换算质心位置。
                output = recenterOnRegion(output, region);
            }
        }
        double blurVariance = LabelImageAnalyzer.laplacianVariance(
                LabelImageAnalyzer.downscale(toGrayImage(output), ANALYSIS_MAX_WIDTH));
        return new ProcessedFrame(output, region, blurVariance,
                blurVariance < BLUR_VARIANCE_MIN);
    }

    /** 以亮区质心为中心做二次裁剪（按旋正后外接矩形比例收缩）。 */
    private static Bitmap recenterOnRegion(Bitmap roi, LabelImageAnalyzer.LabelRegion region) {
        // 亮区明显偏离引导框中心时，以质心为中心、保持原尺寸 90% 重新取景。
        int width = (int) (roi.getWidth() * 0.9f);
        int height = (int) (roi.getHeight() * 0.9f);
        int centerX = Math.round((0.5f + region.offsetX) * roi.getWidth());
        int centerY = Math.round((0.5f + region.offsetY) * roi.getHeight());
        int left = Math.max(0, Math.min(roi.getWidth() - width, centerX - width / 2));
        int top = Math.max(0, Math.min(roi.getHeight() - height, centerY - height / 2));
        return Bitmap.createBitmap(roi, left, top, width, height);
    }

    private static LabelImageAnalyzer.GrayImage toGrayImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        return LabelImageAnalyzer.toGray(argb, width, height);
    }
}

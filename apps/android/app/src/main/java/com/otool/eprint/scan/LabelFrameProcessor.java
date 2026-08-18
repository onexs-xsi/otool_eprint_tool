package com.otool.eprint.scan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
        public final boolean croppedToLabel;
        public final boolean deskewed;

        ProcessedFrame(Bitmap bitmap, LabelImageAnalyzer.LabelRegion region,
                       double blurVariance, boolean blurry, boolean croppedToLabel,
                       boolean deskewed) {
            this.bitmap = bitmap;
            this.region = region;
            this.blurVariance = blurVariance;
            this.blurry = blurry;
            this.croppedToLabel = croppedToLabel;
            this.deskewed = deskewed;
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
        return process(frame, guide, true, MIN_COVERAGE, BLUR_VARIANCE_MIN);
    }

    /** 可配置流水线：标签外区域裁除、最低定位占比与模糊阈值均来自识别配置页。 */
    public static ProcessedFrame process(Bitmap frame, RectF guide, boolean cropToLabel,
                                         float minimumCoverage,
                                         double blurVarianceThreshold) {
        int[] crop = LabelImageAnalyzer.guideCropRect(frame.getWidth(), frame.getHeight(),
                guide.left, guide.top, guide.right, guide.bottom);
        Bitmap roi = Bitmap.createBitmap(frame, crop[0], crop[1],
                crop[2] - crop[0], crop[3] - crop[1]);

        LabelImageAnalyzer.GrayImage gray = toGrayImage(roi);
        LabelImageAnalyzer.GrayImage small =
                LabelImageAnalyzer.downscale(gray, ANALYSIS_MAX_WIDTH);
        int threshold = LabelImageAnalyzer.otsuThreshold(small);
        LabelImageAnalyzer.LabelRegion brightRegion =
                LabelImageAnalyzer.findLargestBrightRegion(small, threshold);
        LabelImageAnalyzer.GrayImage paperMask = LabelImageAnalyzer.openBinaryMask(
                LabelImageAnalyzer.downscale(toPaperMask(roi), ANALYSIS_MAX_WIDTH), 2);
        LabelImageAnalyzer.LabelRegion paperRegion =
                LabelImageAnalyzer.findLargestBrightRegion(paperMask, 127);
        boolean paperReliable = paperRegion.found && paperRegion.coverage >= 0.08f;
        LabelImageAnalyzer.LabelRegion region = paperReliable ? paperRegion : brightRegion;
        LabelImageAnalyzer.GrayImage regionCoordinates = paperReliable ? paperMask : small;

        Bitmap output = roi;
        boolean cropped = false;
        boolean deskewed = false;
        if (region.found && region.coverage >= minimumCoverage) {
            if (cropToLabel) {
                output = paperReliable
                        ? maskAndCropPaperRegion(roi, regionCoordinates, region)
                        : cropToRegion(roi, regionCoordinates, region);
                cropped = output != roi;
            }
            boolean needsDeskew = Math.abs(region.angleDegrees) >= DESKEW_MIN_ANGLE_DEGREES;
            boolean needsRecenter = Math.abs(region.offsetX) >= RECENTER_MIN_OFFSET
                    || Math.abs(region.offsetY) >= RECENTER_MIN_OFFSET;
            if (needsDeskew && !(paperReliable && cropped)) {
                // 旋正后铺白透明角，GLM 不再接收标签外的黑色三角区域。
                Matrix matrix = new Matrix();
                matrix.postRotate(-region.angleDegrees,
                        output.getWidth() / 2f, output.getHeight() / 2f);
                Bitmap rotated = Bitmap.createBitmap(output, 0, 0,
                        output.getWidth(), output.getHeight(),
                        matrix, true);
                Bitmap white = Bitmap.createBitmap(rotated.getWidth(), rotated.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(white);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(rotated, 0f, 0f, null);
                output = white;
                deskewed = true;
            }
            if (!cropToLabel && needsRecenter) {
                // 占比/偏移与分辨率无关，直接按 ROI 尺寸换算质心位置。
                output = recenterOnRegion(output, region);
            }
        }
        double blurVariance = LabelImageAnalyzer.laplacianVariance(
                LabelImageAnalyzer.downscale(toGrayImage(output), ANALYSIS_MAX_WIDTH));
        return new ProcessedFrame(output, region, blurVariance,
                blurVariance < blurVarianceThreshold, cropped, deskewed);
    }

    /**
     * 按纸张连通区逐行的左右边界保留标签内部，再裁到连通区外框。与旋转矩形相比，
     * 该方式能适应透视梯形和斜放标签，同时不会把四角的包装/桌面带入 OCR 输入。
     */
    private static Bitmap maskAndCropPaperRegion(Bitmap roi,
                                                  LabelImageAnalyzer.GrayImage analyzed,
                                                  LabelImageAnalyzer.LabelRegion region) {
        if (region.rowLeft == null || region.rowRight == null) return roi;
        float scaleX = roi.getWidth() / (float) analyzed.width;
        float scaleY = roi.getHeight() / (float) analyzed.height;
        int edgePadding = Math.max(1, Math.round(analyzed.width * 0.008f));
        int left = clamp(Math.round((region.left - edgePadding) * scaleX),
                0, roi.getWidth() - 1);
        int top = clamp(Math.round((region.top - 1) * scaleY),
                0, roi.getHeight() - 1);
        int right = clamp(Math.round((region.right + edgePadding) * scaleX),
                left + 1, roi.getWidth());
        int bottom = clamp(Math.round((region.bottom + 1) * scaleY),
                top + 1, roi.getHeight());
        int outputWidth = right - left;
        int outputHeight = bottom - top;
        if (outputWidth < roi.getWidth() * 0.25f
                || outputHeight < roi.getHeight() * 0.20f) {
            return roi;
        }

        Bitmap output = Bitmap.createBitmap(roi, left, top, outputWidth, outputHeight)
                .copy(Bitmap.Config.ARGB_8888, true);
        int[] pixels = new int[outputWidth * outputHeight];
        output.getPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight);
        for (int y = 0; y < outputHeight; y++) {
            int sourceY = top + y;
            int analysisY = clamp((int) (sourceY / scaleY), 0, analyzed.height - 1);
            int spanLeft = region.rowLeft[analysisY];
            int spanRight = region.rowRight[analysisY];
            if (spanRight < spanLeft) {
                for (int delta = 1; delta <= 3 && spanRight < spanLeft; delta++) {
                    int above = analysisY - delta;
                    int below = analysisY + delta;
                    if (above >= 0 && region.rowRight[above] >= region.rowLeft[above]) {
                        spanLeft = region.rowLeft[above];
                        spanRight = region.rowRight[above];
                    } else if (below < analyzed.height
                            && region.rowRight[below] >= region.rowLeft[below]) {
                        spanLeft = region.rowLeft[below];
                        spanRight = region.rowRight[below];
                    }
                }
            }
            int keepLeft = spanRight >= spanLeft
                    ? Math.round((spanLeft - edgePadding) * scaleX) : Integer.MAX_VALUE;
            int keepRight = spanRight >= spanLeft
                    ? Math.round((spanRight + edgePadding + 1) * scaleX) : Integer.MIN_VALUE;
            for (int x = 0; x < outputWidth; x++) {
                int sourceX = left + x;
                if (sourceX < keepLeft || sourceX >= keepRight) {
                    pixels[y * outputWidth + x] = Color.WHITE;
                }
            }
        }
        output.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight);
        return output;
    }

    /** 把降采样分析坐标映射回 ROI，保留 3% 安全边距后裁出真实标签。 */
    private static Bitmap cropToRegion(Bitmap roi, LabelImageAnalyzer.GrayImage analyzed,
                                       LabelImageAnalyzer.LabelRegion region) {
        float scaleX = roi.getWidth() / (float) analyzed.width;
        float scaleY = roi.getHeight() / (float) analyzed.height;
        int marginX = Math.max(4, Math.round(roi.getWidth() * 0.03f));
        int marginY = Math.max(4, Math.round(roi.getHeight() * 0.03f));
        int left = clamp(Math.round(region.left * scaleX) - marginX, 0, roi.getWidth() - 1);
        int top = clamp(Math.round(region.top * scaleY) - marginY, 0, roi.getHeight() - 1);
        int right = clamp(Math.round(region.right * scaleX) + marginX, left + 1, roi.getWidth());
        int bottom = clamp(Math.round(region.bottom * scaleY) + marginY, top + 1, roi.getHeight());
        if (right - left < roi.getWidth() * 0.25f
                || bottom - top < roi.getHeight() * 0.20f) {
            return roi;
        }
        return Bitmap.createBitmap(roi, left, top, right - left, bottom - top);
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static LabelImageAnalyzer.GrayImage toGrayImage(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        return LabelImageAnalyzer.toGray(argb, width, height);
    }

    private static LabelImageAnalyzer.GrayImage toPaperMask(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        return LabelImageAnalyzer.toPaperMask(argb, width, height);
    }
}

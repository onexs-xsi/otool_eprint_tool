package com.otool.eprint.scan.opencv;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 解码无关的码区候选检测（OpenCV 版）：Sobel 梯度 + 闭运算 + 轮廓几何筛选，
 * 与 BarcodeMasker 内纯 Java 实现的判定语义一致（梯度阈值经 convertScaleAbs 归一到
 * 逐像素差分量级）。返回像素坐标遮盖框列表；OpenCV 不可用或任何一步失败返回 null，
 * 调用方回退纯 Java 实现。
 */
public final class OpenCVBarcodeRegionFinder {
    private static final String TAG = "OpenCVBarcodeRegionFinder";
    /** 分析限宽：码区候选不需要全分辨率，降采样后轮廓更稳定。 */
    private static final int ANALYSIS_MAX_WIDTH = 640;
    /** Sobel 梯度按 1/4 归一，使阈值与纯 Java 实现的逐像素差分量级一致。 */
    private static final double GRADIENT_SCALE = 0.25;

    private OpenCVBarcodeRegionFinder() {}

    /**
     * 一维条纹候选：单方向强梯度区域闭运算后取长条形轮廓。
     * 返回 null 表示 OpenCV 路径不可用，需回退 Java 实现；空列表表示确实无候选。
     */
    public static List<float[]> findStripeRegions(Bitmap bitmap) {
        return detect(bitmap, true);
    }

    /**
     * 近方形矩阵候选：双方向高频梯度区域闭运算后取近方形轮廓。
     * 返回 null 表示 OpenCV 路径不可用，需回退 Java 实现；空列表表示确实无候选。
     */
    public static List<float[]> findMatrixRegions(Bitmap bitmap) {
        return detect(bitmap, false);
    }

    private static List<float[]> detect(Bitmap bitmap, boolean stripes) {
        if (bitmap == null || bitmap.getWidth() < 24 || bitmap.getHeight() < 24
                || !OpenCVSupport.isAvailable()) {
            return null;
        }
        try {
            return detectInternal(bitmap, stripes);
        } catch (Throwable failure) {
            Log.w(TAG, (stripes ? "条纹" : "矩阵") + "候选检测失败，回退 Java 实现", failure);
            return null;
        }
    }

    private static List<float[]> detectInternal(Bitmap bitmap, boolean stripes) {
        Mat source = new Mat();
        Mat resized = new Mat();
        Mat gray = new Mat();
        Mat gradX = new Mat();
        Mat gradY = new Mat();
        Mat absX = new Mat();
        Mat absY = new Mat();
        Mat mask = new Mat();
        Mat comparison = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Utils.bitmapToMat(bitmap, source);
            double scale = Math.min(1.0, ANALYSIS_MAX_WIDTH / (double) source.cols());
            if (scale < 1.0) {
                Imgproc.resize(source, resized, new Size(
                        Math.max(1, Math.round(source.cols() * scale)),
                        Math.max(1, Math.round(source.rows() * scale))));
            } else {
                source.copyTo(resized);
            }
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.Sobel(gray, gradX, CvType.CV_16S, 1, 0, 3);
            Imgproc.Sobel(gray, gradY, CvType.CV_16S, 0, 1, 3);
            Core.convertScaleAbs(gradX, absX, GRADIENT_SCALE, 0);
            Core.convertScaleAbs(gradY, absY, GRADIENT_SCALE, 0);
            if (stripes) {
                buildStripeMask(absX, absY, mask, comparison);
                // 水平闭运算连接竖条纹；纵向条码由转置掩码路径另行覆盖。
                kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                        new Size(Math.max(9, gray.cols() / 24), 3));
            } else {
                buildMatrixMask(absX, absY, mask, comparison);
                int kernelSide = Math.max(5, Math.min(gray.cols(), gray.rows()) / 40);
                kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                        new Size(kernelSide, kernelSide));
            }
            Imgproc.morphologyEx(mask, closed, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(closed, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<float[]> regions = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                Rect box = Imgproc.boundingRect(contour);
                float density = Core.countNonZero(closed.submat(box))
                        / (float) (box.width * box.height);
                float[] region = stripes
                        ? stripeRegion(box, density, gray.cols(), gray.rows())
                        : matrixRegion(box, density, gray.cols(), gray.rows());
                if (region != null) {
                    // 分析坐标映射回原图。
                    for (int index = 0; index < 4; index++) {
                        region[index] = (float) (region[index] / scale);
                    }
                    regions.add(region);
                }
            }
            return regions;
        } finally {
            source.release();
            resized.release();
            gray.release();
            gradX.release();
            gradY.release();
            absX.release();
            absY.release();
            mask.release();
            comparison.release();
            closed.release();
            if (kernel != null) kernel.release();
            hierarchy.release();
            for (MatOfPoint contour : contours) contour.release();
        }
    }

    /** 条纹掩码：|gx| 或 |gy| 单方向显著强于另一方向（阈值对齐 Java 版的 30 / 1.55+5）。 */
    private static void buildStripeMask(Mat absX, Mat absY, Mat mask, Mat comparison) {
        Mat strongX = new Mat();
        Mat strongY = new Mat();
        Mat dominanceX = new Mat();
        Mat dominanceY = new Mat();
        Mat verticalStripes = new Mat();
        Mat horizontalStripes = new Mat();
        try {
            Imgproc.threshold(absX, strongX, 30, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(absY, strongY, 30, 255, Imgproc.THRESH_BINARY);
            Core.multiply(absY, new Scalar(1.55), dominanceX);
            Core.add(dominanceX, new Scalar(5), dominanceX);
            Core.compare(absX, dominanceX, comparison, Core.CMP_GE);
            Core.bitwise_and(strongX, comparison, verticalStripes);
            Core.multiply(absX, new Scalar(1.55), dominanceY);
            Core.add(dominanceY, new Scalar(5), dominanceY);
            Core.compare(absY, dominanceY, comparison, Core.CMP_GE);
            Core.bitwise_and(strongY, comparison, horizontalStripes);
            Core.bitwise_or(verticalStripes, horizontalStripes, mask);
        } finally {
            strongX.release();
            strongY.release();
            dominanceX.release();
            dominanceY.release();
            verticalStripes.release();
            horizontalStripes.release();
        }
    }

    /** 矩阵掩码：双方向梯度都不弱（阈值对齐 Java 版的 weakest>=22 且 >= strongest*0.42）。 */
    private static void buildMatrixMask(Mat absX, Mat absY, Mat mask, Mat comparison) {
        Mat weakest = new Mat();
        Mat strongest = new Mat();
        Mat dense = new Mat();
        try {
            Core.min(absX, absY, weakest);
            Core.max(absX, absY, strongest);
            Imgproc.threshold(weakest, dense, 22, 255, Imgproc.THRESH_BINARY);
            Core.multiply(strongest, new Scalar(0.42), comparison);
            Core.compare(weakest, comparison, comparison, Core.CMP_GE);
            Core.bitwise_and(dense, comparison, mask);
        } finally {
            weakest.release();
            strongest.release();
            dense.release();
        }
    }

    /** 长条形几何筛选（阈值对齐 Java 版），返回带安全边距的遮盖框或 null。 */
    private static float[] stripeRegion(Rect box, float density, int width, int height) {
        float longSide = Math.max(box.width, box.height);
        float shortSide = Math.min(box.width, box.height);
        if (longSide < Math.max(32f, Math.max(width, height) * 0.055f)
                || shortSide < 8f || longSide / shortSide < 2.35f || density < 0.32f) {
            return null;
        }
        boolean horizontal = box.width >= box.height;
        float padLong = longSide * 0.06f;
        float padShort = Math.max(6f, shortSide * 0.35f);
        float padX = horizontal ? padLong : padShort;
        float padY = horizontal ? padShort : padLong;
        return new float[]{
                clamp(box.x - padX, 0f, width),
                clamp(box.y - padY, 0f, height),
                clamp(box.x + box.width + padX, 0f, width),
                clamp(box.y + box.height + padY, 0f, height)};
    }

    /** 近方形几何筛选（阈值对齐 Java 版），返回带安全边距的遮盖框或 null。 */
    private static float[] matrixRegion(Rect box, float density, int width, int height) {
        float longer = Math.max(box.width, box.height);
        float shorter = Math.min(box.width, box.height);
        float minimumSide = Math.max(28f, Math.min(width, height) * 0.045f);
        if (shorter < minimumSide || longer / shorter > 1.55f || density < 0.34f
                || box.width * (float) box.height > width * (float) height * 0.18f) {
            return null;
        }
        float padding = Math.max(8f, longer * 0.16f);
        return new float[]{
                clamp(box.x - padding, 0f, width),
                clamp(box.y - padding, 0f, height),
                clamp(box.x + box.width + padding, 0f, width),
                clamp(box.y + box.height + padding, 0f, height)};
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

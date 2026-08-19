package com.otool.eprint.scan.opencv;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.Locale;

/**
 * 标签反光抑制：在几何拉正之后估计大尺度光照场，用除法归一化压低反光/阴影梯度，
 * 再以低强度 CLAHE 和反锐化恢复浅色小字。只在光照不均达到阈值且结果确有改善时采纳。
 * 单帧中已经完全过曝的像素不可恢复，因此这里不做伪造式 inpaint。
 */
public final class LabelGlareReducer {
    private static final String TAG = "LabelGlareReducer";
    private static final double MIN_ILLUMINATION_VARIATION = 0.025;
    private static final double MIN_CLIPPED_RATIO = 0.12;
    private static final double MIN_CLIPPED_VARIATION = 0.015;
    private static final double MIN_RELATIVE_IMPROVEMENT = 0.03;

    public static final class Result {
        public final Bitmap bitmap;
        public final boolean applied;
        public final double beforeVariation;
        public final double afterVariation;
        public final double clippedRatio;

        Result(Bitmap bitmap, boolean applied, double beforeVariation,
               double afterVariation, double clippedRatio) {
            this.bitmap = bitmap;
            this.applied = applied;
            this.beforeVariation = beforeVariation;
            this.afterVariation = afterVariation;
            this.clippedRatio = clippedRatio;
        }
    }

    private LabelGlareReducer() {}

    public static Result reduce(Bitmap source) {
        if (source == null || source.getWidth() < 96 || source.getHeight() < 96
                || !OpenCVSupport.isAvailable()) {
            return new Result(source, false, 0, 0, 0);
        }
        try {
            return reduceInternal(source);
        } catch (Throwable failure) {
            Log.w(TAG, "反光抑制失败，保留原图", failure);
            return new Result(source, false, 0, 0, 0);
        }
    }

    private static Result reduceInternal(Bitmap source) {
        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat paperMask = new Mat();
        Mat highlightMask = new Mat();
        Mat clippedPaper = new Mat();
        Mat illumination = new Mat();
        Mat grayFloat = new Mat();
        Mat illuminationFloat = new Mat();
        Mat normalizedFloat = new Mat();
        Mat normalized = new Mat();
        Mat equalized = new Mat();
        Mat blended = new Mat();
        Mat softened = new Mat();
        Mat sharpened = new Mat();
        Mat outputRgba = new Mat();
        CLAHE clahe = null;
        try {
            Utils.bitmapToMat(source, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            // 只用亮纸区域统计，尽量排除文字、二维码和一维码对光照估计的干扰。
            Imgproc.threshold(gray, paperMask, 150, 255, Imgproc.THRESH_BINARY);
            double paperPixels = Core.countNonZero(paperMask);
            if (paperPixels < gray.total() * 0.25) {
                return new Result(source, false, 0, 0, 0);
            }
            int kernel = illuminationKernel(gray.cols(), gray.rows());
            Imgproc.GaussianBlur(gray, illumination, new Size(kernel, kernel), 0);
            double beforeVariation = illuminationVariation(illumination, paperMask);
            Imgproc.threshold(gray, highlightMask, 248, 255, Imgproc.THRESH_BINARY);
            Core.bitwise_and(highlightMask, paperMask, clippedPaper);
            double clippedRatio = Core.countNonZero(clippedPaper) / paperPixels;
            if (!shouldApply(beforeVariation, clippedRatio)) {
                Log.d(TAG, "光照均匀，无需反光抑制：variation=" + format(beforeVariation)
                        + " clipped=" + format(clippedRatio));
                return new Result(source, false, beforeVariation, beforeVariation, clippedRatio);
            }

            gray.convertTo(grayFloat, CvType.CV_32F);
            illumination.convertTo(illuminationFloat, CvType.CV_32F);
            Core.add(illuminationFloat, new Scalar(8.0), illuminationFloat);
            // 白纸目标亮度 215：保留灰阶余量，避免把浅色笔画再次推到饱和区。
            Core.divide(grayFloat, illuminationFloat, normalizedFloat, 215.0);
            normalizedFloat.convertTo(normalized, CvType.CV_8U);
            clahe = Imgproc.createCLAHE(1.65, new Size(8, 8));
            clahe.apply(normalized, equalized);
            Core.addWeighted(gray, 0.30, equalized, 0.70, 0, blended);
            Imgproc.GaussianBlur(blended, softened, new Size(3, 3), 0);
            Core.addWeighted(blended, 1.16, softened, -0.16, 0, sharpened);

            Mat afterIllumination = new Mat();
            double afterVariation;
            try {
                Imgproc.GaussianBlur(sharpened, afterIllumination,
                        new Size(kernel, kernel), 0);
                afterVariation = illuminationVariation(afterIllumination, paperMask);
            } finally {
                afterIllumination.release();
            }
            if (!isImprovement(beforeVariation, afterVariation)) {
                Log.d(TAG, "反光抑制收益不足，保留原图：" + format(beforeVariation)
                        + " -> " + format(afterVariation));
                return new Result(source, false, beforeVariation, afterVariation, clippedRatio);
            }

            Imgproc.cvtColor(sharpened, outputRgba, Imgproc.COLOR_GRAY2RGBA);
            Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(outputRgba, output);
            Log.i(TAG, "反光抑制已应用：variation=" + format(beforeVariation)
                    + " -> " + format(afterVariation) + " clipped=" + format(clippedRatio)
                    + " kernel=" + kernel);
            return new Result(output, true, beforeVariation, afterVariation, clippedRatio);
        } finally {
            if (clahe != null) clahe.collectGarbage();
            rgba.release();
            gray.release();
            paperMask.release();
            highlightMask.release();
            clippedPaper.release();
            illumination.release();
            grayFloat.release();
            illuminationFloat.release();
            normalizedFloat.release();
            normalized.release();
            equalized.release();
            blended.release();
            softened.release();
            sharpened.release();
            outputRgba.release();
        }
    }

    static boolean shouldApply(double illuminationVariation, double clippedRatio) {
        return illuminationVariation >= MIN_ILLUMINATION_VARIATION
                || (clippedRatio >= MIN_CLIPPED_RATIO
                && illuminationVariation >= MIN_CLIPPED_VARIATION);
    }

    static boolean isImprovement(double beforeVariation, double afterVariation) {
        return beforeVariation > 0 && afterVariation >= 0
                && afterVariation <= beforeVariation * (1.0 - MIN_RELATIVE_IMPROVEMENT);
    }

    static int illuminationKernel(int width, int height) {
        int smaller = Math.max(1, Math.min(width, height));
        int kernel = Math.max(31, Math.min(151, smaller / 3));
        return (kernel & 1) == 0 ? kernel + 1 : kernel;
    }

    private static double illuminationVariation(Mat illumination, Mat mask) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble deviation = new MatOfDouble();
        try {
            Core.meanStdDev(illumination, mean, deviation, mask);
            double[] meanValues = mean.toArray();
            double[] deviationValues = deviation.toArray();
            double average = meanValues.length == 0 ? 0 : meanValues[0];
            double spread = deviationValues.length == 0 ? 0 : deviationValues[0];
            return average <= 1 ? 0 : spread / average;
        } finally {
            mean.release();
            deviation.release();
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}

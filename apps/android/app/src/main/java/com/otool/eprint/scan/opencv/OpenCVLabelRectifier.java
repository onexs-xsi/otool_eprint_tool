package com.otool.eprint.scan.opencv;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 标签四点透视纠偏：在引导框 ROI 内定位纸张轮廓，能逼近四边形就直接透视拉正；
 * 轮廓不是四边形时退用最小外接旋转矩形的四角走同一透视变换。
 * 检测按多通道尝试（Otsu 亮区 → Otsu 反相 → HSV 白卡 → Canny 边缘 → 自适应阈值），
 * 每个通道内按面积降序尝试前若干轮廓、多档 approxPolyDP epsilon，
 * 第一个产出有效四边形的通道胜出。任何一步失败（OpenCV 不可用、无有效轮廓、
 * 角点异常、输出尺寸异常）都返回 null，调用方回退现有 Java 旋正路径。
 * 角点排序与四边形校验是纯 JVM 静态方法，可单测。
 */
public final class OpenCVLabelRectifier {
    private static final String TAG = "OpenCVLabelRectifier";
    /** 轮廓分析限宽，兼顾角点精度与耗时。 */
    private static final int ANALYSIS_MAX_WIDTH = 640;
    /** 候选轮廓面积占 ROI 的合理区间；超出视为误检。 */
    private static final double MIN_AREA_RATIO = 0.10;
    private static final double MAX_AREA_RATIO = 0.98;
    /** 每个通道内按面积降序尝试的轮廓数。 */
    private static final int MAX_CONTOUR_CANDIDATES = 6;
    /** approxPolyDP 的 epsilon 系数（× 轮廓周长），按序尝试。 */
    private static final double[] EPSILON_FACTORS = {0.02, 0.035, 0.05};
    /** 四边形占 ROI 面积的合理区间（排序后校验）。 */
    private static final double MIN_QUAD_AREA_RATIO = 0.10;
    private static final double MAX_QUAD_AREA_RATIO = 0.99;
    /** 输出正视图的最小边长、相对输入的最大放大倍数与极限长宽比。 */
    private static final int MIN_OUTPUT_SIDE = 48;
    private static final double MAX_OUTPUT_SCALE = 1.5;
    private static final double MAX_OUTPUT_ASPECT = 8.0;

    private OpenCVLabelRectifier() {}

    /** 尝试透视纠偏；失败返回 null，调用方回退原 Java 旋正/裁剪路径。 */
    public static Bitmap rectify(Bitmap roi) {
        if (roi == null || roi.getWidth() < MIN_OUTPUT_SIDE * 2
                || roi.getHeight() < MIN_OUTPUT_SIDE * 2
                || !OpenCVSupport.isAvailable()) {
            return null;
        }
        try {
            return rectifyInternal(roi);
        } catch (Throwable failure) {
            Log.w(TAG, "透视纠偏失败，回退 Java 路径", failure);
            return null;
        }
    }

    private static Bitmap rectifyInternal(Bitmap roi) {
        Mat source = new Mat();
        Mat analysis = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat transform = null;
        Mat warped = null;
        try {
            Utils.bitmapToMat(roi, source);
            double scale = Math.min(1.0, ANALYSIS_MAX_WIDTH / (double) source.cols());
            if (scale < 1.0) {
                Imgproc.resize(source, analysis,
                        new Size(Math.round(source.cols() * scale),
                                Math.round(source.rows() * scale)));
            } else {
                source.copyTo(analysis);
            }
            Imgproc.cvtColor(analysis, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            // 多通道按序尝试，第一个产出有效四边形的通道胜出。
            DetectionResult detection = detectOtsu(blurred, false);
            if (detection == null) detection = detectOtsu(blurred, true);
            if (detection == null) detection = detectWhiteCard(analysis);
            if (detection == null) detection = detectCanny(blurred);
            if (detection == null) detection = detectAdaptive(blurred);
            // 全部通道都逼近不出四边形时，才对 Otsu 亮区最大轮廓用 minAreaRect 兜底，
            // 避免粗糙外接矩形抢占更精确的边缘/自适应通道。
            if (detection == null) detection = detectMinAreaRectFallback(blurred);
            if (detection == null) return null;
            float[] corners = detection.corners;
            // 分析坐标映射回 ROI 全分辨率再做透视变换，保证输出清晰度。
            for (int index = 0; index < corners.length; index++) {
                corners[index] = (float) (corners[index] / scale);
            }
            float[] ordered = sortQuadCorners(corners);
            if (!isUsableQuad(ordered, source.cols(), source.rows())) {
                Log.d(TAG, detection.channel + " 四边形映射回全分辨率后校验失败");
                return null;
            }

            double outputWidth = Math.max(
                    distance(ordered, 0, 1), distance(ordered, 3, 2));
            double outputHeight = Math.max(
                    distance(ordered, 0, 3), distance(ordered, 1, 2));
            double longestInput = Math.max(source.cols(), source.rows());
            double aspect = outputWidth / Math.max(1.0, outputHeight);
            if (outputWidth < MIN_OUTPUT_SIDE || outputHeight < MIN_OUTPUT_SIDE
                    || outputWidth > longestInput * MAX_OUTPUT_SCALE
                    || outputHeight > longestInput * MAX_OUTPUT_SCALE
                    || aspect > MAX_OUTPUT_ASPECT || aspect < 1.0 / MAX_OUTPUT_ASPECT) {
                Log.d(TAG, detection.channel + " 输出尺寸异常 " + (int) outputWidth
                        + "x" + (int) outputHeight);
                return null;
            }

            MatOfPoint2f srcQuad = new MatOfPoint2f(
                    new Point(ordered[0], ordered[1]), new Point(ordered[2], ordered[3]),
                    new Point(ordered[4], ordered[5]), new Point(ordered[6], ordered[7]));
            MatOfPoint2f dstQuad = new MatOfPoint2f(
                    new Point(0, 0), new Point(outputWidth - 1, 0),
                    new Point(outputWidth - 1, outputHeight - 1),
                    new Point(0, outputHeight - 1));
            transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad);
            srcQuad.release();
            dstQuad.release();
            warped = new Mat();
            // 透视外区域铺白，与 Java 旋正路径的输出约定一致。
            Imgproc.warpPerspective(source, warped, transform,
                    new Size(outputWidth, outputHeight), Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT, new Scalar(255, 255, 255, 255));
            Bitmap output = Bitmap.createBitmap((int) outputWidth, (int) outputHeight,
                    Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(warped, output);
            Log.i(TAG, detection.channel + " 命中：四边形面积占比 "
                    + String.format(Locale.US, "%.3f", detection.quadAreaRatio)
                    + "，输出 " + (int) outputWidth + "x" + (int) outputHeight);
            return output;
        } finally {
            source.release();
            analysis.release();
            gray.release();
            blurred.release();
            if (transform != null) transform.release();
            if (warped != null) warped.release();
        }
    }

    /** Otsu 阈值通道：invert=false 取亮区（纸张通常比背景亮），invert=true 取反相（深色标签/托盘）。 */
    private static DetectionResult detectOtsu(Mat blurred, boolean invert) {
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            Imgproc.threshold(blurred, binary, 0, 255,
                    (invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY)
                            | Imgproc.THRESH_OTSU);
            kernel = closeKernel(blurred);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel);
            return findQuad(closed, invert ? "otsu-inv" : "otsu-bright");
        } finally {
            binary.release();
            closed.release();
            if (kernel != null) kernel.release();
        }
    }

    /**
     * HSV 白卡通道：标签卡通常低饱和、高亮度（S≤60、V≥140），与米色/灰色背景可分；
     * 先开运算剥离粘连的白色细长物（软管、线缆）再闭运算连片。
     * 离线调参依据：.tools/verification/debug_hsv.py 在真实场景截图上的验证结果。
     */
    private static DetectionResult detectWhiteCard(Mat analysisRgba) {
        Mat rgb = new Mat();
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat opened = new Mat();
        Mat closed = new Mat();
        Mat openKernel = null;
        Mat closeKernel = null;
        try {
            Imgproc.cvtColor(analysisRgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv, new Scalar(0, 0, 140), new Scalar(180, 60, 255), mask);
            int openSize = Math.max(3, Math.min(mask.cols(), mask.rows()) / 130) | 1;
            openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(openSize, openSize));
            Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, openKernel);
            closeKernel = closeKernel(mask);
            Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, closeKernel);
            return findQuad(closed, "white-card");
        } finally {
            rgb.release();
            hsv.release();
            mask.release();
            opened.release();
            closed.release();
            if (openKernel != null) openKernel.release();
            if (closeKernel != null) closeKernel.release();
        }
    }

    /** Canny 边缘通道：亮度相近的标签卡与白色软管仍能被边缘分开。 */
    private static DetectionResult detectCanny(Mat blurred) {
        Mat edges = new Mat();
        Mat dilated = new Mat();
        Mat closed = new Mat();
        Mat dilateKernel = null;
        Mat closeKernel = null;
        try {
            Imgproc.Canny(blurred, edges, 50, 150);
            dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(edges, dilated, dilateKernel);
            closeKernel = closeKernel(blurred);
            Imgproc.morphologyEx(dilated, closed, Imgproc.MORPH_CLOSE, closeKernel);
            return findQuad(closed, "canny");
        } finally {
            edges.release();
            dilated.release();
            closed.release();
            if (dilateKernel != null) dilateKernel.release();
            if (closeKernel != null) closeKernel.release();
        }
    }

    /** 自适应阈值通道：对光照不均、局部对比度场景更稳健。 */
    private static DetectionResult detectAdaptive(Mat blurred) {
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            int blockSize = Math.max(3, Math.min(blurred.cols(), blurred.rows()) / 20) | 1;
            Imgproc.adaptiveThreshold(blurred, binary, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, 5);
            kernel = closeKernel(blurred);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel);
            return findQuad(closed, "adaptive");
        } finally {
            binary.release();
            closed.release();
            if (kernel != null) kernel.release();
        }
    }

    /** minAreaRect 兜底四边形的最大面积占比；更大基本是"整张 ROI 粘连成一块"的误检。 */
    private static final double MAX_FALLBACK_AREA_RATIO = 0.90;

    /**
     * 最后的兜底：Otsu 亮区最大轮廓的最小外接旋转矩形四角。
     * 四角先在分析分辨率过 {@link #isValidApproxQuad}，且面积占比不得超过
     * {@link #MAX_FALLBACK_AREA_RATIO}（超出说明亮区已粘成一整块，拉出的是整幅 ROI），
     * 避免粗糙外接矩形抢占更精确的边缘/自适应通道。
     */
    private static DetectionResult detectMinAreaRectFallback(Mat blurred) {
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            Imgproc.threshold(blurred, binary, 0, 255,
                    Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            kernel = closeKernel(blurred);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel);
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(closed, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            try {
                double imageArea = closed.cols() * (double) closed.rows();
                MatOfPoint largest = null;
                double largestArea = 0;
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area > largestArea) {
                        largestArea = area;
                        largest = contour;
                    }
                }
                if (largest == null || largestArea < imageArea * MIN_AREA_RATIO
                        || largestArea > imageArea * MAX_AREA_RATIO) {
                    Log.d(TAG, "minAreaRect 兜底无有效轮廓（轮廓数=" + contours.size() + "）");
                    return null;
                }
                float[] corners = minAreaRectCorners(largest);
                if (!isValidApproxQuad(corners, closed.cols(), closed.rows())) {
                    Log.d(TAG, "minAreaRect 兜底四边形校验失败");
                    return null;
                }
                double ratio = quadArea(corners) / imageArea;
                if (ratio > MAX_FALLBACK_AREA_RATIO) {
                    Log.d(TAG, "minAreaRect 兜底四边形面积占比过高（"
                            + String.format(Locale.US, "%.3f", ratio) + "），视为整幅粘连，放弃");
                    return null;
                }
                return new DetectionResult(corners, "minAreaRect", ratio);
            } finally {
                for (MatOfPoint contour : contours) contour.release();
            }
        } finally {
            binary.release();
            closed.release();
            if (kernel != null) kernel.release();
        }
    }

    /** 闭运算结构元：连断边，尺寸随分析图缩放。 */
    private static Mat closeKernel(Mat image) {
        int kernelSize = Math.max(3, Math.min(image.cols(), image.rows()) / 60) | 1;
        return Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(kernelSize, kernelSize));
    }

    /**
     * 在二值图上找标签四边形：按面积降序取前 {@link #MAX_CONTOUR_CANDIDATES} 个
     * 面积占比合理的轮廓，逐个用多档 epsilon 做 approxPolyDP，第一个逼近出有效
     * 四边形的轮廓即采纳；都不出四边形时返回 null，由调用方尝试下一通道。
     */
    private static DetectionResult findQuad(Mat closed, String channel) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(closed, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();
        try {
            double imageArea = closed.cols() * (double) closed.rows();
            List<MatOfPoint> candidates = new ArrayList<>();
            List<Double> candidateAreas = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area >= imageArea * MIN_AREA_RATIO
                        && area <= imageArea * MAX_AREA_RATIO) {
                    candidates.add(contour);
                    candidateAreas.add(area);
                }
            }
            // 候选数量小，插入排序按面积降序即可。
            for (int i = 1; i < candidates.size(); i++) {
                MatOfPoint contour = candidates.get(i);
                double area = candidateAreas.get(i);
                int j = i - 1;
                while (j >= 0 && candidateAreas.get(j) < area) {
                    candidates.set(j + 1, candidates.get(j));
                    candidateAreas.set(j + 1, candidateAreas.get(j));
                    j--;
                }
                candidates.set(j + 1, contour);
                candidateAreas.set(j + 1, area);
            }
            int tried = Math.min(candidates.size(), MAX_CONTOUR_CANDIDATES);
            for (int i = 0; i < tried; i++) {
                float[] quad = approxQuad(candidates.get(i), closed.cols(), closed.rows());
                if (quad != null) {
                    return new DetectionResult(quad, channel, quadArea(quad) / imageArea);
                }
            }
            Log.d(TAG, channel + " 未逼近出有效四边形（候选轮廓数="
                    + candidates.size() + "/" + contours.size() + "）");
            return null;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
        }
    }

    /**
     * 对单个轮廓按 {@link #EPSILON_FACTORS} 多档 epsilon 做 approxPolyDP，
     * 返回第一个通过 {@link #isValidApproxQuad} 校验的四边形（8 个浮点，无序），否则 null。
     */
    private static float[] approxQuad(MatOfPoint contour, int width, int height) {
        MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
        MatOfPoint2f approx = new MatOfPoint2f();
        try {
            double perimeter = Imgproc.arcLength(curve, true);
            for (double factor : EPSILON_FACTORS) {
                Imgproc.approxPolyDP(curve, approx, factor * perimeter, true);
                Point[] points = approx.toArray();
                if (points.length != 4) continue;
                float[] corners = new float[8];
                for (int index = 0; index < 4; index++) {
                    corners[index * 2] = (float) points[index].x;
                    corners[index * 2 + 1] = (float) points[index].y;
                }
                if (isValidApproxQuad(corners, width, height)) return corners;
            }
            return null;
        } finally {
            curve.release();
            approx.release();
        }
    }

    /** 非四边形轮廓的兜底：最小外接旋转矩形四角（兼容 boxPoints 的两种输出布局）。 */
    private static float[] minAreaRectCorners(MatOfPoint contour) {
        MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
        Mat boxPoints = new Mat();
        try {
            RotatedRect box = Imgproc.minAreaRect(curve);
            Imgproc.boxPoints(box, boxPoints);
            float[] corners = new float[8];
            // boxPoints 输出布局随 OpenCV 版本不同：4x1 CV_32FC2 或 4x2 CV_32FC1，两种都兼容。
            boolean twoChannel = boxPoints.channels() == 2;
            for (int index = 0; index < 4; index++) {
                double x;
                double y;
                if (twoChannel) {
                    double[] xy = boxPoints.get(index, 0);
                    x = xy[0];
                    y = xy[1];
                } else {
                    x = boxPoints.get(index, 0)[0];
                    y = boxPoints.get(index, 1)[0];
                }
                corners[index * 2] = (float) x;
                corners[index * 2 + 1] = (float) y;
            }
            return corners;
        } finally {
            curve.release();
            boxPoints.release();
        }
    }

    /** 四边形面积（鞋带公式，corners 为无序四角但保持多边形环绕序）。 */
    private static double quadArea(float[] corners) {
        double area = 0;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            area += corners[index * 2] * (double) corners[next * 2 + 1]
                    - corners[next * 2] * (double) corners[index * 2 + 1];
        }
        return Math.abs(area) / 2;
    }

    /** 通道检测结果：无序四角 + 通道名 + 四边形面积占比（供日志与后续校验）。 */
    private static final class DetectionResult {
        final float[] corners;
        final String channel;
        final double quadAreaRatio;

        DetectionResult(float[] corners, String channel, double quadAreaRatio) {
            this.corners = corners;
            this.channel = channel;
            this.quadAreaRatio = quadAreaRatio;
        }
    }

    /** 角点排序为 左上、右上、右下、左下；纯几何逻辑，供 JVM 单测。 */
    static float[] sortQuadCorners(float[] corners) {
        float[] ordered = new float[8];
        int topLeft = 0;
        int bottomRight = 0;
        int topRight = 0;
        int bottomLeft = 0;
        for (int index = 1; index < 4; index++) {
            float sum = corners[index * 2] + corners[index * 2 + 1];
            float diff = corners[index * 2] - corners[index * 2 + 1];
            if (sum < corners[topLeft * 2] + corners[topLeft * 2 + 1]) topLeft = index;
            if (sum > corners[bottomRight * 2] + corners[bottomRight * 2 + 1]) {
                bottomRight = index;
            }
            if (diff > corners[topRight * 2] - corners[topRight * 2 + 1]) topRight = index;
            if (diff < corners[bottomLeft * 2] - corners[bottomLeft * 2 + 1]) {
                bottomLeft = index;
            }
        }
        int[] order = {topLeft, topRight, bottomRight, bottomLeft};
        for (int index = 0; index < 4; index++) {
            ordered[index * 2] = corners[order[index] * 2];
            ordered[index * 2 + 1] = corners[order[index] * 2 + 1];
        }
        return ordered;
    }

    /**
     * approxPolyDP 四边形采纳校验：排序后须为凸四边形、面积占比在
     * [MIN_QUAD_AREA_RATIO, MAX_QUAD_AREA_RATIO]、角点在界内 30% 容差内。
     * 纯几何逻辑，供 JVM 单测；corners 为无序四角。
     */
    static boolean isValidApproxQuad(float[] corners, int imageWidth, int imageHeight) {
        if (corners == null || corners.length != 8) return false;
        return isUsableQuad(sortQuadCorners(corners), imageWidth, imageHeight);
    }

    /**
     * 四边形有效性校验：角点不超出 ROI 太多、凸四边形、边长与面积占比合理。
     * 纯几何逻辑，供 JVM 单测；ordered 须为 {@link #sortQuadCorners} 的输出。
     */
    static boolean isUsableQuad(float[] ordered, int imageWidth, int imageHeight) {
        if (ordered == null || ordered.length != 8 || imageWidth <= 0 || imageHeight <= 0) {
            return false;
        }
        float marginX = imageWidth * 0.3f;
        float marginY = imageHeight * 0.3f;
        for (int index = 0; index < 4; index++) {
            float x = ordered[index * 2];
            float y = ordered[index * 2 + 1];
            if (!Float.isFinite(x) || !Float.isFinite(y)
                    || x < -marginX || x > imageWidth + marginX
                    || y < -marginY || y > imageHeight + marginY) {
                return false;
            }
        }
        double crossSign = 0;
        double area = 0;
        double minSide = Math.min(imageWidth, imageHeight) * 0.12;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            int after = (index + 2) % 4;
            double edgeX = ordered[next * 2] - ordered[index * 2];
            double edgeY = ordered[next * 2 + 1] - ordered[index * 2 + 1];
            if (Math.hypot(edgeX, edgeY) < minSide) return false;
            double turnX = ordered[after * 2] - ordered[next * 2];
            double turnY = ordered[after * 2 + 1] - ordered[next * 2 + 1];
            double cross = edgeX * turnY - edgeY * turnX;
            if (Math.abs(cross) < 1e-6) return false;
            if (crossSign == 0) {
                crossSign = Math.signum(cross);
            } else if (Math.signum(cross) != crossSign) {
                return false; // 凹四边形或自交，排序/逼近不可信
            }
            area += ordered[index * 2] * (double) ordered[next * 2 + 1]
                    - ordered[next * 2] * (double) ordered[index * 2 + 1];
        }
        area = Math.abs(area) / 2;
        double imageArea = imageWidth * (double) imageHeight;
        return area >= imageArea * MIN_QUAD_AREA_RATIO
                && area <= imageArea * MAX_QUAD_AREA_RATIO;
    }

    private static double distance(float[] points, int from, int to) {
        return Math.hypot(points[to * 2] - points[from * 2],
                points[to * 2 + 1] - points[from * 2 + 1]);
    }
}

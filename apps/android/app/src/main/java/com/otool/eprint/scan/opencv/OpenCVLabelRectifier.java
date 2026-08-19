package com.otool.eprint.scan.opencv;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 标签四点透视纠偏：在引导框 ROI 内定位纸张轮廓，只接受具有标签证据的真实凸四边形；
 * 轮廓不是四边形或候选冲突时返回 null，由调用方执行无损的旋转/整框回退。
 * 检测按多通道尝试（Otsu 亮区 → Otsu 反相 → HSV 白卡 → Canny 边缘 → 自适应阈值），
 * 每个通道内按面积降序尝试前若干轮廓、多档 approxPolyDP epsilon，所有通道候选统一按
 * 纸面、边缘、内容和贴边风险评分。任何一步失败（OpenCV 不可用、无高置信度轮廓、
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
    /** 只有标签证据充分、且明显优于次优候选时才允许破坏性透视裁剪。 */
    private static final double MIN_PERSPECTIVE_SCORE = 0.55;
    private static final double MIN_RUNNER_UP_GAP = 0.05;

    /** 可诊断的纠偏结果；ROTATED_RECT 仅表示旋转降级，不冒充透视成功。 */
    public static final class RectificationResult {
        public final Bitmap bitmap;
        public final float[] sourceQuad;
        public final String method;
        public final double confidence;
        public final double runnerUpGap;
        public final boolean perspectiveFixed;

        RectificationResult(Bitmap bitmap, float[] sourceQuad, String method,
                            double confidence, double runnerUpGap,
                            boolean perspectiveFixed) {
            this.bitmap = bitmap;
            this.sourceQuad = sourceQuad;
            this.method = method;
            this.confidence = confidence;
            this.runnerUpGap = runnerUpGap;
            this.perspectiveFixed = perspectiveFixed;
        }

        public String summary() {
            return method + " · 置信度 "
                    + Math.round(confidence * 100) + "%"
                    + (perspectiveFixed ? " · 四边形透视" : " · 仅旋转降级");
        }
    }

    private OpenCVLabelRectifier() {}

    /** 尝试透视纠偏；失败返回 null，调用方回退原 Java 旋正/裁剪路径。 */
    public static Bitmap rectify(Bitmap roi) {
        RectificationResult result = rectifyDetailed(roi);
        return result == null ? null : result.bitmap;
    }

    /** 尝试透视纠偏并返回角点、方法与置信度，供 UI 和回归日志核对。 */
    public static RectificationResult rectifyDetailed(Bitmap roi) {
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

    private static RectificationResult rectifyInternal(Bitmap roi) {
        Mat source = new Mat();
        Mat analysis = new Mat();
        Mat gray = new Mat();
        Mat blurred = new Mat();
        Mat scoreEdges = new Mat();
        Mat scoreEdgesDilated = new Mat();
        Mat fineEdges = new Mat();
        Mat strictPaperMask = new Mat();
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
            Imgproc.Canny(blurred, scoreEdges, 50, 150);
            Imgproc.Canny(blurred, fineEdges, 30, 100);
            Mat edgeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.dilate(scoreEdges, scoreEdgesDilated, edgeKernel);
            edgeKernel.release();
            buildStrictPaperMask(analysis, strictPaperMask);

            // 所有通道都参与候选生成，禁止“第一个合法即胜出”。透明袋/桌面的大亮区
            // 经常先于真正白标签形成凸四边形，必须用标签证据统一评分。
            List<DetectionResult> candidates = new ArrayList<>();
            candidates.addAll(detectOtsuCandidates(blurred, false));
            candidates.addAll(detectOtsuCandidates(blurred, true));
            candidates.addAll(detectWhiteCardStrictCandidates(analysis));
            candidates.addAll(detectWhiteCardCandidates(analysis));
            candidates.addAll(detectCannyCandidates(blurred));
            candidates.addAll(detectAdaptiveCandidates(blurred));
            candidates = deduplicateCandidates(candidates, analysis.cols(), analysis.rows());
            for (DetectionResult candidate : candidates) {
                scoreCandidate(candidate, strictPaperMask, scoreEdges,
                        scoreEdgesDilated, analysis.cols(), analysis.rows());
            }
            candidates.sort((left, right) -> Double.compare(right.score, left.score));

            DetectionResult detection = candidates.isEmpty() ? null : candidates.get(0);
            double runnerUpScore = candidates.size() > 1 ? candidates.get(1).score : 0.0;
            double runnerUpGap = detection == null ? 0.0 : detection.score - runnerUpScore;
            logCandidates(candidates);
            if (!isConfidentPerspective(detection == null ? 0 : detection.score,
                    runnerUpScore, candidates.size())) {
                Log.w(TAG, "无高置信度真四边形，保留无损回退；best="
                        + (detection == null ? "none"
                        : detection.channel + "/" + format(detection.score))
                        + " gap=" + format(runnerUpGap));
                return null;
            }
            float[] refinedCorners = refineQuadWithLines(
                    detection.corners, fineEdges, strictPaperMask,
                    analysis.cols(), analysis.rows());
            boolean lineRefined = refinedCorners != null;
            float[] corners = expandQuad(lineRefined ? refinedCorners : detection.corners,
                    analysis.cols(), analysis.rows(), 0.025f);
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
            String method = detection.channel + (lineRefined ? "+edge-lines" : "");
            Log.i(TAG, method + " 胜出：score=" + format(detection.score)
                    + " gap=" + format(runnerUpGap) + "，四边形面积占比 "
                    + String.format(Locale.US, "%.3f", detection.quadAreaRatio)
                    + "，输出 " + (int) outputWidth + "x" + (int) outputHeight);
            float[] fullResolutionQuad = ordered.clone();
            return new RectificationResult(output, fullResolutionQuad,
                    method, detection.score, runnerUpGap, true);
        } finally {
            source.release();
            analysis.release();
            gray.release();
            blurred.release();
            scoreEdges.release();
            scoreEdgesDilated.release();
            fineEdges.release();
            strictPaperMask.release();
            if (transform != null) transform.release();
            if (warped != null) warped.release();
        }
    }

    /** Otsu 阈值通道：invert=false 取亮区（纸张通常比背景亮），invert=true 取反相（深色标签/托盘）。 */
    private static List<DetectionResult> detectOtsuCandidates(Mat blurred, boolean invert) {
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            Imgproc.threshold(blurred, binary, 0, 255,
                    (invert ? Imgproc.THRESH_BINARY_INV : Imgproc.THRESH_BINARY)
                            | Imgproc.THRESH_OTSU);
            kernel = closeKernel(blurred);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel);
            return findQuads(closed, invert ? "otsu-inv" : "otsu-bright");
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
    private static List<DetectionResult> detectWhiteCardCandidates(Mat analysisRgba) {
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
            return findQuads(closed, "white-card");
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

    /**
     * 严格白卡通道：V≥200、S<80 只保留"纸白"区域。宽松白卡（V≥140）会把塑料袋高光、
     * 米色桌面一并连通，导致整张 ROI 粘成一块、各通道都逼近不出四边形（2026-08-19 电阻
     * 标签实拍复现）。严格阈值下标签卡通常能独立成块；只采纳轮廓产生的真实四边形，
     * 不再用旋转矩形冒充透视角点。
     */
    private static List<DetectionResult> detectWhiteCardStrictCandidates(Mat analysisRgba) {
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
            Core.inRange(hsv, new Scalar(0, 0, 200), new Scalar(180, 80, 255), mask);
            int openSize = Math.max(3, Math.min(mask.cols(), mask.rows()) / 130) | 1;
            openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(openSize, openSize));
            Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, openKernel);
            closeKernel = closeKernel(mask);
            Imgproc.morphologyEx(opened, closed, Imgproc.MORPH_CLOSE, closeKernel);
            return findQuads(closed, "white-card-strict");
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

    /** 与严格白卡通道一致的纸面信号，供跨通道候选统一评分。 */
    private static void buildStrictPaperMask(Mat analysisRgba, Mat output) {
        Mat rgb = new Mat();
        Mat hsv = new Mat();
        Mat raw = new Mat();
        Mat kernel = null;
        try {
            Imgproc.cvtColor(analysisRgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv, new Scalar(0, 0, 200), new Scalar(180, 80, 255), raw);
            int openSize = Math.max(3, Math.min(raw.cols(), raw.rows()) / 130) | 1;
            kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, new Size(openSize, openSize));
            Imgproc.morphologyEx(raw, output, Imgproc.MORPH_OPEN, kernel);
        } finally {
            rgb.release();
            hsv.release();
            raw.release();
            if (kernel != null) kernel.release();
        }
    }

    /** Canny 边缘通道：亮度相近的标签卡与白色软管仍能被边缘分开。 */
    private static List<DetectionResult> detectCannyCandidates(Mat blurred) {
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
            return findQuads(closed, "canny");
        } finally {
            edges.release();
            dilated.release();
            closed.release();
            if (dilateKernel != null) dilateKernel.release();
            if (closeKernel != null) closeKernel.release();
        }
    }

    /** 自适应阈值通道：对光照不均、局部对比度场景更稳健。 */
    private static List<DetectionResult> detectAdaptiveCandidates(Mat blurred) {
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = null;
        try {
            int blockSize = Math.max(3, Math.min(blurred.cols(), blurred.rows()) / 20) | 1;
            Imgproc.adaptiveThreshold(blurred, binary, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, 5);
            kernel = closeKernel(blurred);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel);
            return findQuads(closed, "adaptive");
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
    private static List<DetectionResult> findQuads(Mat closed, String channel) {
        List<MatOfPoint> contours = new ArrayList<>();
        List<DetectionResult> results = new ArrayList<>();
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
                    results.add(new DetectionResult(quad, channel,
                            quadArea(quad) / imageArea));
                }
            }
            if (results.isEmpty()) {
                Log.d(TAG, channel + " 未逼近出有效四边形（候选轮廓数="
                        + candidates.size() + "/" + contours.size() + "）");
            }
            return results;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
        }
    }

    /** 跨通道近似相同的四边形只保留一个，避免同一标签占据冠亚军导致分差误判。 */
    private static List<DetectionResult> deduplicateCandidates(
            List<DetectionResult> candidates, int width, int height) {
        List<DetectionResult> unique = new ArrayList<>();
        double diagonal = Math.hypot(width, height);
        for (DetectionResult candidate : candidates) {
            int duplicateIndex = -1;
            for (int index = 0; index < unique.size(); index++) {
                DetectionResult existing = unique.get(index);
                if (Math.abs(existing.quadAreaRatio - candidate.quadAreaRatio) <= 0.08
                        && averageCornerDistance(existing.corners, candidate.corners)
                        <= diagonal * 0.05) {
                    duplicateIndex = index;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                unique.add(candidate);
            } else if (channelPrior(candidate.channel)
                    > channelPrior(unique.get(duplicateIndex).channel)) {
                unique.set(duplicateIndex, candidate);
            }
        }
        return unique;
    }

    private static double averageCornerDistance(float[] left, float[] right) {
        float[] orderedLeft = sortQuadCorners(left);
        float[] orderedRight = sortQuadCorners(right);
        double total = 0;
        for (int index = 0; index < 4; index++) {
            total += Math.hypot(orderedLeft[index * 2] - orderedRight[index * 2],
                    orderedLeft[index * 2 + 1] - orderedRight[index * 2 + 1]);
        }
        return total / 4.0;
    }

    private static double channelPrior(String channel) {
        if (channel.startsWith("white-card-strict")) return 5;
        if (channel.startsWith("white-card")) return 4;
        if (channel.startsWith("canny")) return 3;
        if (channel.startsWith("adaptive")) return 2;
        return 1;
    }

    /**
     * 标签证据评分：纸面纯度/内外对比、四边边缘支撑、内部文字/码区边缘密度、
     * 面积与贴边风险。面积只作弱先验，禁止“大轮廓天然胜出”。
     */
    private static void scoreCandidate(DetectionResult candidate, Mat strictPaperMask,
                                       Mat scoreEdges, Mat scoreEdgesDilated,
                                       int width, int height) {
        float[] ordered = sortQuadCorners(candidate.corners);
        Mat polygonMask = Mat.zeros(height, width, CvType.CV_8UC1);
        Mat signalInside = new Mat();
        Mat edgeInside = new Mat();
        Mat dilatedPolygon = new Mat();
        Mat ring = new Mat();
        Mat signalRing = new Mat();
        Mat ringKernel = null;
        MatOfPoint polygon = new MatOfPoint(
                new Point(ordered[0], ordered[1]), new Point(ordered[2], ordered[3]),
                new Point(ordered[4], ordered[5]), new Point(ordered[6], ordered[7]));
        try {
            Imgproc.fillConvexPoly(polygonMask, polygon, new Scalar(255));
            double insideArea = Math.max(1, Core.countNonZero(polygonMask));
            Core.bitwise_and(strictPaperMask, polygonMask, signalInside);
            candidate.paperFill = Core.countNonZero(signalInside) / insideArea;
            Core.bitwise_and(scoreEdges, polygonMask, edgeInside);
            candidate.contentDensity = Core.countNonZero(edgeInside) / insideArea;

            int ringSize = Math.max(7, Math.min(width, height) / 45) | 1;
            ringKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, new Size(ringSize, ringSize));
            Imgproc.dilate(polygonMask, dilatedPolygon, ringKernel);
            Core.subtract(dilatedPolygon, polygonMask, ring);
            double ringArea = Math.max(1, Core.countNonZero(ring));
            Core.bitwise_and(strictPaperMask, ring, signalRing);
            double outsidePaperFill = Core.countNonZero(signalRing) / ringArea;
            double paperContrast = Math.max(0, candidate.paperFill - outsidePaperFill);

            candidate.edgeSupport = edgeSupport(ordered, scoreEdgesDilated, width, height);
            candidate.borderTouch = borderTouchRatio(ordered, width, height);
            double areaQuality = areaQuality(candidate.quadAreaRatio);
            candidate.score = candidateScore(candidate.channel, candidate.paperFill,
                    paperContrast, candidate.edgeSupport, candidate.contentDensity,
                    areaQuality, candidate.borderTouch);
        } finally {
            polygon.release();
            polygonMask.release();
            signalInside.release();
            edgeInside.release();
            dilatedPolygon.release();
            ring.release();
            signalRing.release();
            if (ringKernel != null) ringKernel.release();
        }
    }

    private static double edgeSupport(float[] ordered, Mat dilatedEdges,
                                      int width, int height) {
        double total = 0;
        for (int side = 0; side < 4; side++) {
            int next = (side + 1) % 4;
            double x0 = ordered[side * 2];
            double y0 = ordered[side * 2 + 1];
            double x1 = ordered[next * 2];
            double y1 = ordered[next * 2 + 1];
            int samples = Math.max(24, Math.min(220,
                    (int) Math.round(Math.hypot(x1 - x0, y1 - y0))));
            int supported = 0;
            for (int sample = 0; sample < samples; sample++) {
                double t = samples == 1 ? 0 : sample / (double) (samples - 1);
                int x = Math.max(0, Math.min(width - 1,
                        (int) Math.round(x0 + (x1 - x0) * t)));
                int y = Math.max(0, Math.min(height - 1,
                        (int) Math.round(y0 + (y1 - y0) * t)));
                double[] value = dilatedEdges.get(y, x);
                if (value != null && value.length > 0 && value[0] > 0) supported++;
            }
            total += supported / (double) samples;
        }
        return total / 4.0;
    }

    private static double borderTouchRatio(float[] ordered, int width, int height) {
        double marginX = width * 0.02;
        double marginY = height * 0.02;
        int touched = 0;
        for (int index = 0; index < 4; index++) {
            double x = ordered[index * 2];
            double y = ordered[index * 2 + 1];
            if (x <= marginX || x >= width - marginX
                    || y <= marginY || y >= height - marginY) touched++;
        }
        return touched / 4.0;
    }

    private static double areaQuality(double ratio) {
        if (ratio <= 0.70) return 1.0;
        return Math.max(0.20, 1.0 - (ratio - 0.70) / 0.28 * 0.80);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** 纯几何/统计评分收口，供 JVM 锁定“真标签胜过外部大亮区”的关键回归。 */
    static double candidateScore(String channel, double paperFill, double paperContrast,
                                 double edgeSupport, double contentDensity,
                                 double areaQuality, double borderTouch) {
        double channelBonus = channel != null && channel.startsWith("white-card-strict") ? 0.08
                : channel != null && channel.startsWith("white-card") ? 0.04
                : channel != null && channel.startsWith("canny") ? 0.02
                : channel != null && channel.startsWith("adaptive") ? 0.01 : 0.0;
        return clamp01(
                0.24 * clamp01(paperFill)
                        + 0.18 * clamp01(paperContrast / 0.60)
                        + 0.22 * clamp01(edgeSupport)
                        + 0.20 * clamp01(contentDensity / 0.08)
                        + 0.08 * clamp01(areaQuality)
                        + channelBonus
                        - 0.25 * clamp01(borderTouch));
    }

    static boolean isConfidentPerspective(double bestScore, double runnerUpScore,
                                           int candidateCount) {
        if (bestScore < MIN_PERSPECTIVE_SCORE) return false;
        return candidateCount <= 1 || bestScore - runnerUpScore >= MIN_RUNNER_UP_GAP;
    }

    /** 从质心向外扩安全边距，防角点轻微内缩时切到标签边缘文字。 */
    static float[] expandQuad(float[] corners, int width, int height, float ratio) {
        float[] ordered = sortQuadCorners(corners);
        float centerX = 0;
        float centerY = 0;
        for (int index = 0; index < 4; index++) {
            centerX += ordered[index * 2];
            centerY += ordered[index * 2 + 1];
        }
        centerX /= 4f;
        centerY /= 4f;
        float[] expanded = new float[8];
        float scale = 1f + Math.max(0f, ratio);
        for (int index = 0; index < 4; index++) {
            float x = centerX + (ordered[index * 2] - centerX) * scale;
            float y = centerY + (ordered[index * 2 + 1] - centerY) * scale;
            expanded[index * 2] = Math.max(0, Math.min(width - 1, x));
            expanded[index * 2 + 1] = Math.max(0, Math.min(height - 1, y));
        }
        return expanded;
    }

    /**
     * 在初始轮廓四边附近寻找有“内侧纸白、外侧非纸白”证据的长直线，四条独立边求交。
     * 可修复透明袋高光把某个轮廓角向外拖拽的情况；任一边证据不足时原样放弃精修。
     */
    private static float[] refineQuadWithLines(float[] initialCorners, Mat fineEdges,
                                               Mat paperMask, int width, int height) {
        float[] ordered = sortQuadCorners(initialCorners);
        Mat lines = new Mat();
        try {
            int minimumLineLength = Math.max(35, Math.min(width, height) / 12);
            Imgproc.HoughLinesP(fineEdges, lines, 1, Math.PI / 720.0,
                    18, minimumLineLength, Math.max(18, Math.min(width, height) / 20));
            List<LineModel> segments = readLineModels(lines);
            if (segments.isEmpty()) return null;
            double centerX = 0;
            double centerY = 0;
            for (int index = 0; index < 4; index++) {
                centerX += ordered[index * 2];
                centerY += ordered[index * 2 + 1];
            }
            centerX /= 4.0;
            centerY /= 4.0;
            LineModel[] sides = new LineModel[4];
            for (int side = 0; side < 4; side++) {
                int next = (side + 1) % 4;
                sides[side] = selectBoundaryLine(segments, paperMask,
                        ordered[side * 2], ordered[side * 2 + 1],
                        ordered[next * 2], ordered[next * 2 + 1],
                        centerX, centerY, width, height);
                if (sides[side] == null) return null;
            }
            float[] refined = new float[8];
            float[] topLeft = intersectInfiniteLines(sides[3].coordinates(), sides[0].coordinates());
            float[] topRight = intersectInfiniteLines(sides[0].coordinates(), sides[1].coordinates());
            float[] bottomRight = intersectInfiniteLines(sides[1].coordinates(), sides[2].coordinates());
            float[] bottomLeft = intersectInfiniteLines(sides[2].coordinates(), sides[3].coordinates());
            if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
                return null;
            }
            float[][] points = {topLeft, topRight, bottomRight, bottomLeft};
            double diagonal = Math.hypot(width, height);
            for (int index = 0; index < 4; index++) {
                refined[index * 2] = points[index][0];
                refined[index * 2 + 1] = points[index][1];
                if (Math.hypot(refined[index * 2] - ordered[index * 2],
                        refined[index * 2 + 1] - ordered[index * 2 + 1])
                        > diagonal * 0.18) {
                    return null;
                }
            }
            if (!isUsableQuad(refined, width, height)) return null;
            Log.i(TAG, "边线精修成功：top=" + sides[0].shortSummary()
                    + " right=" + sides[1].shortSummary()
                    + " bottom=" + sides[2].shortSummary()
                    + " left=" + sides[3].shortSummary());
            return refined;
        } finally {
            lines.release();
        }
    }

    private static List<LineModel> readLineModels(Mat lines) {
        List<LineModel> result = new ArrayList<>();
        if (lines.empty()) return result;
        if (lines.channels() >= 4) {
            for (int row = 0; row < lines.rows(); row++) {
                for (int col = 0; col < lines.cols(); col++) {
                    double[] value = lines.get(row, col);
                    if (value != null && value.length >= 4) {
                        result.add(new LineModel(value[0], value[1], value[2], value[3]));
                    }
                }
            }
        } else if (lines.cols() >= 4) {
            for (int row = 0; row < lines.rows(); row++) {
                double[] value = new double[4];
                boolean complete = true;
                for (int col = 0; col < 4; col++) {
                    double[] cell = lines.get(row, col);
                    if (cell == null || cell.length == 0) {
                        complete = false;
                        break;
                    }
                    value[col] = cell[0];
                }
                if (complete) result.add(new LineModel(value[0], value[1], value[2], value[3]));
            }
        }
        return result;
    }

    private static LineModel selectBoundaryLine(List<LineModel> segments, Mat paperMask,
                                                double x0, double y0, double x1, double y1,
                                                double centerX, double centerY,
                                                int width, int height) {
        double sideX = x1 - x0;
        double sideY = y1 - y0;
        double sideLength = Math.hypot(sideX, sideY);
        if (sideLength < 1) return null;
        double unitX = sideX / sideLength;
        double unitY = sideY / sideLength;
        double maxDistance = Math.min(width, height) * 0.16;
        LineModel best = null;
        double bestScore = -Double.MAX_VALUE;
        for (LineModel line : segments) {
            double orientationCosine = Math.abs(unitX * line.unitX + unitY * line.unitY);
            double angleDifference = Math.toDegrees(Math.acos(
                    Math.max(-1, Math.min(1, orientationCosine))));
            if (angleDifference > 20.0) continue;
            double midpointX = (line.x0 + line.x1) / 2.0;
            double midpointY = (line.y0 + line.y1) / 2.0;
            double deltaX = midpointX - x0;
            double deltaY = midpointY - y0;
            double lineDistance = Math.abs(unitX * deltaY - unitY * deltaX);
            double projection = (deltaX * unitX + deltaY * unitY) / sideLength;
            if (lineDistance > maxDistance || projection < -0.30 || projection > 1.30) continue;
            double contrast = linePaperContrast(
                    line, paperMask, centerX, centerY, width, height);
            if (contrast < 0.08) continue;
            double score = line.length * (0.35 + contrast) - lineDistance * 0.40;
            if (score > bestScore) {
                bestScore = score;
                best = line.withEvidence(score, contrast);
            }
        }
        return best;
    }

    private static double linePaperContrast(LineModel line, Mat paperMask,
                                            double centerX, double centerY,
                                            int width, int height) {
        double normalX = -line.unitY;
        double normalY = line.unitX;
        double midpointX = (line.x0 + line.x1) / 2.0;
        double midpointY = (line.y0 + line.y1) / 2.0;
        if ((centerX - midpointX) * normalX + (centerY - midpointY) * normalY < 0) {
            normalX = -normalX;
            normalY = -normalY;
        }
        double bestContrast = -1;
        int samples = Math.max(12, Math.min(120, (int) Math.round(line.length / 4.0)));
        int[] offsets = {5, 9, 13};
        for (int offset : offsets) {
            int inside = 0;
            int outside = 0;
            int insideCount = 0;
            int outsideCount = 0;
            for (int sample = 0; sample < samples; sample++) {
                double t = 0.08 + 0.84 * sample / Math.max(1.0, samples - 1.0);
                double baseX = line.x0 + (line.x1 - line.x0) * t;
                double baseY = line.y0 + (line.y1 - line.y0) * t;
                int insideX = (int) Math.round(baseX + normalX * offset);
                int insideY = (int) Math.round(baseY + normalY * offset);
                int outsideX = (int) Math.round(baseX - normalX * offset);
                int outsideY = (int) Math.round(baseY - normalY * offset);
                if (insideX >= 0 && insideX < width && insideY >= 0 && insideY < height) {
                    double[] value = paperMask.get(insideY, insideX);
                    if (value != null && value.length > 0 && value[0] > 0) inside++;
                    insideCount++;
                }
                if (outsideX >= 0 && outsideX < width
                        && outsideY >= 0 && outsideY < height) {
                    double[] value = paperMask.get(outsideY, outsideX);
                    if (value != null && value.length > 0 && value[0] > 0) outside++;
                    outsideCount++;
                }
            }
            double insideRatio = inside / (double) Math.max(1, insideCount);
            double outsideRatio = outside / (double) Math.max(1, outsideCount);
            bestContrast = Math.max(bestContrast, insideRatio - outsideRatio);
        }
        return bestContrast;
    }

    /** 两条无限直线求交；每条格式为 x0,y0,x1,y1，平行时返回 null。 */
    static float[] intersectInfiniteLines(float[] first, float[] second) {
        if (first == null || second == null || first.length != 4 || second.length != 4) {
            return null;
        }
        double ax = first[2] - first[0];
        double ay = first[3] - first[1];
        double bx = second[2] - second[0];
        double by = second[3] - second[1];
        double denominator = ax * by - ay * bx;
        if (Math.abs(denominator) < 1e-6) return null;
        double deltaX = second[0] - first[0];
        double deltaY = second[1] - first[1];
        double t = (deltaX * by - deltaY * bx) / denominator;
        return new float[]{(float) (first[0] + t * ax), (float) (first[1] + t * ay)};
    }

    private static final class LineModel {
        final double x0;
        final double y0;
        final double x1;
        final double y1;
        final double unitX;
        final double unitY;
        final double length;
        final double score;
        final double contrast;

        LineModel(double x0, double y0, double x1, double y1) {
            this(x0, y0, x1, y1, 0, 0);
        }

        LineModel(double x0, double y0, double x1, double y1,
                  double score, double contrast) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.length = Math.max(1e-6, Math.hypot(x1 - x0, y1 - y0));
            this.unitX = (x1 - x0) / length;
            this.unitY = (y1 - y0) / length;
            this.score = score;
            this.contrast = contrast;
        }

        LineModel withEvidence(double newScore, double newContrast) {
            return new LineModel(x0, y0, x1, y1, newScore, newContrast);
        }

        float[] coordinates() {
            return new float[]{(float) x0, (float) y0, (float) x1, (float) y1};
        }

        String shortSummary() {
            return "L" + Math.round(length) + "/c" + format(contrast);
        }
    }

    private static void logCandidates(List<DetectionResult> candidates) {
        int count = Math.min(8, candidates.size());
        for (int index = 0; index < count; index++) {
            DetectionResult value = candidates.get(index);
            Log.i(TAG, "候选#" + (index + 1) + " " + value.channel
                    + " score=" + format(value.score)
                    + " area=" + format(value.quadAreaRatio)
                    + " paper=" + format(value.paperFill)
                    + " edge=" + format(value.edgeSupport)
                    + " content=" + format(value.contentDensity)
                    + " border=" + format(value.borderTouch));
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.3f", value);
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
        double paperFill;
        double contentDensity;
        double edgeSupport;
        double borderTouch;
        double score;

        DetectionResult(float[] corners, String channel, double quadAreaRatio) {
            this.corners = corners;
            this.channel = channel;
            this.quadAreaRatio = quadAreaRatio;
        }
    }

    /** 角点排序为 左上、右上、右下、左下；纯几何逻辑，供 JVM 单测。 */
    static float[] sortQuadCorners(float[] corners) {
        if (corners == null || corners.length != 8) return corners;
        float centerX = 0;
        float centerY = 0;
        for (int index = 0; index < 4; index++) {
            centerX += corners[index * 2];
            centerY += corners[index * 2 + 1];
        }
        centerX /= 4f;
        centerY /= 4f;
        int[] around = {0, 1, 2, 3};
        // 四点按相对质心极角递增排列；屏幕坐标 y 向下时即 TL->TR->BR->BL 环向。
        for (int i = 1; i < around.length; i++) {
            int value = around[i];
            double angle = Math.atan2(corners[value * 2 + 1] - centerY,
                    corners[value * 2] - centerX);
            int j = i - 1;
            while (j >= 0 && Math.atan2(corners[around[j] * 2 + 1] - centerY,
                    corners[around[j] * 2] - centerX) > angle) {
                around[j + 1] = around[j];
                j--;
            }
            around[j + 1] = value;
        }
        int start = 0;
        for (int index = 1; index < 4; index++) {
            int current = around[index];
            int best = around[start];
            if (corners[current * 2] + corners[current * 2 + 1]
                    < corners[best * 2] + corners[best * 2 + 1]) {
                start = index;
            }
        }
        int[] order = new int[4];
        for (int index = 0; index < 4; index++) {
            order[index] = around[(start + index) % 4];
        }
        float[] ordered = new float[8];
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

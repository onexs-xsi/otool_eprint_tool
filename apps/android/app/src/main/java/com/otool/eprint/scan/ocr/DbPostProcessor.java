package com.otool.eprint.scan.ocr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * DBNet 检测后处理（纯 JVM，可单测）：概率图 → 二值化 → 连通域 →
 * 凸包 + 旋转卡壳求最小外接矩形 → unclip 外扩 → 文本行四边形（检测图坐标系）。
 * 四边形的 unclip 直接等价于沿两轴各外扩 distance（distance = 面积·ratio/周长），
 * 不引入 clipper 依赖。算法与 RapidOCR（Apache-2.0）的 DB 后处理流程一致。
 */
public final class DbPostProcessor {

    /** 文本行框：points 为 {tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y}。 */
    public static final class Box {
        public final float[] points;
        /** 框内平均概率，0~1。 */
        public final float score;

        Box(float[] points, float score) {
            this.points = points;
            this.score = score;
        }

        /** 框的短边像素长（近似，用于过滤碎框）。 */
        public float shortSide() {
            float w1 = dist(points[0], points[1], points[2], points[3]);
            float w2 = dist(points[4], points[5], points[6], points[7]);
            float h1 = dist(points[2], points[3], points[4], points[5]);
            float h2 = dist(points[6], points[7], points[0], points[1]);
            return Math.min(Math.max(w1, w2), Math.max(h1, h2));
        }

        static float dist(float x1, float y1, float x2, float y2) {
            return (float) Math.hypot(x2 - x1, y2 - y1);
        }
    }

    private DbPostProcessor() {}

    /**
     * @param prob        检测模型输出的概率图（0~1，行主序，长度 = width*height）
     * @param threshold   二值化阈值（PaddleOCR 默认 0.3）
     * @param boxThreshold 框内平均概率下限（PaddleOCR 默认 0.5）
     * @param unclipRatio 外扩系数（PaddleOCR 默认 1.5）
     * @return 检测图坐标系下的文本行四边形，按从上到下、从左到右排序
     */
    public static List<Box> findBoxes(float[] prob, int width, int height,
                                      float threshold, float boxThreshold, float unclipRatio) {
        List<Box> boxes = new ArrayList<>();
        boolean[] visited = new boolean[width * height];
        for (int start = 0; start < prob.length; start++) {
            if (visited[start] || prob[start] < threshold) continue;
            int[] component = floodFill(prob, visited, width, height, start, threshold);
            if (component.length < 4) continue;
            float score = mean(prob, component);
            if (score < boxThreshold) continue;
            float[] rect = minAreaRect(component, width);
            if (rect == null) continue;
            float[] unclipped = unclip(rect, unclipRatio);
            clip(unclipped, width, height);
            Box box = new Box(unclipped, score);
            if (box.shortSide() < 3f) continue;
            boxes.add(box);
        }
        boxes.sort((a, b) -> {
            float dy = (a.points[1] + a.points[7]) / 2f - (b.points[1] + b.points[7]) / 2f;
            if (Math.abs(dy) > 1f) return Float.compare(dy, 0f);
            return Float.compare(a.points[0], b.points[0]);
        });
        return boxes;
    }

    /** 框坐标从检测图坐标系映射回原图坐标系（scale = 原图 / 检测图）。 */
    public static float[] scaleBox(float[] points, float scaleX, float scaleY) {
        float[] out = new float[points.length];
        for (int index = 0; index < points.length; index += 2) {
            out[index] = points[index] * scaleX;
            out[index + 1] = points[index + 1] * scaleY;
        }
        return out;
    }

    /** 四邻接 flood-fill，返回连通域像素的线性索引数组。 */
    private static int[] floodFill(float[] prob, boolean[] visited, int width, int height,
                                   int start, float threshold) {
        int[] indices = new int[64];
        int count = 0;
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);
        visited[start] = true;
        while (!stack.isEmpty()) {
            int pixel = stack.pop();
            if (count == indices.length) {
                int[] grown = new int[indices.length * 2];
                System.arraycopy(indices, 0, grown, 0, count);
                indices = grown;
            }
            indices[count++] = pixel;
            int x = pixel % width;
            int y = pixel / width;
            if (x > 0) stack = pushIf(prob, visited, width, pixel - 1, threshold, stack);
            if (x < width - 1) stack = pushIf(prob, visited, width, pixel + 1, threshold, stack);
            if (y > 0) stack = pushIf(prob, visited, width, pixel - width, threshold, stack);
            if (y < height - 1) stack = pushIf(prob, visited, width, pixel + width, threshold, stack);
        }
        int[] result = new int[count];
        System.arraycopy(indices, 0, result, 0, count);
        return result;
    }

    private static Deque<Integer> pushIf(float[] prob, boolean[] visited, int width,
                                         int pixel, float threshold, Deque<Integer> stack) {
        if (!visited[pixel] && prob[pixel] >= threshold) {
            visited[pixel] = true;
            stack.push(pixel);
        }
        return stack;
    }

    private static float mean(float[] prob, int[] component) {
        double sum = 0;
        for (int pixel : component) sum += prob[pixel];
        return (float) (sum / component.length);
    }

    /**
     * 最小外接矩形：凸包（单调链）+ 旋转卡壳。
     * @return 8 元素数组 {tl, tr, br, bl}，退化（共线/单点）时返回 null。
     */
    static float[] minAreaRect(int[] component, int width) {
        float[][] hull = convexHull(component, width);
        if (hull.length < 3) {
            if (hull.length == 2) {
                // 退化线段：以 1px 厚度补成矩形。
                float x1 = hull[0][0], y1 = hull[0][1], x2 = hull[1][0], y2 = hull[1][1];
                return orderCorners(new float[]{x1, y1, x2, y1, x2, y2 + 1, x1, y2 + 1});
            }
            return null;
        }
        float bestArea = Float.MAX_VALUE;
        float bestUx = 1, bestUy = 0, bestMinU = 0, bestMaxU = 0, bestMinN = 0, bestMaxN = 0;
        for (int i = 0; i < hull.length; i++) {
            float[] a = hull[i];
            float[] b = hull[(i + 1) % hull.length];
            float dx = b[0] - a[0];
            float dy = b[1] - a[1];
            float len = (float) Math.hypot(dx, dy);
            if (len < 1e-6f) continue;
            float ux = dx / len, uy = dy / len;
            float nx = -uy, ny = ux;
            float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
            float minN = Float.MAX_VALUE, maxN = -Float.MAX_VALUE;
            for (float[] p : hull) {
                float u = p[0] * ux + p[1] * uy;
                float n = p[0] * nx + p[1] * ny;
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (n < minN) minN = n;
                if (n > maxN) maxN = n;
            }
            float area = (maxU - minU) * (maxN - minN);
            if (area < bestArea) {
                bestArea = area;
                bestUx = ux;
                bestUy = uy;
                bestMinU = minU;
                bestMaxU = maxU;
                bestMinN = minN;
                bestMaxN = maxN;
            }
        }
        if (bestArea == Float.MAX_VALUE) return null;
        float[] corners = cornersFromAxes(bestUx, bestUy, bestMinU, bestMaxU, bestMinN, bestMaxN);
        return orderCorners(corners);
    }

    /** 旋转卡壳坐标系（u 沿矩形长边方向，n 为其法向）还原四顶点。 */
    private static float[] cornersFromAxes(float ux, float uy,
                                           float minU, float maxU, float minN, float maxN) {
        float nx = -uy, ny = ux;
        return new float[]{
                minU * ux + minN * nx, minU * uy + minN * ny,
                maxU * ux + minN * nx, maxU * uy + minN * ny,
                maxU * ux + maxN * nx, maxU * uy + maxN * ny,
                minU * ux + maxN * nx, minU * uy + maxN * ny};
    }

    /** 顶点重排为 {tl, tr, br, bl}：和最小为 tl、和最大为 br、差最小为 tr、差最大为 bl。 */
    static float[] orderCorners(float[] corners) {
        float[] out = new float[8];
        int tl = 0, br = 0, tr = 0, bl = 0;
        float minSum = Float.MAX_VALUE, maxSum = -Float.MAX_VALUE;
        float minDiff = Float.MAX_VALUE, maxDiff = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = corners[i * 2] + corners[i * 2 + 1];
            float diff = corners[i * 2 + 1] - corners[i * 2];
            if (sum < minSum) { minSum = sum; tl = i; }
            if (sum > maxSum) { maxSum = sum; br = i; }
            if (diff < minDiff) { minDiff = diff; tr = i; }
            if (diff > maxDiff) { maxDiff = diff; bl = i; }
        }
        out[0] = corners[tl * 2]; out[1] = corners[tl * 2 + 1];
        out[2] = corners[tr * 2]; out[3] = corners[tr * 2 + 1];
        out[4] = corners[br * 2]; out[5] = corners[br * 2 + 1];
        out[6] = corners[bl * 2]; out[7] = corners[bl * 2 + 1];
        return out;
    }

    /**
     * unclip：矩形沿两轴各外扩 distance = 面积·ratio/周长（四边形 clipper 外扩的等价形式）。
     */
    static float[] unclip(float[] rect, float unclipRatio) {
        float w = (Box.dist(rect[0], rect[1], rect[2], rect[3])
                + Box.dist(rect[4], rect[5], rect[6], rect[7])) / 2f;
        float h = (Box.dist(rect[2], rect[3], rect[4], rect[5])
                + Box.dist(rect[6], rect[7], rect[0], rect[1])) / 2f;
        float perimeter = 2f * (w + h);
        if (perimeter < 1e-6f) return rect;
        float distance = w * h * unclipRatio / perimeter;
        float centerX = (rect[0] + rect[4]) / 2f;
        float centerY = (rect[1] + rect[5]) / 2f;
        float ux = rect[2] - rect[0];
        float uy = rect[3] - rect[1];
        float uLen = (float) Math.hypot(ux, uy);
        if (uLen < 1e-6f) return rect;
        ux /= uLen;
        uy /= uLen;
        float nx = -uy, ny = ux;
        float halfW = w / 2f + distance;
        float halfH = h / 2f + distance;
        float[] out = new float[8];
        out[0] = centerX - halfW * ux - halfH * nx;
        out[1] = centerY - halfW * uy - halfH * ny;
        out[2] = centerX + halfW * ux - halfH * nx;
        out[3] = centerY + halfW * uy - halfH * ny;
        out[4] = centerX + halfW * ux + halfH * nx;
        out[5] = centerY + halfW * uy + halfH * ny;
        out[6] = centerX - halfW * ux + halfH * nx;
        out[7] = centerY - halfW * uy + halfH * ny;
        return out;
    }

    private static void clip(float[] points, int width, int height) {
        for (int index = 0; index < points.length; index += 2) {
            points[index] = Math.max(0f, Math.min(width - 1f, points[index]));
            points[index + 1] = Math.max(0f, Math.min(height - 1f, points[index + 1]));
        }
    }

    /** Andrew 单调链凸包，返回逆时针序顶点（[x,y] 对），输入为像素线性索引。 */
    static float[][] convexHull(int[] component, int width) {
        float[][] points = new float[component.length][2];
        for (int i = 0; i < component.length; i++) {
            points[i][0] = component[i] % width;
            points[i][1] = component[i] / width;
        }
        java.util.Arrays.sort(points, (a, b) -> a[0] != b[0]
                ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        float[][] hull = new float[points.length * 2][];
        int size = 0;
        for (float[] p : points) {
            while (size >= 2 && cross(hull[size - 2], hull[size - 1], p) <= 0) size--;
            hull[size++] = p;
        }
        int lower = size + 1;
        for (int i = points.length - 2; i >= 0; i--) {
            float[] p = points[i];
            while (size >= lower && cross(hull[size - 2], hull[size - 1], p) <= 0) size--;
            hull[size++] = p;
        }
        float[][] result = new float[Math.max(0, size - 1)][];
        System.arraycopy(hull, 0, result, 0, result.length);
        return result;
    }

    private static float cross(float[] o, float[] a, float[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
}

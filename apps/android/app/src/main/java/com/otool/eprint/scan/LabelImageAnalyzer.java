package com.otool.eprint.scan;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 标签定位纯算法核心：只操作 int[] 灰度像素数组，不依赖 android.graphics，
 * 因此可以在 JVM 单元测试中直接运行。Bitmap 适配层见 {@link LabelFrameProcessor}。
 */
public final class LabelImageAnalyzer {
    /** 灰度图（每像素 0~255）。 */
    public static final class GrayImage {
        public final int width;
        public final int height;
        public final int[] pixels;

        public GrayImage(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        public int at(int x, int y) {
            return pixels[y * width + x];
        }
    }

    /** 最大连通亮区的外接旋转矩形评估结果。 */
    public static final class LabelRegion {
        public boolean found;
        public int left;
        public int top;
        public int right;
        public int bottom;
        public int pixelCount;
        /** 亮区像素占总像素比例。 */
        public float coverage;
        /** 质心相对画面中心的归一化偏移（-0.5~0.5）。 */
        public float offsetX;
        public float offsetY;
        /** 主轴倾角（度），已归一化到 [-45,45]，用于旋正。 */
        public float angleDegrees;
        /** 旋正坐标系下的外接矩形宽高（以质心为中心）。 */
        public int rotatedWidth;
        public int rotatedHeight;
        /** 胜出连通区每一行的左右边界（分析图坐标），用于精确清除标签外区域。 */
        public int[] rowLeft;
        public int[] rowRight;
    }

    private LabelImageAnalyzer() {}

    /** ARGB 像素数组转灰度（ITU-R 601 亮度）。 */
    public static GrayImage toGray(int[] argb, int width, int height) {
        int[] gray = new int[width * height];
        for (int index = 0; index < gray.length; index++) {
            int color = argb[index];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            gray[index] = (r * 299 + g * 587 + b * 114) / 1000;
        }
        return new GrayImage(width, height, gray);
    }

    /**
     * 彩色画面转“纸张候选”二值图：保留中高亮、RGB 色差较小的中性纸面，排除木纹、
     * 彩色包装和黑色显示器。纸面上的黑字形成孔洞，不影响背景连通区定位。
     */
    public static GrayImage toPaperMask(int[] argb, int width, int height) {
        int[] mask = new int[width * height];
        for (int index = 0; index < mask.length; index++) {
            int color = argb[index];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            int maximum = Math.max(r, Math.max(g, b));
            int minimum = Math.min(r, Math.min(g, b));
            int luminance = (r * 299 + g * 587 + b * 114) / 1000;
            // 高亮处允许轻微色温偏移，较暗区域则收紧色差，避免棕色纸袋/木纹
            // 与白色标签连成同一候选区。
            int allowedChroma = Math.max(22, luminance * 18 / 100);
            mask[index] = luminance >= 120 && maximum - minimum <= allowedChroma ? 255 : 0;
        }
        return new GrayImage(width, height, mask);
    }

    /**
     * 二值开运算（先腐蚀后膨胀），用于断开塑料反光、线缆等细长高亮物与标签纸的连接。
     * 输入输出仍为 0/255，半径 0 时原样返回。
     */
    public static GrayImage openBinaryMask(GrayImage source, int radius) {
        if (radius <= 0) return source;
        int width = source.width;
        int height = source.height;
        int[] eroded = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean keep = true;
                for (int dy = -radius; dy <= radius && keep; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height
                                || source.pixels[ny * width + nx] == 0) {
                            keep = false;
                            break;
                        }
                    }
                }
                eroded[y * width + x] = keep ? 255 : 0;
            }
        }
        int[] opened = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean fill = false;
                for (int dy = -radius; dy <= radius && !fill; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < width && ny < height
                                && eroded[ny * width + nx] != 0) {
                            fill = true;
                            break;
                        }
                    }
                }
                opened[y * width + x] = fill ? 255 : 0;
            }
        }
        return new GrayImage(width, height, opened);
    }

    /** 限宽降采样（盒式平均），宽度不超过 maxWidth 时原样返回。 */
    public static GrayImage downscale(GrayImage source, int maxWidth) {
        if (source.width <= maxWidth) return source;
        int newWidth = maxWidth;
        int newHeight = Math.max(1, Math.round(source.height * (float) maxWidth / source.width));
        int[] out = new int[newWidth * newHeight];
        float scaleX = source.width / (float) newWidth;
        float scaleY = source.height / (float) newHeight;
        for (int y = 0; y < newHeight; y++) {
            int srcY0 = (int) (y * scaleY);
            int srcY1 = Math.min(source.height, Math.max(srcY0 + 1, (int) ((y + 1) * scaleY)));
            for (int x = 0; x < newWidth; x++) {
                int srcX0 = (int) (x * scaleX);
                int srcX1 = Math.min(source.width, Math.max(srcX0 + 1, (int) ((x + 1) * scaleX)));
                long sum = 0;
                int count = 0;
                for (int sy = srcY0; sy < srcY1; sy++) {
                    for (int sx = srcX0; sx < srcX1; sx++) {
                        sum += source.at(sx, sy);
                        count++;
                    }
                }
                out[y * newWidth + x] = (int) (sum / count);
            }
        }
        return new GrayImage(newWidth, newHeight, out);
    }

    /** Otsu 自适应阈值，返回 0~255。 */
    public static int otsuThreshold(GrayImage image) {
        int[] histogram = new int[256];
        for (int value : image.pixels) histogram[value & 0xFF]++;
        int total = image.pixels.length;
        long sum = 0;
        for (int level = 0; level < 256; level++) sum += (long) level * histogram[level];
        long sumBackground = 0;
        long weightBackground = 0;
        double bestVariance = -1;
        int bestThreshold = 127;
        for (int level = 0; level < 256; level++) {
            weightBackground += histogram[level];
            if (weightBackground == 0) continue;
            long weightForeground = total - weightBackground;
            if (weightForeground == 0) break;
            sumBackground += (long) level * histogram[level];
            double meanBackground = sumBackground / (double) weightBackground;
            double meanForeground = (sum - sumBackground) / (double) weightForeground;
            double delta = meanBackground - meanForeground;
            double variance = (double) weightBackground * weightForeground * delta * delta;
            if (variance > bestVariance) {
                bestVariance = variance;
                bestThreshold = level;
            }
        }
        return bestThreshold;
    }

    /** 找阈值以上的最大连通亮区（4 邻域洪泛），并评估占比、偏移与主轴倾角。 */
    public static LabelRegion findLargestBrightRegion(GrayImage image, int threshold) {
        int width = image.width;
        int height = image.height;
        LabelRegion result = new LabelRegion();
        if (width <= 0 || height <= 0) return result;
        boolean[] visited = new boolean[width * height];
        Deque<Integer> stack = new ArrayDeque<>();
        int bestSize = 0;
        long bestSumX = 0;
        long bestSumY = 0;
        long bestSumXX = 0;
        long bestSumYY = 0;
        long bestSumXY = 0;
        int bestLeft = 0;
        int bestTop = 0;
        int bestRight = 0;
        int bestBottom = 0;
        int bestSeed = -1;
        for (int start = 0; start < width * height; start++) {
            if (visited[start] || image.pixels[start] <= threshold) continue;
            visited[start] = true;
            stack.push(start);
            int size = 0;
            long sumX = 0;
            long sumY = 0;
            long sumXX = 0;
            long sumYY = 0;
            long sumXY = 0;
            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            while (!stack.isEmpty()) {
                int current = stack.pop();
                int x = current % width;
                int y = current / width;
                size++;
                sumX += x;
                sumY += y;
                sumXX += (long) x * x;
                sumYY += (long) y * y;
                sumXY += (long) x * y;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
                pushNeighbor(image, visited, stack, threshold, x - 1, y);
                pushNeighbor(image, visited, stack, threshold, x + 1, y);
                pushNeighbor(image, visited, stack, threshold, x, y - 1);
                pushNeighbor(image, visited, stack, threshold, x, y + 1);
            }
            if (size > bestSize) {
                bestSize = size;
                bestSeed = start;
                bestSumX = sumX;
                bestSumY = sumY;
                bestSumXX = sumXX;
                bestSumYY = sumYY;
                bestSumXY = sumXY;
                bestLeft = left;
                bestTop = top;
                bestRight = right;
                bestBottom = bottom;
            }
        }
        if (bestSize == 0) return result;
        result.found = true;
        result.left = bestLeft;
        result.top = bestTop;
        result.right = bestRight + 1;
        result.bottom = bestBottom + 1;
        result.pixelCount = bestSize;
        result.coverage = bestSize / (float) (width * height);
        double centroidX = bestSumX / (double) bestSize;
        double centroidY = bestSumY / (double) bestSize;
        result.offsetX = (float) (centroidX / width - 0.5);
        result.offsetY = (float) (centroidY / height - 0.5);

        // 二阶中心矩求主轴方向：比凸包+旋转卡壳简单，手持小角度倾斜足够精确。
        double mu20 = bestSumXX / (double) bestSize - centroidX * centroidX;
        double mu02 = bestSumYY / (double) bestSize - centroidY * centroidY;
        double mu11 = bestSumXY / (double) bestSize - centroidX * centroidY;
        double angle = Math.toDegrees(0.5 * Math.atan2(2 * mu11, mu20 - mu02));
        if (mu02 > mu20) angle += 90;
        while (angle > 45) angle -= 90;
        while (angle < -45) angle += 90;
        result.angleDegrees = (float) angle;

        // 旋正坐标系下的外接矩形宽高（重扫一遍亮区像素投影）。
        double radians = Math.toRadians(result.angleDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        // 只重扫胜出的连通区；若把其他亮区也计入，会把旋正裁剪尺寸错误扩大到整幅画面。
        boolean[] selected = new boolean[width * height];
        int[] rowLeft = new int[height];
        int[] rowRight = new int[height];
        java.util.Arrays.fill(rowLeft, width);
        java.util.Arrays.fill(rowRight, -1);
        stack.clear();
        selected[bestSeed] = true;
        stack.push(bestSeed);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            int x = current % width;
            int y = current / width;
            double dx = x - centroidX;
            double dy = y - centroidY;
            double u = dx * cos + dy * sin;
            double v = -dx * sin + dy * cos;
            if (x < rowLeft[y]) rowLeft[y] = x;
            if (x > rowRight[y]) rowRight[y] = x;
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
            pushNeighbor(image, selected, stack, threshold, x - 1, y);
            pushNeighbor(image, selected, stack, threshold, x + 1, y);
            pushNeighbor(image, selected, stack, threshold, x, y - 1);
            pushNeighbor(image, selected, stack, threshold, x, y + 1);
        }
        result.rotatedWidth = Math.max(1, (int) Math.ceil(maxU - minU));
        result.rotatedHeight = Math.max(1, (int) Math.ceil(maxV - minV));
        result.rowLeft = rowLeft;
        result.rowRight = rowRight;
        return result;
    }

    /** 拉普拉斯方差清晰度评估：值越小越糊，可用于提示重拍。 */
    public static double laplacianVariance(GrayImage image) {
        int width = image.width;
        int height = image.height;
        if (width < 3 || height < 3) return 0;
        long sum = 0;
        long sumSquares = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int laplacian = 4 * image.at(x, y)
                        - image.at(x - 1, y) - image.at(x + 1, y)
                        - image.at(x, y - 1) - image.at(x, y + 1);
                sum += laplacian;
                sumSquares += (long) laplacian * laplacian;
                count++;
            }
        }
        double mean = sum / (double) count;
        return sumSquares / (double) count - mean * mean;
    }

    /** 归一化引导框（0~1）转像素裁剪矩形，含边界钳制。 */
    public static int[] guideCropRect(int width, int height,
                                      float normLeft, float normTop,
                                      float normRight, float normBottom) {
        int left = clamp(Math.round(normLeft * width), 0, width - 1);
        int top = clamp(Math.round(normTop * height), 0, height - 1);
        int right = clamp(Math.round(normRight * width), left + 1, width);
        int bottom = clamp(Math.round(normBottom * height), top + 1, height);
        return new int[]{left, top, right, bottom};
    }

    private static void pushNeighbor(GrayImage image, boolean[] visited,
                                     Deque<Integer> stack, int threshold, int x, int y) {
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
        int index = y * image.width + x;
        if (visited[index] || image.pixels[index] <= threshold) return;
        visited[index] = true;
        stack.push(index);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

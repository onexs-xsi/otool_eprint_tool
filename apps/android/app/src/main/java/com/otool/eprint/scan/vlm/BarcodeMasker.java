package com.otool.eprint.scan.vlm;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

import com.otool.eprint.scan.opencv.OpenCVBarcodeRegionFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** 在图片送入 GLM-OCR 前定位并以白块遮盖二维码和一维码，减少无效视觉 token。 */
public final class BarcodeMasker {
    private static final String TAG = "BarcodeMasker";
    private static final List<BarcodeFormat> FORMATS = Arrays.asList(
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
            BarcodeFormat.PDF_417, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93, BarcodeFormat.CODABAR, BarcodeFormat.ITF,
            BarcodeFormat.EAN_8, BarcodeFormat.EAN_13, BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E, BarcodeFormat.RSS_14, BarcodeFormat.RSS_EXPANDED);

    private BarcodeMasker() {}

    public static MaskResult mask(Bitmap source) {
        return mask(source, java.util.Collections.emptyList(), false);
    }

    /**
     * 精确遮盖：默认只采用可解码码区、QR finder 和近方形矩阵区域；实验性一维条纹
     * 定位必须显式开启。与 PP-OCR 文本框重叠的候选直接放弃，避免误伤有效文字。
     */
    public static MaskResult mask(Bitmap source, List<RectF> protectedTextRegions,
                                  boolean experimentalWeakCodeMask) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return new MaskResult(source, 0, 0);
        }
        List<MaskBounds> regions;
        try {
            regions = detect(source, experimentalWeakCodeMask);
        } catch (RuntimeException detectionError) {
            return new MaskResult(source, 0, 0);
        }
        int protectedCount = 0;
        if (protectedTextRegions != null && !protectedTextRegions.isEmpty()) {
            for (int index = regions.size() - 1; index >= 0; index--) {
                if (overlapsProtectedText(regions.get(index), protectedTextRegions)) {
                    regions.remove(index);
                    protectedCount++;
                }
            }
        }
        if (regions.isEmpty()) return new MaskResult(source, 0, protectedCount);

        Bitmap masked = source.copy(Bitmap.Config.ARGB_8888, true);
        if (masked == null) {
            masked = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            new Canvas(masked).drawBitmap(source, 0f, 0f, null);
        }
        Canvas canvas = new Canvas(masked);
        Paint cover = new Paint();
        cover.setStyle(Paint.Style.FILL);
        cover.setColor(Color.WHITE);
        cover.setAntiAlias(false);
        for (MaskBounds region : regions) {
            canvas.drawRect(region.left, region.top, region.right, region.bottom, cover);
        }
        return new MaskResult(masked, regions.size(), protectedCount);
    }

    private static List<MaskBounds> detect(Bitmap bitmap, boolean experimentalWeakCodeMask) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, FORMATS);

        MultiFormatReader delegate = new MultiFormatReader();
        delegate.setHints(hints);
        List<MaskBounds> regions = new ArrayList<>();
        try {
            Result[] decoded = new GenericMultipleBarcodeReader(delegate).decodeMultiple(binary, hints);
            for (Result result : decoded) addRegion(regions, result, width, height);
        } catch (NotFoundException ignored) {
            try {
                addRegion(regions, delegate.decode(binary, hints), width, height);
            } catch (NotFoundException ignoredSingle) {
                // 未检出码区时保留原图；不猜测、不覆盖文字区域。
            } finally {
                delegate.reset();
            }
        }
        int decodedCount = regions.size();
        // 解码会受污损、低分辨率和内容校验影响；QR finder pattern 本身可定位时也应遮盖。
        try {
            DetectorResult[] detected = new MultiDetector(binary.getBlackMatrix())
                    .detectMulti(hints);
            for (DetectorResult result : detected) {
                addBounds(regions, boundsFor(result.getPoints(), BarcodeFormat.QR_CODE,
                        width, height));
            }
        } catch (NotFoundException ignored) {
            // 没有完整 finder pattern 时继续尝试一维码条纹定位。
        }
        int finderCount = regions.size() - decodedCount;

        int[] luminance = new int[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int color = pixels[index];
            luminance[index] = (Color.red(color) * 299 + Color.green(color) * 587
                    + Color.blue(color) * 114) / 1000;
        }
        int beforeStripes = regions.size();
        if (experimentalWeakCodeMask) {
            // OpenCV 梯度 + 闭运算实现优先；返回 null（不可用/失败）时回退纯 Java 实现。
            List<float[]> openCvStripes =
                    OpenCVBarcodeRegionFinder.findStripeRegions(bitmap);
            if (openCvStripes != null) {
                for (float[] stripe : openCvStripes) {
                    addBounds(regions, new MaskBounds(stripe[0], stripe[1],
                            stripe[2], stripe[3]));
                }
            } else {
                for (MaskBounds stripeRegion : findStripeRegions(luminance, width, height)) {
                    addBounds(regions, stripeRegion);
                }
            }
        }
        int stripeCount = regions.size() - beforeStripes;
        int beforeMatrix = regions.size();
        List<float[]> openCvMatrix = OpenCVBarcodeRegionFinder.findMatrixRegions(bitmap);
        if (openCvMatrix != null) {
            for (float[] matrix : openCvMatrix) {
                addBounds(regions, new MaskBounds(matrix[0], matrix[1],
                        matrix[2], matrix[3]));
            }
        } else {
            for (MaskBounds matrixRegion : findMatrixRegions(luminance, width, height)) {
                addBounds(regions, matrixRegion);
            }
        }
        Log.i(TAG, "detected regions: decoded=" + decodedCount + ", qrFinder="
                + finderCount + ", stripe=" + stripeCount + ", matrix="
                + (regions.size() - beforeMatrix));
        return regions;
    }

    private static boolean overlapsProtectedText(MaskBounds region,
                                                 List<RectF> protectedRegions) {
        float regionArea = region.width() * region.height();
        if (regionArea <= 0f) return false;
        for (RectF text : protectedRegions) {
            if (text == null || text.width() <= 0f || text.height() <= 0f) continue;
            float overlapWidth = Math.max(0f, Math.min(region.right, text.right)
                    - Math.max(region.left, text.left));
            float overlapHeight = Math.max(0f, Math.min(region.bottom, text.bottom)
                    - Math.max(region.top, text.top));
            float overlap = overlapWidth * overlapHeight;
            if (overlap / regionArea > 0.22f
                    || overlap / (text.width() * text.height()) > 0.68f) return true;
        }
        return false;
    }

    private static void addRegion(List<MaskBounds> regions, Result result,
                                  int imageWidth, int imageHeight) {
        if (result == null) return;
        MaskBounds bounds = boundsFor(result.getResultPoints(), result.getBarcodeFormat(),
                imageWidth, imageHeight);
        addBounds(regions, bounds);
    }

    private static void addBounds(List<MaskBounds> regions, MaskBounds bounds) {
        if (bounds == null) return;
        for (MaskBounds existing : regions) {
            if (existing.overlapRatio(bounds) > 0.65f) return;
        }
        regions.add(bounds);
    }

    /**
     * 解码无关的一维码定位：在小网格中寻找单一方向、高密度且连续的明暗边缘。
     * 它只接受长条形连通区，避免把普通文字和器件轮廓误当成条码。
     */
    static List<MaskBounds> findStripeRegions(int[] luminance, int width, int height) {
        List<MaskBounds> regions = new ArrayList<>();
        if (luminance == null || width < 24 || height < 24
                || luminance.length < width * height) {
            return regions;
        }
        int block = Math.max(4, Math.min(12, Math.min(width, height) / 80));
        int columns = (width + block - 1) / block;
        int rows = (height + block - 1) / block;
        byte[] direction = new byte[columns * rows];
        for (int row = 0; row < rows; row++) {
            int top = row * block;
            int bottom = Math.min(height, top + block);
            for (int column = 0; column < columns; column++) {
                int left = column * block;
                int right = Math.min(width, left + block);
                long xEdges = 0L;
                long yEdges = 0L;
                int samples = 0;
                for (int y = Math.max(1, top); y < bottom; y++) {
                    int offset = y * width;
                    int previousOffset = (y - 1) * width;
                    for (int x = Math.max(1, left); x < right; x++) {
                        int value = luminance[offset + x];
                        xEdges += Math.abs(value - luminance[offset + x - 1]);
                        yEdges += Math.abs(value - luminance[previousOffset + x]);
                        samples++;
                    }
                }
                if (samples == 0) continue;
                float xAverage = xEdges / (float) samples;
                float yAverage = yEdges / (float) samples;
                if (xAverage >= 30f && xAverage >= yAverage * 1.55f + 5f) {
                    direction[row * columns + column] = 1; // 竖条纹，条码横向延伸
                } else if (yAverage >= 30f && yAverage >= xAverage * 1.55f + 5f) {
                    direction[row * columns + column] = 2; // 横条纹，条码纵向延伸
                }
            }
        }
        collectStripeComponents(regions, direction, (byte) 1, columns, rows,
                block, width, height, luminance);
        collectStripeComponents(regions, direction, (byte) 2, columns, rows,
                block, width, height, luminance);
        return regions;
    }

    private static void collectStripeComponents(List<MaskBounds> regions, byte[] direction,
                                                byte expected, int columns, int rows,
                                                int block, int width, int height,
                                                int[] luminance) {
        boolean[] visited = new boolean[direction.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < direction.length; start++) {
            if (visited[start] || direction[start] != expected) continue;
            visited[start] = true;
            queue.add(start);
            int minColumn = start % columns;
            int maxColumn = minColumn;
            int minRow = start / columns;
            int maxRow = minRow;
            int cells = 0;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int column = current % columns;
                int row = current / columns;
                cells++;
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
                for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                    for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                        if (rowOffset == 0 && columnOffset == 0) continue;
                        int nextColumn = column + columnOffset;
                        int nextRow = row + rowOffset;
                        if (nextColumn < 0 || nextColumn >= columns
                                || nextRow < 0 || nextRow >= rows) continue;
                        int next = nextRow * columns + nextColumn;
                        if (!visited[next] && direction[next] == expected) {
                            visited[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }
            float componentWidth = Math.min(width, (maxColumn + 1) * block)
                    - minColumn * block;
            float componentHeight = Math.min(height, (maxRow + 1) * block)
                    - minRow * block;
            float longSide = Math.max(componentWidth, componentHeight);
            float shortSide = Math.min(componentWidth, componentHeight);
            float density = cells / (float) ((maxColumn - minColumn + 1)
                    * (maxRow - minRow + 1));
            boolean expectedOrientation = expected == 1
                    ? componentWidth >= componentHeight : componentHeight >= componentWidth;
            int pixelLeft = minColumn * block;
            int pixelTop = minRow * block;
            int pixelRight = Math.min(width, (maxColumn + 1) * block);
            int pixelBottom = Math.min(height, (maxRow + 1) * block);
            int dark = 0;
            int light = 0;
            int sampled = 0;
            for (int y = pixelTop; y < pixelBottom; y += 2) {
                for (int x = pixelLeft; x < pixelRight; x += 2) {
                    int value = luminance[y * width + x];
                    if (value < 105) dark++;
                    if (value > 175) light++;
                    sampled++;
                }
            }
            float darkRatio = sampled == 0 ? 0f : dark / (float) sampled;
            float lightRatio = sampled == 0 ? 0f : light / (float) sampled;
            float transitionCoverage = stripeTransitionCoverage(luminance, width,
                    pixelLeft, pixelTop, pixelRight, pixelBottom, expected);
            if (!expectedOrientation || longSide < Math.max(32f, Math.max(width, height) * 0.055f)
                    || shortSide < 8f || longSide / shortSide < 2.35f || density < 0.32f
                    || darkRatio < 0.12f || lightRatio < 0.18f
                    || transitionCoverage < 0.42f) {
                continue;
            }
            float left = pixelLeft;
            float top = pixelTop;
            float right = pixelRight;
            float bottom = pixelBottom;
            float padLong = longSide * 0.06f;
            float padShort = Math.max(6f, shortSide * 0.35f);
            MaskBounds bounds = expected == 1
                    ? new MaskBounds(clamp(left - padLong, 0f, width),
                    clamp(top - padShort, 0f, height),
                    clamp(right + padLong, 0f, width),
                    clamp(bottom + padShort, 0f, height))
                    : new MaskBounds(clamp(left - padShort, 0f, width),
                    clamp(top - padLong, 0f, height),
                    clamp(right + padShort, 0f, width),
                    clamp(bottom + padLong, 0f, height));
            addBounds(regions, bounds);
        }
    }

    private static float stripeTransitionCoverage(int[] luminance, int width,
                                                  int left, int top, int right, int bottom,
                                                  byte direction) {
        int lines = 0;
        int qualifying = 0;
        if (direction == 1) {
            int minimumTransitions = Math.max(7, (right - left) / 24);
            for (int y = top; y < bottom; y += 2) {
                int transitions = countTransitions(luminance, width, left, right, y, true);
                if (transitions >= minimumTransitions) qualifying++;
                lines++;
            }
        } else {
            int minimumTransitions = Math.max(7, (bottom - top) / 24);
            for (int x = left; x < right; x += 2) {
                int transitions = countTransitions(luminance, width, top, bottom, x, false);
                if (transitions >= minimumTransitions) qualifying++;
                lines++;
            }
        }
        return lines == 0 ? 0f : qualifying / (float) lines;
    }

    private static int countTransitions(int[] luminance, int width,
                                        int start, int end, int fixed, boolean horizontal) {
        int previous = -1;
        int transitions = 0;
        for (int position = start; position < end; position++) {
            int value = horizontal
                    ? luminance[fixed * width + position]
                    : luminance[position * width + fixed];
            int state = value < 105 ? 0 : value > 175 ? 1 : -1;
            if (state < 0) continue;
            if (previous >= 0 && state != previous) transitions++;
            previous = state;
        }
        return transitions;
    }

    /** 解码无关的二维码候选定位：只接受双方向高频、近方形且高密度的连通区。 */
    static List<MaskBounds> findMatrixRegions(int[] luminance, int width, int height) {
        List<MaskBounds> regions = new ArrayList<>();
        if (luminance == null || width < 24 || height < 24
                || luminance.length < width * height) {
            return regions;
        }
        int block = Math.max(4, Math.min(12, Math.min(width, height) / 80));
        int columns = (width + block - 1) / block;
        int rows = (height + block - 1) / block;
        boolean[] dense = new boolean[columns * rows];
        for (int row = 0; row < rows; row++) {
            int top = row * block;
            int bottom = Math.min(height, top + block);
            for (int column = 0; column < columns; column++) {
                int left = column * block;
                int right = Math.min(width, left + block);
                long xEdges = 0L;
                long yEdges = 0L;
                int samples = 0;
                for (int y = Math.max(1, top); y < bottom; y++) {
                    int offset = y * width;
                    int previousOffset = (y - 1) * width;
                    for (int x = Math.max(1, left); x < right; x++) {
                        int value = luminance[offset + x];
                        xEdges += Math.abs(value - luminance[offset + x - 1]);
                        yEdges += Math.abs(value - luminance[previousOffset + x]);
                        samples++;
                    }
                }
                if (samples == 0) continue;
                float xAverage = xEdges / (float) samples;
                float yAverage = yEdges / (float) samples;
                float strongest = Math.max(xAverage, yAverage);
                float weakest = Math.min(xAverage, yAverage);
                dense[row * columns + column] = weakest >= 22f
                        && weakest >= strongest * 0.42f;
            }
        }

        boolean[] visited = new boolean[dense.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < dense.length; start++) {
            if (visited[start] || !dense[start]) continue;
            visited[start] = true;
            queue.add(start);
            int minColumn = start % columns;
            int maxColumn = minColumn;
            int minRow = start / columns;
            int maxRow = minRow;
            int cells = 0;
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int column = current % columns;
                int row = current / columns;
                cells++;
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
                for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                    for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                        if (rowOffset == 0 && columnOffset == 0) continue;
                        int nextColumn = column + columnOffset;
                        int nextRow = row + rowOffset;
                        if (nextColumn < 0 || nextColumn >= columns
                                || nextRow < 0 || nextRow >= rows) continue;
                        int next = nextRow * columns + nextColumn;
                        if (!visited[next] && dense[next]) {
                            visited[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }
            float componentWidth = Math.min(width, (maxColumn + 1) * block)
                    - minColumn * block;
            float componentHeight = Math.min(height, (maxRow + 1) * block)
                    - minRow * block;
            float longer = Math.max(componentWidth, componentHeight);
            float shorter = Math.min(componentWidth, componentHeight);
            float density = cells / (float) ((maxColumn - minColumn + 1)
                    * (maxRow - minRow + 1));
            float minimumSide = Math.max(28f, Math.min(width, height) * 0.045f);
            if (shorter < minimumSide || longer / shorter > 1.55f || density < 0.34f
                    || componentWidth * componentHeight > width * height * 0.18f) {
                continue;
            }
            float padding = Math.max(8f, longer * 0.16f);
            addBounds(regions, new MaskBounds(
                    clamp(minColumn * block - padding, 0f, width),
                    clamp(minRow * block - padding, 0f, height),
                    clamp(Math.min(width, (maxColumn + 1) * block) + padding, 0f, width),
                    clamp(Math.min(height, (maxRow + 1) * block) + padding, 0f, height)));
        }
        return regions;
    }

    /** ResultPoint → 保守遮盖框；纯几何逻辑，供 JVM 回归测试。 */
    static MaskBounds boundsFor(ResultPoint[] points, BarcodeFormat format,
                                int imageWidth, int imageHeight) {
        if (points == null || points.length < 2 || imageWidth <= 0 || imageHeight <= 0) {
            return null;
        }
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
        float spanX = Math.max(1f, maxX - minX);
        float spanY = Math.max(1f, maxY - minY);
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;

        boolean twoDimensional = format == BarcodeFormat.QR_CODE
                || format == BarcodeFormat.DATA_MATRIX || format == BarcodeFormat.AZTEC;
        if (twoDimensional) {
            float side = Math.max(spanX, spanY);
            float half = Math.max(8f, side * 0.68f);
            minX = centerX - half;
            maxX = centerX + half;
            minY = centerY - half;
            maxY = centerY + half;
        } else if (spanX >= spanY) {
            float padX = Math.max(6f, spanX * 0.08f);
            float halfHeight = Math.max(10f, spanX * 0.17f);
            minX -= padX;
            maxX += padX;
            minY = centerY - halfHeight;
            maxY = centerY + halfHeight;
        } else {
            float padY = Math.max(6f, spanY * 0.08f);
            float halfWidth = Math.max(10f, spanY * 0.17f);
            minY -= padY;
            maxY += padY;
            minX = centerX - halfWidth;
            maxX = centerX + halfWidth;
        }
        return new MaskBounds(clamp(minX, 0f, imageWidth), clamp(minY, 0f, imageHeight),
                clamp(maxX, 0f, imageWidth), clamp(maxY, 0f, imageHeight));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class MaskResult {
        public final Bitmap bitmap;
        public final int maskedCount;
        public final int protectedCount;

        MaskResult(Bitmap bitmap, int maskedCount, int protectedCount) {
            this.bitmap = bitmap;
            this.maskedCount = maskedCount;
            this.protectedCount = protectedCount;
        }
    }

    static final class MaskBounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        MaskBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() {
            return Math.max(0f, right - left);
        }

        float height() {
            return Math.max(0f, bottom - top);
        }

        float overlapRatio(MaskBounds other) {
            float overlapWidth = Math.max(0f, Math.min(right, other.right)
                    - Math.max(left, other.left));
            float overlapHeight = Math.max(0f, Math.min(bottom, other.bottom)
                    - Math.max(top, other.top));
            float overlap = overlapWidth * overlapHeight;
            float smaller = Math.min(width() * height(), other.width() * other.height());
            return smaller <= 0f ? 0f : overlap / smaller;
        }
    }
}

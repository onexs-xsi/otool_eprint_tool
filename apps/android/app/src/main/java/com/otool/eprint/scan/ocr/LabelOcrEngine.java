package com.otool.eprint.scan.ocr;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PP-OCRv6 级联 OCR 引擎（det DBNet → cls 方向分类 → rec CTC 识别），
 * 全部端侧推理，onnxruntime-android 纯 Java API，无 NDK 改造。
 * 算法流程与 RapidOCR（Apache-2.0）Java 流水线一致；后处理/解码在
 * {@link DbPostProcessor} / {@link CtcDecoder}（纯 JVM，可单测）。
 *
 * 使用：后台线程 {@link #load(Context)}（数秒，切入「标签」档时预热）→
 * {@link #recognize(Bitmap)}（输入为 LabelFrameProcessor 处理后的 Bitmap，
 * 输出 {@link OcrResult}，行坐标为输入图坐标系）。线程安全：加载与识别串行化。
 */
public final class LabelOcrEngine {
    private static final String TAG = "LabelOcrEngine";
    private static final String DET_MODEL = "ocr/PP-OCRv6_det_small.onnx";
    private static final String REC_MODEL = "ocr/PP-OCRv6_rec_small.onnx";
    private static final String CLS_MODEL = "ocr/ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx";
    private static final String REC_DICT = "ocr/ppocrv6_dict.txt";

    /** DBNet 限边（长边像素，PaddleOCR 默认 960）。 */
    static final int DET_LIMIT_SIDE = 960;
    /** DBNet 输入边长对齐倍数。 */
    static final int DET_ALIGN = 32;
    static final float DET_THRESHOLD = 0.3f;
    static final float DET_BOX_THRESHOLD = 0.5f;
    static final float DET_UNCLIP_RATIO = 1.5f;
    /** cls 输入尺寸与判定阈值（ch_PP-LCNet_x0_25_textline_ori 为静态 80×160）。 */
    static final int CLS_WIDTH = 160;
    static final int CLS_HEIGHT = 80;
    static final float CLS_ROTATE_THRESHOLD = 0.9f;
    /** rec 输入定高与最大宽高比。 */
    static final int REC_HEIGHT = 48;
    static final int REC_MAX_RATIO = 40;

    /** 归一化常数：det 按 ImageNet 标准，cls/rec 按 (x-0.5)/0.5。 */
    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};

    public static final class OcrInitException extends Exception {
        public OcrInitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private OrtEnvironment environment;
    private OrtSession detSession;
    private OrtSession clsSession;
    private OrtSession recSession;
    private List<String> dict;
    private String initError;

    public synchronized boolean isReady() {
        return detSession != null && recSession != null && clsSession != null;
    }

    public synchronized String initError() {
        return initError;
    }

    /** 加载模型与字典（幂等，耗时操作须在后台线程调用）。失败不抛异常，记录 initError。 */
    public synchronized void load(Context context) {
        if (isReady() || initError != null) return;
        try {
            AssetManager assets = context.getApplicationContext().getAssets();
            dict = loadDict(assets);
            environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.max(2, Math.min(4,
                    Runtime.getRuntime().availableProcessors())));
            detSession = environment.createSession(readAsset(assets, DET_MODEL), options);
            clsSession = environment.createSession(readAsset(assets, CLS_MODEL), options);
            recSession = environment.createSession(readAsset(assets, REC_MODEL), options);
        } catch (OrtException | IOException | RuntimeException e) {
            initError = e.getMessage() == null ? e.toString() : e.getMessage();
            closeQuietly();
        }
    }

    /** 整流水线识别；调用前需 {@link #isReady()}。后台线程执行。 */
    public synchronized OcrResult recognize(Bitmap source) {
        if (!isReady()) {
            return new OcrResult(new ArrayList<>(), 0, 0, 0, 0);
        }
        long start = System.nanoTime();
        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();

        // ---- det：限边对齐缩放 → 归一化 → 推理 → 概率图 → 文本行四边形 ----
        float scale = Math.min(1f, Math.min(DET_LIMIT_SIDE / (float) srcWidth,
                DET_LIMIT_SIDE / (float) srcHeight));
        int detWidth = align((int) (srcWidth * scale));
        int detHeight = align((int) (srcHeight * scale));
        Bitmap detInput = Bitmap.createScaledBitmap(source, detWidth, detHeight, true);
        float[] prob;
        try {
            prob = runDet(detInput);
        } catch (OrtException e) {
            android.util.Log.w(TAG, "det inference failed", e);
            return new OcrResult(new ArrayList<>(), 0, 0, 0, elapsedMs(start));
        }
        List<DbPostProcessor.Box> boxes = DbPostProcessor.findBoxes(prob, detWidth, detHeight,
                DET_THRESHOLD, DET_BOX_THRESHOLD, DET_UNCLIP_RATIO);
        android.util.Log.i(TAG, "det: " + detWidth + "x" + detHeight + ", boxes=" + boxes.size());
        long detMs = elapsedMs(start);

        // ---- 按行裁剪旋正 → cls 方向纠正 → rec 识别 ----
        float scaleX = srcWidth / (float) detWidth;
        float scaleY = srcHeight / (float) detHeight;
        long clsMs = 0;
        long recMs = 0;
        List<OcrLine> lines = new ArrayList<>();
        for (DbPostProcessor.Box box : boxes) {
            float[] quad = DbPostProcessor.scaleBox(box.points, scaleX, scaleY);
            long clsStart = System.nanoTime();
            Bitmap crop = cropLine(source, quad);
            if (crop == null) continue;
            try {
                crop = correctOrientation(crop);
            } catch (OrtException e) {
                // cls 失败不丢行：保持原方向继续送 rec（降级）。
                android.util.Log.w(TAG, "cls failed: " + e.getMessage());
            }
            clsMs += elapsedMs(clsStart);
            long recStart = System.nanoTime();
            CtcDecoder.Result decoded;
            try {
                decoded = runRec(crop);
            } catch (OrtException e) {
                android.util.Log.w(TAG, "rec failed: " + e.getMessage());
                continue;
            }
            recMs += elapsedMs(recStart);
            String text = decoded.text.trim();
            if (text.isEmpty()) continue;
            lines.add(toLine(text, decoded.confidence, quad));
        }
        return new OcrResult(lines, detMs, clsMs, recMs, elapsedMs(start));
    }

    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (detSession != null) detSession.close();
            if (clsSession != null) clsSession.close();
            if (recSession != null) recSession.close();
        } catch (OrtException ignored) {
            // 关闭失败不影响降级路径。
        }
        detSession = null;
        clsSession = null;
        recSession = null;
    }

    // ---------------- det ----------------

    private float[] runDet(Bitmap input) throws OrtException {
        int width = input.getWidth();
        int height = input.getHeight();
        FloatBuffer buffer = toNormalizedBuffer(input, DET_MEAN, DET_STD);
        OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer,
                new long[]{1, 3, height, width});
        try (OrtSession.Result result = detSession.run(
                Collections.singletonMap(detSession.getInputNames().iterator().next(), tensor))) {
            float[][][][] output = (float[][][][]) result.get(0).getValue();
            tensor.close();
            float[] prob = new float[width * height];
            float maxProb = 0f;
            for (int y = 0; y < height; y++) {
                System.arraycopy(output[0][0][y], 0, prob, y * width, width);
            }
            for (float v : prob) if (v > maxProb) maxProb = v;
            android.util.Log.i(TAG, "det prob max=" + maxProb);
            return prob;
        }
    }

    // ---------------- cls ----------------

    /** cls 判定方向；概率超过阈值判为倒置时把行图旋转 180°。 */
    private Bitmap correctOrientation(Bitmap crop) throws OrtException {
        int width = crop.getWidth();
        int height = crop.getHeight();
        int resizedWidth = Math.min(CLS_WIDTH,
                Math.max(1, Math.round(width * (CLS_HEIGHT / (float) height))));
        Bitmap input = Bitmap.createBitmap(CLS_WIDTH, CLS_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input);
        canvas.drawColor(0xFF7F7F7F); // 归一化后约等于 0，与 PaddleOCR 的零填充对齐
        Bitmap resized = Bitmap.createScaledBitmap(crop, resizedWidth, CLS_HEIGHT, true);
        canvas.drawBitmap(resized, 0, 0, new Paint());
        FloatBuffer buffer = toNormalizedBuffer(input, null, null);
        OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer,
                new long[]{1, 3, CLS_HEIGHT, CLS_WIDTH});
        try (OrtSession.Result result = clsSession.run(
                Collections.singletonMap(clsSession.getInputNames().iterator().next(), tensor))) {
            float[][] output = (float[][]) result.get(0).getValue();
            tensor.close();
            float[] probs = softmax(output[0]);
            if (probs.length >= 2 && probs[1] > CLS_ROTATE_THRESHOLD) {
                Matrix matrix = new Matrix();
                matrix.postRotate(180, width / 2f, height / 2f);
                return Bitmap.createBitmap(crop, 0, 0, width, height, matrix, true);
            }
            return crop;
        }
    }

    // ---------------- rec ----------------

    private CtcDecoder.Result runRec(Bitmap crop) throws OrtException {
        int targetWidth = Math.max(REC_HEIGHT, Math.round(
                crop.getWidth() * (REC_HEIGHT / (float) crop.getHeight())));
        targetWidth = Math.min(targetWidth, REC_HEIGHT * REC_MAX_RATIO);
        Bitmap input = Bitmap.createScaledBitmap(crop, targetWidth, REC_HEIGHT, true);
        FloatBuffer buffer = toNormalizedBuffer(input, null, null);
        OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer,
                new long[]{1, 3, REC_HEIGHT, targetWidth});
        try (OrtSession.Result result = recSession.run(
                Collections.singletonMap(recSession.getInputNames().iterator().next(), tensor))) {
            float[][][] output = (float[][][]) result.get(0).getValue();
            tensor.close();
            float[][] steps = output[0];
            for (int step = 0; step < steps.length; step++) {
                steps[step] = softmax(steps[step]);
            }
            return CtcDecoder.decode(steps, dict);
        }
    }

    // ---------------- 图像工具 ----------------

    /** Bitmap → CHW float buffer（RGB 顺序）；mean/std 为 null 时用 (x-0.5)/0.5。 */
    private static FloatBuffer toNormalizedBuffer(Bitmap bitmap, float[] mean, float[] std) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        FloatBuffer buffer = FloatBuffer.allocate(3 * width * height);
        int plane = width * height;
        float[] values = new float[3 * plane];
        for (int index = 0; index < plane; index++) {
            int pixel = argb[index];
            float r = ((pixel >> 16) & 0xFF) / 255f;
            float g = ((pixel >> 8) & 0xFF) / 255f;
            float b = (pixel & 0xFF) / 255f;
            if (mean == null) {
                values[index] = (r - 0.5f) / 0.5f;
                values[plane + index] = (g - 0.5f) / 0.5f;
                values[2 * plane + index] = (b - 0.5f) / 0.5f;
            } else {
                values[index] = (r - mean[0]) / std[0];
                values[plane + index] = (g - mean[1]) / std[1];
                values[2 * plane + index] = (b - mean[2]) / std[2];
            }
        }
        buffer.put(values);
        buffer.rewind();
        return buffer;
    }

    /**
     * 按文本行四边形透视裁剪并拉平为水平条带（Matrix.setPolyToPoly 四点映射）。
     * 返回 null 表示行框退化（宽高过小）。
     */
    static Bitmap cropLine(Bitmap source, float[] quad) {
        float widthTop = DbPostProcessor.Box.dist(quad[0], quad[1], quad[2], quad[3]);
        float widthBottom = DbPostProcessor.Box.dist(quad[6], quad[7], quad[4], quad[5]);
        float heightLeft = DbPostProcessor.Box.dist(quad[0], quad[1], quad[6], quad[7]);
        float heightRight = DbPostProcessor.Box.dist(quad[2], quad[3], quad[4], quad[5]);
        int cropWidth = Math.round(Math.max(widthTop, widthBottom));
        int cropHeight = Math.round(Math.max(heightLeft, heightRight));
        if (cropWidth < 3 || cropHeight < 3) return null;
        float[] dst = {0, 0, cropWidth, 0, cropWidth, cropHeight, 0, cropHeight};
        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(quad, 0, dst, 0, 4);
        Bitmap crop = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(crop);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, matrix, paint);
        return crop;
    }

    private static OcrLine toLine(String text, float confidence, float[] quad) {
        float left = Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
        float right = Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
        float top = Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
        float bottom = Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
        return new OcrLine(text, confidence,
                Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
    }

    private static int align(int value) {
        return Math.max(DET_ALIGN, (value + DET_ALIGN - 1) / DET_ALIGN * DET_ALIGN);
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;
        double sum = 0;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        if (sum > 0) for (int i = 0; i < out.length; i++) out[i] /= (float) sum;
        return out;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static byte[] readAsset(AssetManager assets, String name) throws IOException {
        try (InputStream in = assets.open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1 << 16];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    private static List<String> loadDict(AssetManager assets) throws IOException {
        String content = new String(readAsset(assets, REC_DICT), StandardCharsets.UTF_8);
        List<String> dict = new ArrayList<>();
        for (String line : content.split("\n")) {
            // 字典逐行为一个字符（含空格行），只去掉行尾 \r。
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty()) dict.add(line);
        }
        return dict;
    }
}

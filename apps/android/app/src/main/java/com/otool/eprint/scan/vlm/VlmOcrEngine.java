package com.otool.eprint.scan.vlm;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * GLM-OCR 0.9B 文档 VLM 引擎（MNN 端侧推理），「标签」档的 VLM 主链路。
 *
 * benchmark 阶段模型不打进 APK：adb push 到
 * {@code /sdcard/Android/data/<pkg>/files/models/glm-ocr-mnn/}（{@link #modelDir}）。
 * 未部署时 {@link #isReady()} 为 false、{@link #initError()} 返回原因；开发阶段由调用方明确报错，
 * 不自动改用其他 OCR 结果冒充 VLM 输出。
 *
 * 图片传入方式：存临时 PNG → prompt 内嵌 {@code <img>绝对路径</img>}，引擎自行解析加载；
 * chat template（[gMASK]&lt;sop&gt;&lt;|user|&gt;…）由引擎自动套用。
 *
 * 使用：后台线程 {@link #load(Context)}（秒级~十秒级）→ {@link #recognize}（秒级~十秒级，
 * 调用方须做超时保护并可通过 {@link #requestStop()} 打断）。所有方法串行化。
 */
public final class VlmOcrEngine {
    private static final String TAG = "VlmOcrEngine";
    static final int DEGENERATE_RUN_LIMIT = 48;

    public static final String MODEL_DIR_NAME = "glm-ocr-mnn";
    /** 全文转写触发词（GLM-OCR 官方 SDK 默认值）。 */
    public static final String TRANSCRIBE_PROMPT = VlmSettings.DEFAULT_PROMPT;

    /** 加载必需文件（缺一则视为未部署）。 */
    private static final String[] REQUIRED_FILES = {
            "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
            "visual.mnn", "visual.mnn.weight", "embeddings_bf16.bin", "tokenizer.txt",
    };

    private final GlmOcrSession session = new GlmOcrSession();
    private String initError;
    private long loadMs = -1;
    private volatile boolean stopRequested;
    private VlmSettings settings = VlmSettings.defaults();

    /** 模型目录：{@code <外部私有目录>/models/glm-ocr-mnn}。 */
    public static File modelDir(Context context) {
        File base = context.getExternalFilesDir("models");
        if (base == null) base = new File(context.getFilesDir(), "models");
        return new File(base, MODEL_DIR_NAME);
    }

    /** 缺哪个文件返回哪个文件名；齐全返回 null。纯 JVM，可单测。 */
    public static String missingModelFile(File dir) {
        if (dir == null || !dir.isDirectory()) return "(目录不存在)";
        for (String name : REQUIRED_FILES) {
            if (!new File(dir, name).isFile()) return name;
        }
        return null;
    }

    /** 组全文转写 prompt：图片以 <img>绝对路径</img> 内嵌。纯 JVM，可单测。 */
    public static String buildTranscribePrompt(File imageFile) {
        return buildTranscribePrompt(imageFile, TRANSCRIBE_PROMPT);
    }

    public static String buildTranscribePrompt(File imageFile, String prompt) {
        return "<img>" + imageFile.getAbsolutePath() + "</img>" + prompt;
    }

    /** 默认配置 JSON，保留给纯 JVM 测试和诊断。 */
    static String defaultConfigJson() {
        return VlmSettings.defaults().configJson();
    }

    public synchronized boolean isReady() {
        return session.isLoaded();
    }

    public synchronized String initError() {
        return initError;
    }

    /** 上次模型加载耗时（毫秒），未加载为 -1。 */
    public synchronized long loadMs() {
        return loadMs;
    }

    public synchronized VlmSettings settings() {
        return settings;
    }

    public synchronized long timeoutMs() {
        return settings.timeoutMs();
    }

    /** 请求停止当前识别（流式回调在下一个 token 生效）。 */
    public void requestStop() {
        stopRequested = true;
    }

    /** 加载模型（幂等）。失败只记录 initError，不抛出。 */
    public synchronized void load(Context context) {
        if (session.isLoaded() || initError != null) return;
        settings = VlmSettingsStore.load(context);
        File dir = modelDir(context);
        String missing = missingModelFile(dir);
        if (missing != null) {
            initError = "模型未部署（缺 " + missing + "）：" + dir.getAbsolutePath();
            Log.w(TAG, initError);
            return;
        }
        if (!GlmOcrSession.isNativeAvailable()) {
            initError = "native 库 libglmocr 不可用";
            Log.e(TAG, initError);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            session.load(new File(dir, "config.json").getAbsolutePath(), settings.configJson());
            session.setMaxNewTokens(settings.maxNewTokens);
            loadMs = System.currentTimeMillis() - start;
            Log.i(TAG, "GLM-OCR loaded in " + loadMs + "ms; backend=" + settings.backend
                    + ", threads=" + settings.threadCount + ", maxTokens="
                    + settings.maxNewTokens);
        } catch (Throwable error) {
            initError = "加载失败：" + error.getMessage();
            Log.e(TAG, initError, error);
        }
    }

    /**
     * 识别单张标签图。阻塞调用，必须在后台线程；调用方负责超时（配合 requestStop）。
     *
     * @param frame    LabelFrameProcessor 处理后的标签图
     * @param tempDir  临时目录（写 vlm_input.png）
     * @param listener 流式增量回调（可为 null）
     */
    public synchronized VlmResult recognize(Bitmap frame, File tempDir,
                                            GlmOcrSession.ProgressListener listener) {
        long start = System.currentTimeMillis();
        if (!session.isLoaded()) {
            return VlmResult.failure(initError != null ? initError : "模型未加载", 0);
        }
        File imageFile = new File(tempDir, "vlm_input.png");
        try (FileOutputStream out = new FileOutputStream(imageFile)) {
            if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return VlmResult.failure("图片编码失败",
                        System.currentTimeMillis() - start);
            }
        } catch (IOException error) {
            return VlmResult.failure("临时图写入失败：" + error.getMessage(),
                    System.currentTimeMillis() - start);
        }
        stopRequested = false;
        String prompt = buildTranscribePrompt(imageFile, settings.prompt);
        Log.i(TAG, "submit prompt: " + prompt);
        StringBuilder observed = new StringBuilder();
        boolean[] degenerate = {false};
        HashMap<String, Object> raw = session.submit(prompt, chunk -> {
            if (chunk != null) {
                observed.append(chunk);
                if (hasDegenerateCharacterRun(observed, DEGENERATE_RUN_LIMIT)) {
                    degenerate[0] = true;
                    stopRequested = true;
                    Log.e(TAG, "generation stopped: repeated character run >= "
                            + DEGENERATE_RUN_LIMIT);
                    return true;
                }
                if (listener != null) listener.onProgress(chunk);
            }
            return stopRequested;
        });
        long totalMs = System.currentTimeMillis() - start;
        if (degenerate[0]) {
            return VlmResult.failure("模型输出退化（连续重复字符）；请提高采样惩罚或调整提示词",
                    totalMs);
        }
        Object error = raw.get("error");
        if (error != null) {
            Log.e(TAG, "submit error: " + error);
            return VlmResult.failure(String.valueOf(error), totalMs);
        }
        return new VlmResult(
                String.valueOf(raw.getOrDefault("text", "")),
                longValue(raw, "prompt_len"), longValue(raw, "decode_len"),
                longValue(raw, "vision_time") / 1000, longValue(raw, "prefill_time") / 1000,
                longValue(raw, "decode_time") / 1000, totalMs,
                raw.containsKey("stopped"), null);
    }

    private static long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /** 检测连续同一 Unicode 字符；用于终止单 token 退化，不修改或伪造识别正文。 */
    static boolean hasDegenerateCharacterRun(CharSequence text, int limit) {
        if (text == null || limit <= 1) return false;
        int previous = -1;
        int run = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            offset += Character.charCount(codePoint);
            if (codePoint == previous) {
                run++;
            } else {
                previous = codePoint;
                run = 1;
            }
            if (run >= limit) return true;
        }
        return false;
    }

    public synchronized void close() {
        session.release();
    }
}

package com.otool.eprint.scan.qwen;

import android.content.Context;
import android.util.Log;

import com.otool.eprint.scan.vlm.GlmOcrSession;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Qwen3.5-2B-MNN 的纯文本运行边界。
 *
 * <p>该类只接受普通字符串，不提供 Bitmap/File/image API；图片标签、视觉 token 与多模态
 * content 会在进入 JNI 前被拒绝。native 结果的 vision_time 必须严格为 0，否则本次结果失败。
 * 官方视觉文件仍保留并校验，以保证固定 revision 的快照完整性。</p>
 */
public final class QwenTextOnlyEngine {
    private static final String TAG = "QwenTextOnlyEngine";

    public static final String MODEL_ID = "taobao-mnn/Qwen3.5-2B-MNN";
    public static final String REVISION = "35781816d7b6a9dcb273a6765ac9563401951c3c";
    public static final String MODEL_DIR_NAME = "qwen3.5-2b-mnn";
    public static final String TEXT_CONFIG_FILE = "config-text-only.json";
    public static final String MANIFEST_FILE = "model_manifest.json";
    public static final int MAX_PROMPT_CHARS = 16_000;
    /** 兼容旧调用方：等于当前推荐默认值；可配置上限见 {@link QwenSettings}. */
    public static final int MAX_NEW_TOKENS = QwenSettings.defaults().maxNewTokens;

    private static final class FileSpec {
        final long bytes;
        final String sha256;

        FileSpec(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final LinkedHashMap<String, FileSpec> EXPECTED_FILES = expectedFiles();

    private final GlmOcrSession session = new GlmOcrSession();
    private volatile boolean stopRequested;
    private String initError;
    private long loadMs = -1;
    private QwenSettings activeSettings = QwenSettings.defaults();
    private String verifiedDeploymentFingerprint;

    public static File modelDir(Context context) {
        File base = context.getExternalFilesDir("models");
        if (base == null) base = new File(context.getFilesDir(), "models");
        return new File(base, MODEL_DIR_NAME);
    }

    /** 缺失文件名；目录和文件均存在时返回 null，不在 UI 线程做大文件哈希。 */
    public static String missingModelFile(File dir) {
        if (dir == null || !dir.isDirectory()) return "(目录不存在)";
        if (!new File(dir, MANIFEST_FILE).isFile()) return MANIFEST_FILE;
        for (String name : EXPECTED_FILES.keySet()) {
            if (!new File(dir, name).isFile()) return name;
        }
        return null;
    }

    /** 返回禁止原因；合法 text-only prompt 返回 null。 */
    public static String forbiddenPromptReason(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) return "prompt 为空";
        if (prompt.length() > MAX_PROMPT_CHARS) return "prompt 超过 " + MAX_PROMPT_CHARS + " 字符";
        String lower = prompt.toLowerCase(Locale.ROOT);
        String[] forbidden = {
                "<img", "</img", "<|vision_start|>", "<|vision_end|>",
                "<|image_pad|>", "<|video_pad|>", "image_url", "\"image\":"
        };
        for (String marker : forbidden) {
            if (lower.contains(marker)) return "text-only 禁止标记：" + marker;
        }
        return null;
    }

    static String runtimeConfigJson() {
        return runtimeConfigJson(QwenSettings.defaults());
    }

    static String runtimeConfigJson(QwenSettings settings) {
        QwenSettings value = settings == null ? QwenSettings.defaults() : settings;
        return "{\"backend_type\":\"cpu\",\"thread_num\":" + value.threadCount + ","
                + "\"precision\":\"low\",\"memory\":\"low\",\"async\":false,"
                + "\"max_new_tokens\":" + value.maxNewTokens + ","
                + "\"sampler_type\":\"penalty\",\"penalty\":1.05,"
                + "\"repetition_penalty\":1.05,\"penalty_sampler\":\"greedy\","
                + "\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    }

    public synchronized void load(Context context) {
        load(context, QwenSettings.defaults());
    }

    public synchronized void load(Context context, QwenSettings settings) {
        if (session.isLoaded() || initError != null) return;
        activeSettings = settings == null ? QwenSettings.defaults() : settings;
        long start = System.currentTimeMillis();
        File dir = modelDir(context);
        String missing = missingModelFile(dir);
        if (missing != null) {
            initError = "模型未部署（缺 " + missing + "）：" + dir.getAbsolutePath();
            return;
        }
        String deploymentIssue = verifyDeploymentCached(dir);
        if (deploymentIssue != null) {
            initError = deploymentIssue;
            return;
        }
        if (!GlmOcrSession.isNativeAvailable()) {
            initError = "native 库 libglmocr 不可用";
            return;
        }
        try {
            session.load(new File(dir, TEXT_CONFIG_FILE).getAbsolutePath(),
                    runtimeConfigJson(activeSettings));
            session.setMaxNewTokens(activeSettings.maxNewTokens);
            loadMs = System.currentTimeMillis() - start;
            Log.i(TAG, "loaded " + MODEL_ID + "@" + REVISION + " in " + loadMs
                    + "ms; input=text-only; threads=" + activeSettings.threadCount
                    + "; max_new_tokens=" + activeSettings.maxNewTokens);
        } catch (Throwable error) {
            session.release();
            initError = "Qwen 加载失败：" + error.getMessage();
            Log.e(TAG, initError, error);
        }
    }

    public synchronized boolean isReady() {
        return session.isLoaded();
    }

    public synchronized String initError() {
        return initError;
    }

    public synchronized long loadMs() {
        return loadMs;
    }

    public void requestStop() {
        stopRequested = true;
    }

    public synchronized QwenTextResult generate(
            String prompt, GlmOcrSession.ProgressListener listener) {
        long start = System.currentTimeMillis();
        String forbidden = forbiddenPromptReason(prompt);
        if (forbidden != null) return QwenTextResult.failure(forbidden, 0);
        if (!session.isLoaded()) {
            return QwenTextResult.failure(initError != null ? initError : "Qwen 模型未加载", 0);
        }
        stopRequested = false;
        HashMap<String, Object> raw = session.submit(prompt, chunk -> {
            boolean listenerStop = listener != null && listener.onProgress(chunk);
            stopRequested = stopRequested || listenerStop;
            return stopRequested;
        });
        long totalMs = System.currentTimeMillis() - start;
        Object nativeError = raw.get("error");
        if (nativeError != null) {
            return QwenTextResult.failure(String.valueOf(nativeError), totalMs);
        }
        long visionUs = longValue(raw, "vision_time");
        String text = stripTrailingEop(String.valueOf(raw.getOrDefault("text", "")));
        String error = null;
        if (visionUs != 0) {
            error = "text-only 约束失败：vision_time=" + visionUs + "us";
        } else if (text.contains("<think>") || text.contains("</think>")) {
            error = "non-thinking 约束失败：输出包含 think 标记";
        }
        return new QwenTextResult(text,
                longValue(raw, "prompt_len"), longValue(raw, "decode_len"),
                visionUs / 1000, longValue(raw, "prefill_time") / 1000,
                longValue(raw, "decode_time") / 1000, totalMs,
                raw.containsKey("stopped"), error);
    }

    public synchronized void release() {
        session.release();
        initError = null;
        loadMs = -1;
        stopRequested = false;
        activeSettings = QwenSettings.defaults();
    }

    static String stripTrailingEop(String text) {
        String clean = text == null ? "" : text;
        while (clean.endsWith("<eop>")) {
            clean = clean.substring(0, clean.length() - "<eop>".length());
        }
        return clean;
    }

    private static String verifyDeployment(File dir) {
        try {
            JSONObject manifest = new JSONObject(readUtf8(new File(dir, MANIFEST_FILE)));
            if (!MODEL_ID.equals(manifest.optString("modelId"))) return "模型 manifest id 不匹配";
            if (!REVISION.equals(manifest.optString("revision"))) return "模型 revision 不匹配";
            if (!"text-only".equals(manifest.optString("input_mode"))) return "manifest 不是 text-only";
            if (!"forbidden".equals(manifest.optString("vision_input"))) return "manifest 未禁止视觉输入";
            if (manifest.optInt("require_vision_time_us", -1) != 0) {
                return "manifest 未要求 vision_time_us=0";
            }
            for (Map.Entry<String, FileSpec> entry : EXPECTED_FILES.entrySet()) {
                File file = new File(dir, entry.getKey());
                FileSpec expected = entry.getValue();
                if (file.length() != expected.bytes) {
                    return "模型文件大小错误：" + entry.getKey();
                }
                if (!expected.sha256.equals(sha256(file))) {
                    return "模型文件 SHA-256 错误：" + entry.getKey();
                }
            }
            return null;
        } catch (Exception error) {
            return "模型校验失败：" + error.getMessage();
        }
    }

    /** 同一进程内模型文件元数据未变化时复用已完成的全量 SHA-256，避免 GLM/Qwen 串行切换重复哈希。 */
    private synchronized String verifyDeploymentCached(File dir) {
        String fingerprint = deploymentFingerprint(dir);
        if (fingerprint.equals(verifiedDeploymentFingerprint)) return null;
        String issue = verifyDeployment(dir);
        if (issue == null) verifiedDeploymentFingerprint = fingerprint;
        return issue;
    }

    private static String deploymentFingerprint(File dir) {
        StringBuilder value = new StringBuilder(dir.getAbsolutePath());
        File manifest = new File(dir, MANIFEST_FILE);
        value.append('|').append(manifest.length()).append('@').append(manifest.lastModified());
        for (String name : EXPECTED_FILES.keySet()) {
            File file = new File(dir, name);
            value.append('|').append(name).append(':').append(file.length())
                    .append('@').append(file.lastModified());
        }
        return value.toString();
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private static LinkedHashMap<String, FileSpec> expectedFiles() {
        LinkedHashMap<String, FileSpec> files = new LinkedHashMap<>();
        files.put(".gitattributes", new FileSpec(1642,
                "200966173edc40adcf63cfc6b016dad6bd8a8447893ec636a914363d188d8f82"));
        files.put("README.md", new FileSpec(1171,
                "d484d35e51037538596096a34bc32e9918978fed1589e506e346c147581d0d72"));
        files.put("config.json", new FileSpec(652,
                "92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d"));
        files.put("export_args.json", new FileSpec(1040,
                "a5b3a7d2c45e53c887e539259696d5fcec793637c9a3e9fbc7cd0dd008af6a87"));
        files.put("llm.mnn", new FileSpec(2148136,
                "23df98f8b341b277365e0bbca025c1d192939e3d32d7f79776352c6f32e77960"));
        files.put("llm.mnn.json", new FileSpec(5344018,
                "7131ff4f1a441add1039d371815ad94652ae6801f579591cb2f9ad90b5954025"));
        files.put("llm.mnn.weight", new FileSpec(1176647702L,
                "c93f71a2dbecf9328782bd38861656d8faa82e95e7f99607350074768a482054"));
        files.put("llm_config.json", new FileSpec(8692,
                "a88234b36c2af0eff8e5c89667011badf71c15e30459eb0e21030a8f3f9ed240"));
        files.put("tokenizer.txt", new FileSpec(6465727,
                "7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4"));
        files.put("visual.mnn", new FileSpec(488096,
                "88fc40a7b676e90eb2cb86d854db15cb90b9eb1f34087ab0f48c5e43572c8dac"));
        files.put("visual.mnn.weight", new FileSpec(195587264,
                "8f90e106f5b9ae9a939faed240305cfdd5c6740ae91d3fc418a990bee0cce36b"));
        files.put(TEXT_CONFIG_FILE, new FileSpec(460,
                "407eff87907dee8b0ceab22f27cf54b533a69a3ae218c2552bbac3d4ee11e64e"));
        return files;
    }
}

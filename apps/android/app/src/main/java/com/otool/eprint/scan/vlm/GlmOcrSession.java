package com.otool.eprint.scan.vlm;

import java.util.HashMap;

/**
 * MNN-LLM 3.6.1 预编译 libllm 的 native 会话薄封装。
 * 对应 C++ 侧 {@code app/src/main/cpp/glm_ocr_jni.cpp}（参照 MnnLlmChat Apache-2.0 裁剪）。
 *
 * 兼容期类名保留为 GlmOcrSession；GLM 视觉 OCR 与 Qwen text-only 均可复用普通文本 submit。
 * 线程模型：所有方法 synchronized，调用方保证单线程使用。
 * native 库缺失（如单测环境）时 {@link #isNativeAvailable()} 返回 false，不抛异常。
 */
public final class GlmOcrSession {

    /** 流式增量回调。chunk 为新增文本；chunk == null 表示生成结束。返回 true 请求停止。 */
    public interface ProgressListener {
        boolean onProgress(String chunk);
    }

    private static Boolean nativeAvailable;

    public static synchronized boolean isNativeAvailable() {
        if (nativeAvailable == null) {
            try {
                System.loadLibrary("glmocr");
                nativeAvailable = Boolean.TRUE;
            } catch (UnsatisfiedLinkError error) {
                nativeAvailable = Boolean.FALSE;
            }
        }
        return nativeAvailable;
    }

    private long nativePtr;

    /**
     * 加载模型（耗时数秒，需后台线程）。
     *
     * @param configPath 模型 config.json 绝对路径
     * @param configJson 覆盖 config.json 的 JSON（如 backend_type/thread_num/max_new_tokens），可空
     * @throws IllegalStateException 加载失败（native 层抛出）
     */
    public synchronized void load(String configPath, String configJson) {
        if (nativePtr != 0) return;
        nativePtr = nativeInit(configPath, configJson);
    }

    public synchronized boolean isLoaded() {
        return nativePtr != 0;
    }

    /**
     * 提交一次识别（阻塞至生成结束或被 listener 中止）。
     *
     * @return HashMap：text(全文)、prompt_len、decode_len、vision_time/prefill_time/decode_time(µs)、
     *         或 error（失败/未加载）、stopped（被中止）
     */
    public synchronized HashMap<String, Object> submit(String prompt, ProgressListener listener) {
        if (nativePtr == 0) {
            HashMap<String, Object> error = new HashMap<>();
            error.put("error", "session not loaded");
            return error;
        }
        return nativeSubmit(nativePtr, prompt, listener);
    }

    /** 单次生成的输出 token 上限（wrapper 侧强制，默认 1024）。 */
    public synchronized void setMaxNewTokens(int maxNewTokens) {
        if (nativePtr != 0) nativeSetMaxNewTokens(nativePtr, maxNewTokens);
    }

    /** 运行时更新引擎配置（precision / backend_type / thread_num 等），返回是否被引擎接受。 */
    public synchronized boolean updateConfig(String configJson) {
        return nativePtr != 0 && nativeUpdateConfig(nativePtr, configJson);
    }

    public synchronized void release() {
        if (nativePtr != 0) {
            nativeRelease(nativePtr);
            nativePtr = 0;
        }
    }

    private native long nativeInit(String configPath, String configJson);

    private native HashMap<String, Object> nativeSubmit(long sessionPtr, String prompt,
                                                        ProgressListener listener);

    private native void nativeReset(long sessionPtr);

    private native void nativeRelease(long sessionPtr);

    private native void nativeSetMaxNewTokens(long sessionPtr, int maxNewTokens);

    private native boolean nativeUpdateConfig(long sessionPtr, String configJson);
}

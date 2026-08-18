package com.otool.eprint.scan.vlm;

/** 可持久化的 GLM-OCR 运行参数。构造时统一校验，避免无效配置传入 native。 */
public final class VlmSettings {
    public static final String DEFAULT_PROMPT = "Text Recognition:";

    public static final String[] BACKEND_VALUES = {"cpu", "opencl", "vulkan"};
    public static final String[] BACKEND_LABELS = {"CPU（兼容优先）", "OpenCL GPU", "Vulkan GPU"};
    public static final String[] PRECISION_VALUES = {"low", "normal", "high"};
    public static final String[] PRECISION_LABELS = {"低（FP16 优先）", "标准", "高"};
    public static final String[] MEMORY_VALUES = {"low", "normal", "high"};
    public static final String[] MEMORY_LABELS = {"低内存", "标准", "高性能"};

    public final String backend;
    public final int threadCount;
    public final String precision;
    public final String memory;
    public final int maxNewTokens;
    public final int timeoutSeconds;
    public final float repetitionPenalty;
    public final float presencePenalty;
    public final float frequencyPenalty;
    public final String prompt;

    public VlmSettings(String backend, int threadCount, String precision, String memory,
                       int maxNewTokens, int timeoutSeconds, float repetitionPenalty,
                       float presencePenalty, float frequencyPenalty, String prompt) {
        this.backend = knownValue(backend, BACKEND_VALUES, "cpu");
        this.threadCount = clamp(threadCount, 1, 8);
        this.precision = knownValue(precision, PRECISION_VALUES, "low");
        this.memory = knownValue(memory, MEMORY_VALUES, "low");
        this.maxNewTokens = clamp(maxNewTokens, 64, 2048);
        this.timeoutSeconds = clamp(timeoutSeconds, 10, 300);
        this.repetitionPenalty = clamp(repetitionPenalty, 1.0f, 3.0f);
        this.presencePenalty = clamp(presencePenalty, 0.0f, 2.0f);
        this.frequencyPenalty = clamp(frequencyPenalty, 0.0f, 2.0f);
        String cleanPrompt = prompt == null ? "" : prompt.trim();
        this.prompt = cleanPrompt.isEmpty() ? DEFAULT_PROMPT : cleanPrompt;
    }

    public static VlmSettings defaults() {
        // GLM-OCR 原始 penalty=1.1 在部分电子料标签上会陷入单 token 循环。
        // 该组确定性 penalty 参数经 SM-X800 真机复现图验证，可让模型恢复正文生成。
        return new VlmSettings("cpu", 4, "low", "low", 1024, 60,
                1.6f, 0.8f, 0.4f, DEFAULT_PROMPT);
    }

    public long timeoutMs() {
        return timeoutSeconds * 1000L;
    }

    /**
     * 传给 MNN set_config 的受控 JSON。采样保持 greedy，避免 OCR 文本随机漂移；
     * penalty/presence/frequency 只用于打破已在真机复现的重复 token 退化。
     * 视觉编码与 LLM 运行时必须同步完成：MNN 官方 llm_demo 同样在正式推理前关闭 async，
     * 否则独立 processor runtime 的视觉 embedding 可能尚未物化就进入解码。
     */
    public String configJson() {
        return "{\"backend_type\":\"" + backend + "\"," 
                + "\"thread_num\":" + threadCount + ","
                + "\"precision\":\"" + precision + "\"," 
                + "\"memory\":\"" + memory + "\"," 
                + "\"max_new_tokens\":" + maxNewTokens + ","
                + "\"sampler_type\":\"penalty\","
                + "\"penalty\":" + repetitionPenalty + ","
                + "\"repetition_penalty\":" + repetitionPenalty + ","
                + "\"presence_penalty\":" + presencePenalty + ","
                + "\"frequency_penalty\":" + frequencyPenalty + ","
                + "\"penalty_sampler\":\"greedy\","
                + "\"async\":false}";
    }

    public static int indexOf(String value, String[] values) {
        if (value != null) {
            for (int index = 0; index < values.length; index++) {
                if (value.equals(values[index])) return index;
            }
        }
        return 0;
    }

    private static String knownValue(String value, String[] values, String fallback) {
        for (String candidate : values) {
            if (candidate.equals(value)) return value;
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}

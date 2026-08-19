package com.otool.eprint.scan.vlm;

/** 可持久化的 GLM-OCR 运行参数。构造时统一校验，避免无效配置传入 native。 */
public final class VlmSettings {
    /** v1.5.17~v1.5.26 持久化过的旧默认提示词，用于覆盖安装后的无感迁移。 */
    static final String LEGACY_EXTRACTION_PROMPT_V1 =
            "请按下列 JSON 格式输出电子元器件标签信息，只输出 JSON：\n"
            + "{\n"
            + "  \"manufacturer_part_number\": \"\",\n"
            + "  \"lcsc_part_number\": \"\",\n"
            + "  \"category\": \"\",\n"
            + "  \"specification\": \"\",\n"
            + "  \"package\": \"\",\n"
            + "  \"brand\": \"\",\n"
            + "  \"parameters\": [{\"name\": \"\", \"value\": \"\"}]\n"
            + "}\n"
            + "必须输出全部七个字段；未识别值用空字符串，parameters 无内容用空数组，不得猜测。\n"
            + "specification 汇总关键规格，parameters 列出其余可见技术参数。\n"
            + "忽略数量、订单、批次、追踪、仓储编码及二维码/一维码内容；不要输出 Markdown 或说明。";

    /**
     * GLM-OCR 官方“信息提取”模式要求严格 JSON。该 schema 覆盖三档模板的全部技术字段，
     * parameters 继续承载不同器件特有参数，保证一次识别可供全部模板取用。
     */
    public static final String DEFAULT_PROMPT =
            "从图片提取电子元器件标签，只输出一个 JSON 对象。先逐字符核对，再按字段语义归类；"
            + "不得仅按所在行或字号猜字段。\n"
            + "字段规则：\n"
            + "1. manufacturer_part_number：器件厂商原始型号/MPN，保持完整且不得拆分。"
            + "立创标签中形如“4位数字-字母-字母数字-3位数字”的首行内部/仓储编码不是型号，"
            + "必须忽略。\n"
            + "2. lcsc_part_number：立创料号，只接受大写 C 加至少 5 位数字。\n"
            + "3. category：标签明确写出的器件品类。\n"
            + "4. specification：电气规格/参数值，例如 12pF ±5% 50V；不得包含 ROHS、"
            + "数量、封装、型号或换行拼接的其他字段。\n"
            + "5. package：独立封装代码，例如 0402、SMD5050。\n"
            + "6. brand：标签明确写出的品牌/厂商。\n"
            + "7. parameters：仅放除 specification 外、标签明确给出名称和值的技术参数；"
            + "不得放型号、品牌、品类、封装或内部编码。\n"
            + "输出格式：\n"
            + "{\n"
            + "  \"manufacturer_part_number\": \"\",\n"
            + "  \"lcsc_part_number\": \"\",\n"
            + "  \"category\": \"\",\n"
            + "  \"specification\": \"\",\n"
            + "  \"package\": \"\",\n"
            + "  \"brand\": \"\",\n"
            + "  \"parameters\": [{\"name\": \"\", \"value\": \"\"}]\n"
            + "}\n"
            + "必须输出全部七个字段；未识别值用空字符串，parameters 无内容用空数组。"
            + "不得猜测、改写字符或输出 Markdown/说明。忽略数量、订单、批次、追踪、"
            + "ROHS/合规文字、内部/仓储编码及二维码/一维码内容。";
    static final String LEGACY_TRANSCRIBE_PROMPT = "Text Recognition:";

    public static final String[] BACKEND_VALUES = {"cpu", "opencl", "vulkan"};
    // SM-X800（Adreno 730）+ MNN 3.6.1 实测：int4 量化的 GLM-OCR 在 GPU 后端显著慢于 CPU
    // （详见 docs/refactor_gpu_opencv_0818.md benchmark），GPU 选项保留供其他设备尝试。
    public static final String[] BACKEND_LABELS = {
            "CPU（兼容优先，SM-X800 实测最快）", "OpenCL GPU（实测慢于 CPU）", "Vulkan GPU（实测慢于 CPU）"};
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
        this.prompt = completeExtractionPrompt(prompt);
    }

    public static VlmSettings defaults() {
        // 信息提取包含大量重复 JSON 标点和键名；使用接近官方默认的轻量重复惩罚，
        // 避免高 presence/frequency penalty 破坏严格 schema。
        return new VlmSettings("cpu", 4, "low", "low", 1024, 60,
                1.1f, 0.0f, 0.0f, DEFAULT_PROMPT);
    }

    public long timeoutMs() {
        return timeoutSeconds * 1000L;
    }

    /**
     * 传给 MNN set_config 的受控 JSON。采样保持 greedy，避免 OCR 文本随机漂移；
     * penalty/presence/frequency 只用于打破已在真机复现的重复 token 退化。
     * 视觉编码与 LLM 运行时必须同步完成：MNN 官方 llm_demo 同样在正式推理前关闭 async，
     * 否则独立 processor runtime 的视觉 embedding 可能尚未物化就进入解码。
     * mllm 子块让视觉编码器跟随所选后端（官方 config.json 中该块独立存在，不随顶层覆盖）。
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
                + "\"async\":false,"
                + "\"mllm\":{\"backend_type\":\"" + backend + "\","
                + "\"thread_num\":" + threadCount + ","
                + "\"precision\":\"normal\","
                + "\"memory\":\"low\"}}";
    }

    /** 仅替换推理后端的副本（其余参数原样保留），用于 GPU 加载失败后的 CPU 回退。 */
    public VlmSettings withBackend(String newBackend) {
        return new VlmSettings(newBackend, threadCount, precision, memory, maxNewTokens,
                timeoutSeconds, repetitionPenalty, presencePenalty, frequencyPenalty, prompt);
    }

    public static int indexOf(String value, String[] values) {
        if (value != null) {
            for (int index = 0; index < values.length; index++) {
                if (value.equals(values[index])) return index;
            }
        }
        return 0;
    }

    /** 旧“全文转写”配置自动迁移；自定义要求保留，但不能移除固定的完整 JSON schema。 */
    static String completeExtractionPrompt(String prompt) {
        String clean = prompt == null ? "" : prompt.trim();
        if (clean.isEmpty() || isLegacyTranscribePrompt(clean)
                || LEGACY_EXTRACTION_PROMPT_V1.equals(clean)) {
            return DEFAULT_PROMPT;
        }
        if (hasCompleteSchema(clean)) return clean;
        return clean + "\n\n" + DEFAULT_PROMPT;
    }

    static boolean isLegacyTranscribePrompt(String prompt) {
        return prompt != null && LEGACY_TRANSCRIBE_PROMPT.equalsIgnoreCase(prompt.trim());
    }

    private static boolean hasCompleteSchema(String prompt) {
        return prompt.contains("manufacturer_part_number")
                && prompt.contains("lcsc_part_number")
                && prompt.contains("category")
                && prompt.contains("specification")
                && prompt.contains("package")
                && prompt.contains("brand")
                && prompt.contains("parameters");
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

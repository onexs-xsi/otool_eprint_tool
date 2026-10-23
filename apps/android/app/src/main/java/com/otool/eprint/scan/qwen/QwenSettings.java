package com.otool.eprint.scan.qwen;

/** Qwen3.5 text-only 后处理配置；构造时统一收敛范围，避免无效值进入 native。 */
public final class QwenSettings {
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 8;
    public static final int MIN_NEW_TOKENS = 64;
    public static final int MAX_NEW_TOKENS = 512;
    public static final int MIN_TIMEOUT_SECONDS = 10;
    public static final int MAX_TIMEOUT_SECONDS = 180;
    public static final int MAX_EXTRA_INSTRUCTIONS_CHARS = 1_000;

    public final boolean enabled;
    public final boolean onlyWhenCoreFieldsMissing;
    public final boolean allowEvidenceBackedCorrections;
    public final boolean requireCatalogMatch;
    public final int threadCount;
    public final int maxNewTokens;
    public final int timeoutSeconds;
    public final String extraInstructions;

    public QwenSettings(boolean enabled, boolean onlyWhenCoreFieldsMissing,
                        boolean allowEvidenceBackedCorrections, boolean requireCatalogMatch,
                        int threadCount, int maxNewTokens, int timeoutSeconds,
                        String extraInstructions) {
        this.enabled = enabled;
        this.allowEvidenceBackedCorrections = allowEvidenceBackedCorrections;
        // 纠错必须检查“字段完整但明显错误”的记录，因此与“仅缺失时调用”互斥。
        this.onlyWhenCoreFieldsMissing = onlyWhenCoreFieldsMissing
                && !allowEvidenceBackedCorrections;
        this.requireCatalogMatch = requireCatalogMatch;
        this.threadCount = clamp(threadCount, MIN_THREADS, MAX_THREADS);
        this.maxNewTokens = clamp(maxNewTokens, MIN_NEW_TOKENS, MAX_NEW_TOKENS);
        this.timeoutSeconds = clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        String clean = extraInstructions == null ? "" : extraInstructions.trim();
        this.extraInstructions = clean.length() <= MAX_EXTRA_INSTRUCTIONS_CHARS
                ? clean : clean.substring(0, MAX_EXTRA_INSTRUCTIONS_CHARS);
    }

    /** 默认每次 OCR 后都实际运行，落盘仍保持 fill-only 与严格证据校验。 */
    public static QwenSettings defaults() {
        return new QwenSettings(true, false, false, true,
                4, 192, 60, "");
    }

    public long timeoutMs() {
        return timeoutSeconds * 1000L;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

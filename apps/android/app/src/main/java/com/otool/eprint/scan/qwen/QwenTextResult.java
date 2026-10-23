package com.otool.eprint.scan.qwen;

/** Qwen text-only 单次生成结果及 MNN 性能指标。 */
public final class QwenTextResult {
    public final String text;
    public final long promptTokens;
    public final long decodeTokens;
    public final long visionMs;
    public final long prefillMs;
    public final long decodeMs;
    public final long totalMs;
    public final boolean stopped;
    public final String error;

    public QwenTextResult(String text, long promptTokens, long decodeTokens,
                          long visionMs, long prefillMs, long decodeMs, long totalMs,
                          boolean stopped, String error) {
        this.text = text;
        this.promptTokens = promptTokens;
        this.decodeTokens = decodeTokens;
        this.visionMs = visionMs;
        this.prefillMs = prefillMs;
        this.decodeMs = decodeMs;
        this.totalMs = totalMs;
        this.stopped = stopped;
        this.error = error;
    }

    public static QwenTextResult failure(String error, long totalMs) {
        return new QwenTextResult("", 0, 0, 0, 0, 0, totalMs, false, error);
    }

    public boolean isOk() {
        return error == null;
    }

    public String timingSummary() {
        return "prompt " + promptTokens + " tok · decode " + decodeTokens
                + " tok · prefill " + prefillMs + "ms · decode " + decodeMs
                + "ms · 总计 " + totalMs + "ms";
    }
}

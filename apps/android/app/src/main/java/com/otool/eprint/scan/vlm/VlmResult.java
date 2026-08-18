package com.otool.eprint.scan.vlm;

/**
 * VLM（GLM-OCR）单图识别结果：全文 + benchmark 埋点。
 * error 非空表示失败（此时 text 可能为部分输出）。
 */
public final class VlmResult {
    public final String text;
    public final long promptTokens;
    public final long decodeTokens;
    /** 视觉编码耗时（毫秒）。 */
    public final long visionMs;
    public final long prefillMs;
    public final long decodeMs;
    /** 含图片落盘、组 prompt 的端到端耗时（毫秒）。 */
    public final long totalMs;
    /** 被超时/停止打断时仍有部分输出。 */
    public final boolean stopped;
    public final String error;

    public VlmResult(String text, long promptTokens, long decodeTokens,
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

    public static VlmResult failure(String error, long totalMs) {
        return new VlmResult("", 0, 0, 0, 0, 0, totalMs, false, error);
    }

    public boolean isOk() {
        return error == null;
    }

    /** 耗时摘要，如「prefill 1.2s · decode 3.4s(42 tok) · 共 5.1s」。 */
    public String timingSummary() {
        return "prefill " + prefillMs + "ms(" + promptTokens + " tok)"
                + " · decode " + decodeMs + "ms(" + decodeTokens + " tok)"
                + " · 共 " + totalMs + "ms";
    }
}

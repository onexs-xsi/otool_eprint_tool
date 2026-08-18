package com.otool.eprint.scan.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OCR 流水线结果：识别行 + 各阶段耗时（毫秒，benchmark 埋点）。纯 JVM。 */
public final class OcrResult {
    public final List<OcrLine> lines;
    public final long detMs;
    public final long clsMs;
    public final long recMs;
    public final long totalMs;

    public OcrResult(List<OcrLine> lines, long detMs, long clsMs, long recMs, long totalMs) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.detMs = detMs;
        this.clsMs = clsMs;
        this.recMs = recMs;
        this.totalMs = totalMs;
    }

    /** OCR 原文全文（按行拼接）。 */
    public String fullText() {
        StringBuilder text = new StringBuilder();
        for (OcrLine line : lines) {
            if (text.length() > 0) text.append('\n');
            text.append(line.text);
        }
        return text.toString();
    }

    /** 分阶段耗时摘要，如「检测 120ms · 方向 8ms · 识别 340ms · 共 470ms」。 */
    public String timingSummary() {
        return "检测 " + detMs + "ms · 方向 " + clsMs + "ms · 识别 " + recMs
                + "ms · 共 " + totalMs + "ms";
    }
}

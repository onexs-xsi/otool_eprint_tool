package com.otool.eprint.scan.ocr;

/**
 * 引擎无关的 OCR 行结果：文本 + 置信度 + 包围盒（像素坐标）。
 * PP-OCR / ML Kit / 端侧 VLM 等任何引擎的输出都归一化为该结构，
 * 上游（LabelTextParser 等）不感知引擎差异。纯 JVM 可单测。
 */
public final class OcrLine {
    public final String text;
    /** 0~1，引擎未提供时为 0。 */
    public final float confidence;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public OcrLine(String text, float confidence, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int width() {
        return Math.max(0, right - left);
    }

    public int height() {
        return Math.max(0, bottom - top);
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    /** 包围盒是否有效（部分引擎/降级链路可能不提供坐标）。 */
    public boolean hasRect() {
        return right > left && bottom > top;
    }
}

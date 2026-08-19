package com.otool.eprint.scan;

import com.otool.eprint.scan.ocr.OcrLine;

import java.util.ArrayList;
import java.util.List;

/**
 * GLM-OCR 全文转写（{@code Text Recognition:}）输出 → {@link OcrLine} 行序列
 * → {@link LabelTextParser} 规则归类。v1.5.28 起 VLM 默认链路（docs/ocr_optimize_0819.md）：
 * 0.9B int4 模型转写可靠（真机 12/12 行），字段归类交给规则，再与 PP-OCR 融合。
 * 纯 JVM，可单测。
 */
public final class VlmTranscriptionParser {
    private VlmTranscriptionParser() {}

    /**
     * 转写文本 → 统一器件记录；无任何有效行时返回 null（调用方按解析失败处理，
     * 不得用空记录冒充成功）。
     */
    public static ComponentRecord parse(String vlmText) {
        List<OcrLine> lines = toLines(vlmText);
        if (lines.isEmpty()) return null;
        return LabelTextParser.parse(lines);
    }

    /**
     * 按行拆分转写输出：剥除 Markdown 代码围栏与退化行（单字符连跑/占比过高），
     * 合成纵向递增的占位包围盒以保持阅读顺序（规则解析只依赖行序，不依赖真实坐标）。
     */
    public static List<OcrLine> toLines(String vlmText) {
        List<OcrLine> lines = new ArrayList<>();
        if (vlmText == null) return lines;
        for (String raw : vlmText.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("```")) continue;
            if (isDegenerateLine(line)) continue;
            int top = lines.size() * 24;
            lines.add(new OcrLine(line, 0.9f, 0, top, Math.max(line.length(), 1) * 12, top + 20));
        }
        return lines;
    }

    /**
     * 退化行判定：贪心解码偶发在开头吐出一段单字符重复垃圾（如 "3333…"）。
     * 长度 ≥6 且（最长同字符连跑 ≥8，或单字符占比 ≥70%）即视为退化行。
     * 阈值显著高于引擎 48 字熔断：熔断保长退化，本判定清理短残渣。
     */
    static boolean isDegenerateLine(String line) {
        int[] codePoints = line.codePoints().toArray();
        if (codePoints.length < 6) return false;
        int bestRun = 1;
        int run = 1;
        for (int i = 1; i < codePoints.length; i++) {
            run = codePoints[i] == codePoints[i - 1] ? run + 1 : 1;
            bestRun = Math.max(bestRun, run);
        }
        int bestCount = 1;
        for (int i = 0; i < codePoints.length; i++) {
            int count = 0;
            for (int k = 0; k < codePoints.length; k++) {
                if (codePoints[k] == codePoints[i]) count++;
            }
            bestCount = Math.max(bestCount, count);
        }
        return bestRun >= 8 || bestCount * 10 >= codePoints.length * 7;
    }
}

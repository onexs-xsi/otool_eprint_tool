package com.otool.eprint.scan.ocr;

import java.util.List;

/**
 * CTC greedy 解码器（纯 JVM，可单测）。
 * 输入识别模型每个时间步的字符概率分布 [T][C]，索引 0 为 CTC blank；
 * 去重 + 去 blank 后按字典映射为文本，置信度取非 blank 命中步的概率均值。
 */
public final class CtcDecoder {

    public static final class Result {
        public final String text;
        /** 0~1；无有效字符时为 0。 */
        public final float confidence;

        Result(String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private CtcDecoder() {}

    /**
     * @param probs 每个时间步各类别概率（或 logit，argmax 结果一致，置信度会偏差）；
     *              第一维是时间步，第二维是类别（长度 = dict.size() + 1，索引 0 为 blank）。
     * @param dict  字典，索引 i 对应类别 i+1。
     */
    public static Result decode(float[][] probs, List<String> dict) {
        if (probs == null || probs.length == 0) return new Result("", 0f);
        StringBuilder text = new StringBuilder();
        float confidenceSum = 0f;
        int charCount = 0;
        int lastIndex = 0; // 0 = blank
        for (float[] step : probs) {
            int best = argmax(step);
            float bestProb = step[best];
            if (best != 0 && best != lastIndex) {
                if (best - 1 < dict.size()) {
                    text.append(dict.get(best - 1));
                    confidenceSum += Math.min(1f, Math.max(0f, bestProb));
                    charCount++;
                }
            }
            lastIndex = best;
        }
        float confidence = charCount == 0 ? 0f : confidenceSum / charCount;
        return new Result(text.toString(), confidence);
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int index = 1; index < values.length; index++) {
            if (values[index] > values[best]) best = index;
        }
        return best;
    }
}

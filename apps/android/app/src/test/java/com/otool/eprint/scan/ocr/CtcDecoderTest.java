package com.otool.eprint.scan.ocr;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CtcDecoderTest {
    private static final List<String> DICT = Arrays.asList("a", "b", "c");

    /** 构造一步概率分布：index 处概率 p，其余为剩余均分。 */
    private static float[] step(int index, float p) {
        float[] step = new float[DICT.size() + 1];
        float rest = (1f - p) / DICT.size();
        Arrays.fill(step, rest);
        step[index] = p;
        return step;
    }

    @Test
    public void decode_skipsBlanksAndCollapsesRepeats() {
        // blank, a, a, blank, b, b, b, c → "abc"
        float[][] probs = {
                step(0, 0.9f),
                step(1, 0.8f),
                step(1, 0.7f), // 与上一步同标签，折叠
                step(0, 0.6f),
                step(2, 0.9f),
                step(2, 0.5f),
                step(2, 0.4f),
                step(3, 0.95f),
        };
        CtcDecoder.Result result = CtcDecoder.decode(probs, DICT);
        assertEquals("abc", result.text);
        // 置信度 = 各字符首个命中步概率均值：(0.8 + 0.9 + 0.95) / 3
        assertEquals((0.8f + 0.9f + 0.95f) / 3f, result.confidence, 1e-4f);
    }

    @Test
    public void decode_blankSeparatedRepeatsAreKept() {
        // a, blank, a → "aa"（blank 隔开不折叠）
        float[][] probs = {step(1, 0.9f), step(0, 0.9f), step(1, 0.8f)};
        CtcDecoder.Result result = CtcDecoder.decode(probs, DICT);
        assertEquals("aa", result.text);
    }

    @Test
    public void decode_allBlankReturnsEmpty() {
        float[][] probs = {step(0, 0.99f), step(0, 0.98f)};
        CtcDecoder.Result result = CtcDecoder.decode(probs, DICT);
        assertEquals("", result.text);
        assertEquals(0f, result.confidence, 1e-6f);
    }

    @Test
    public void decode_emptyInputReturnsEmpty() {
        CtcDecoder.Result result = CtcDecoder.decode(new float[0][0], DICT);
        assertEquals("", result.text);
        assertEquals(0f, result.confidence, 1e-6f);
    }

    @Test
    public void decode_ignoresOutOfDictIndex() {
        // 类别索引超出字典长度（模型输出维度多于字典）时不崩溃、不追加字符。
        float[] step = new float[DICT.size() + 3];
        step[DICT.size() + 2] = 0.99f;
        CtcDecoder.Result result = CtcDecoder.decode(new float[][]{step}, DICT);
        assertEquals("", result.text);
        assertTrue(result.confidence >= 0f);
    }
}

package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ocr.OcrLine;

import org.junit.Test;

import java.util.List;

/** VlmTranscriptionParser：转写文本 → 行序列 → 规则归类的纯 JVM 单测。 */
public final class VlmTranscriptionParserTest {

    /** 真机 S4 工作点（image_size=616, penalty 1.3/0.3/0.2）实测输出。 */
    private static final String DEVICE_TRANSCRIPTION_S4 =
            "3333333333333333333333333333\n"
            + "\n"
            + "QTY: 100个\n"
            + "贴片电容(MLCC)\n"
            + "C45359894\n"
            + "26+ ROHS\n"
            + "12pF ±5% 50V\n"
            + "0402\n"
            + "CGA0402C0G120J500GT\n"
            + "HRE(芯声)\n"
            + "\n"
            + "SO26063011291\n"
            + "0630-0057\n"
            + "3115/84";

    @Test
    public void toLines_stripsDegeneratePrefixAndBlankLines() {
        List<OcrLine> lines = VlmTranscriptionParser.toLines(DEVICE_TRANSCRIPTION_S4);
        assertEquals(11, lines.size());
        assertEquals("QTY: 100个", lines.get(0).text);
        assertEquals("CGA0402C0G120J500GT", lines.get(6).text);
        // 占位包围盒纵向递增，保持阅读顺序
        assertTrue(lines.get(1).top > lines.get(0).top);
        assertTrue(lines.get(0).hasRect());
    }

    @Test
    public void toLines_stripsMarkdownFences() {
        List<OcrLine> lines = VlmTranscriptionParser.toLines(
                "```\nC45359894\n```\n0402");
        assertEquals(2, lines.size());
        assertEquals("C45359894", lines.get(0).text);
        assertEquals("0402", lines.get(1).text);
    }

    @Test
    public void parse_recoversAllCanonicalFieldsFromDeviceOutput() {
        ComponentRecord record = VlmTranscriptionParser.parse(DEVICE_TRANSCRIPTION_S4);
        assertEquals("C45359894", record.supplierPartNumber);
        assertEquals("CGA0402C0G120J500GT", record.manufacturerPartNumber);
        assertEquals("贴片电容(MLCC)", record.fields.get("品类"));
        assertEquals("12pF ±5% 50V", record.fields.get("规格"));
        assertEquals("0402", record.fields.get("封装"));
        assertEquals("HRE(芯声)", record.fields.get("厂商"));
    }

    @Test
    public void parse_returnsNullWhenNothingUsable() {
        assertNull(VlmTranscriptionParser.parse(null));
        assertNull(VlmTranscriptionParser.parse(""));
        assertNull(VlmTranscriptionParser.parse("3333333333333333333333333333"));
        assertNull(VlmTranscriptionParser.parse("```\n```"));
    }

    /** 真机电阻标签文字列裁剪后的转写（含 "3.00mW" 残读垃圾行与"厚膜电阻 0Ω 333.333mW" 混合行）。 */
    private static final String DEVICE_TRANSCRIPTION_RESISTOR =
            "3.00mW\n"
            + "C844719\n"
            + "26+ ROHS\n"
            + "厚膜电阻 0Ω 333.333mW\n"
            + "0603\n"
            + "CRCW06030000ZOEAHP\n"
            + "VISHAY(威世)";

    @Test
    public void parse_resistorMixedCategorySpecLine() {
        ComponentRecord record = VlmTranscriptionParser.parse(DEVICE_TRANSCRIPTION_RESISTOR);
        assertEquals("C844719", record.supplierPartNumber);
        // 混合行拆分：中文段品类、单位段规格；单位数更多的行胜出（压过 "3.00mW" 残读）
        assertEquals("厚膜电阻", record.fields.get("品类"));
        assertEquals("0Ω 333.333mW", record.fields.get("规格"));
        assertEquals("0603", record.fields.get("封装"));
        assertEquals("VISHAY(威世)", record.fields.get("厂商"));
        assertTrue(record.manufacturerPartNumber.startsWith("CRCW"));
    }

    @Test
    public void isDegenerateLine_detection() {
        assertTrue(VlmTranscriptionParser.isDegenerateLine("333333333333"));
        assertTrue(VlmTranscriptionParser.isDegenerateLine("33333343333333"));
        assertFalse(VlmTranscriptionParser.isDegenerateLine("34152020"));
        assertFalse(VlmTranscriptionParser.isDegenerateLine("C45359894"));
        assertFalse(VlmTranscriptionParser.isDegenerateLine("12pF ±5% 50V"));
        assertFalse(VlmTranscriptionParser.isDegenerateLine("333"));
    }
}

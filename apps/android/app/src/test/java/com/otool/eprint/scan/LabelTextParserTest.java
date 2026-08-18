package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ocr.OcrLine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 以 docs/ocr_plan_0818.md §3.5 两张样品标签（立创横版贴纸）的人工转写为 fixture。
 * 包围盒按 640x380 标签图手工估计，行高约 30px。
 */
public final class LabelTextParserTest {
    /** 样品一：LED 灯珠标签（0250-A-E18-001 / C2843785 / XL-5050RGBC-WS2812B）。 */
    private static List<OcrLine> sampleLed() {
        return Arrays.asList(
                new OcrLine("0250-A-E18-001", 0.98f, 40, 20, 320, 50),
                new OcrLine("XL-5050RGBC-WS2812B", 0.97f, 40, 60, 420, 90),
                new OcrLine("QTY:20", 0.99f, 40, 100, 200, 130),
                new OcrLine("C2843785", 0.99f, 40, 140, 240, 170),
                new OcrLine("SO25011518496", 0.98f, 40, 180, 320, 210),
                new OcrLine("0115-0081", 0.96f, 340, 180, 500, 210));
    }

    /** 样品二：贴片电容标签（3417-B-D06-004 / C45359894 / CGA0402C0G120J500GT）。 */
    private static List<OcrLine> sampleMlcc() {
        return Arrays.asList(
                new OcrLine("3417-B-D06-004", 0.98f, 40, 20, 320, 50),
                new OcrLine("HRE(芯声)", 0.95f, 40, 60, 260, 90),
                new OcrLine("贴片电容(MLCC)", 0.95f, 40, 100, 320, 130),
                new OcrLine("CGA0402C0G120J500GT", 0.97f, 40, 140, 440, 170),
                new OcrLine("12pF ±5% 50V", 0.94f, 40, 180, 300, 210),
                new OcrLine("0402", 0.99f, 40, 220, 140, 250),
                // 数量行被二维码遮住末位，残行 "0" 在同一水平带右侧。
                new OcrLine("QTY:10", 0.97f, 40, 260, 200, 290),
                new OcrLine("0", 0.90f, 205, 262, 222, 288),
                new OcrLine("C45359894", 0.99f, 40, 300, 250, 330),
                new OcrLine("SO26063011291", 0.98f, 40, 340, 320, 370),
                new OcrLine("0630-0057", 0.96f, 340, 300, 500, 330),
                // 样品二中被遮挡产生的 "B" 孤行：附近无同带宿主行，应被丢弃。
                new OcrLine("B", 0.60f, 600, 20, 616, 50));
    }

    @Test
    public void parsesLedSampleFields() {
        ComponentRecord record = LabelTextParser.parse(sampleLed());
        assertEquals(SupplierFormat.LCSC, record.supplier);
        assertTrue(record.recognized);
        assertEquals("C2843785", record.supplierPartNumber);
        assertEquals("XL-5050RGBC-WS2812B", record.manufacturerPartNumber);
        assertEquals(20, record.quantity);
        assertEquals("SO25011518496", record.orderNumber);
        assertEquals("0115-0081", record.trackingId);
        assertEquals("0250-A-E18-001", record.fields.get("内部编码"));
        assertTrue(record.warning.isEmpty());
        assertTrue(record.confidence >= 70);
    }

    @Test
    public void parsesMlccSampleFields() {
        ComponentRecord record = LabelTextParser.parse(sampleMlcc());
        assertEquals(SupplierFormat.LCSC, record.supplier);
        assertTrue(record.recognized);
        assertEquals("C45359894", record.supplierPartNumber);
        assertEquals("CGA0402C0G120J500GT", record.manufacturerPartNumber);
        // 残行 "0" 并入数量行。
        assertEquals(100, record.quantity);
        assertEquals("SO26063011291", record.orderNumber);
        assertEquals("0630-0057", record.trackingId);
        assertEquals("3417-B-D06-004", record.fields.get("内部编码"));
        assertEquals("12pF ±5% 50V", record.fields.get("规格"));
        assertEquals("0402", record.fields.get("封装"));
        assertEquals("HRE(芯声)", record.fields.get("厂商"));
        assertEquals("贴片电容(MLCC)", record.fields.get("品类"));
        // "B" 孤行被丢弃，不污染任何字段。
        assertFalse(record.fields.containsValue("B"));
    }

    @Test
    public void commonMisreadsAreCorrected() {
        assertEquals("12pF ±5% 50V", LabelTextParser.correctCommonMisreads("12pF 土5% 50V"));
        assertEquals("1001", LabelTextParser.correctCommonMisreads("10O1"));
        assertEquals("311", LabelTextParser.correctCommonMisreads("3l1"));
        // 非数字之间的字母不受影响（型号中的 O/l 保留）。
        assertEquals("XLO", LabelTextParser.correctCommonMisreads("XLO"));
    }

    @Test
    public void modelLikeLineWithVoltageFragmentIsNotMisreadAsSpec() {
        // PZ254V-11-04P 含 "254V" 片段，但整行是型号而非规格。
        ComponentRecord record = LabelTextParser.parse(Arrays.asList(
                new OcrLine("C2691448", 0.99f, 40, 100, 240, 130),
                new OcrLine("QTY:20", 0.99f, 40, 140, 200, 170),
                new OcrLine("PZ254V-11-04P", 0.97f, 40, 60, 400, 90)));
        assertEquals("PZ254V-11-04P", record.manufacturerPartNumber);
        assertFalse(record.fields.containsKey("规格"));
    }

    @Test
    public void emptyInputIsNotRecognized() {
        ComponentRecord record = LabelTextParser.parse(Collections.<OcrLine>emptyList());
        assertFalse(record.recognized);
        assertEquals(0, record.confidence);
        assertEquals("未识别到有效字段，请对准标签重拍", record.warning);
    }

    @Test
    public void missingFieldsProduceWarning() {
        ComponentRecord record = LabelTextParser.parse(Collections.singletonList(
                new OcrLine("C45359894", 0.99f, 40, 100, 250, 130)));
        assertFalse(record.recognized);
        assertEquals("C45359894", record.supplierPartNumber);
        assertTrue(record.warning.contains("型号"));
        assertTrue(record.warning.contains("数量"));
    }
}

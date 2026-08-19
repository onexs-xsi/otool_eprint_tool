package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ocr.OcrLine;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

public final class GlmInformationExtractionParserTest {
    @Test
    public void parsesCompleteMlccExtractionForAllTemplates() throws JSONException {
        String output = "```json\n{"
                + "\"manufacturer_part_number\":\"CGA0402C0G120J500GT\","
                + "\"lcsc_part_number\":\"C45359894静\","
                + "\"category\":\"贴片电容(MLCC)\","
                + "\"specification\":\"12pF ±5% 50V\","
                + "\"package\":\"0402\","
                + "\"brand\":\"HRE(芯声)\","
                + "\"parameters\":[{\"name\":\"介质\",\"value\":\"C0G\"}]"
                + "}\n```";
        ComponentRecord record = GlmInformationExtractionParser.parse(output);
        assertTrue(record.recognized);
        assertEquals("CGA0402C0G120J500GT", record.manufacturerPartNumber);
        assertEquals("C45359894", record.supplierPartNumber);
        assertEquals("贴片电容(MLCC)", record.fields.get("品类"));
        assertEquals("12pF ±5% 50V", record.fields.get("规格"));
        assertEquals("0402", record.fields.get("封装"));
        assertEquals("HRE(芯声)", record.fields.get("厂商"));
        assertEquals("介质:C0G", record.fields.get("参数"));
        assertEquals(0, record.quantity);
        assertTrue(record.orderNumber.isEmpty());
        assertTrue(record.trackingId.isEmpty());
    }

    @Test(expected = JSONException.class)
    public void rejectsIncompleteSchema() throws JSONException {
        GlmInformationExtractionParser.parse(
                "{\"manufacturer_part_number\":\"ABC\",\"lcsc_part_number\":\"C12345\"}");
    }

    @Test
    public void keepsMissingValuesEmptyAndDoesNotGuess() throws JSONException {
        ComponentRecord record = GlmInformationExtractionParser.parse("{"
                + "\"manufacturer_part_number\":\"\","
                + "\"lcsc_part_number\":\"not-a-c-number\","
                + "\"category\":\"LED\","
                + "\"specification\":\"\","
                + "\"package\":\"\","
                + "\"brand\":\"\","
                + "\"parameters\":[]}");
        assertFalse(record.recognized);
        assertTrue(record.manufacturerPartNumber.isEmpty());
        assertTrue(record.supplierPartNumber.isEmpty());
        assertEquals("LED", record.fields.get("品类"));
        assertTrue(record.warning.contains("缺少型号"));
    }

    @Test
    public void preservesStringArrayParametersReturnedByModel() throws JSONException {
        ComponentRecord record = GlmInformationExtractionParser.parse("{"
                + "\"manufacturer_part_number\":\"PZ254V-11-04P\","
                + "\"lcsc_part_number\":\"C2691448\","
                + "\"category\":\"排针\","
                + "\"specification\":\"1x4P\","
                + "\"package\":\"P=2.54mm\","
                + "\"brand\":\"XFCN\","
                + "\"parameters\":[\"方针直插\",\"插件\"]}");

        assertEquals("方针直插 · 插件", record.fields.get("参数"));
    }

    @Test
    public void groundsRoleConfusionAndSplitModelWithSameImageOcr() throws JSONException {
        ComponentRecord evidence = LabelTextParser.parse(Arrays.asList(
                new OcrLine("3417-B-D06-004", 0.99f, 20, 20, 240, 50),
                new OcrLine("C45359894", 0.99f, 20, 60, 180, 90),
                new OcrLine("26+ ROHS", 0.98f, 20, 100, 180, 130),
                new OcrLine("12pF ±5% 50V", 0.99f, 20, 140, 240, 170),
                new OcrLine("0402", 0.99f, 20, 180, 100, 210),
                new OcrLine("CGA0402C0G120J500GT", 0.99f, 20, 220, 360, 250),
                new OcrLine("HRE(芯声)", 0.98f, 20, 260, 180, 290),
                new OcrLine("贴片电容(MLCC)", 0.98f, 20, 300, 260, 330)));
        String confused = "{"
                + "\"manufacturer_part_number\":\"3417-B-D06-004\","
                + "\"lcsc_part_number\":\"C45359894\","
                + "\"category\":\"贴片电容(MLCC)\","
                + "\"specification\":\"28+ ROHS\\n12pF ±5% 50V\\n0402\","
                + "\"package\":\"\","
                + "\"brand\":\"\","
                + "\"parameters\":["
                + "{\"name\":\"CGA0402C0G\",\"value\":\"120J500GT\"},"
                + "{\"name\":\"HRE(芯声)\"}]}";

        ComponentRecord grounded = GlmInformationExtractionParser.parse(confused, evidence);

        assertEquals("CGA0402C0G120J500GT", grounded.manufacturerPartNumber);
        assertEquals("C45359894", grounded.supplierPartNumber);
        assertEquals("12pF ±5% 50V", grounded.fields.get("规格"));
        assertEquals("0402", grounded.fields.get("封装"));
        assertEquals("HRE(芯声)", grounded.fields.get("厂商"));
        assertFalse(grounded.fields.containsKey("参数"));
        assertTrue(grounded.raw.contains("OCR-grounding: corrected"));
    }
}

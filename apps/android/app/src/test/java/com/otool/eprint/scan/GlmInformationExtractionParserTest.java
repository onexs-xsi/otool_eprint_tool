package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public final class GlmInformationExtractionParserTest {
    @Test
    public void parsesCompleteMlccExtractionForAllTemplates() throws JSONException {
        String output = "```json\n{"
                + "\"manufacturer_part_number\":\"CGA0402C0G120J500GT\","
                + "\"lcsc_part_number\":\"C45359894\","
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
}

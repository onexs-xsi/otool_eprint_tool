package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QrContentParserTest {
    @Test
    public void parsesObservedManufacturingPayload() {
        QrContentParser.Parsed parsed = QrContentParser.parse(
                "{on:SO25011518496,pc:C2843785,pm:XL-5050RGBC-WS2812B,qty:20,mc:null,cc:1}");
        assertEquals("结构化字段", parsed.type);
        assertEquals("SO25011518496", parsed.fields.get("on"));
        assertEquals("20", parsed.fields.get("qty"));
        assertEquals(6, parsed.fields.size());
    }

    @Test
    public void parsesAndDecodesUriQuery() {
        QrContentParser.Parsed parsed = QrContentParser.parse(
                "https://example.invalid/label?sku=A%201&qty=20#ignored");
        assertEquals("网址 / URI 参数", parsed.type);
        assertEquals("A 1", parsed.fields.get("sku"));
        assertEquals("20", parsed.fields.get("qty"));
    }

    @Test
    public void preservesCommaInsideQuotedJsonValue() {
        QrContentParser.Parsed parsed = QrContentParser.parse("{\"name\":\"A,B\",\"qty\":2}");
        assertEquals("A,B", parsed.fields.get("name"));
        assertEquals("2", parsed.fields.get("qty"));
    }

    @Test
    public void plainTextRemainsPlainAndSummaryDoesNotInventFields() {
        QrContentParser.Parsed parsed = QrContentParser.parse("hello printer");
        assertEquals("普通文本", parsed.type);
        assertTrue(parsed.fields.isEmpty());
        assertTrue(parsed.summary().contains("字符数：13"));
    }
}

package com.otool.eprint.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.LabelTextParser;
import com.otool.eprint.scan.ocr.OcrLine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 三档模板采用固定前缀列；缺值只留前缀，不输出采购与追溯信息。 */
public class ComponentLabelFactoryTest {
    private static ComponentRecord mlccRecord() {
        return LabelTextParser.parse(Arrays.asList(
                new OcrLine("3417-B-D06-004", 0.98f, 0, 0, 0, 0),
                new OcrLine("C45359894", 0.99f, 0, 0, 0, 0),
                new OcrLine("QTY:100", 0.99f, 0, 0, 0, 0),
                new OcrLine("贴片电容(MLCC)", 0.96f, 0, 0, 0, 0),
                new OcrLine("12pF ±5% 50V", 0.96f, 0, 0, 0, 0),
                new OcrLine("0402", 0.99f, 0, 0, 0, 0),
                new OcrLine("CGA0402C0G120J500GT", 0.98f, 0, 0, 0, 0),
                new OcrLine("HRE(芯声)", 0.95f, 0, 0, 0, 0),
                new OcrLine("SO26063011291", 0.98f, 0, 0, 0, 0),
                new OcrLine("0630-0057", 0.96f, 0, 0, 0, 0)));
    }

    private static float estimatedWidthMm(TextElement element) {
        float emUnits = 0f;
        for (int index = 0; index < element.text.length(); index++) {
            emUnits += element.text.charAt(index) > 0x2E7F ? 1f : 0.55f;
        }
        return emUnits * element.fontSizePt * 25.4f / 72f;
    }

    private static void assertSingleLineFits(LabelDocument document) {
        assertTrue(document.elements.size() >= 1);
        for (TextElement element : document.elements) {
            assertTrue("元素 " + element.id + " 估算宽度 " + estimatedWidthMm(element)
                            + " 超过可用宽度 " + element.widthMm,
                    estimatedWidthMm(element) <= element.widthMm + 0.01f);
            assertTrue(element.fontSizePt >= 4.5f);
        }
    }

    private static void assertValueRowsDoNotOverlap(LabelDocument document) {
        float previousBottom = -1f;
        for (TextElement element : document.elements) {
            if (element.id.endsWith("_prefix")) continue;
            assertTrue("元素 " + element.id + " 与上一行重叠",
                    element.yMm >= previousBottom - 0.01f);
            previousBottom = element.yMm + element.fontSizePt * 25.4f / 72f;
        }
        assertTrue(previousBottom <= document.heightMm + 0.01f);
    }

    private static String allText(LabelDocument document) {
        StringBuilder value = new StringBuilder();
        for (TextElement element : document.elements) value.append(element.text).append('\n');
        return value.toString();
    }

    private static void assertFixedPrefix(LabelDocument document, String id, String prefix) {
        TextElement label = document.find(id + "_prefix");
        TextElement value = document.find(id);
        assertNotNull(label);
        assertNotNull(value);
        assertEquals(prefix, label.text);
        assertEquals(0.4f, label.xMm, 0.001f);
        assertEquals(4.1f, label.widthMm, 0.001f);
        assertEquals(TextElement.ALIGN_RIGHT, label.alignment);
        assertEquals(4.7f, value.xMm, 0.001f);
        assertEquals(19.9f, value.widthMm, 0.001f);
        assertEquals(label.yMm, value.yMm, 0.001f);
    }

    @Test
    public void compactUsesFourFixedPrefixesAndNeverIncludesLcscNumber() {
        LabelDocument document = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                ComponentTemplate.COMPACT, true, true, false);
        assertEquals(8, document.elements.size());
        assertFixedPrefix(document, "component_model", "型号:");
        assertFixedPrefix(document, "component_category", "品类:");
        assertFixedPrefix(document, "component_spec", "规格:");
        assertFixedPrefix(document, "component_package", "封装:");
        assertEquals("CGA0402C0G120J500GT", document.find("component_model").text);
        assertFalse(allText(document).contains("C45359894"));
        assertNull(document.find("component_supplier_prefix"));
        assertSingleLineFits(document);
        assertValueRowsDoNotOverlap(document);
    }

    @Test
    public void missingValuesKeepPrefixAndLeaveValueBlank() {
        ComponentRecord incomplete = LabelTextParser.parse(Collections.singletonList(
                new OcrLine("C45359894", 0.99f, 0, 0, 0, 0)));
        LabelDocument compact = ComponentLabelFactory.create(incomplete, 203, 25f, 15f,
                ComponentTemplate.COMPACT, true, true, false);
        assertFixedPrefix(compact, "component_model", "型号:");
        assertFixedPrefix(compact, "component_spec", "规格:");
        assertTrue(compact.find("component_model").text.isEmpty());
        assertTrue(compact.find("component_spec").text.isEmpty());
        assertFalse(allText(compact).contains("C45359894"));
    }

    @Test
    public void standardUsesFixedPrefixesAndIncludesLcscNumber() {
        LabelDocument document = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                ComponentTemplate.STANDARD, true, true, false);
        assertFixedPrefix(document, "component_model", "型号:");
        assertFixedPrefix(document, "component_category", "品类:");
        assertFixedPrefix(document, "component_spec", "规格:");
        assertFixedPrefix(document, "component_package", "封装:");
        assertFixedPrefix(document, "component_brand", "品牌:");
        assertFixedPrefix(document, "component_supplier", "立创:");
        assertEquals("C45359894", document.find("component_supplier").text);
        assertSingleLineFits(document);
        assertValueRowsDoNotOverlap(document);
    }

    @Test
    public void detailedAlwaysKeepsParametersPrefixEvenWhenValueMissing() {
        LabelDocument document = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                ComponentTemplate.DETAILED, true, true, false);
        assertFixedPrefix(document, "component_parameters", "参数:");
        assertTrue(document.find("component_parameters").text.isEmpty());
        assertFixedPrefix(document, "component_supplier", "立创:");
        assertSingleLineFits(document);
        assertValueRowsDoNotOverlap(document);
    }

    @Test
    public void allTemplatesOmitQuantityTraceAndInternalWarehouseCode() {
        for (ComponentTemplate template : ComponentTemplate.values()) {
            LabelDocument document = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                    template, true, true, false);
            String text = allText(document);
            assertFalse(text.contains("QTY"));
            assertFalse(text.contains("SO26063011291"));
            assertFalse(text.contains("0630-0057"));
            assertFalse(text.contains("3417-B-D06-004"));
            assertNull(document.find("component_quantity_prefix"));
            assertNull(document.find("component_trace_prefix"));
        }
    }

    @Test
    public void brandOptionRemovesTheWholeBrandRowOnlyWhenDisabled() {
        for (ComponentTemplate template : Arrays.asList(
                ComponentTemplate.STANDARD, ComponentTemplate.DETAILED)) {
            LabelDocument withBrand = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                    template, true, true, false);
            LabelDocument withoutBrand = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                    template, true, false, false);
            assertFixedPrefix(withBrand, "component_brand", "品牌:");
            assertTrue(allText(withBrand).contains("HRE"));
            assertNull(withoutBrand.find("component_brand_prefix"));
            assertNull(withoutBrand.find("component_brand"));
        }
    }

    @Test
    public void fieldSelectionRemovesDisabledCategoriesFromDetailedTemplate() {
        ComponentFieldSelection fields = new ComponentFieldSelection(
                true, false, true, false, false, false, true);
        LabelDocument document = ComponentLabelFactory.create(mlccRecord(), 203, 25f, 15f,
                ComponentTemplate.DETAILED, true, false, fields);
        assertNotNull(document.find("component_model_prefix"));
        assertNotNull(document.find("component_spec_prefix"));
        assertNotNull(document.find("component_supplier_prefix"));
        assertNull(document.find("component_category_prefix"));
        assertNull(document.find("component_package_prefix"));
        assertNull(document.find("component_brand_prefix"));
        assertNull(document.find("component_parameters_prefix"));
    }
}

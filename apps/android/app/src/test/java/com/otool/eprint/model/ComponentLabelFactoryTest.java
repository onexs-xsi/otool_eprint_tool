package com.otool.eprint.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.ComponentLabelParser;
import com.otool.eprint.scan.SupplierFormat;

import org.junit.Test;

/** 模板生成的标签元素必须单行适配标签宽度，且各行垂直不重叠。 */
public class ComponentLabelFactoryTest {
    private static ComponentRecord longRecord() {
        // 真实立创贴纸样本：pm 长达 19 字符，旧版面在 25 mm 宽内折行重叠
        String qr = "{on:SO26063011291,pc:C45359894,pm:CGA0402C0G120J500GT,"
                + "qty:100,mc:,cc:1,pdi:222571873,hp:12}";
        return ComponentLabelParser.parse(qr, SupplierFormat.AUTO);
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
            assertTrue(element.fontSizePt >= 6f);
        }
    }

    private static void assertNoVerticalOverlap(LabelDocument document) {
        float previousBottom = -1f;
        for (TextElement element : document.elements) {
            assertTrue("元素 " + element.id + " 与上一行重叠",
                    element.yMm >= previousBottom - 0.01f);
            previousBottom = element.yMm + element.fontSizePt * 25.4f / 72f;
        }
        assertTrue(previousBottom <= document.heightMm + 0.01f);
    }

    @Test
    public void standardTemplateFitsLongPartNumbers() {
        LabelDocument document = ComponentLabelFactory.create(longRecord(), 203, 25f, 15f,
                ComponentTemplate.STANDARD, true, true, false);
        assertSingleLineFits(document);
        assertNoVerticalOverlap(document);
    }

    @Test
    public void compactTemplateCentersSingleHeadline() {
        LabelDocument document = ComponentLabelFactory.create(longRecord(), 203, 25f, 15f,
                ComponentTemplate.COMPACT, true, true, false);
        assertEquals(1, document.elements.size());
        assertEquals(TextElement.ALIGN_CENTER, document.elements.get(0).alignment);
        assertSingleLineFits(document);
    }

    @Test
    public void detailedTemplateFlowsAllFields() {
        LabelDocument document = ComponentLabelFactory.create(longRecord(), 203, 25f, 15f,
                ComponentTemplate.DETAILED, true, true, false);
        assertTrue(document.elements.size() >= 3);
        assertSingleLineFits(document);
        assertNoVerticalOverlap(document);
    }

    @Test
    public void hiddenTraceRemovesTraceLine() {
        ComponentRecord record = longRecord();
        LabelDocument withTrace = ComponentLabelFactory.create(record, 203, 25f, 15f,
                ComponentTemplate.STANDARD, true, true, false);
        LabelDocument withoutTrace = ComponentLabelFactory.create(record, 203, 25f, 15f,
                ComponentTemplate.STANDARD, true, false, false);
        assertTrue(withoutTrace.elements.size() < withTrace.elements.size());
        for (TextElement element : withoutTrace.elements) {
            assertTrue(!element.id.equals("component_trace"));
        }
    }
}

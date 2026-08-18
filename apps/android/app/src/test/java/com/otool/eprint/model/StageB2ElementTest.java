package com.otool.eprint.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 图片/图标/表格元素与序列号按份数递增的模型行为。 */
public class StageB2ElementTest {
    @Test
    public void advanceSerialByCopies() {
        TextElement element = new TextElement();
        element.serialCounter = 1;
        element.serialStep = 2;
        element.advanceSerial(3);
        assertEquals(7, element.serialCounter);
        element.advanceSerial(1);
        assertEquals(9, element.serialCounter);
    }

    @Test
    public void tableCellsPadsMissingCells() {
        TextElement element = new TextElement();
        element.type = TextElement.TYPE_TABLE;
        element.tableRows = 2;
        element.tableCols = 3;
        element.text = "a\nb\nc";
        String[] cells = element.tableCells();
        assertEquals(6, cells.length);
        assertEquals("a", cells[0]);
        assertEquals("c", cells[2]);
        assertEquals("", cells[5]);
    }

    @Test
    public void tableRowsColsClampInNormalize() {
        LabelDocument document = new LabelDocument();
        TextElement element = document.addElement(TextElement.TYPE_TABLE);
        element.tableRows = 99;
        element.tableCols = 0;
        document.normalize();
        assertEquals(8, element.tableRows);
        assertEquals(1, element.tableCols);
    }

    @Test
    public void imageIconTableTypesRoundTrip() throws Exception {
        LabelDocument document = new LabelDocument();
        TextElement image = document.addElement(TextElement.TYPE_IMAGE);
        image.text = "/data/x.jpg";
        TextElement icon = document.addElement(TextElement.TYPE_ICON);
        icon.format = "warning";
        TextElement table = document.addElement(TextElement.TYPE_TABLE);
        table.tableRows = 3;
        table.tableCols = 4;
        LabelDocument restored = LabelDocument.fromJson(document.toJson().toString());
        assertEquals(3, restored.elements.size());
        assertEquals(TextElement.TYPE_IMAGE, restored.elements.get(0).type);
        assertEquals("/data/x.jpg", restored.elements.get(0).text);
        assertEquals("warning", restored.elements.get(1).format);
        assertEquals(3, restored.elements.get(2).tableRows);
        assertEquals(4, restored.elements.get(2).tableCols);
        assertTrue(restored.elements.get(2).isBoxed());
        assertTrue(restored.elements.get(2).isTextual());
    }

    @Test
    public void copyPreservesTypeSpecificFieldsAndGetsNewId() {
        TextElement source = new TextElement();
        source.type = TextElement.TYPE_TABLE;
        source.text = "a\nb\nc\nd";
        source.format = "warning";
        source.heightMm = 9f;
        source.lineWidthMm = 0.7f;
        source.filled = true;
        source.locked = true;
        source.serialCounter = 17;
        source.serialStep = 3;
        source.serialDigits = 5;
        source.tableRows = 3;
        source.tableCols = 4;

        TextElement copy = source.copyWithNewId();

        assertNotEquals(source.id, copy.id);
        assertEquals(source.type, copy.type);
        assertEquals(source.text, copy.text);
        assertEquals(source.format, copy.format);
        assertEquals(source.heightMm, copy.heightMm, 0.001f);
        assertEquals(source.lineWidthMm, copy.lineWidthMm, 0.001f);
        assertEquals(source.filled, copy.filled);
        assertEquals(source.locked, copy.locked);
        assertEquals(source.serialCounter, copy.serialCounter);
        assertEquals(source.serialStep, copy.serialStep);
        assertEquals(source.serialDigits, copy.serialDigits);
        assertEquals(source.tableRows, copy.tableRows);
        assertEquals(source.tableCols, copy.tableCols);
    }
}

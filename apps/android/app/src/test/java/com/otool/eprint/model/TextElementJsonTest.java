package com.otool.eprint.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 元素类型化后的 JSON 兼容性：旧文档缺省字段按文本默认值读取，新字段完整往返。 */
public class TextElementJsonTest {
    @Test
    public void legacyJsonWithoutTypeFieldsReadsAsTextDefaults() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("id", "welcome")
                .put("text", "02214227")
                .put("xMm", 1).put("yMm", 5.4).put("widthMm", 23)
                .put("fontSizePt", 12).put("bold", true).put("alignment", 1);
        TextElement element = TextElement.fromJson(legacy);
        assertEquals(TextElement.TYPE_TEXT, element.type);
        assertEquals("", element.format);
        assertEquals(8f, element.heightMm, 0.001f);
        assertEquals(0.3f, element.lineWidthMm, 0.001f);
        assertFalse(element.filled);
        assertTrue(element.showLabel);
        assertEquals("bottom", element.barcodeTextPosition);
        assertEquals("M", element.qrErrorCorrection);
        assertEquals(2, element.quietZoneModules);
        assertEquals(0, element.rotationDegrees);
        assertFalse(element.locked);
        assertTrue(element.isTextual());
        assertFalse(element.isBoxed());
    }

    @Test
    public void typedFieldsRoundTrip() throws Exception {
        TextElement element = new TextElement();
        element.type = TextElement.TYPE_BARCODE;
        element.text = "1234567890";
        element.format = "EAN_13";
        element.heightMm = 9.5f;
        element.lineWidthMm = 0.8f;
        element.filled = true;
        element.showLabel = false;
        element.barcodeTextPosition = "none";
        element.qrErrorCorrection = "H";
        element.quietZoneModules = 4;
        element.rotationDegrees = 270;
        element.locked = true;
        TextElement restored = TextElement.fromJson(element.toJson());
        assertEquals(TextElement.TYPE_BARCODE, restored.type);
        assertEquals("EAN_13", restored.format);
        assertEquals(9.5f, restored.heightMm, 0.001f);
        assertEquals(0.8f, restored.lineWidthMm, 0.001f);
        assertTrue(restored.filled);
        assertFalse(restored.showLabel);
        assertEquals("none", restored.barcodeTextPosition);
        assertEquals("H", restored.qrErrorCorrection);
        assertEquals(4, restored.quietZoneModules);
        assertEquals(270, restored.rotationDegrees);
        assertTrue(restored.locked);
        assertTrue(restored.isBoxed());
    }

    @Test
    public void documentWithTypedElementsSurvivesJsonCycle() throws Exception {
        LabelDocument document = new LabelDocument();
        document.addElement(TextElement.TYPE_QRCODE);
        document.addElement(TextElement.TYPE_RECT);
        LabelDocument restored = LabelDocument.fromJson(document.toJson().toString());
        assertEquals(2, restored.elements.size());
        assertEquals(TextElement.TYPE_QRCODE, restored.elements.get(0).type);
        assertEquals(TextElement.TYPE_RECT, restored.elements.get(1).type);
        assertTrue(restored.elements.get(0).heightMm >= 1f);
    }

    @Test
    public void barcodeWithoutFormatNormalizesToCode128() {
        TextElement barcode = new TextElement();
        barcode.type = TextElement.TYPE_BARCODE;
        barcode.text = "1234567890";
        barcode.format = "";
        LabelDocument document = new LabelDocument();
        document.elements.add(barcode);
        document.normalize();
        assertEquals("CODE_128", barcode.format);
    }

    @Test
    public void typeSpecificOptionsNormalizeToSafeDefaults() {
        LabelDocument document = new LabelDocument();
        TextElement qr = document.addElement(TextElement.TYPE_QRCODE);
        qr.qrErrorCorrection = "invalid";
        qr.format = "invalid";
        qr.quietZoneModules = 99;
        qr.rotationDegrees = 46;
        TextElement arc = document.addElement(TextElement.TYPE_ARCTEXT);
        arc.format = "invalid";
        TextElement image = document.addElement(TextElement.TYPE_IMAGE);
        image.format = "invalid";

        document.normalize();

        assertEquals("M", qr.qrErrorCorrection);
        assertEquals("QR_CODE", qr.format);
        assertEquals(16, qr.quietZoneModules);
        assertEquals(0, qr.rotationDegrees);
        assertEquals("up", arc.format);
        assertEquals("fit", image.format);
    }

    @Test
    public void newlyInsertedElementsStartInsideCanvasWhenTheyFit() {
        LabelDocument document = new LabelDocument();
        document.widthMm = 25f;
        document.heightMm = 15f;
        document.addElement(TextElement.TYPE_TEXT);
        document.addElement(TextElement.TYPE_ICON);
        document.addElement(TextElement.TYPE_QRCODE);
        TextElement barcode = document.addElement(TextElement.TYPE_BARCODE);

        assertTrue(barcode.xMm >= 0f);
        assertTrue(barcode.yMm >= 0f);
        assertTrue(barcode.xMm + barcode.widthMm <= document.widthMm + 0.001f);
        assertTrue(barcode.yMm + barcode.heightMm <= document.heightMm + 0.001f);
    }

    @Test
    public void normalizePreservesIntentionalElementOverflow() throws Exception {
        LabelDocument document = new LabelDocument();
        TextElement element = new TextElement();
        element.id = "overflow";
        element.xMm = -4f;
        element.yMm = 14f;
        element.widthMm = 40f;
        element.heightMm = 20f;
        document.elements.add(element);

        document.normalize();

        assertEquals(-4f, element.xMm, 0.001f);
        assertEquals(14f, element.yMm, 0.001f);
        assertEquals(40f, element.widthMm, 0.001f);
        assertEquals(20f, element.heightMm, 0.001f);

        LabelDocument restored = LabelDocument.fromJson(document.toJson().toString());
        TextElement restoredElement = restored.find("overflow");
        assertEquals(-4f, restoredElement.xMm, 0.001f);
        assertEquals(14f, restoredElement.yMm, 0.001f);
        assertEquals(40f, restoredElement.widthMm, 0.001f);
        assertEquals(20f, restoredElement.heightMm, 0.001f);
    }

    @Test
    public void positionDotConversionKeepsNegativeCoordinates() {
        assertTrue(LabelDocument.mmPositionToDots(-2f, 203) < 0);
        assertEquals(0, LabelDocument.mmPositionToDots(0f, 203));
    }
}

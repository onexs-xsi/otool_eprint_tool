package com.otool.eprint.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 序列号格式化/递增与文档级边框样式的兼容性。 */
public class SerialElementTest {
    @Test
    public void serialTextPadsCounterToDigits() {
        TextElement element = new TextElement();
        element.type = TextElement.TYPE_SERIAL;
        element.text = "NO.";
        element.serialCounter = 7;
        element.serialDigits = 4;
        assertEquals("NO.0007", element.serialText());
        element.serialCounter = 12345;
        assertEquals("NO.12345", element.serialText());
    }

    @Test
    public void advanceSerialUsesStepAndNeverBelowOne() {
        TextElement element = new TextElement();
        element.serialCounter = 10;
        element.serialStep = 5;
        element.advanceSerial();
        assertEquals(15, element.serialCounter);
        element.serialStep = 0;
        element.advanceSerial();
        assertEquals(16, element.serialCounter);
    }

    @Test
    public void displayTextCoversTimeAndSerial() {
        TextElement serial = new TextElement();
        serial.type = TextElement.TYPE_SERIAL;
        serial.text = "批次";
        serial.serialCounter = 1;
        assertEquals("批次001", serial.displayText());
        TextElement time = new TextElement();
        time.type = TextElement.TYPE_TIME;
        time.format = TimeElementFormat.DATE.pattern;
        assertTrue(time.displayText().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void borderStyleRoundTripsAndClamps() throws Exception {
        LabelDocument document = new LabelDocument();
        document.borderStyle = 2;
        document.addText("x"); // 空文档在 fromJson 会被默认文档替换（既有行为），需带元素
        LabelDocument restored = LabelDocument.fromJson(document.toJson().toString());
        assertEquals(2, restored.borderStyle);
        LabelDocument legacy = LabelDocument.fromJson("{\"widthMm\":25,\"heightMm\":15,\"elements\":[]}");
        assertEquals(0, legacy.borderStyle);
        restored.borderStyle = 9;
        restored.normalize();
        assertEquals(2, restored.borderStyle);
    }
}

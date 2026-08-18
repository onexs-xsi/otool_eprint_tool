package com.otool.eprint.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** 画布元素。type 决定渲染与属性编辑方式；新增字段均有默认值，旧 JSON 文档无损读取。 */
public final class TextElement {
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_BARCODE = "barcode";
    public static final String TYPE_QRCODE = "qrcode";
    public static final String TYPE_LINE = "line";
    public static final String TYPE_RECT = "rect";
    public static final String TYPE_TIME = "time";
    public static final String TYPE_SERIAL = "serial";
    public static final String TYPE_ARCTEXT = "arctext";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_ICON = "icon";
    public static final String TYPE_TABLE = "table";

    public String id;
    public String type;
    public String text;
    public String format;
    public float xMm;
    public float yMm;
    public float widthMm;
    public float heightMm;
    public float lineWidthMm;
    public float fontSizePt;
    public boolean bold;
    public int alignment;
    public boolean filled;
    /** 一维码是否在条纹下方显示可读内容。 */
    public boolean showLabel;
    /** 一维码可读文字位置：none/top/bottom。保留 showLabel 兼容旧文档。 */
    public String barcodeTextPosition;
    /** 二维码纠错级别：L/M/Q/H。 */
    public String qrErrorCorrection;
    /** 条码/二维码四周静区，单位为码元。 */
    public int quietZoneModules;
    /** 条码/二维码旋转角度：0/90/180/270。 */
    public int rotationDegrees;
    public boolean locked;
    public int serialCounter;
    public int serialStep;
    public int serialDigits;
    public int tableRows;
    public int tableCols;

    public TextElement() {
        id = UUID.randomUUID().toString();
        type = TYPE_TEXT;
        text = "新文本";
        format = "";
        xMm = 3f;
        yMm = 4f;
        widthMm = 34f;
        heightMm = 8f;
        lineWidthMm = 0.3f;
        fontSizePt = 12f;
        bold = false;
        alignment = ALIGN_LEFT;
        filled = false;
        showLabel = true;
        barcodeTextPosition = "bottom";
        qrErrorCorrection = "M";
        quietZoneModules = 2;
        rotationDegrees = 0;
        locked = false;
        serialCounter = 1;
        serialStep = 1;
        serialDigits = 3;
        tableRows = 2;
        tableCols = 2;
    }

    public boolean isTextual() {
        return TYPE_TEXT.equals(type) || TYPE_TIME.equals(type)
                || TYPE_SERIAL.equals(type) || TYPE_ARCTEXT.equals(type)
                || TYPE_TABLE.equals(type);
    }

    /** 条码/二维码/矩形/弧形/图片/图标/表格等按 widthMm×heightMm 盒区域渲染与命中的元素。 */
    public boolean isBoxed() {
        return TYPE_BARCODE.equals(type) || TYPE_QRCODE.equals(type)
                || TYPE_RECT.equals(type) || TYPE_LINE.equals(type)
                || TYPE_ARCTEXT.equals(type) || TYPE_IMAGE.equals(type)
                || TYPE_ICON.equals(type) || TYPE_TABLE.equals(type);
    }

    /** 渲染用文本：时间取当前时钟，序列号取前缀+补零计数，其余为原始内容。 */
    public String displayText() {
        if (TYPE_TIME.equals(type)) return TimeElementFormat.byPattern(format).formatNow();
        if (TYPE_SERIAL.equals(type)) return serialText();
        return text == null ? "" : text;
    }

    public String serialText() {
        StringBuilder digits = new StringBuilder(Integer.toString(Math.max(0, serialCounter)));
        while (digits.length() < Math.max(1, serialDigits)) digits.insert(0, '0');
        return (text == null ? "" : text) + digits;
    }

    public void advanceSerial() {
        advanceSerial(1);
    }

    public void advanceSerial(int times) {
        serialCounter += Math.max(1, serialStep) * Math.max(1, times);
    }

    /** 表格单元格内容：text 按行优先每行一格，不足补空串。 */
    public String[] tableCells() {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        int total = Math.max(1, tableRows) * Math.max(1, tableCols);
        String[] cells = new String[total];
        for (int index = 0; index < total; index++) {
            cells[index] = index < lines.length ? lines[index] : "";
        }
        return cells;
    }

    /** 创建具有新 id 的完整副本，保留所有通用和类型专属属性。 */
    public TextElement copyWithNewId() {
        TextElement copy = new TextElement();
        copy.type = type;
        copy.text = text;
        copy.format = format;
        copy.xMm = xMm;
        copy.yMm = yMm;
        copy.widthMm = widthMm;
        copy.heightMm = heightMm;
        copy.lineWidthMm = lineWidthMm;
        copy.fontSizePt = fontSizePt;
        copy.bold = bold;
        copy.alignment = alignment;
        copy.filled = filled;
        copy.showLabel = showLabel;
        copy.barcodeTextPosition = barcodeTextPosition;
        copy.qrErrorCorrection = qrErrorCorrection;
        copy.quietZoneModules = quietZoneModules;
        copy.rotationDegrees = rotationDegrees;
        copy.locked = locked;
        copy.serialCounter = serialCounter;
        copy.serialStep = serialStep;
        copy.serialDigits = serialDigits;
        copy.tableRows = tableRows;
        copy.tableCols = tableCols;
        return copy;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("text", text);
        json.put("format", format);
        json.put("xMm", xMm);
        json.put("yMm", yMm);
        json.put("widthMm", widthMm);
        json.put("heightMm", heightMm);
        json.put("lineWidthMm", lineWidthMm);
        json.put("fontSizePt", fontSizePt);
        json.put("bold", bold);
        json.put("alignment", alignment);
        json.put("filled", filled);
        json.put("showLabel", showLabel);
        json.put("barcodeTextPosition", barcodeTextPosition);
        json.put("qrErrorCorrection", qrErrorCorrection);
        json.put("quietZoneModules", quietZoneModules);
        json.put("rotationDegrees", rotationDegrees);
        json.put("locked", locked);
        json.put("serialCounter", serialCounter);
        json.put("serialStep", serialStep);
        json.put("serialDigits", serialDigits);
        json.put("tableRows", tableRows);
        json.put("tableCols", tableCols);
        return json;
    }

    public static TextElement fromJson(JSONObject json) {
        TextElement element = new TextElement();
        element.id = json.optString("id", element.id);
        element.type = json.optString("type", element.type);
        element.text = json.optString("text", element.text);
        element.format = json.optString("format", element.format);
        element.xMm = (float) json.optDouble("xMm", element.xMm);
        element.yMm = (float) json.optDouble("yMm", element.yMm);
        element.widthMm = (float) json.optDouble("widthMm", element.widthMm);
        element.heightMm = (float) json.optDouble("heightMm", element.heightMm);
        element.lineWidthMm = (float) json.optDouble("lineWidthMm", element.lineWidthMm);
        element.fontSizePt = (float) json.optDouble("fontSizePt", element.fontSizePt);
        element.bold = json.optBoolean("bold", element.bold);
        element.alignment = json.optInt("alignment", element.alignment);
        element.filled = json.optBoolean("filled", element.filled);
        element.showLabel = json.optBoolean("showLabel", element.showLabel);
        element.barcodeTextPosition = json.optString(
                "barcodeTextPosition", element.barcodeTextPosition);
        element.qrErrorCorrection = json.optString("qrErrorCorrection", element.qrErrorCorrection);
        element.quietZoneModules = json.optInt("quietZoneModules", element.quietZoneModules);
        element.rotationDegrees = json.optInt("rotationDegrees", element.rotationDegrees);
        element.locked = json.optBoolean("locked", element.locked);
        element.serialCounter = json.optInt("serialCounter", element.serialCounter);
        element.serialStep = json.optInt("serialStep", element.serialStep);
        element.serialDigits = json.optInt("serialDigits", element.serialDigits);
        element.tableRows = json.optInt("tableRows", element.tableRows);
        element.tableCols = json.optInt("tableCols", element.tableCols);
        return element;
    }

    public String effectiveBarcodeTextPosition() {
        if (!showLabel || "none".equals(barcodeTextPosition)) return "none";
        return "top".equals(barcodeTextPosition) ? "top" : "bottom";
    }
}

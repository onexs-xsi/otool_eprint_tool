package com.otool.eprint.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class LabelDocument {
    public static final float MIN_WIDTH_MM = 10f;
    public static final float MAX_WIDTH_MM = 80f;
    public static final float MIN_HEIGHT_MM = 10f;
    public static final float MAX_HEIGHT_MM = 200f;
    public static final float MIN_ELEMENT_SIZE_MM = 1f;
    public static final float MAX_ELEMENT_SIZE_MM = 1000f;
    public static final float MAX_ELEMENT_OFFSET_MM = 1000f;

    public float widthMm = 25f;
    public float heightMm = 15f;
    public int dpi = 203;
    /** 0 无边框 / 1 单框 / 2 双框 */
    public int borderStyle = 0;
    public final List<TextElement> elements = new ArrayList<>();

    public static LabelDocument createDefault() {
        LabelDocument document = new LabelDocument();
        TextElement welcome = new TextElement();
        welcome.id = "welcome";
        welcome.text = "扫码内容将显示在这里";
        welcome.bold = true;
        welcome.alignment = TextElement.ALIGN_CENTER;
        document.elements.add(welcome);
        document.centerWelcome();
        return document;
    }

    /** 把默认占位文本（id=welcome）在标签内水平、垂直居中；行高按字号全角估算。 */
    public void centerWelcome() {
        TextElement welcome = find("welcome");
        if (welcome == null) return;
        welcome.alignment = TextElement.ALIGN_CENTER;
        welcome.xMm = 1f;
        welcome.widthMm = Math.max(1f, widthMm - 2f);
        float lineMm = welcome.fontSizePt * 25.4f / 72f;
        String text = welcome.text == null ? "" : welcome.text;
        float emUnits = 0f;
        for (int index = 0; index < text.length(); index++) {
            emUnits += text.charAt(index) > 0x2E7F ? 1f : 0.55f;
        }
        int lines = (int) Math.ceil(emUnits * lineMm / welcome.widthMm);
        lines = Math.max(1, Math.min(4, lines));
        welcome.heightMm = Math.max(MIN_ELEMENT_SIZE_MM, lines * lineMm * 1.2f);
        welcome.yMm = (heightMm - welcome.heightMm) / 2f;
        normalize();
    }

    public TextElement addText(String content) {
        TextElement element = new TextElement();
        element.text = content;
        element.yMm = Math.min(heightMm - 4f, 4f + elements.size() * 6f);
        element.widthMm = Math.max(1f, widthMm - 6f);
        elements.add(element);
        normalize();
        return element;
    }

    /** 按类型创建元素，按插入序号做网格化错位落位（参考 WePrint 栅格自动落位的简化版）。 */
    public TextElement addElement(String type) {
        TextElement element = new TextElement();
        element.type = type == null ? TextElement.TYPE_TEXT : type;
        int slot = elements.size();
        element.xMm = 2f + (slot % 3) * 3f;
        element.yMm = Math.min(heightMm - 4f, 2f + (slot % 4) * 3f);
        switch (element.type) {
            case TextElement.TYPE_BARCODE:
                element.text = "1234567890";
                element.format = "CODE_128";
                element.widthMm = Math.max(10f, widthMm - 10f);
                element.heightMm = Math.max(8f, Math.min(12f, heightMm - 4f));
                break;
            case TextElement.TYPE_QRCODE:
                element.text = "EPRINT";
                element.format = "QR_CODE";
                float side = Math.max(6f, Math.min(12f, heightMm - 4f));
                element.widthMm = side;
                element.heightMm = side;
                break;
            case TextElement.TYPE_LINE:
                element.text = "";
                element.format = "h";
                element.widthMm = Math.max(4f, widthMm - 8f);
                element.heightMm = 2f;
                element.lineWidthMm = 0.3f;
                break;
            case TextElement.TYPE_RECT:
                element.text = "";
                element.widthMm = Math.max(6f, widthMm - 10f);
                element.heightMm = Math.max(4f, heightMm - 6f);
                element.lineWidthMm = 0.4f;
                break;
            case TextElement.TYPE_TIME:
                element.text = "";
                element.format = TimeElementFormat.DATE.pattern;
                element.widthMm = Math.max(1f, widthMm - 6f);
                break;
            case TextElement.TYPE_SERIAL:
                element.text = "NO.";
                element.serialCounter = 1;
                element.serialStep = 1;
                element.serialDigits = 3;
                element.bold = true;
                element.widthMm = Math.max(1f, widthMm - 6f);
                break;
            case TextElement.TYPE_ARCTEXT:
                element.text = "弧形文字";
                element.format = "up";
                element.bold = true;
                element.widthMm = Math.max(8f, widthMm - 8f);
                element.heightMm = Math.max(4f, element.widthMm / 2f);
                break;
            case TextElement.TYPE_IMAGE:
                element.text = "";
                element.format = "fit";
                element.widthMm = Math.max(8f, widthMm - 10f);
                element.heightMm = Math.max(5f, heightMm - 7f);
                break;
            case TextElement.TYPE_ICON:
                element.text = "";
                element.format = "check";
                element.widthMm = 8f;
                element.heightMm = 8f;
                element.lineWidthMm = 0.7f;
                break;
            case TextElement.TYPE_TABLE:
                element.text = "名称\n数量\n电阻\n10";
                element.tableRows = 2;
                element.tableCols = 2;
                element.fontSizePt = 8f;
                element.widthMm = Math.max(10f, widthMm - 10f);
                element.heightMm = Math.max(5f, heightMm - 7f);
                element.lineWidthMm = 0.2f;
                break;
            default:
                element.type = TextElement.TYPE_TEXT;
                element.text = "新文本";
                element.widthMm = Math.max(1f, widthMm - 6f);
                break;
        }
        // 仅修正新元素的初始落位：默认完整放入标签；后续拖动/编辑仍允许越界。
        element.xMm = initialPosition(element.xMm, element.widthMm, widthMm);
        element.yMm = initialPosition(element.yMm, element.heightMm, heightMm);
        elements.add(element);
        normalize();
        return element;
    }

    private static float initialPosition(float position, float size, float documentSize) {
        return Math.max(0f, Math.min(position, Math.max(0f, documentSize - size)));
    }

    public TextElement find(String id) {
        if (id == null) return null;
        for (TextElement element : elements) {
            if (id.equals(element.id)) return element;
        }
        return null;
    }

    public void remove(String id) {
        elements.removeIf(element -> element.id.equals(id));
    }

    public void normalize() {
        widthMm = clamp(widthMm, MIN_WIDTH_MM, MAX_WIDTH_MM);
        heightMm = clamp(heightMm, MIN_HEIGHT_MM, MAX_HEIGHT_MM);
        dpi = dpi == 300 ? 300 : 203;
        borderStyle = Math.max(0, Math.min(2, borderStyle));

        for (TextElement element : elements) {
            element.text = element.text == null ? "" : element.text;
            element.type = element.type == null ? TextElement.TYPE_TEXT : element.type;
            element.format = element.format == null ? "" : element.format;
            if (TextElement.TYPE_BARCODE.equals(element.type) && element.format.isEmpty()) {
                element.format = "CODE_128";
            }
            if (TextElement.TYPE_BARCODE.equals(element.type)
                    && !com.otool.eprint.render.BarcodeRasterizer.isBarcodeFormat(element.format)) {
                element.format = "CODE_128";
            }
            if (TextElement.TYPE_QRCODE.equals(element.type)
                    && !com.otool.eprint.render.BarcodeRasterizer.isTwoDimensionalFormat(
                    element.format)) {
                element.format = "QR_CODE";
            }
            if (!("none".equals(element.barcodeTextPosition)
                    || "top".equals(element.barcodeTextPosition)
                    || "bottom".equals(element.barcodeTextPosition))) {
                element.barcodeTextPosition = element.showLabel ? "bottom" : "none";
            }
            element.showLabel = !"none".equals(element.barcodeTextPosition)
                    && element.showLabel;
            if (TextElement.TYPE_QRCODE.equals(element.type)
                    && !("L".equals(element.qrErrorCorrection)
                    || "M".equals(element.qrErrorCorrection)
                    || "Q".equals(element.qrErrorCorrection)
                    || "H".equals(element.qrErrorCorrection))) {
                element.qrErrorCorrection = "M";
            }
            element.quietZoneModules = Math.max(0, Math.min(16, element.quietZoneModules));
            int rotation = ((element.rotationDegrees % 360) + 360) % 360;
            element.rotationDegrees = rotation == 90 || rotation == 180 || rotation == 270
                    ? rotation : 0;
            if (TextElement.TYPE_ARCTEXT.equals(element.type)
                    && !("up".equals(element.format) || "down".equals(element.format))) {
                element.format = "up";
            }
            if (TextElement.TYPE_IMAGE.equals(element.type)
                    && !("fit".equals(element.format) || "fill".equals(element.format)
                    || "stretch".equals(element.format))) {
                element.format = "fit";
            }
            // 元素允许部分或全部越出标签；实际打印由标签位图边界自然裁切。
            element.xMm = clamp(element.xMm, -MAX_ELEMENT_OFFSET_MM, MAX_ELEMENT_OFFSET_MM);
            element.yMm = clamp(element.yMm, -MAX_ELEMENT_OFFSET_MM, MAX_ELEMENT_OFFSET_MM);
            element.widthMm = clamp(element.widthMm, MIN_ELEMENT_SIZE_MM, MAX_ELEMENT_SIZE_MM);
            element.heightMm = clamp(element.heightMm, MIN_ELEMENT_SIZE_MM, MAX_ELEMENT_SIZE_MM);
            element.lineWidthMm = clamp(element.lineWidthMm, 0.1f, 3f);
            element.fontSizePt = clamp(element.fontSizePt, 6f, 72f);
            element.tableRows = (int) clamp(element.tableRows, 1, 8);
            element.tableCols = (int) clamp(element.tableCols, 1, 8);
            element.alignment = Math.max(TextElement.ALIGN_LEFT, Math.min(TextElement.ALIGN_RIGHT, element.alignment));
        }
    }

    public JSONObject toJson() throws JSONException {
        normalize();
        JSONObject json = new JSONObject();
        json.put("widthMm", widthMm);
        json.put("heightMm", heightMm);
        json.put("dpi", dpi);
        json.put("borderStyle", borderStyle);
        JSONArray items = new JSONArray();
        for (TextElement element : elements) items.put(element.toJson());
        json.put("elements", items);
        return json;
    }

    public static LabelDocument fromJson(String value) {
        try {
            JSONObject json = new JSONObject(value);
            LabelDocument document = new LabelDocument();
            document.widthMm = (float) json.optDouble("widthMm", document.widthMm);
            document.heightMm = (float) json.optDouble("heightMm", document.heightMm);
            document.dpi = json.optInt("dpi", document.dpi);
            document.borderStyle = json.optInt("borderStyle", document.borderStyle);
            JSONArray items = json.optJSONArray("elements");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item != null) document.elements.add(TextElement.fromJson(item));
                }
            }
            document.normalize();
            document.centerWelcome();
            if (document.elements.isEmpty()) return createDefault();
            return document;
        } catch (JSONException ignored) {
            return createDefault();
        }
    }

    public static int mmToDots(float mm, int dpi) {
        return Math.max(1, Math.round(mm * dpi / 25.4f));
    }

    /** 坐标换算允许负值；与保证尺寸至少 1 点的 mmToDots 分开。 */
    public static int mmPositionToDots(float mm, int dpi) {
        return Math.round(mm * dpi / 25.4f);
    }

    public static float clamp(float value, float minimum, float maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}

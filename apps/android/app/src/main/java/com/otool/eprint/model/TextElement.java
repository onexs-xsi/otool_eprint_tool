package com.otool.eprint.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class TextElement {
    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    public String id;
    public String text;
    public float xMm;
    public float yMm;
    public float widthMm;
    public float fontSizePt;
    public boolean bold;
    public int alignment;

    public TextElement() {
        id = UUID.randomUUID().toString();
        text = "新文本";
        xMm = 3f;
        yMm = 4f;
        widthMm = 34f;
        fontSizePt = 12f;
        bold = false;
        alignment = ALIGN_LEFT;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("text", text);
        json.put("xMm", xMm);
        json.put("yMm", yMm);
        json.put("widthMm", widthMm);
        json.put("fontSizePt", fontSizePt);
        json.put("bold", bold);
        json.put("alignment", alignment);
        return json;
    }

    public static TextElement fromJson(JSONObject json) {
        TextElement element = new TextElement();
        element.id = json.optString("id", element.id);
        element.text = json.optString("text", element.text);
        element.xMm = (float) json.optDouble("xMm", element.xMm);
        element.yMm = (float) json.optDouble("yMm", element.yMm);
        element.widthMm = (float) json.optDouble("widthMm", element.widthMm);
        element.fontSizePt = (float) json.optDouble("fontSizePt", element.fontSizePt);
        element.bold = json.optBoolean("bold", element.bold);
        element.alignment = json.optInt("alignment", element.alignment);
        return element;
    }
}

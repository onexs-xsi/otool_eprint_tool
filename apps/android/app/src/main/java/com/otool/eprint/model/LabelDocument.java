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

    public float widthMm = 25f;
    public float heightMm = 15f;
    public int dpi = 203;
    public final List<TextElement> elements = new ArrayList<>();

    public static LabelDocument createDefault() {
        LabelDocument document = new LabelDocument();
        TextElement welcome = new TextElement();
        welcome.id = "welcome";
        welcome.text = "扫码内容将显示在这里";
        welcome.bold = true;
        welcome.alignment = TextElement.ALIGN_CENTER;
        document.elements.add(welcome);
        return document;
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

        for (TextElement element : elements) {
            element.text = element.text == null ? "" : element.text;
            element.xMm = clamp(element.xMm, 0f, Math.max(0f, widthMm - 1f));
            element.yMm = clamp(element.yMm, 0f, Math.max(0f, heightMm - 1f));
            element.widthMm = clamp(element.widthMm, 1f, Math.max(1f, widthMm - element.xMm));
            element.fontSizePt = clamp(element.fontSizePt, 6f, 72f);
            element.alignment = Math.max(TextElement.ALIGN_LEFT, Math.min(TextElement.ALIGN_RIGHT, element.alignment));
        }
    }

    public JSONObject toJson() throws JSONException {
        normalize();
        JSONObject json = new JSONObject();
        json.put("widthMm", widthMm);
        json.put("heightMm", heightMm);
        json.put("dpi", dpi);
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
            JSONArray items = json.optJSONArray("elements");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item != null) document.elements.add(TextElement.fromJson(item));
                }
            }
            document.normalize();
            if (document.elements.isEmpty()) return createDefault();
            return document;
        } catch (JSONException ignored) {
            return createDefault();
        }
    }

    public static int mmToDots(float mm, int dpi) {
        return Math.max(1, Math.round(mm * dpi / 25.4f));
    }

    public static float clamp(float value, float minimum, float maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}

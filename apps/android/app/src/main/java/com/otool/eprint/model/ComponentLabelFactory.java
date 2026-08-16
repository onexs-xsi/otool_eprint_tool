package com.otool.eprint.model;

import com.otool.eprint.scan.ComponentRecord;

/** Builds the compact 25x15 mm v1 component label using only local scan data. */
public final class ComponentLabelFactory {
    private ComponentLabelFactory() {}

    public static LabelDocument create(ComponentRecord record, int dpi) {
        return create(record, dpi, 25f, 15f);
    }

    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm) {
        LabelDocument document = new LabelDocument();
        document.widthMm = widthMm;
        document.heightMm = heightMm;
        document.dpi = dpi;
        document.elements.clear();

        String headline = first(record.manufacturerPartNumber, record.supplierPartNumber,
                "未识别器件");
        document.elements.add(text("component_model", headline, 1.2f, 1.0f,
                22.6f, 9.5f, true, TextElement.ALIGN_LEFT));
        String second = join("  ", record.supplierPartNumber,
                record.quantity > 0 ? "QTY " + record.quantity : "");
        document.elements.add(text("component_part", second, 1.2f, 7.0f,
                22.6f, 7.5f, true, TextElement.ALIGN_LEFT));
        String third = join("  ", record.orderNumber,
                record.trackingId.isEmpty() ? "" : "#" + record.trackingId);
        if (!third.isEmpty()) {
            document.elements.add(text("component_trace", third, 1.2f, 11.2f,
                    22.6f, 6.0f, false, TextElement.ALIGN_LEFT));
        }
        float xScale = widthMm / 25f;
        float yScale = heightMm / 15f;
        float fontScale = Math.min(xScale, yScale);
        for (TextElement element : document.elements) {
            element.xMm *= xScale;
            element.yMm *= yScale;
            element.widthMm *= xScale;
            element.fontSizePt = LabelDocument.clamp(element.fontSizePt * fontScale, 6f, 24f);
        }
        document.normalize();
        return document;
    }

    private static TextElement text(String id, String value, float x, float y,
                                    float width, float points, boolean bold, int alignment) {
        TextElement element = new TextElement();
        element.id = id;
        element.text = value;
        element.xMm = x;
        element.yMm = y;
        element.widthMm = width;
        element.fontSizePt = points;
        element.bold = bold;
        element.alignment = alignment;
        return element;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    private static String join(String separator, String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }
}

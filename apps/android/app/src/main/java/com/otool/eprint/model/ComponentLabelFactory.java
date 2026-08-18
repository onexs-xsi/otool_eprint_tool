package com.otool.eprint.model;

import com.otool.eprint.scan.ComponentRecord;

import java.util.ArrayList;
import java.util.List;

/** Builds component labels from local scan data using the selected template. */
public final class ComponentLabelFactory {
    private ComponentLabelFactory() {}

    public static LabelDocument create(ComponentRecord record, int dpi) {
        return create(record, dpi, 25f, 15f);
    }

    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm) {
        return create(record, dpi, widthMm, heightMm, ComponentTemplate.STANDARD,
                true, true, false);
    }

    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm,
                                       ComponentTemplate template, boolean boldHeadline,
                                       boolean showTrace, boolean largeFont) {
        LabelDocument document = new LabelDocument();
        document.widthMm = widthMm;
        document.heightMm = heightMm;
        document.dpi = dpi;
        document.elements.clear();
        switch (template) {
            case COMPACT:
                buildCompact(document, record, boldHeadline, largeFont);
                break;
            case DETAILED:
                buildDetailed(document, record, boldHeadline, showTrace, largeFont);
                break;
            case STANDARD:
            default:
                buildStandard(document, record, boldHeadline, showTrace, largeFont);
                break;
        }
        float xScale = widthMm / 25f;
        float yScale = heightMm / 15f;
        float fontScale = Math.min(xScale, yScale) * (largeFont ? 1.15f : 1f);
        for (TextElement element : document.elements) {
            element.xMm *= xScale;
            element.yMm *= yScale;
            element.widthMm *= xScale;
            element.fontSizePt = LabelDocument.clamp(element.fontSizePt * fontScale, 6f, 24f);
        }
        document.normalize();
        return document;
    }

    /** 简洁模板：型号单行大字，在标签内水平垂直居中。 */
    private static void buildCompact(LabelDocument document, ComponentRecord record,
                                     boolean boldHeadline, boolean largeFont) {
        String headline = first(record.manufacturerPartNumber, record.supplierPartNumber,
                "未识别器件");
        float fontPt = largeFont ? 14f : 12f;
        float lineMm = fontPt * 25.4f / 72f;
        TextElement element = text("component_model", headline, 1.2f,
                Math.max(0f, (15f - lineMm) / 2f), 22.6f, fontPt,
                boldHeadline, TextElement.ALIGN_CENTER);
        document.elements.add(element);
    }

    /** 标准模板：型号标题 + 料号/数量 + 可选追溯行（v1 版面）。 */
    private static void buildStandard(LabelDocument document, ComponentRecord record,
                                      boolean boldHeadline, boolean showTrace, boolean largeFont) {
        String headline = first(record.manufacturerPartNumber, record.supplierPartNumber,
                "未识别器件");
        document.elements.add(text("component_model", headline, 1.2f, 1.0f,
                22.6f, 9.5f, boldHeadline, TextElement.ALIGN_LEFT));
        String second = join("  ", record.supplierPartNumber,
                record.quantity > 0 ? "QTY " + record.quantity : "");
        if (!second.isEmpty()) {
            document.elements.add(text("component_part", second, 1.2f, 7.0f,
                    22.6f, 7.5f, boldHeadline, TextElement.ALIGN_LEFT));
        }
        if (showTrace) {
            String third = join("  ", record.orderNumber,
                    record.trackingId.isEmpty() ? "" : "#" + record.trackingId);
            if (!third.isEmpty()) {
                document.elements.add(text("component_trace", third, 1.2f, 11.2f,
                        22.6f, 6.0f, false, TextElement.ALIGN_LEFT));
            }
        }
    }

    /** 完整模板：全部字段小字多行，按实际有数据的行均分高度。 */
    private static void buildDetailed(LabelDocument document, ComponentRecord record,
                                      boolean boldHeadline, boolean showTrace, boolean largeFont) {
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"component_model",
                first(record.manufacturerPartNumber, record.supplierPartNumber, "未识别器件"),
                "headline"});
        if (!record.supplierPartNumber.isEmpty()) {
            lines.add(new String[]{"component_supplier", "料号 " + record.supplierPartNumber, "body"});
        }
        if (record.quantity > 0) {
            lines.add(new String[]{"component_quantity", "数量 " + record.quantity, "body"});
        }
        if (showTrace) {
            String trace = join("  ", record.orderNumber,
                    record.trackingId.isEmpty() ? "" : "#" + record.trackingId);
            if (!trace.isEmpty()) lines.add(new String[]{"component_trace", trace, "trace"});
        }
        float topMm = 0.8f;
        float stepMm = (15f - topMm * 2f) / Math.max(1, lines.size());
        for (int index = 0; index < lines.size(); index++) {
            String[] line = lines.get(index);
            boolean headline = "headline".equals(line[2]);
            float fontPt = headline ? 8f : ("body".equals(line[2]) ? 7f : 6f);
            document.elements.add(text(line[0], line[1], 1.2f, topMm + stepMm * index,
                    22.6f, fontPt, headline && boldHeadline, TextElement.ALIGN_LEFT));
        }
    }

    private static TextElement text(String id, String value, float x, float y,
                                    float width, float points, boolean bold, int alignment) {
        TextElement element = new TextElement();
        element.id = id;
        // 长料号自动缩字，保证单行不折行（折行会让各行在 15 mm 标签上重叠）；
        // 缩到下限仍超宽时截断末尾，保住订单号等关键前缀。
        float fontPt = fitFontPt(value, width, points, bold);
        String fitted = value;
        if (fontPt <= MIN_FONT_PT && estimatedMm(value, fontPt, bold) > width) {
            fitted = truncateToFit(value, width, fontPt, bold);
        }
        element.text = fitted;
        element.xMm = x;
        element.yMm = y;
        element.widthMm = width;
        element.fontSizePt = fontPt;
        element.bold = bold;
        element.alignment = alignment;
        return element;
    }

    private static final float MIN_FONT_PT = 6f;

    /** 估算文本宽度（全角 1em、半角 0.55em），超过可用宽度时按比例缩小字号。 */
    static float fitFontPt(String value, float widthMm, float points, boolean bold) {
        float estimatedMm = estimatedMm(value, points, bold);
        if (estimatedMm <= widthMm) return points;
        return Math.max(MIN_FONT_PT, points * widthMm / estimatedMm);
    }

    private static String truncateToFit(String value, float widthMm, float fontPt, boolean bold) {
        String text = value;
        while (text.length() > 4 && estimatedMm(text + "…", fontPt, bold) > widthMm) {
            text = text.substring(0, text.length() - 1);
        }
        return text.length() < value.length() ? text + "…" : value;
    }

    private static float estimatedMm(String value, float fontPt, boolean bold) {
        if (value == null || value.isEmpty()) return 0f;
        float emUnits = 0f;
        for (int index = 0; index < value.length(); index++) {
            emUnits += value.charAt(index) > 0x2E7F ? 1f : 0.55f;
        }
        return emUnits * fontPt * 25.4f / 72f * (bold ? 1.08f : 1f);
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

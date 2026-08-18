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
                                       boolean showBrand, boolean largeFont) {
        ComponentFieldSelection defaults = ComponentFieldSelection.defaults();
        return create(record, dpi, widthMm, heightMm, template, boldHeadline, largeFont,
                new ComponentFieldSelection(defaults.model, defaults.category,
                        defaults.specification, defaults.packageName, showBrand,
                        defaults.parameters, defaults.supplierPartNumber));
    }

    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm,
                                       ComponentTemplate template, boolean boldHeadline,
                                       boolean largeFont, ComponentFieldSelection fields) {
        LabelDocument document = new LabelDocument();
        document.widthMm = widthMm;
        document.heightMm = heightMm;
        document.dpi = dpi;
        document.elements.clear();
        switch (template) {
            case COMPACT:
                buildCompact(document, record, boldHeadline, largeFont, fields);
                break;
            case DETAILED:
                buildDetailed(document, record, boldHeadline, largeFont, fields);
                break;
            case STANDARD:
            default:
                buildStandard(document, record, boldHeadline, largeFont, fields);
                break;
        }
        float xScale = widthMm / 25f;
        float yScale = heightMm / 15f;
        float fontScale = Math.min(xScale, yScale);
        for (TextElement element : document.elements) {
            element.xMm *= xScale;
            element.yMm *= yScale;
            element.widthMm *= xScale;
            element.fontSizePt = LabelDocument.clamp(element.fontSizePt * fontScale, 4.5f, 24f);
        }
        document.normalize();
        return document;
    }

    /** 简洁模板：固定前缀列的四项技术身份；任何情况下都不显示立创料号。 */
    private static void buildCompact(LabelDocument document, ComponentRecord record,
                                     boolean boldHeadline, boolean largeFont,
                                     ComponentFieldSelection fields) {
        List<FieldRow> rows = new ArrayList<>();
        addBaseRows(rows, record, boldHeadline, fields);
        addFixedRows(document, rows, largeFont);
    }

    /** 标准模板：固定前缀列的基础技术字段、可选品牌和立创料号。 */
    private static void buildStandard(LabelDocument document, ComponentRecord record,
                                      boolean boldHeadline, boolean largeFont,
                                      ComponentFieldSelection fields) {
        List<FieldRow> rows = new ArrayList<>();
        addBaseRows(rows, record, boldHeadline, fields);
        if (fields.brand) {
            rows.add(new FieldRow("component_brand", "品牌:", field(record, "厂商"), false));
        }
        if (fields.supplierPartNumber) {
            rows.add(new FieldRow("component_supplier", "立创:", record.supplierPartNumber, false));
        }
        addFixedRows(document, rows, largeFont);
    }

    /** 完整模板：固定前缀列展开全部结构化技术字段；采购/追溯字段仍不进入标签。 */
    private static void buildDetailed(LabelDocument document, ComponentRecord record,
                                      boolean boldHeadline, boolean largeFont,
                                      ComponentFieldSelection fields) {
        List<FieldRow> rows = new ArrayList<>();
        addBaseRows(rows, record, boldHeadline, fields);
        if (fields.brand) {
            rows.add(new FieldRow("component_brand", "品牌:", field(record, "厂商"), false));
        }
        if (fields.parameters) {
            rows.add(new FieldRow("component_parameters", "参数:", field(record, "参数"), false));
        }
        if (fields.supplierPartNumber) {
            rows.add(new FieldRow("component_supplier", "立创:", record.supplierPartNumber, false));
        }
        addFixedRows(document, rows, largeFont);
    }

    private static void addBaseRows(List<FieldRow> rows, ComponentRecord record,
                                    boolean boldHeadline, ComponentFieldSelection fields) {
        if (fields.model) {
            rows.add(new FieldRow("component_model", "型号:", record.manufacturerPartNumber,
                    boldHeadline));
        }
        if (fields.category) {
            rows.add(new FieldRow("component_category", "品类:", field(record, "品类"), false));
        }
        if (fields.specification) {
            rows.add(new FieldRow("component_spec", "规格:", field(record, "规格"), false));
        }
        if (fields.packageName) {
            rows.add(new FieldRow("component_package", "封装:", field(record, "封装"), false));
        }
    }

    /** 前缀和值拆成两个元素；所有模板使用完全相同的 x/width，缺值也保留前缀元素。 */
    private static void addFixedRows(LabelDocument document, List<FieldRow> rows,
                                     boolean largeFont) {
        float topMm = 0.45f;
        float stepMm = 14.1f / Math.max(1, rows.size());
        boolean crowded = rows.size() >= 6;
        float prefixPt = crowded ? 4.8f : (largeFont ? 5.8f : 5.3f);
        float valuePt = crowded ? (largeFont ? 5.4f : 5.0f) : (largeFont ? 7.0f : 6.2f);
        for (int index = 0; index < rows.size(); index++) {
            FieldRow row = rows.get(index);
            float y = topMm + stepMm * index;
            document.elements.add(text(row.id + "_prefix", row.prefix, 0.4f, y,
                    4.1f, prefixPt, false, TextElement.ALIGN_RIGHT));
            document.elements.add(text(row.id, row.value, 4.7f, y,
                    19.9f, valuePt, row.boldValue, TextElement.ALIGN_LEFT));
        }
    }

    private static String field(ComponentRecord record, String key) {
        String value = record.fields.get(key);
        return value == null ? "" : value.trim();
    }

    private static final class FieldRow {
        final String id;
        final String prefix;
        final String value;
        final boolean boldValue;

        FieldRow(String id, String prefix, String value, boolean boldValue) {
            this.id = id;
            this.prefix = prefix;
            this.value = value == null ? "" : value;
            this.boldValue = boldValue;
        }
    }

    private static TextElement text(String id, String value, float x, float y,
                                    float width, float points, boolean bold, int alignment) {
        TextElement element = new TextElement();
        element.id = id;
        // 长料号自动缩字，保证单行不折行（折行会让各行在 15 mm 标签上重叠）；
        // 缩到下限仍超宽时截断末尾，保住型号等关键身份字段。
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

    /** 固定前缀列压缩了值列；4.5pt 在 203 DPI 下约 13 像素，仍可完整保留长 MPN。 */
    private static final float MIN_FONT_PT = 4.5f;

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

}

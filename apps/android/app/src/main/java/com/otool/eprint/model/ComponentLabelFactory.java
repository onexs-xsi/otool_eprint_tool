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
        return create(record, dpi, widthMm, heightMm, template, boldHeadline, largeFont,
                fields, true);
    }

    /**
     * 按标签实际尺寸直接排版（不再是 25×15 基准 + 事后缩放）：行距由可用高度决定，
     * 空间不足时按字段优先级取舍；separators 控制是否输出分隔线（模板配置项）。
     */
    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm,
                                       ComponentTemplate template, boolean boldHeadline,
                                       boolean largeFont, ComponentFieldSelection fields,
                                       boolean separators) {
        return create(record, dpi, widthMm, heightMm, template, boldHeadline, largeFont,
                fields, separators, false);
    }

    /**
     * 模板只控制版面密度，七类字段完全由 fields 决定；removeBlankFields 开启时，
     * 值为空的字段在空间取舍和排版前整行剔除。
     */
    public static LabelDocument create(ComponentRecord record, int dpi,
                                       float widthMm, float heightMm,
                                       ComponentTemplate template, boolean boldHeadline,
                                       boolean largeFont, ComponentFieldSelection fields,
                                       boolean separators, boolean removeBlankFields) {
        LabelDocument document = new LabelDocument();
        document.widthMm = widthMm;
        document.heightMm = heightMm;
        document.dpi = dpi;
        document.elements.clear();
        List<FieldRow> rows = selectedRows(record, boldHeadline, fields);
        if (removeBlankFields) removeBlankRows(rows);
        layoutRows(document, rows, template, largeFont, separators);
        document.normalize();
        return document;
    }

    /** 所有模板共享字段集合；候选行仅由字段开关决定（priority 越小越先被丢）。 */
    private static List<FieldRow> selectedRows(ComponentRecord record, boolean boldHeadline,
                                               ComponentFieldSelection fields) {
        List<FieldRow> rows = new ArrayList<>();
        addBaseRows(rows, record, boldHeadline, fields);
        if (fields.brand) {
            rows.add(new FieldRow("component_brand", "品牌:", field(record, "厂商"),
                    false, PRIORITY_BRAND));
        }
        if (fields.parameters) {
            rows.add(new FieldRow("component_parameters", "参数:", field(record, "参数"),
                    false, PRIORITY_PARAMETERS));
        }
        if (fields.supplierPartNumber) {
            rows.add(new FieldRow("component_supplier", "立创:", record.supplierPartNumber,
                    false, PRIORITY_SUPPLIER));
        }
        return rows;
    }

    private static void removeBlankRows(List<FieldRow> rows) {
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (rows.get(index).value.trim().isEmpty()) rows.remove(index);
        }
    }

    private static void addBaseRows(List<FieldRow> rows, ComponentRecord record,
                                    boolean boldHeadline, ComponentFieldSelection fields) {
        if (fields.model) {
            rows.add(new FieldRow("component_model", "型号:", record.manufacturerPartNumber,
                    boldHeadline, PRIORITY_MODEL));
        }
        if (fields.category) {
            rows.add(new FieldRow("component_category", "品类:", field(record, "品类"),
                    false, PRIORITY_CATEGORY));
        }
        if (fields.specification) {
            rows.add(new FieldRow("component_spec", "规格:", field(record, "规格"),
                    false, PRIORITY_SPEC));
        }
        if (fields.packageName) {
            rows.add(new FieldRow("component_package", "封装:", field(record, "封装"),
                    false, PRIORITY_PACKAGE));
        }
    }

    // 字段取舍优先级：型号是身份核心永不主动丢；参数/品牌最可先丢。
    static final int PRIORITY_MODEL = 100;
    static final int PRIORITY_SPEC = 90;
    static final int PRIORITY_PACKAGE = 80;
    static final int PRIORITY_SUPPLIER = 70;
    static final int PRIORITY_CATEGORY = 60;
    static final int PRIORITY_BRAND = 40;
    static final int PRIORITY_PARAMETERS = 10;

    /** 最小行距：最小字号 4.5pt ≈ 1.59mm，行距再小就会上下行相触。 */
    static final float MIN_PITCH_MM = 2.0f;
    private static final float PT_PER_MM = 72f / 25.4f;
    /** 字号占行距的填充系数；0.72 在 203 DPI 热敏纸上密度与可读性较均衡。 */
    static final float PITCH_FILL = 0.72f;

    /**
     * 前缀列右对齐 + 值列左对齐，两列之间竖分隔线；行距由标签高度均分，
     * 行数超出最小行距时从低优先级字段开始取舍，直到放得下为止。
     */
    private static void layoutRows(LabelDocument document, List<FieldRow> rows,
                                   ComponentTemplate template, boolean largeFont,
                                   boolean separators) {
        float widthMm = document.widthMm;
        float heightMm = document.heightMm;
        float marginX = Math.max(0.25f, widthMm * 0.012f);
        float marginY = Math.max(0.3f, heightMm * 0.03f);
        float usableH = heightMm - marginY * 2;
        List<FieldRow> kept = new ArrayList<>(rows);
        while (kept.size() > 1 && usableH / kept.size() < MIN_PITCH_MM) {
            int drop = 0;
            for (int index = 1; index < kept.size(); index++) {
                if (kept.get(index).priority < kept.get(drop).priority) drop = index;
            }
            kept.remove(drop);
        }
        float pitch = usableH / Math.max(1, kept.size());
        float valuePt = LabelDocument.clamp(
                pitch * PT_PER_MM * PITCH_FILL * templateFontScale(template)
                        * (largeFont ? 1.1f : 1f), MIN_FONT_PT, 24f);
        float prefixPt = Math.max(4.2f, valuePt * 0.75f);
        // 前缀列宽按"3 个全角字符 + 呼吸位"实测自适应（所有前缀均为"XX："格式），
        // 上限 5.0mm：过宽的前缀列会把长型号逼进截断（实测 19 字符粗体 MPN 需要 ~19mm）。
        float prefixNeedMm = estimatedMm("参数：", prefixPt, false) + 0.15f;
        float prefixW = LabelDocument.clamp(Math.max(widthMm * 0.17f, prefixNeedMm),
                3.0f, Math.min(widthMm * 0.34f, 5.0f));
        // 列间隙 0.4mm：竖线居中后，前缀与值文字各留 0.2mm 呼吸位，不贴线。
        float gapMm = 0.4f;
        float valueX = marginX + prefixW + gapMm;
        float valueW = widthMm - valueX - marginX;
        float rowH = pitch * 0.95f;
        for (int index = 0; index < kept.size(); index++) {
            FieldRow row = kept.get(index);
            float y = marginY + pitch * index;
            document.elements.add(text(row.id + "_prefix", row.prefix, marginX, y,
                    prefixW, rowH, prefixPt, false, TextElement.ALIGN_RIGHT));
            document.elements.add(text(row.id, row.value, valueX, y,
                    valueW, rowH, valuePt, row.boldValue, TextElement.ALIGN_LEFT));
        }
        if (separators) {
            addSeparators(document, kept.size(), marginX, marginY, valueX - gapMm / 2f, pitch);
        }
    }

    /** 三档模板只改变字号密度，不再增加、删除或锁定字段。 */
    private static float templateFontScale(ComponentTemplate template) {
        switch (template) {
            case COMPACT:
                return 0.92f;
            case DETAILED:
                return 1.08f;
            case STANDARD:
            default:
                return 1f;
        }
    }

    /**
     * 分隔线：前缀/值列之间的竖线 + 首行（型号）下方横线；行距 ≥2.8mm 的较宽松标签
     * 再补全行间横线，呈表格化。线宽 0.15mm（203 DPI 约 1~2 点）。
     */
    private static void addSeparators(LabelDocument document, int rows,
                                      float marginX, float marginY, float sepX, float pitch) {
        if (rows < 2) return;
        float widthMm = document.widthMm;
        float heightMm = document.heightMm;
        document.elements.add(line("component_sep_v", "v", sepX, marginY,
                0f, heightMm - marginY * 2));
        document.elements.add(line("component_sep_head", "h", marginX, marginY + pitch,
                widthMm - marginX * 2, 0f));
        if (pitch >= 2.8f) {
            for (int index = 2; index < rows; index++) {
                document.elements.add(line("component_sep_r" + index, "h",
                        marginX, marginY + pitch * index, widthMm - marginX * 2, 0f));
            }
        }
    }

    private static TextElement line(String id, String format, float x, float y,
                                    float width, float height) {
        TextElement element = new TextElement();
        element.id = id;
        element.type = TextElement.TYPE_LINE;
        element.format = format;
        element.text = "";
        element.xMm = x;
        element.yMm = y;
        element.widthMm = width;
        element.heightMm = height;
        element.lineWidthMm = 0.15f;
        return element;
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
        final int priority;

        FieldRow(String id, String prefix, String value, boolean boldValue, int priority) {
            this.id = id;
            this.prefix = prefix;
            this.value = value == null ? "" : value;
            this.boldValue = boldValue;
            this.priority = priority;
        }
    }

    private static TextElement text(String id, String value, float x, float y,
                                    float width, float height, float points,
                                    boolean bold, int alignment) {
        TextElement element = new TextElement();
        element.id = id;
        // 长料号自动缩字，保证单行不折行（折行会让各行在小标签上重叠）；
        // 舒适下限 4.5pt 之下还留 4.0pt 硬下限（203 DPI 约 11px），缩到硬下限仍超宽才截断，
        // 尽量避免丢字符；渲染层另有 Paint 实测缩字兜底（TextFitUtil）。
        float fontPt = fitFontPt(value, width, points, bold);
        String fitted = value;
        if (fontPt <= FIT_HARD_MIN_PT && estimatedMm(value, fontPt, bold) > width) {
            fitted = truncateToFit(value, width, fontPt, bold);
        }
        element.text = fitted;
        element.xMm = x;
        element.yMm = y;
        element.widthMm = width;
        element.heightMm = height;
        element.fontSizePt = fontPt;
        element.bold = bold;
        element.alignment = alignment;
        return element;
    }

    /** 舒适下限：4.5pt 在 203 DPI 下约 13 像素，仍可完整保留长 MPN。 */
    private static final float MIN_FONT_PT = 4.5f;
    /** 缩字硬下限：4.0pt 约 11 像素；低于此值宁可截断也不再缩。 */
    static final float FIT_HARD_MIN_PT = 4.0f;

    /** 估算文本宽度（全角 1em、半角 0.6em），超过可用宽度时按比例缩小字号。
     *  0.6em 比理论值保守：热敏/系统字体的数字大写偏宽，留 1% 呼吸位防预览折行。 */
    static float fitFontPt(String value, float widthMm, float points, boolean bold) {
        float estimatedMm = estimatedMm(value, points, bold);
        float usableMm = widthMm * 0.99f;
        if (estimatedMm <= usableMm) return points;
        return Math.max(FIT_HARD_MIN_PT, points * usableMm / estimatedMm);
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
            emUnits += value.charAt(index) > 0x2E7F ? 1f : 0.6f;
        }
        return emUnits * fontPt * 25.4f / 72f * (bold ? 1.05f : 1f);
    }

}

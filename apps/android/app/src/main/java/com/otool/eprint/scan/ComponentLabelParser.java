package com.otool.eprint.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Offline supplier-label parser. It never performs network lookups or mutates input data. */
public final class ComponentLabelParser {
    private ComponentLabelParser() {}

    public static ComponentRecord parse(String raw, SupplierFormat requested) {
        QrContentParser.Parsed generic = QrContentParser.parse(raw);
        Map<String, String> normalized = normalizeKeys(generic.fields);
        SupplierFormat effective = requested == SupplierFormat.AUTO
                ? detect(normalized) : requested;
        if (effective == SupplierFormat.LCSC) return parseLcsc(raw, normalized);
        return new ComponentRecord(SupplierFormat.AUTO, false, 0, "", "", "", 0,
                "", raw, normalized, "尚未识别供应商格式，已保留原始扫码数据");
    }

    private static SupplierFormat detect(Map<String, String> fields) {
        int score = 0;
        if (fields.containsKey("pc")) score += 35;
        if (fields.containsKey("pm")) score += 25;
        if (fields.containsKey("on")) score += 20;
        if (fields.containsKey("qty")) score += 20;
        String pc = clean(fields.get("pc"));
        if (pc.toUpperCase(Locale.ROOT).matches("C[0-9]{3,}")) score += 20;
        return score >= 70 ? SupplierFormat.LCSC : SupplierFormat.AUTO;
    }

    private static ComponentRecord parseLcsc(String raw, Map<String, String> fields) {
        String order = clean(fields.get("on"));
        String part = clean(fields.get("pc"));
        String model = clean(fields.get("pm"));
        String tracking = clean(fields.get("pdi"));
        int quantity = positiveInteger(fields.get("qty"));
        int score = 0;
        if (!order.isEmpty()) score += 20;
        if (part.toUpperCase(Locale.ROOT).matches("C[0-9]{3,}")) score += 35;
        if (!model.isEmpty()) score += 25;
        if (quantity > 0) score += 20;
        String warning = score >= 70 ? ""
                : "字段不完整；打印前请核对料号、型号与数量";
        return new ComponentRecord(SupplierFormat.LCSC, score >= 70, Math.min(100, score),
                order, part, model, quantity, tracking, raw,
                fields.isEmpty() ? Collections.emptyMap() : fields, warning);
    }

    private static Map<String, String> normalizeKeys(Map<String, String> fields) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            result.put(field.getKey().trim().toLowerCase(Locale.ROOT), clean(field.getValue()));
        }
        return result;
    }

    private static int positiveInteger(String value) {
        try {
            return Math.max(0, Integer.parseInt(clean(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.trim();
        return "null".equalsIgnoreCase(result) ? "" : result;
    }
}

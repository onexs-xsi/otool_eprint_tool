package com.otool.eprint.scan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GLM-OCR“信息提取”严格 JSON → 统一器件记录，供三档模板一次取用。 */
public final class GlmInformationExtractionParser {
    private static final String[] REQUIRED_KEYS = {
            "manufacturer_part_number", "lcsc_part_number", "category",
            "specification", "package", "brand", "parameters"
    };
    private static final Pattern LCSC_PART = Pattern.compile("\\bC\\d{5,}\\b");

    private GlmInformationExtractionParser() {}

    /**
     * 只接受包含完整 schema 的 JSON；缺键直接报错，避免把不完整自由文本静默写入模板。
     */
    public static ComponentRecord parse(String output) throws JSONException {
        String jsonText = extractJsonObject(output);
        JSONObject json = new JSONObject(jsonText);
        for (String key : REQUIRED_KEYS) {
            if (!json.has(key)) throw new JSONException("GLM 信息提取缺少字段：" + key);
        }

        String model = clean(json.optString("manufacturer_part_number", ""));
        String supplierPart = normalizeLcscPart(json.optString("lcsc_part_number", ""));
        String category = clean(json.optString("category", ""));
        String specification = clean(json.optString("specification", ""));
        String packageCode = clean(json.optString("package", ""));
        String brand = clean(json.optString("brand", ""));
        String parameters = parameterSummary(json.opt("parameters"));

        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "品类", category);
        put(fields, "规格", specification);
        put(fields, "封装", packageCode);
        put(fields, "厂商", brand);
        put(fields, "参数", parameters);

        int populated = 0;
        if (!model.isEmpty()) populated++;
        if (!supplierPart.isEmpty()) populated++;
        if (!category.isEmpty()) populated++;
        if (!specification.isEmpty()) populated++;
        if (!packageCode.isEmpty()) populated++;
        if (!brand.isEmpty()) populated++;
        if (!parameters.isEmpty()) populated++;
        int confidence = Math.min(100, populated * 14 + (!model.isEmpty() ? 2 : 0));
        boolean recognized = !model.isEmpty() || !supplierPart.isEmpty();
        String warning = model.isEmpty() ? "缺少型号，打印前请人工核对" : "";
        SupplierFormat supplier = supplierPart.isEmpty() ? SupplierFormat.AUTO : SupplierFormat.LCSC;
        return new ComponentRecord(supplier, recognized, confidence, "", supplierPart,
                model, 0, "", output, fields, warning);
    }

    static String extractJsonObject(String output) throws JSONException {
        String text = output == null ? "" : output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new JSONException("GLM 信息提取未返回 JSON 对象");
        return text.substring(start, end + 1);
    }

    private static String normalizeLcscPart(String value) {
        Matcher matcher = LCSC_PART.matcher(clean(value).toUpperCase());
        return matcher.find() ? matcher.group() : "";
    }

    private static String parameterSummary(Object raw) {
        StringBuilder summary = new StringBuilder();
        if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.optJSONObject(index);
                if (item != null) {
                    appendParameter(summary, item.optString("name", ""),
                            item.optString("value", ""));
                    continue;
                }
                appendRawParameter(summary, values.optString(index, ""));
            }
        } else if (raw instanceof JSONObject) {
            JSONObject values = (JSONObject) raw;
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                appendParameter(summary, key, values.optString(key, ""));
            }
        }
        return summary.toString();
    }

    private static void appendParameter(StringBuilder target, String name, String value) {
        String cleanName = clean(name);
        String cleanValue = clean(value);
        if (cleanName.isEmpty() || cleanValue.isEmpty()) return;
        if (target.length() > 0) target.append(" · ");
        target.append(cleanName).append(':').append(cleanValue);
    }

    private static void appendRawParameter(StringBuilder target, String value) {
        String cleanValue = clean(value);
        if (cleanValue.isEmpty()) return;
        if (target.length() > 0) target.append(" · ");
        target.append(cleanValue);
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (!value.isEmpty()) fields.put(key, value);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        return "null".equalsIgnoreCase(cleaned) ? "" : cleaned;
    }
}

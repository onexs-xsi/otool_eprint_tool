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
    private static final Pattern PACKAGE_VALUE = Pattern.compile(
            "(?i)^(?:SMD[-\\s]?)?(?:0[2346]0[23]|0805|1206|1210|2512)$"
                    + "|^(?:SOT|SOP|SSOP|TSSOP|QFN|DFN|BGA|LGA|DIP|TO)[-\\s]?\\w+$"
                    + "|^P\\s*=\\s*\\d+(?:\\.\\d+)?\\s*mm$");

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

    /**
     * 解析后用同图 PP-OCR 的高置信候选做确定性落地校验。VLM 仍负责字段语义；这里仅纠正
     * 有明确反证的角色混淆（内部编码冒充型号、规格混入 ROHS 等）并补齐其漏字段。
     */
    public static ComponentRecord parse(String output, ComponentRecord ocrEvidence)
            throws JSONException {
        return groundWithOcr(parse(output), ocrEvidence);
    }

    static ComponentRecord groundWithOcr(ComponentRecord vlm, ComponentRecord ocrEvidence) {
        if (vlm == null || ocrEvidence == null || ocrEvidence.confidence < 80
                || ocrEvidence.raw.isEmpty()) {
            return vlm;
        }
        int corrections = 0;
        String model = vlm.manufacturerPartNumber;
        String internalCode = clean(ocrEvidence.fields.get("内部编码"));
        String ocrModel = ocrEvidence.manufacturerPartNumber;
        if (!ocrModel.isEmpty() && (model.isEmpty() || sameValue(model, internalCode)
                || (!supportedByOcr(model, ocrEvidence.raw)
                && supportedByOcr(ocrModel, ocrEvidence.raw)))) {
            if (!sameValue(model, ocrModel)) corrections++;
            model = ocrModel;
        }

        String supplierPart = vlm.supplierPartNumber;
        String ocrSupplierPart = ocrEvidence.supplierPartNumber;
        if (!ocrSupplierPart.isEmpty() && (supplierPart.isEmpty()
                || (!supportedByOcr(supplierPart, ocrEvidence.raw)
                && supportedByOcr(ocrSupplierPart, ocrEvidence.raw)))) {
            if (!sameValue(supplierPart, ocrSupplierPart)) corrections++;
            supplierPart = ocrSupplierPart;
        }

        Map<String, String> fields = new LinkedHashMap<>(vlm.fields);
        corrections += groundField(fields, "品类", ocrEvidence, false);
        corrections += groundField(fields, "规格", ocrEvidence, true);
        corrections += groundPackage(fields, ocrEvidence);
        corrections += groundField(fields, "厂商", ocrEvidence, false);

        String parameters = clean(fields.get("参数"));
        String groundedParameters = removeCanonicalParameters(parameters, model, supplierPart,
                internalCode, fields);
        if (!sameValue(parameters, groundedParameters)) corrections++;
        if (groundedParameters.isEmpty()) fields.remove("参数");
        else fields.put("参数", groundedParameters);

        if (corrections == 0) return vlm;
        String warning = model.isEmpty() ? "缺少型号，打印前请人工核对" : "";
        String raw = vlm.raw + "\n[OCR-grounding: corrected " + corrections + " field(s)]";
        SupplierFormat supplier = supplierPart.isEmpty() ? vlm.supplier : SupplierFormat.LCSC;
        return new ComponentRecord(supplier, !model.isEmpty() || !supplierPart.isEmpty(),
                Math.max(vlm.confidence, ocrEvidence.confidence), "", supplierPart,
                model, 0, "", raw, fields, warning);
    }

    private static int groundField(Map<String, String> fields, String key,
                                   ComponentRecord evidence, boolean specification) {
        String value = clean(fields.get(key));
        String candidate = clean(evidence.fields.get(key));
        if (candidate.isEmpty()) return 0;
        boolean contradicted = !value.isEmpty() && !supportedByOcr(value, evidence.raw)
                && supportedByOcr(candidate, evidence.raw);
        if (specification && isPollutedSpecification(value, evidence)) contradicted = true;
        if (value.isEmpty() || contradicted) {
            fields.put(key, candidate);
            return sameValue(value, candidate) ? 0 : 1;
        }
        return 0;
    }

    private static int groundPackage(Map<String, String> fields, ComponentRecord evidence) {
        String value = clean(fields.get("封装"));
        String candidate = clean(evidence.fields.get("封装"));
        if (candidate.isEmpty()) return 0;
        boolean contradicted = !value.isEmpty()
                && (!supportedByOcr(value, evidence.raw) || !PACKAGE_VALUE.matcher(value).matches())
                && supportedByOcr(candidate, evidence.raw);
        if (value.isEmpty() || contradicted) {
            fields.put("封装", candidate);
            return sameValue(value, candidate) ? 0 : 1;
        }
        return 0;
    }

    private static boolean isPollutedSpecification(String value, ComponentRecord evidence) {
        if (value.isEmpty()) return false;
        String upper = value.toUpperCase();
        if (upper.contains("ROHS") || upper.contains("QTY") || value.indexOf('\n') >= 0) {
            return true;
        }
        return duplicatesCanonical(value, evidence.manufacturerPartNumber)
                || duplicatesCanonical(value, evidence.supplierPartNumber)
                || duplicatesCanonical(value, evidence.fields.get("内部编码"))
                || duplicatesCanonical(value, evidence.fields.get("封装"))
                || duplicatesCanonical(value, evidence.fields.get("厂商"));
    }

    private static String removeCanonicalParameters(String parameters, String model,
                                                     String supplierPart, String internalCode,
                                                     Map<String, String> fields) {
        if (parameters.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        for (String part : parameters.split("\\s*·\\s*")) {
            String value = clean(part);
            if (value.isEmpty() || duplicatesCanonical(value, model)
                    || duplicatesCanonical(value, supplierPart)
                    || duplicatesCanonical(value, internalCode)
                    || duplicatesCanonical(value, fields.get("品类"))
                    || duplicatesCanonical(value, fields.get("规格"))
                    || duplicatesCanonical(value, fields.get("封装"))
                    || duplicatesCanonical(value, fields.get("厂商"))) {
                continue;
            }
            if (kept.length() > 0) kept.append(" · ");
            kept.append(value);
        }
        return kept.toString();
    }

    private static boolean duplicatesCanonical(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        return !a.isEmpty() && !b.isEmpty() && a.equals(b);
    }

    private static boolean supportedByOcr(String value, String ocrText) {
        String needle = normalize(value);
        return !needle.isEmpty() && normalize(ocrText).contains(needle);
    }

    private static boolean sameValue(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    private static String normalize(String value) {
        return clean(value).replaceAll("[\\s·:：,，()（）\\[\\]{}]", "")
                .toUpperCase();
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

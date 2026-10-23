package com.otool.eprint.scan.qwen;

import com.otool.eprint.scan.ComponentRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将 OCR 普通文本和当前结构化结果组装为 Qwen 严格 fill-only 请求。 */
public final class QwenComponentPrompt {
    static final int MAX_EVIDENCE_CHARS = 8_000;

    private QwenComponentPrompt() {}

    public static String build(ComponentRecord current, String ocrEvidence,
                               QwenSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manufacturer_part_number", current == null
                ? "" : current.manufacturerPartNumber);
        values.put("lcsc_part_number", current == null ? "" : current.supplierPartNumber);
        values.put("category", field(current, "品类"));
        values.put("specification", field(current, "规格"));
        values.put("package", field(current, "封装"));
        values.put("brand", field(current, "厂商"));
        values.put("parameters", field(current, "参数"));
        JSONObject record = new JSONObject(values);
        Map<String, Object> knownNonMpnValues = new LinkedHashMap<>();
        if (current != null) {
            putIfNotEmpty(knownNonMpnValues, "internal_code", field(current, "内部编码"));
            putIfNotEmpty(knownNonMpnValues, "order_number", current.orderNumber);
            putIfNotEmpty(knownNonMpnValues, "tracking_id", current.trackingId);
            putIfNotEmpty(knownNonMpnValues, "batch_number", field(current, "批次号"));
            putIfNotEmpty(knownNonMpnValues, "category", field(current, "品类"));
            putIfNotEmpty(knownNonMpnValues, "specification", field(current, "规格"));
            putIfNotEmpty(knownNonMpnValues, "package", field(current, "封装"));
            putIfNotEmpty(knownNonMpnValues, "brand", field(current, "厂商"));
        }

        StringBuilder prompt = new StringBuilder(3_000);
        prompt.append("你是离线电子元器件标签的文本整理器。输入只有 OCR 普通文本和当前字段，")
                .append("没有任何视觉输入。请逐字符核对证据，优先补全当前为空的字段；");
        if (settings != null && settings.allowEvidenceBackedCorrections) {
            prompt.append("允许检查明显错误：仅当现有值没有 OCR 证据，或型号误取了下方明确列出的")
                    .append("非型号值，且替代值有明确完整证据时，才允许提出纠正；")
                    .append("程序还会执行格式、目录和逐字段二次校验。不得猜测型号。\n");
        } else {
            prompt.append("已有非空字段必须原样返回，不得猜测、改写型号或把仓储/内部编码当作型号。\n");
        }
        prompt
                .append("候选值必须能在 OCR_EVIDENCE 中找到文字证据；数量、订单、批次、追踪、")
                .append("ROHS/合规文字和码内容都要忽略。manufacturer_part_number 必须来自独立行，")
                .append("纠错时优先选择同时含字母和数字、且不等于品牌/品类/规格/封装的值。")
                .append("parameters 只放明确出现名称和值的技术参数。\n")
                .append("只输出一个 JSON 对象，不得输出 Markdown、说明或额外键，且必须包含全部七键：\n")
                .append("{\"manufacturer_part_number\":\"\",\"lcsc_part_number\":\"\",")
                .append("\"category\":\"\",\"specification\":\"\",\"package\":\"\",")
                .append("\"brand\":\"\",\"parameters\":[]}\n")
                .append("CURRENT_RECORD:\n").append(record).append('\n');
        if (!knownNonMpnValues.isEmpty()) {
            prompt.append("KNOWN_NON_MPN_VALUES（全部禁止作为型号）:\n")
                    .append(new JSONObject(knownNonMpnValues)).append('\n');
        }
        if (settings != null && !settings.extraInstructions.isEmpty()) {
            prompt.append("EXTRA_RULES（不得覆盖上述硬规则）:\n")
                    .append(settings.extraInstructions).append('\n');
        }
        prompt.append("OCR_EVIDENCE:\n").append(sanitizeEvidence(ocrEvidence));
        if (settings != null && settings.allowEvidenceBackedCorrections) {
            List<String> modelHints = modelCandidateHints(current, ocrEvidence, knownNonMpnValues);
            if (!modelHints.isEmpty()) {
                prompt.append("\nMODEL_CANDIDATE_HINTS（从 OCR 独立行筛出的型号候选）:\n")
                        .append(new JSONArray(modelHints)).append('\n')
                        .append("FINAL_CORRECTION_RULE：若要修正 manufacturer_part_number，必须从上述数组选择；")
                        .append("绝不能输出 brand/category/specification/package 或 KNOWN_NON_MPN_VALUES。\n");
            }
        }
        return prompt.toString();
    }

    public static List<String> missingCoreFields(ComponentRecord record) {
        List<String> missing = new ArrayList<>();
        if (record == null || empty(record.manufacturerPartNumber)) missing.add("型号");
        if (record == null || empty(record.supplierPartNumber)) missing.add("立创料号");
        if (empty(field(record, "品类"))) missing.add("品类");
        if (empty(field(record, "规格"))) missing.add("规格");
        if (empty(field(record, "封装"))) missing.add("封装");
        if (empty(field(record, "厂商"))) missing.add("品牌");
        return missing;
    }

    public static boolean hasMissingCoreFields(ComponentRecord record) {
        return !missingCoreFields(record).isEmpty();
    }

    private static String sanitizeEvidence(String value) {
        String clean = value == null ? "" : value.trim();
        clean = clean.replace("<img", "[已移除标记]")
                .replace("</img", "[已移除标记]")
                .replace("<|vision_start|>", "[已移除标记]")
                .replace("<|vision_end|>", "[已移除标记]")
                .replace("<|image_pad|>", "[已移除标记]")
                .replace("<|video_pad|>", "[已移除标记]")
                .replace("image_url", "[已移除标记]");
        return clean.length() <= MAX_EVIDENCE_CHARS
                ? clean : clean.substring(0, MAX_EVIDENCE_CHARS);
    }

    private static String field(ComponentRecord record, String key) {
        if (record == null) return "";
        String value = record.fields.get(key);
        return value == null ? "" : value;
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, String value) {
        if (!empty(value)) target.put(key, value.trim());
    }

    private static List<String> modelCandidateHints(ComponentRecord current, String evidence,
                                                    Map<String, Object> knownNonMpnValues) {
        Set<String> forbidden = new HashSet<>();
        for (Object value : knownNonMpnValues.values()) forbidden.add(normalize(String.valueOf(value)));
        if (current != null) {
            forbidden.add(normalize(current.manufacturerPartNumber));
            forbidden.add(normalize(current.supplierPartNumber));
        }
        List<String> hints = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawLine : (evidence == null ? "" : evidence).split("\\R")) {
            String line = rawLine.trim();
            String normalized = normalize(line);
            String upper = line.toUpperCase(Locale.ROOT);
            if (line.length() < 4 || line.length() > 80 || normalized.isEmpty()
                    || forbidden.contains(normalized) || upper.contains("QTY")
                    || upper.contains("ROHS") || normalized.matches("C\\d+")
                    || !containsLetterAndDigit(line) || !seen.add(normalized)) {
                continue;
            }
            hints.add(line);
            if (hints.size() >= 8) break;
        }
        return hints;
    }

    private static boolean containsLetterAndDigit(String value) {
        boolean letter = false;
        boolean digit = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            letter = letter || Character.isLetter(character);
            digit = digit || Character.isDigit(character);
        }
        return letter && digit;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}%+\\-.]", "")
                .toUpperCase(Locale.ROOT);
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}

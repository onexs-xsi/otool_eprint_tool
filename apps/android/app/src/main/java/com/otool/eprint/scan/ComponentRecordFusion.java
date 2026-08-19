package com.otool.eprint.scan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** PP-OCR 与 GLM-OCR 的最终融合；冲突取舍由五档融合权重决定（见 {@link #merge}）。 */
public final class ComponentRecordFusion {
    private ComponentRecordFusion() {}

    /**
     * 融合两路识别结果。
     *
     * @param fusionWeightLevel 1=PP-OCR 为主（GLM 只补漏）；2=PP 权重大（GLM 置信度须超其 2 倍才翻盘）；
     *                          3=均衡（置信度高者胜）；4=GLM 权重大；5=GLM 为主（PP 只补漏）。
     *                          任一侧字段为空时总是由另一侧补漏，与档位无关。
     */
    public static ComponentRecord merge(ComponentRecord cascade, ComponentRecord glm,
                                        int fusionWeightLevel) {
        if (cascade == null) return glm;
        if (glm == null) return cascade;
        final boolean glmWinsConflict;
        switch (fusionWeightLevel) {
            case 1: glmWinsConflict = false; break;
            case 2: glmWinsConflict = glm.confidence > cascade.confidence * 2; break;
            case 4: glmWinsConflict = glm.confidence * 2 > cascade.confidence; break;
            case 5: glmWinsConflict = true; break;
            default: glmWinsConflict = glm.confidence >= cascade.confidence; break;
        }
        int conflicts = 0;
        String model = choose(cascade.manufacturerPartNumber, glm.manufacturerPartNumber,
                glmWinsConflict);
        if (conflicts(cascade.manufacturerPartNumber, glm.manufacturerPartNumber)) conflicts++;
        String supplierPart = choose(cascade.supplierPartNumber, glm.supplierPartNumber,
                glmWinsConflict);
        if (conflicts(cascade.supplierPartNumber, glm.supplierPartNumber)) conflicts++;

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(cascade.fields.keySet());
        keys.addAll(glm.fields.keySet());
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : keys) {
            String ppValue = cascade.fields.get(key);
            String glmValue = glm.fields.get(key);
            if (conflicts(ppValue, glmValue)) conflicts++;
            String selected = choose(ppValue, glmValue, glmWinsConflict);
            if (selected != null && !selected.trim().isEmpty()) fields.put(key, selected);
        }

        int[] glmWeights = {20, 35, 50, 65, 80};
        int glmWeight = glmWeights[Math.max(1, Math.min(5, fusionWeightLevel)) - 1];
        int confidence = (glm.confidence * glmWeight
                + cascade.confidence * (100 - glmWeight)) / 100;
        StringBuilder warning = new StringBuilder();
        if (!glm.warning.isEmpty()) warning.append(glm.warning);
        if (conflicts > 0) {
            if (warning.length() > 0) warning.append("；");
            warning.append("检测到 ").append(conflicts).append(" 项结果冲突，")
                    .append(conflictPolicyText(fusionWeightLevel));
        }
        SupplierFormat supplier = !supplierPart.isEmpty() ? SupplierFormat.LCSC
                : glm.supplier != SupplierFormat.AUTO ? glm.supplier : cascade.supplier;
        return new ComponentRecord(supplier, !model.isEmpty() || !supplierPart.isEmpty(),
                Math.max(0, Math.min(100, confidence)), "", supplierPart, model,
                0, "", "PP-OCR:\n" + cascade.raw + "\nGLM-OCR:\n" + glm.raw,
                fields, warning.toString());
    }

    private static String conflictPolicyText(int fusionWeightLevel) {
        switch (fusionWeightLevel) {
            case 1: return "已按 PP-OCR 为主融合（GLM-OCR 只补漏）";
            case 2: return "已按 PP-OCR 权重优先融合";
            case 4: return "已按 GLM-OCR 权重优先融合";
            case 5: return "已按 GLM-OCR 为主融合（PP-OCR 只补漏）";
            default: return "已按高置信结果融合";
        }
    }

    private static String choose(String cascade, String glm, boolean preferGlm) {
        String ppValue = clean(cascade);
        String glmValue = clean(glm);
        if (glmValue.isEmpty()) return ppValue;
        if (ppValue.isEmpty()) return glmValue;
        return preferGlm ? glmValue : ppValue;
    }

    private static boolean conflicts(String first, String second) {
        String a = normalize(first);
        String b = normalize(second);
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return clean(value).replaceAll("[\\s·:：,，()（）]", "").toUpperCase();
    }
}

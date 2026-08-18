package com.otool.eprint.scan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** PP-OCR 与 GLM-OCR 的最终融合；字段冲突时默认由结构化 GLM 结果覆盖。 */
public final class ComponentRecordFusion {
    private ComponentRecordFusion() {}

    public static ComponentRecord merge(ComponentRecord cascade, ComponentRecord glm,
                                        boolean preferGlmOnConflict) {
        if (cascade == null) return glm;
        if (glm == null) return cascade;
        boolean glmWinsConflict = preferGlmOnConflict || glm.confidence >= cascade.confidence;
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

        int glmWeight = preferGlmOnConflict ? 70 : 50;
        int confidence = (glm.confidence * glmWeight
                + cascade.confidence * (100 - glmWeight)) / 100;
        StringBuilder warning = new StringBuilder();
        if (!glm.warning.isEmpty()) warning.append(glm.warning);
        if (conflicts > 0) {
            if (warning.length() > 0) warning.append("；");
            warning.append("检测到 ").append(conflicts).append(" 项结果冲突，")
                    .append(preferGlmOnConflict ? "已按 GLM-OCR 优先融合" : "已按高置信结果融合");
        }
        SupplierFormat supplier = !supplierPart.isEmpty() ? SupplierFormat.LCSC
                : glm.supplier != SupplierFormat.AUTO ? glm.supplier : cascade.supplier;
        return new ComponentRecord(supplier, !model.isEmpty() || !supplierPart.isEmpty(),
                Math.max(0, Math.min(100, confidence)), "", supplierPart, model,
                0, "", "PP-OCR:\n" + cascade.raw + "\nGLM-OCR:\n" + glm.raw,
                fields, warning.toString());
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

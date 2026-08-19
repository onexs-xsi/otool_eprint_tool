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
        return merge(cascade, glm, fusionWeightLevel, CategoryPackageMatcher.empty(), false);
    }

    /**
     * 融合两路结果，并可让立创离线字典对“品类/封装/厂商”字段提供强证据。强权重只在
     * 一侧命中字典、另一侧未命中时越过全局 OCR 权重，不影响型号、规格和立创料号。
     */
    public static ComponentRecord merge(ComponentRecord cascade, ComponentRecord glm,
                                        int fusionWeightLevel,
                                        CategoryPackageMatcher catalogMatcher,
                                        boolean dictionaryStrongWeight) {
        CategoryPackageMatcher catalog = catalogMatcher == null
                ? CategoryPackageMatcher.empty() : catalogMatcher;
        if (cascade == null) return dictionaryStrongWeight ? normalizeCatalogFields(glm, catalog) : glm;
        if (glm == null) return dictionaryStrongWeight ? normalizeCatalogFields(cascade, catalog) : cascade;
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
        FieldChoice categoryChoice = dictionaryStrongWeight
                ? chooseCatalogField("品类", cascade.fields.get("品类"), glm.fields.get("品类"),
                "", glmWinsConflict, catalog)
                : new FieldChoice(choose(cascade.fields.get("品类"), glm.fields.get("品类"),
                glmWinsConflict), false);
        String selectedCategory = categoryChoice.value;
        int dictionaryProtected = categoryChoice.protectedConflict ? 1 : 0;
        for (String key : keys) {
            String ppValue = cascade.fields.get(key);
            String glmValue = glm.fields.get(key);
            if (conflicts(ppValue, glmValue)) conflicts++;
            FieldChoice choice;
            if ("品类".equals(key)) {
                choice = categoryChoice;
            } else if (dictionaryStrongWeight && isCatalogField(key)) {
                choice = chooseCatalogField(key, ppValue, glmValue, selectedCategory,
                        glmWinsConflict, catalog);
            } else {
                choice = new FieldChoice(choose(ppValue, glmValue, glmWinsConflict), false);
            }
            if (choice != categoryChoice && choice.protectedConflict) dictionaryProtected++;
            String selected = choice.value;
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
        if (dictionaryProtected > 0) {
            if (warning.length() > 0) warning.append("；");
            warning.append("立创字典强权重保护 ").append(dictionaryProtected)
                    .append(" 项品类/品牌/封装字段");
        }
        SupplierFormat supplier = !supplierPart.isEmpty() ? SupplierFormat.LCSC
                : glm.supplier != SupplierFormat.AUTO ? glm.supplier : cascade.supplier;
        return new ComponentRecord(supplier, !model.isEmpty() || !supplierPart.isEmpty(),
                Math.max(0, Math.min(100, confidence)), "", supplierPart, model,
                0, "", "PP-OCR:\n" + cascade.raw + "\nGLM-OCR:\n" + glm.raw,
                fields, warning.toString());
    }

    private static boolean isCatalogField(String key) {
        return "品类".equals(key) || "封装".equals(key)
                || "厂商".equals(key) || "品牌".equals(key);
    }

    private static FieldChoice chooseCatalogField(String key, String cascade, String glm,
                                                   String category, boolean preferGlm,
                                                   CategoryPackageMatcher catalog) {
        String ppValue = clean(cascade);
        String glmValue = clean(glm);
        String ppCanonical = canonicalCatalogValue(key, category, ppValue, catalog);
        String glmCanonical = canonicalCatalogValue(key, category, glmValue, catalog);
        boolean ppKnown = !ppCanonical.isEmpty();
        boolean glmKnown = !glmCanonical.isEmpty();
        boolean hasConflict = conflicts(ppValue, glmValue);

        if (ppKnown && !glmKnown) {
            return new FieldChoice(ppCanonical, hasConflict && !glmValue.isEmpty());
        }
        if (glmKnown && !ppKnown) {
            return new FieldChoice(glmCanonical, hasConflict && !ppValue.isEmpty());
        }
        if (ppKnown && glmKnown) {
            if (normalize(ppCanonical).equals(normalize(glmCanonical))) {
                return new FieldChoice(preferGlm ? glmCanonical : ppCanonical, false);
            }
            return new FieldChoice(preferGlm ? glmCanonical : ppCanonical, false);
        }
        return new FieldChoice(choose(ppValue, glmValue, preferGlm), false);
    }

    private static String canonicalCatalogValue(String key, String category, String value,
                                                CategoryPackageMatcher catalog) {
        if (value.isEmpty()) return "";
        if ("品类".equals(key)) return catalog.canonicalCategory(value);
        if ("封装".equals(key)) return catalog.canonicalPackage(category, value);
        if ("厂商".equals(key) || "品牌".equals(key)) {
            return catalog.canonicalBrand(category, value);
        }
        return "";
    }

    private static ComponentRecord normalizeCatalogFields(ComponentRecord record,
                                                          CategoryPackageMatcher catalog) {
        if (record == null) return null;
        Map<String, String> fields = new LinkedHashMap<>(record.fields);
        String category = canonicalCatalogValue("品类", "", clean(fields.get("品类")), catalog);
        if (!category.isEmpty()) fields.put("品类", category);
        for (String key : new String[]{"封装", "厂商", "品牌"}) {
            String canonical = canonicalCatalogValue(key, category, clean(fields.get(key)), catalog);
            if (!canonical.isEmpty()) fields.put(key, canonical);
        }
        return new ComponentRecord(record.supplier, record.recognized, record.confidence,
                record.orderNumber, record.supplierPartNumber, record.manufacturerPartNumber,
                record.quantity, record.trackingId, record.raw, fields, record.warning);
    }

    private static final class FieldChoice {
        final String value;
        final boolean protectedConflict;

        FieldChoice(String value, boolean protectedConflict) {
            this.value = value;
            this.protectedConflict = protectedConflict;
        }
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

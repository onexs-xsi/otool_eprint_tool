package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ComponentRecordFusionTest {
    @Test
    public void glmWinsConflictsAndPpFillsMissingFields() {
        ComponentRecord pp = record(90, "PP-MODEL", "C111111", "0402", "12pF");
        ComponentRecord glm = record(60, "GLM-MODEL", "C222222", "", "12pF ±5% 50V");

        ComponentRecord fused = ComponentRecordFusion.merge(pp, glm, true);

        assertEquals("GLM-MODEL", fused.manufacturerPartNumber);
        assertEquals("C222222", fused.supplierPartNumber);
        assertEquals("0402", fused.fields.get("封装"));
        assertEquals("12pF ±5% 50V", fused.fields.get("规格"));
        assertTrue(fused.warning.contains("GLM-OCR 优先"));
    }

    private static ComponentRecord record(int confidence, String model, String lcsc,
                                          String packageCode, String specification) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (!packageCode.isEmpty()) fields.put("封装", packageCode);
        if (!specification.isEmpty()) fields.put("规格", specification);
        return new ComponentRecord(SupplierFormat.LCSC, true, confidence, "", lcsc,
                model, 0, "", "raw", fields, "");
    }
}

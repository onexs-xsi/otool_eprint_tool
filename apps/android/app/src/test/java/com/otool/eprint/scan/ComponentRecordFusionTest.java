package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ComponentRecordFusionTest {
    @Test
    public void level5_glmWinsConflictsAndPpFillsMissingFields() {
        ComponentRecord pp = record(90, "PP-MODEL", "C111111", "0402", "12pF");
        ComponentRecord glm = record(60, "GLM-MODEL", "C222222", "", "12pF ±5% 50V");

        ComponentRecord fused = ComponentRecordFusion.merge(pp, glm, 5);

        assertEquals("GLM-MODEL", fused.manufacturerPartNumber);
        assertEquals("C222222", fused.supplierPartNumber);
        assertEquals("0402", fused.fields.get("封装"));
        assertEquals("12pF ±5% 50V", fused.fields.get("规格"));
        assertTrue(fused.warning.contains("GLM-OCR 为主"));
    }

    @Test
    public void level1_ppWinsConflictsAndGlmFillsMissingFields() {
        ComponentRecord pp = record(60, "PP-MODEL", "", "0402", "12pF");
        ComponentRecord glm = record(90, "GLM-MODEL", "C222222", "SMD", "12pF ±5% 50V");

        ComponentRecord fused = ComponentRecordFusion.merge(pp, glm, 1);

        assertEquals("PP-MODEL", fused.manufacturerPartNumber);
        assertEquals("C222222", fused.supplierPartNumber);
        assertEquals("0402", fused.fields.get("封装"));
        assertEquals("12pF", fused.fields.get("规格"));
        assertTrue(fused.warning.contains("PP-OCR 为主"));
    }

    @Test
    public void level3_higherConfidenceWinsConflict() {
        ComponentRecord ppStrong = record(90, "PP-MODEL", "", "", "");
        ComponentRecord glmWeak = record(60, "GLM-MODEL", "", "", "");
        assertEquals("PP-MODEL",
                ComponentRecordFusion.merge(ppStrong, glmWeak, 3).manufacturerPartNumber);
        ComponentRecord ppWeak = record(60, "PP-MODEL", "", "", "");
        ComponentRecord glmStrong = record(90, "GLM-MODEL", "", "", "");
        assertEquals("GLM-MODEL",
                ComponentRecordFusion.merge(ppWeak, glmStrong, 3).manufacturerPartNumber);
    }

    @Test
    public void level2_glmNeedsOverwhelmingConfidenceToWin() {
        ComponentRecord pp = record(90, "PP-MODEL", "", "", "");
        ComponentRecord glmClose = record(100, "GLM-MODEL", "", "", "");
        ComponentRecord glmStrong = record(100, "GLM-MODEL", "", "", "");
        // PP 权重大：GLM 置信度 100 不超过 90*2=180，PP 保持。
        assertEquals("PP-MODEL",
                ComponentRecordFusion.merge(pp, glmClose, 2).manufacturerPartNumber);
        // PP 低置信时被 GLM 翻盘（100 > 40*2）。
        ComponentRecord ppWeak = record(40, "PP-MODEL", "", "", "");
        assertEquals("GLM-MODEL",
                ComponentRecordFusion.merge(ppWeak, glmStrong, 2).manufacturerPartNumber);
    }

    @Test
    public void level4_glmWinsUnlessPpOverwhelming() {
        ComponentRecord pp = record(100, "PP-MODEL", "", "", "");
        ComponentRecord glm = record(60, "GLM-MODEL", "", "", "");
        // GLM 权重大：60*2=120 > 100，GLM 胜出。
        assertEquals("GLM-MODEL", ComponentRecordFusion.merge(pp, glm, 4).manufacturerPartNumber);
        // PP 压倒性置信（130 > 60*2）时 PP 保持。
        ComponentRecord ppStrong = record(130, "PP-MODEL", "", "", "");
        assertEquals("PP-MODEL", ComponentRecordFusion.merge(ppStrong, glm, 4).manufacturerPartNumber);
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

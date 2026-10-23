package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.qwen.QwenSettings;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QwenComponentEnricherTest {
    private static final String OUTPUT = "{"
            + "\"manufacturer_part_number\":\"CRCW06030000Z0EAHP\","
            + "\"lcsc_part_number\":\"C844719\","
            + "\"category\":\"厚膜电阻\","
            + "\"specification\":\"0Ω 333mW\","
            + "\"package\":\"0603\","
            + "\"brand\":\"VISHAY\","
            + "\"parameters\":[]}";

    @Test
    public void fillsOnlyEvidenceBackedValuesAndCanonicalizesCatalogFields() throws Exception {
        ComponentRecord current = record("CRCW06030000Z0EAHP", "", Collections.emptyMap());
        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(current, OUTPUT,
                "C844719\nCRCW06030000Z0EAHP\n厚膜电阻\n0Ω 333mW\n0603\nVISHAY",
                catalog(), QwenSettings.defaults());
        assertTrue(result.changed());
        assertEquals("CRCW06030000Z0EAHP", result.record.manufacturerPartNumber);
        assertEquals("C844719", result.record.supplierPartNumber);
        assertEquals("厚膜电阻", result.record.fields.get("品类"));
        assertEquals("0603", result.record.fields.get("封装"));
        assertEquals("VISHAY(威世)", result.record.fields.get("厂商"));
        assertEquals(5, result.filled);
        assertEquals(0, result.corrected);
        assertEquals(0, result.rejected);
    }

    @Test
    public void rejectsHallucinationAndInternalCodeEvenWhenPrinted() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("内部编码", "3427-B-D12-003");
        ComponentRecord current = record("", "", fields);
        String output = OUTPUT.replace("CRCW06030000Z0EAHP", "3427-B-D12-003")
                .replace("C844719", "C999999");
        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(current, output,
                "3427-B-D12-003\n厚膜电阻\n0Ω 333mW\n0603\nVISHAY",
                catalog(), QwenSettings.defaults());
        assertEquals("", result.record.manufacturerPartNumber);
        assertEquals("", result.record.supplierPartNumber);
        assertTrue(result.rejected >= 2);
    }

    @Test
    public void correctionRequiresExplicitPolicyAndContradictingEvidence() throws Exception {
        ComponentRecord current = record("WRONG-MODEL", "", Collections.emptyMap());
        String candidate = OUTPUT.replace("CRCW06030000Z0EAHP", "RIGHT-MODEL2");
        String evidence = "RIGHT-MODEL2\nC844719\n厚膜电阻\n0Ω 333mW\n0603\nVISHAY";

        QwenComponentEnricher.Result protectedResult = QwenComponentEnricher.apply(
                current, candidate, evidence, catalog(), QwenSettings.defaults());
        assertEquals("WRONG-MODEL", protectedResult.record.manufacturerPartNumber);

        QwenSettings correction = new QwenSettings(true, false, true, true,
                4, 192, 60, "");
        QwenComponentEnricher.Result corrected = QwenComponentEnricher.apply(
                current, candidate, evidence, catalog(), correction);
        assertEquals("RIGHT-MODEL2", corrected.record.manufacturerPartNumber);
        assertEquals(1, corrected.corrected);
    }

    @Test
    public void completeEchoProducesNoRecordMutation() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("品类", "厚膜电阻");
        fields.put("规格", "0Ω 333mW");
        fields.put("封装", "0603");
        fields.put("厂商", "VISHAY(威世)");
        ComponentRecord current = record("CRCW06030000Z0EAHP", "C844719", fields);
        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(current, OUTPUT,
                "C844719\nCRCW06030000Z0EAHP\n厚膜电阻\n0Ω 333mW\n0603\nVISHAY",
                catalog(), QwenSettings.defaults());
        assertFalse(result.changed());
        assertTrue(current == result.record);
    }

    @Test
    public void packageEmbeddedOnlyInsideModelIsNotIndependentEvidence() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("品类", "厚膜电阻");
        fields.put("规格", "0Ω 333mW");
        fields.put("厂商", "VISHAY(威世)");
        ComponentRecord current = record("CRCW06030000Z0EAHP", "C844719", fields);
        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(current, OUTPUT,
                "C844719\nCRCW06030000Z0EAHP\n厚膜电阻\n0Ω 333mW\nVISHAY",
                catalog(), QwenSettings.defaults());
        assertFalse(result.changed());
        assertEquals(null, result.record.fields.get("封装"));
        assertEquals(1, result.rejected);
    }

    @Test
    public void correctionCanReplaceInternalCodeMistakenForModel() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("内部编码", "3427-B-D12-003");
        ComponentRecord current = record("3427-B-D12-003", "C844719", fields);
        String evidence = "3427-B-D12-003\nC844719\nCRCW06030000Z0EAHP\n"
                + "厚膜电阻\n0Ω 333mW\n0603\nVISHAY";

        QwenComponentEnricher.Result protectedResult = QwenComponentEnricher.apply(
                current, OUTPUT, evidence, catalog(), QwenSettings.defaults());
        assertEquals("3427-B-D12-003", protectedResult.record.manufacturerPartNumber);

        QwenSettings correction = new QwenSettings(true, true, true, true,
                4, 192, 60, "");
        QwenComponentEnricher.Result corrected = QwenComponentEnricher.apply(
                current, OUTPUT, evidence, catalog(), correction);
        assertEquals("CRCW06030000Z0EAHP", corrected.record.manufacturerPartNumber);
        assertEquals(1, corrected.corrected);
        assertTrue(corrected.summary().contains("修正明显错误 1 项"));
        assertFalse(correction.onlyWhenCoreFieldsMissing);
    }

    @Test
    public void correctionCanReplaceCatalogInvalidPackageEvenWhenPrinted() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("品类", "厚膜电阻");
        fields.put("规格", "0Ω 333mW");
        fields.put("封装", "9999");
        fields.put("厂商", "VISHAY(威世)");
        ComponentRecord current = record("CRCW06030000Z0EAHP", "C844719", fields);
        String evidence = "C844719\nCRCW06030000Z0EAHP\n厚膜电阻\n0Ω 333mW\n9999\n0603\nVISHAY";
        QwenSettings correction = new QwenSettings(true, false, true, true,
                4, 192, 60, "");

        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(
                current, OUTPUT, evidence, catalog(), correction);
        assertEquals("0603", result.record.fields.get("封装"));
        assertEquals(1, result.corrected);
    }

    @Test
    public void correctionRejectsBrandProposedAsModel() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("内部编码", "3427-B-D12-003");
        fields.put("品类", "厚膜电阻");
        fields.put("规格", "0Ω 333mW");
        fields.put("封装", "0603");
        fields.put("厂商", "VISHAY(威世)");
        ComponentRecord current = record("3427-B-D12-003", "C844719", fields);
        String invalid = OUTPUT.replace("CRCW06030000Z0EAHP", "VISHAY");
        QwenSettings correction = new QwenSettings(true, false, true, true,
                4, 192, 60, "");

        QwenComponentEnricher.Result result = QwenComponentEnricher.apply(current, invalid,
                "3427-B-D12-003\nC844719\nCRCW06030000Z0EAHP\n厚膜电阻\n"
                        + "0Ω 333mW\n0603\nVISHAY",
                catalog(), correction);
        assertEquals("3427-B-D12-003", result.record.manufacturerPartNumber);
        assertEquals(0, result.corrected);
        assertTrue(result.rejected >= 1);
    }

    private static ComponentRecord record(String model, String supplierPart,
                                          Map<String, String> fields) {
        return new ComponentRecord(supplierPart.isEmpty() ? SupplierFormat.AUTO : SupplierFormat.LCSC,
                !model.isEmpty() || !supplierPart.isEmpty(), 90, "", supplierPart, model,
                0, "", "OCR evidence", fields, "");
    }

    private static CategoryPackageMatcher catalog() {
        return new CategoryPackageMatcher(Collections.singletonList(
                new CategoryPackageMatcher.CategoryRule(
                        "厚膜电阻", "Thick Film Resistors", "电阻器", "被动器件",
                        Arrays.asList("0603", "0805"),
                        Collections.singletonList("VISHAY(威世)"))));
    }
}

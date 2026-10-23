package com.otool.eprint.scan.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.GlmInformationExtractionParser;
import com.otool.eprint.scan.LabelTextParser;
import com.otool.eprint.scan.ocr.OcrLine;

import org.junit.Test;

import java.util.Arrays;

public final class QwenSettingsAndPromptTest {
    @Test
    public void settingsClampNativeRangesAndDefaultActuallyRuns() {
        QwenSettings defaults = QwenSettings.defaults();
        assertTrue(defaults.enabled);
        assertFalse(defaults.onlyWhenCoreFieldsMissing);
        assertFalse(defaults.allowEvidenceBackedCorrections);
        assertTrue(defaults.requireCatalogMatch);

        QwenSettings clamped = new QwenSettings(true, true, true, false,
                99, 9_999, 999, repeat('x', 1_100));
        assertFalse(clamped.onlyWhenCoreFieldsMissing);
        assertTrue(clamped.allowEvidenceBackedCorrections);
        assertEquals(QwenSettings.MAX_THREADS, clamped.threadCount);
        assertEquals(QwenSettings.MAX_NEW_TOKENS, clamped.maxNewTokens);
        assertEquals(QwenSettings.MAX_TIMEOUT_SECONDS, clamped.timeoutSeconds);
        assertEquals(QwenSettings.MAX_EXTRA_INSTRUCTIONS_CHARS,
                clamped.extraInstructions.length());
    }

    @Test
    public void promptCarriesCurrentRecordAndTextEvidenceWithoutVisualTokens() {
        ComponentRecord record = LabelTextParser.parse(Arrays.asList(
                line("C844719"),
                line("CRCW06030000Z0EAHP")));
        String prompt = QwenComponentPrompt.build(record,
                "C844719\nCRCW06030000Z0EAHP\nVISHAY", QwenSettings.defaults());
        assertTrue(prompt.contains("CURRENT_RECORD"));
        assertTrue(prompt.contains("OCR_EVIDENCE"));
        assertTrue(prompt.contains("\"lcsc_part_number\":\"C844719\""));
        assertTrue(prompt.contains("CRCW06030000Z0EAHP"));
        assertFalse(prompt.contains("<|image_pad|>"));
        assertFalse(prompt.contains("<img"));
        assertEquals(null, QwenTextOnlyEngine.forbiddenPromptReason(prompt));
    }

    @Test
    public void missingCoreFieldsExcludeOptionalParameters() throws Exception {
        ComponentRecord complete = GlmInformationExtractionParser.parse("{"
                + "\"manufacturer_part_number\":\"CRCW06030000Z0EAHP\","
                + "\"lcsc_part_number\":\"C844719\","
                + "\"category\":\"厚膜电阻\","
                + "\"specification\":\"0Ω 333mW\","
                + "\"package\":\"0603\","
                + "\"brand\":\"VISHAY\","
                + "\"parameters\":[]}");
        assertFalse(QwenComponentPrompt.hasMissingCoreFields(complete));
    }

    @Test
    public void correctionPromptOnlyRelaxesEchoRuleWhenExplicitlyEnabled() {
        ComponentRecord record = LabelTextParser.parse(Arrays.asList(line("WRONG-MODEL")));
        String protectedPrompt = QwenComponentPrompt.build(record, "RIGHT-MODEL",
                QwenSettings.defaults());
        assertTrue(protectedPrompt.contains("已有非空字段必须原样返回"));

        QwenSettings correction = new QwenSettings(true, false, true, true,
                4, 192, 60, "");
        String correctionPrompt = QwenComponentPrompt.build(record, "RIGHT-MODEL", correction);
        assertTrue(correctionPrompt.contains("才允许提出纠正"));
        assertFalse(correctionPrompt.contains("已有非空字段必须原样返回"));
    }

    @Test
    public void correctionPromptProvidesOnlyPlausibleAlternativeModelHints() throws Exception {
        ComponentRecord mistaken = GlmInformationExtractionParser.parse("{"
                + "\"manufacturer_part_number\":\"3427-B-D12-003\","
                + "\"lcsc_part_number\":\"C844719\","
                + "\"category\":\"厚膜电阻\",\"specification\":\"0Ω 333mW\","
                + "\"package\":\"0603\",\"brand\":\"VISHAY(威世)\",\"parameters\":[]}");
        QwenSettings correction = new QwenSettings(true, true, true, true,
                4, 192, 60, "");
        String prompt = QwenComponentPrompt.build(mistaken,
                "3427-B-D12-003\nC844719\nCRCW06030000Z0EAHP\n厚膜电阻\n"
                        + "0Ω 333mW\n0603\nVISHAY(威世)", correction);
        assertTrue(prompt.contains("MODEL_CANDIDATE_HINTS"));
        assertTrue(prompt.contains("[\"CRCW06030000Z0EAHP\"]"));
        assertTrue(prompt.contains("KNOWN_NON_MPN_VALUES"));
    }

    private static OcrLine line(String value) {
        return new OcrLine(value, 0.99f, 0, 0, 0, 0);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}

package com.otool.eprint.scan.vlm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VlmSettingsTest {
    @Test
    public void defaults_areStable() {
        VlmSettings settings = VlmSettings.defaults();
        assertEquals("cpu", settings.backend);
        assertEquals(4, settings.threadCount);
        assertEquals(1024, settings.maxNewTokens);
        assertEquals(60_000L, settings.timeoutMs());
        assertEquals(1.1f, settings.repetitionPenalty, 0.001f);
        assertEquals(0.0f, settings.presencePenalty, 0.001f);
        assertEquals(0.0f, settings.frequencyPenalty, 0.001f);
        assertTrue(settings.prompt.contains("manufacturer_part_number"));
        assertTrue(settings.prompt.contains("parameters"));
    }

    @Test
    public void constructor_clampsAndRepairsInvalidValues() {
        VlmSettings settings = new VlmSettings(
                "bad", 99, "bad", "bad", 1, 999, 0.1f, 9f, Float.NaN, "   ");
        assertEquals("cpu", settings.backend);
        assertEquals(8, settings.threadCount);
        assertEquals("low", settings.precision);
        assertEquals("low", settings.memory);
        assertEquals(64, settings.maxNewTokens);
        assertEquals(300, settings.timeoutSeconds);
        assertEquals(1.0f, settings.repetitionPenalty, 0.001f);
        assertEquals(2.0f, settings.presencePenalty, 0.001f);
        assertEquals(0.0f, settings.frequencyPenalty, 0.001f);
        assertEquals(VlmSettings.DEFAULT_PROMPT, settings.prompt);
    }

    @Test
    public void configJson_containsPersistedRuntimeChoices() {
        VlmSettings settings = new VlmSettings(
                "vulkan", 6, "normal", "high", 512, 90,
                1.7f, 0.9f, 0.5f, "OCR:");
        String json = settings.configJson();
        assertTrue(json.contains("\"backend_type\":\"vulkan\""));
        assertTrue(json.contains("\"thread_num\":6"));
        assertTrue(json.contains("\"precision\":\"normal\""));
        assertTrue(json.contains("\"memory\":\"high\""));
        assertTrue(json.contains("\"max_new_tokens\":512"));
        assertTrue(json.contains("\"sampler_type\":\"penalty\""));
        assertTrue(json.contains("\"penalty\":1.7"));
        assertTrue(json.contains("\"presence_penalty\":0.9"));
        assertTrue(json.contains("\"frequency_penalty\":0.5"));
        assertTrue(json.contains("\"penalty_sampler\":\"greedy\""));
        assertTrue(json.contains("\"async\":false"));
    }

    @Test
    public void configJson_mllmFollowsBackendWithNormalPrecision() {
        String json = new VlmSettings("vulkan", 6, "low", "low", 512, 90,
                1.1f, 0.0f, 0.0f, "OCR:").configJson();
        assertTrue(json.contains("\"mllm\":{"));
        assertTrue(json.contains("\"mllm\":{\"backend_type\":\"vulkan\",\"thread_num\":6,"
                + "\"precision\":\"normal\",\"memory\":\"low\"}"));
    }

    @Test
    public void withBackend_onlyReplacesBackend() {
        VlmSettings gpu = new VlmSettings("vulkan", 6, "normal", "high", 512, 90,
                1.7f, 0.9f, 0.5f, "OCR:");
        VlmSettings cpu = gpu.withBackend("cpu");
        assertEquals("cpu", cpu.backend);
        assertEquals(6, cpu.threadCount);
        assertEquals("normal", cpu.precision);
        assertEquals("high", cpu.memory);
        assertEquals(512, cpu.maxNewTokens);
        assertEquals(90, cpu.timeoutSeconds);
        assertEquals(1.7f, cpu.repetitionPenalty, 0.001f);
        assertEquals(0.9f, cpu.presencePenalty, 0.001f);
        assertEquals(0.5f, cpu.frequencyPenalty, 0.001f);
        assertEquals(gpu.prompt, cpu.prompt);
    }

    @Test
    public void legacyAndCustomPromptsCannotRemoveCompleteExtractionSchema() {
        assertEquals(VlmSettings.DEFAULT_PROMPT,
                VlmSettings.completeExtractionPrompt("Text Recognition:"));
        String customized = VlmSettings.completeExtractionPrompt("优先读取左侧文字");
        assertTrue(customized.startsWith("优先读取左侧文字"));
        assertTrue(customized.contains("manufacturer_part_number"));
        assertTrue(customized.contains("parameters"));
    }
}

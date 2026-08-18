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
        assertEquals(1.6f, settings.repetitionPenalty, 0.001f);
        assertEquals(0.8f, settings.presencePenalty, 0.001f);
        assertEquals(0.4f, settings.frequencyPenalty, 0.001f);
        assertEquals("Text Recognition:", settings.prompt);
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
}

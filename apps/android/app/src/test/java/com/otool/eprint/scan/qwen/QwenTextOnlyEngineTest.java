package com.otool.eprint.scan.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public final class QwenTextOnlyEngineTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void missingModelFile_requiresManifestFirst() {
        assertEquals(QwenTextOnlyEngine.MANIFEST_FILE,
                QwenTextOnlyEngine.missingModelFile(tempFolder.getRoot()));
        assertEquals("(目录不存在)", QwenTextOnlyEngine.missingModelFile(
                new File(tempFolder.getRoot(), "missing")));
    }

    @Test
    public void promptGuard_allowsTextAndRejectsEveryVisualForm() {
        assertNull(QwenTextOnlyEngine.forbiddenPromptReason("只输出 {\"ok\":true}"));
        assertTrue(QwenTextOnlyEngine.forbiddenPromptReason("<img>/sdcard/a.png</img>")
                .contains("禁止标记"));
        assertTrue(QwenTextOnlyEngine.forbiddenPromptReason("<|vision_start|><|image_pad|>")
                .contains("禁止标记"));
        assertTrue(QwenTextOnlyEngine.forbiddenPromptReason("{\"image_url\":\"x\"}")
                .contains("禁止标记"));
        assertEquals("prompt 为空", QwenTextOnlyEngine.forbiddenPromptReason("  "));
    }

    @Test
    public void runtimeConfig_isCpuNonThinkingAndContainsNoVisualConfig() {
        String config = QwenTextOnlyEngine.runtimeConfigJson();
        assertTrue(config.contains("\"backend_type\":\"cpu\""));
        assertTrue(config.contains("\"thread_num\":4"));
        assertTrue(config.contains("\"enable_thinking\":false"));
        assertTrue(config.contains("\"max_new_tokens\":192"));
        assertFalse(config.contains("mllm"));
        assertFalse(config.contains("visual"));
        assertFalse(config.contains("image"));
    }

    @Test
    public void runtimeConfigUsesConfiguredThreadsAndTokenLimit() {
        QwenSettings settings = new QwenSettings(true, false, false, true,
                2, 320, 60, "");
        String config = QwenTextOnlyEngine.runtimeConfigJson(settings);
        assertTrue(config.contains("\"thread_num\":2"));
        assertTrue(config.contains("\"max_new_tokens\":320"));
    }

    @Test
    public void stripsOnlyTrailingMnnSentinel() {
        assertEquals("{\"ok\":true}",
                QwenTextOnlyEngine.stripTrailingEop("{\"ok\":true}<eop>"));
        assertEquals("prefix<eop>inside",
                QwenTextOnlyEngine.stripTrailingEop("prefix<eop>inside"));
    }
}

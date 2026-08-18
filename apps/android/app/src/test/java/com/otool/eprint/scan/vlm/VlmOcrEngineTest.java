package com.otool.eprint.scan.vlm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/** VlmOcrEngine 纯 JVM 部分：模型目录检测、prompt 组装、默认配置、结果摘要。 */
public class VlmOcrEngineTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void missingModelFile_dirNotExists() {
        File dir = new File(tempFolder.getRoot(), "glm-ocr-mnn");
        assertEquals("(目录不存在)", VlmOcrEngine.missingModelFile(dir));
    }

    @Test
    public void missingModelFile_emptyDir_reportsFirstMissing() {
        assertEquals("config.json", VlmOcrEngine.missingModelFile(tempFolder.getRoot()));
    }

    @Test
    public void missingModelFile_partialDir_reportsMissingWeight() throws IOException {
        new File(tempFolder.getRoot(), "config.json").createNewFile();
        new File(tempFolder.getRoot(), "llm_config.json").createNewFile();
        new File(tempFolder.getRoot(), "llm.mnn").createNewFile();
        assertEquals("llm.mnn.weight", VlmOcrEngine.missingModelFile(tempFolder.getRoot()));
    }

    @Test
    public void missingModelFile_completeDir_returnsNull() throws IOException {
        String[] files = {
                "config.json", "llm_config.json", "llm.mnn", "llm.mnn.weight",
                "visual.mnn", "visual.mnn.weight", "embeddings_bf16.bin", "tokenizer.txt",
        };
        for (String name : files) {
            new File(tempFolder.getRoot(), name).createNewFile();
        }
        assertNull(VlmOcrEngine.missingModelFile(tempFolder.getRoot()));
    }

    @Test
    public void buildInformationExtractionPrompt_embedsImgTagAndCompleteSchema() {
        File image = new File("/sdcard/Android/data/com.otool.eprint/cache/vlm_input.png");
        String prompt = VlmOcrEngine.buildInformationExtractionPrompt(image);
        assertTrue(prompt.startsWith("<img>"));
        assertTrue(prompt.endsWith("</img>" + VlmOcrEngine.INFORMATION_EXTRACTION_PROMPT));
        assertTrue(prompt.contains(image.getAbsolutePath()));
        assertTrue(prompt.contains("manufacturer_part_number"));
        assertTrue(prompt.contains("parameters"));
        assertTrue(VlmOcrEngine.buildInformationExtractionPrompt(image, "Custom OCR:")
                .endsWith("</img>Custom OCR:"));
    }

    @Test
    public void defaultConfigJson_isValidJsonWithExpectedKeys() {
        String json = VlmOcrEngine.defaultConfigJson();
        assertTrue(json.contains("\"backend_type\":\"cpu\""));
        assertTrue(json.contains("\"thread_num\":4"));
        assertTrue(json.contains("\"precision\":\"low\""));
        assertTrue(json.contains("\"max_new_tokens\":1024"));
        assertTrue(json.contains("\"penalty\":1.1"));
        assertTrue(json.contains("\"presence_penalty\":0.0"));
        // 简单括号配平校验，防手抖写坏 JSON。
        assertEquals(json.chars().filter(c -> c == '{').count(),
                json.chars().filter(c -> c == '}').count());
    }

    @Test
    public void degenerateRunDetector_stopsOnlyPathologicalRuns() {
        assertFalse(VlmOcrEngine.hasDegenerateCharacterRun("3333333\nQTY：100个", 8));
        assertTrue(VlmOcrEngine.hasDegenerateCharacterRun("33333333\nQTY：100个", 8));
        assertTrue(VlmOcrEngine.hasDegenerateCharacterRun("芯芯芯芯", 4));
        assertFalse(VlmOcrEngine.hasDegenerateCharacterRun("C45359894\n12pF ±5% 50V", 4));
    }

    @Test
    public void vlmResult_failureAndTimingSummary() {
        VlmResult failure = VlmResult.failure("模型未部署", 12);
        assertFalse(failure.isOk());
        assertEquals("模型未部署", failure.error);

        VlmResult ok = new VlmResult("C45359894", 300, 42, 500, 1200, 3400, 5100, false, null);
        assertTrue(ok.isOk());
        assertEquals("prefill 1200ms(300 tok) · decode 3400ms(42 tok) · 共 5100ms",
                ok.timingSummary());
    }
}

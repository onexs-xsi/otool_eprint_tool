package com.otool.eprint.scan.qwen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.otool.eprint.scan.CategoryPackageMatcher;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.ComponentRecordTestFactory;
import com.otool.eprint.scan.GlmInformationExtractionParser;
import com.otool.eprint.scan.QwenComponentEnricher;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 目标机端到端：应用 UID 下加载、text-only、取消后恢复。 */
@RunWith(AndroidJUnit4.class)
public final class QwenTextOnlyEngineInstrumentedTest {
    @Test
    public void textOnlyJsonAndCancelRecovery() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        QwenTextOnlyEngine engine = new QwenTextOnlyEngine();
        try {
            engine.load(context);
            assertTrue("load failed: " + engine.initError(), engine.isReady());

            QwenTextResult first = engine.generate(
                    "Reply with exactly this JSON and nothing else: {\"app\":true}", null);
            assertTrue("first inference failed: " + first.error, first.isOk());
            assertEquals("{\"app\":true}", first.text.trim());
            assertEquals(0, first.visionMs);

            ComponentRecord current = GlmInformationExtractionParser.parse("{"
                    + "\"manufacturer_part_number\":\"CRCW06030000Z0EAHP\","
                    + "\"lcsc_part_number\":\"C844719\","
                    + "\"category\":\"\",\"specification\":\"\","
                    + "\"package\":\"\",\"brand\":\"\",\"parameters\":[]}");
            String evidence = "3427-B-D12-003\nC844719\n厚膜电阻\n0Ω 333.333mW\n"
                    + "0603\nCRCW06030000Z0EAHP\nVISHAY(威世)";
            QwenSettings settings = QwenSettings.defaults();
            QwenTextResult business = engine.generate(
                    QwenComponentPrompt.build(current, evidence, settings), null);
            assertTrue("business prompt failed: " + business.error + " / " + business.text,
                    business.isOk());
            assertEquals(0, business.visionMs);
            CategoryPackageMatcher catalog = new CategoryPackageMatcher(
                    Collections.singletonList(new CategoryPackageMatcher.CategoryRule(
                            "厚膜电阻", "Thick Film Resistors", "电阻器", "被动器件",
                            Arrays.asList("0603", "0805"),
                            Collections.singletonList("VISHAY(威世)"))));
            QwenComponentEnricher.Result enrichment = QwenComponentEnricher.apply(
                    current, business.text, evidence, catalog, settings);
            assertTrue("no business field accepted: " + business.text, enrichment.changed());
            assertEquals("厚膜电阻", enrichment.record.fields.get("品类"));
            assertEquals("0603", enrichment.record.fields.get("封装"));

            Map<String, String> mistakenFields = new LinkedHashMap<>();
            mistakenFields.put("内部编码", "3427-B-D12-003");
            mistakenFields.put("品类", "厚膜电阻");
            mistakenFields.put("规格", "0Ω 333.333mW");
            mistakenFields.put("封装", "0603");
            mistakenFields.put("厂商", "VISHAY(威世)");
            ComponentRecord mistaken = ComponentRecordTestFactory.create(
                    "C844719", "3427-B-D12-003", evidence, mistakenFields);
            QwenSettings correctionSettings = new QwenSettings(
                    true, true, true, true, 4, 192, 60, "");
            QwenTextResult correction = engine.generate(
                    QwenComponentPrompt.build(mistaken, evidence, correctionSettings), null);
            assertTrue("correction prompt failed: " + correction.error + " / " + correction.text,
                    correction.isOk());
            assertEquals(0, correction.visionMs);
            QwenComponentEnricher.Result corrected = QwenComponentEnricher.apply(
                    mistaken, correction.text, evidence, catalog, correctionSettings);
            assertEquals("Qwen did not replace internal code: " + correction.text,
                    "CRCW06030000Z0EAHP", corrected.record.manufacturerPartNumber);
            assertTrue(corrected.corrected >= 1);

            AtomicInteger streamed = new AtomicInteger();
            QwenTextResult cancelled = engine.generate(
                    "List the integers from 1 to 1000, comma-separated, without omissions.",
                    chunk -> chunk != null && streamed.addAndGet(chunk.length()) >= 32);
            assertTrue("cancel did not stop", cancelled.stopped);
            assertEquals(0, cancelled.visionMs);

            QwenTextResult recovered = engine.generate(
                    "Reply with exactly this JSON and nothing else: {\"recovered\":true}", null);
            assertTrue("recovery failed: " + recovered.error, recovered.isOk());
            assertEquals("{\"recovered\":true}", recovered.text.trim());
            assertEquals(0, recovered.visionMs);
        } finally {
            engine.release();
        }
    }
}

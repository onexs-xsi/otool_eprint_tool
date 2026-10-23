package com.otool.eprint.ui.recognition;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.otool.eprint.R;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.ComponentRecordFusion;
import com.otool.eprint.scan.CategoryPackageMatcher;
import com.otool.eprint.scan.GlmInformationExtractionParser;
import com.otool.eprint.scan.LabelFrameProcessor;
import com.otool.eprint.scan.LabelTextParser;
import com.otool.eprint.scan.LcscPackageCatalog;
import com.otool.eprint.scan.RecognitionSettings;
import com.otool.eprint.scan.QwenComponentEnricher;
import com.otool.eprint.scan.ScanInputMode;
import com.otool.eprint.scan.UnitSpacingFormatter;
import com.otool.eprint.scan.VlmTranscriptionParser;
import com.otool.eprint.scan.ocr.LabelOcrEngine;
import com.otool.eprint.scan.ocr.OcrLine;
import com.otool.eprint.scan.ocr.OcrResult;
import com.otool.eprint.scan.qwen.QwenComponentPrompt;
import com.otool.eprint.scan.qwen.QwenSettings;
import com.otool.eprint.scan.qwen.QwenSettingsStore;
import com.otool.eprint.scan.qwen.QwenTextOnlyEngine;
import com.otool.eprint.scan.qwen.QwenTextResult;
import com.otool.eprint.scan.vlm.BarcodeMasker;
import com.otool.eprint.scan.vlm.VlmOcrEngine;
import com.otool.eprint.scan.vlm.VlmResult;
import com.otool.eprint.view.LabelGuideOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 标签拍摄识别流水线：抓帧 → 引导框 ROI → PP-OCR → 可选 GLM-OCR → Qwen text-only
 * 安全补全 → 模板，以及处理过程预览与右侧识别日志。
 * 与宿主 Activity 共享的状态经 {@link Host} 读写，引擎与线程随 {@link #destroy()} 释放。
 */
public final class LabelRecognitionController {

    /** 宿主 Activity 提供的共享状态与动作。 */
    public interface Host {
        RecognitionSettings recognitionSettings();

        ScanInputMode scanInputMode();

        boolean embeddedCameraActive();

        /** 申请相机权限（对应宿主的 cameraPermissionLauncher）。 */
        void requestCameraPermission();

        void startEmbeddedScanner();

        void startModeDecoding();

        void applyScanInputModeUi();

        void updateScanButtonLabel();

        /** 回写统一器件记录。 */
        void setComponentRecord(ComponentRecord record);

        /** 器件整理模式下重建模板画布。 */
        void regenerateComponentTemplateIfNeeded();
    }

    private final Activity activity;
    private final Host host;
    private final DecoratedBarcodeView barcodeScanner;
    private final LabelGuideOverlay labelGuideOverlay;
    private final ImageView processingPreview;
    private final TextView processingStageBadge;
    private final TextView qrResultText;
    private final TextView qrParseSummary;
    private final TextView componentRecordSummary;

    private final StringBuilder recognitionLog = new StringBuilder();
    private String recognitionStream = "";
    private boolean cameraSuspendedForProcessing;
    // 标签档最近一次抓帧的处理结果，作为 PP-OCR 与 GLM-OCR 的共同输入。
    private Bitmap lastLabelFrame;
    // PP-OCRv6 级联 OCR 引擎（阶段 2a）：单线程后台执行，切入标签档时预热加载。
    private final LabelOcrEngine labelOcrEngine = new LabelOcrEngine();
    // GLM-OCR 读图模型与 Qwen 文本模型严格串行，避免两个大模型同时常驻。
    private final VlmOcrEngine vlmOcrEngine = new VlmOcrEngine();
    private final QwenTextOnlyEngine qwenTextOnlyEngine = new QwenTextOnlyEngine();
    /** 超时后 requestStop 生效的等待上限。 */
    private static final long VLM_STOP_WAIT_MS = 10_000;
    private static final long QWEN_STOP_WAIT_MS = 10_000;
    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private boolean ocrRunning;

    public LabelRecognitionController(Activity activity, Host host,
                                      @Nullable DecoratedBarcodeView barcodeScanner,
                                      @Nullable LabelGuideOverlay labelGuideOverlay,
                                      @Nullable ImageView processingPreview,
                                      @Nullable TextView processingStageBadge,
                                      @Nullable TextView qrResultText,
                                      @Nullable TextView qrParseSummary,
                                      @Nullable TextView componentRecordSummary) {
        this.activity = activity;
        this.host = host;
        this.barcodeScanner = barcodeScanner;
        this.labelGuideOverlay = labelGuideOverlay;
        this.processingPreview = processingPreview;
        this.processingStageBadge = processingStageBadge;
        this.qrResultText = qrResultText;
        this.qrParseSummary = qrParseSummary;
        this.componentRecordSummary = componentRecordSummary;
    }

    public boolean isCameraSuspendedForProcessing() {
        return cameraSuspendedForProcessing;
    }

    public boolean isOcrRunning() {
        return ocrRunning;
    }

    public boolean isRecognitionLogEmpty() {
        return recognitionLog.length() == 0;
    }

    /** 识别配置保存后释放两个大模型引擎，按新配置重新加载。 */
    public void closeLanguageModelEngines() {
        vlmOcrEngine.close();
        qwenTextOnlyEngine.release();
    }

    /** 重启应用前释放全部识别引擎。 */
    public void closeEngines() {
        vlmOcrEngine.close();
        qwenTextOnlyEngine.release();
        labelOcrEngine.close();
    }

    /** onDestroy 委托：停止两个大模型、关闭线程池与全部引擎。 */
    public void destroy() {
        vlmOcrEngine.requestStop();
        qwenTextOnlyEngine.requestStop();
        ocrExecutor.shutdown();
        labelOcrEngine.close();
        vlmOcrEngine.close();
        qwenTextOnlyEngine.release();
    }

    /** 标签档「拍摄识别」：抓帧 → 引导框 ROI → 轮廓自校正。OCR 引擎在阶段 2 接入。 */
    public void captureAndProcessLabel() {
        if (!host.recognitionSettings().labelEnabled) {
            toast("标签识别已在识别配置中关闭");
            return;
        }
        if (!host.recognitionSettings().ppOcrEnabled && !host.recognitionSettings().vlmEnabled) {
            toast("请至少启用 PP-OCR 或 GLM-OCR");
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            host.requestCameraPermission();
            return;
        }
        if (!host.embeddedCameraActive()) {
            host.startEmbeddedScanner();
            toast("摄像头启动中，请稍后再次点按拍摄识别");
            return;
        }
        TextureView preview = findTextureView(barcodeScanner);
        Bitmap frame = LabelFrameProcessor.capturePreviewFrame(preview);
        if (frame == null) {
            toast("相机预览尚未就绪，请稍后重试");
            return;
        }
        prepareFinalRecognitionArea(ScanInputMode.LABEL);
        resetRecognitionLog("开始标签识别");
        beginProcessingPreview(frame, "1/6 · 已抓取摄像头帧");
        appendRecognitionLog("1/6 已抓取摄像头帧");
        RectF guide = labelGuideOverlay != null
                ? labelGuideOverlay.normalizedGuideRect()
                : new RectF(0.07f, 0.07f, 0.93f, 0.93f);
        LabelFrameProcessor.ProcessedFrame processed = LabelFrameProcessor.process(
                frame, guide, host.recognitionSettings().cropToLabel,
                host.recognitionSettings().minimumLabelCoveragePercent / 100f,
                host.recognitionSettings().blurVarianceThreshold,
                host.recognitionSettings().perspectiveRectify,
                host.recognitionSettings().reduceGlare);
        lastLabelFrame = processed.bitmap;
        String processingStage = processed.perspectiveFixed
                ? "2/6 · 真四边形透视已拉正"
                : processed.croppedToLabel
                ? "2/6 · 已清除背景（未确认透视）"
                : "2/6 · 已按引导框整理画面";
        if (processed.glareReduced) processingStage += " + 反光抑制";
        showProcessingFrame(processed.bitmap, processingStage);
        StringBuilder summary = new StringBuilder("已抓取标签画面");
        if (processed.region.found) {
            summary.append("，贴纸占比约 ")
                    .append(Math.round(processed.region.coverage * 100)).append('%');
            if (processed.perspectiveFixed) {
                summary.append("，真四边形透视 ")
                        .append(processed.rectificationMethod)
                        .append("，置信度 ")
                        .append(Math.round(processed.rectificationConfidence * 100))
                        .append("%，分差 ")
                        .append(String.format(Locale.US, "%.2f",
                                processed.rectificationRunnerUpGap));
            } else if (Math.abs(processed.region.angleDegrees)
                    >= LabelFrameProcessor.DESKEW_MIN_ANGLE_DEGREES) {
                summary.append(processed.deskewed ? "，仅旋转降级 " : "，检测倾角 ")
                        .append(String.format(Locale.US, "%.1f", processed.region.angleDegrees))
                        .append("°");
            }
        } else {
            summary.append("，未检出贴纸轮廓，已保留引导框整图");
        }
        if (processed.croppedToLabel && !processed.perspectiveFixed) {
            summary.append("，已保守清除标签外区域（方法 ")
                    .append(processed.rectificationMethod).append('）');
        }
        if (processed.glareReduced) {
            summary.append("，反光抑制已应用（光照差异 ")
                    .append(Math.round(processed.glareVariationBefore * 100)).append("%→")
                    .append(Math.round(processed.glareVariationAfter * 100)).append("%）");
        } else if (processed.glareReductionRequested) {
            summary.append("，反光检测未达到处理阈值或收益不足");
        }
        appendRecognitionLog("2/6 " + summary);
        if (processed.blurry) toast("画面偏糊，请拿稳后重拍");
        runLabelOcr(processed.bitmap);
    }

    /** 后台执行已启用的 OCR；模板只在所有识别与融合结束后生成一次。 */
    private void runLabelOcr(Bitmap frame) {
        if (ocrRunning) {
            toast("正在识别中，请稍候");
            return;
        }
        ocrRunning = true;
        setScanButtonBusy(true);
        ocrExecutor.execute(() -> {
            // 首次在 OCR 后台线程加载 APK 内离线快照；识别阶段不联网。
            CategoryPackageMatcher packageMatcher = LcscPackageCatalog.get(
                    activity.getApplicationContext());
            OcrResult result = new OcrResult(new ArrayList<>(), 0, 0, 0, 0);
            ComponentRecord cascadeRecord = null;
            if (host.recognitionSettings().ppOcrEnabled) {
                labelOcrEngine.load(activity.getApplicationContext());
                if (labelOcrEngine.isReady()) {
                    result = labelOcrEngine.recognize(frame);
                    if (!result.lines.isEmpty()) {
                        cascadeRecord = LabelTextParser.parse(result.lines, packageMatcher);
                    }
                } else if (!host.recognitionSettings().vlmEnabled) {
                    final String error = labelOcrEngine.initError();
                    activity.runOnUiThread(() -> failLabelProcessing(
                            "PP-OCR 引擎加载失败：" + error, "OCR 引擎不可用"));
                    return;
                }
            }
            final OcrResult cascade = result;
            final ComponentRecord ppRecord = cascadeRecord;
            activity.runOnUiThread(() -> showCascadeOcrPreview(frame, cascade, ppRecord));
            if (host.recognitionSettings().vlmEnabled) {
                runLabelVlm(frame, cascade, ppRecord, packageMatcher);
            } else {
                if (ppRecord == null) {
                    activity.runOnUiThread(() -> failLabelProcessing(
                            "PP-OCR 未识别到有效文字", "未识别到文本"));
                } else {
                    continueWithQwen(ppRecord, cascade.fullText(), packageMatcher,
                            "PP-OCR 最终结果 · " + cascade.timingSummary());
                }
            }
        });
    }

    /** GLM-OCR 信息提取通道：码区遮盖 → 严格 JSON → 统一器件记录；失败不冒充成功。 */
    private void runLabelVlm(Bitmap frame, OcrResult cascade,
                             ComponentRecord cascadeRecord,
                             CategoryPackageMatcher packageMatcher) {
        // 上一轮若保留了 Qwen 会话，先释放再加载 GLM，禁止两个大模型同时常驻。
        if (qwenTextOnlyEngine.isReady()) qwenTextOnlyEngine.release();
        vlmOcrEngine.load(activity.getApplicationContext());
        if (!vlmOcrEngine.isReady()) {
            final String reason = vlmOcrEngine.initError();
            activity.runOnUiThread(() -> {
                appendVlmNote("VLM（GLM-OCR）不可用：" + reason
                        + "；将保留 PP-OCR 并继续 Qwen text-only（如已启用）");
            });
            if (cascadeRecord == null) {
                activity.runOnUiThread(() -> failLabelProcessing(
                        "GLM 不可用且 PP-OCR 无有效结果", "识别模型不可用"));
            } else {
                continueWithQwen(cascadeRecord, cascade.fullText(), packageMatcher,
                        "GLM 不可用，回退 PP-OCR · " + cascade.timingSummary());
            }
            return;
        }
        final String backendFallback = vlmOcrEngine.backendFallbackReason();
        if (backendFallback != null) {
            activity.runOnUiThread(() -> appendVlmNote(
                    "GPU 后端加载失败，已自动回退 CPU：" + backendFallback));
        }
        Bitmap vlmInput = frame;
        int maskCount = 0;
        int textProtected = 0;
        if (host.recognitionSettings().maskCodesBeforeVlm) {
            BarcodeMasker.MaskResult maskResult = BarcodeMasker.mask(frame,
                    protectedTextRegions(cascade),
                    host.recognitionSettings().experimentalWeakCodeMask);
            vlmInput = maskResult.bitmap;
            maskCount = maskResult.maskedCount;
            textProtected = maskResult.protectedCount;
        }
        // 先生成与 VlmOcrEngine 实际落盘完全一致的方形输入，再展示并提交；
        // 禁止 UI 预览中间图、引擎内部另裁一张图。
        final Bitmap structuredInput = VlmOcrEngine.prepareModelInput(vlmInput);
        final int maskedCount = maskCount;
        final int protectedCount = textProtected;
        final boolean maskingEnabled = host.recognitionSettings().maskCodesBeforeVlm;
        activity.runOnUiThread(() -> {
            setScanButtonBusy(true);
            Button button = findScanButton();
            if (button != null) button.setText("VLM 识别中…");
            String inputSize = structuredInput.getWidth() + "×" + structuredInput.getHeight();
            showProcessingFrame(structuredInput, maskingEnabled
                    ? "4/6 · GLM 最终输入 " + inputSize + "：遮盖 " + maskedCount + " 处码区"
                    : "4/6 · GLM 最终输入 " + inputSize + "：完整标签");
            appendVlmNote(!maskingEnabled
                    ? "GLM 最终输入为完整标签，仅白底补方；保留二维码/一维码"
                    : maskedCount > 0
                    ? "GLM 输入前已自动遮盖 " + maskedCount + " 个二维码/一维码区域"
                    : "GLM 输入前未检出可遮盖的二维码/一维码区域");
            if (protectedCount > 0) {
                appendVlmNote("另有 " + protectedCount + " 个候选与有效文字重叠，已取消遮盖");
            }
            appendVlmNote("VLM（GLM-OCR 0.9B）识别中，通常需 5~60 秒…");
        });
        final StringBuilder streamed = new StringBuilder();
        final AtomicReference<VlmResult> vlmRef = new AtomicReference<>();
        // 流式刷新节流：每 token 都 post 到 UI 线程会淹没消息队列，300ms 一刷足够观感流畅。
        final long[] lastUiRefresh = {0L};
        Thread vlmThread = new Thread(() -> vlmRef.set(vlmOcrEngine.recognize(
                structuredInput, activity.getCacheDir(), cascade.fullText(), chunk -> {
                    streamed.append(chunk);
                    long now = System.currentTimeMillis();
                    if (now - lastUiRefresh[0] < 300L) return false;
                    lastUiRefresh[0] = now;
                    final String partial = streamed.toString();
                    activity.runOnUiThread(() -> showModelText(partial,
                            "VLM（GLM-OCR 0.9B · MNN）识别中…"));
                    return false;
                })), "vlm-ocr");
        vlmThread.start();
        final long timeoutMs = vlmOcrEngine.timeoutMs();
        try {
            vlmThread.join(timeoutMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        boolean timedOut = vlmThread.isAlive();
        if (timedOut) {
            vlmOcrEngine.requestStop();
            try {
                vlmThread.join(VLM_STOP_WAIT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        final VlmResult vlm = vlmRef.get();
        final boolean vlmTimedOut = timedOut;
        final boolean jsonMode = vlmOcrEngine.isJsonExtractionMode();
        ComponentRecord parsed = null;
        String parseError = null;
        if (vlm != null && vlm.isOk()) {
            if (jsonMode) {
                try {
                    parsed = GlmInformationExtractionParser.parse(vlm.text, cascadeRecord);
                } catch (org.json.JSONException invalidJson) {
                    parseError = invalidJson.getMessage();
                }
            } else {
                // 默认转写模式：转写文本 → 规则归类（VLM 只负责读字，字段归属交给规则）。
                parsed = VlmTranscriptionParser.parse(vlm.text, packageMatcher);
                if (parsed == null) parseError = "转写文本为空或均为退化字符";
            }
        }
        if (vlmTimedOut) {
            continueAfterVlmFailure(cascadeRecord, cascade, packageMatcher,
                    "VLM 超时（>" + (timeoutMs / 1000) + "s）",
                    "GLM 超时，回退 PP-OCR · " + cascade.timingSummary());
            return;
        }
        if (vlm == null || !vlm.isOk()) {
            String error = vlm != null ? vlm.error : "识别被中断";
            continueAfterVlmFailure(cascadeRecord, cascade, packageMatcher,
                    "VLM 识别失败：" + error,
                    "GLM 失败，回退 PP-OCR · " + cascade.timingSummary());
            return;
        }
        String header = "VLM（GLM-OCR 0.9B · MNN）" + (jsonMode ? "信息提取 JSON" : "文字转写")
                + (vlmOcrEngine.loadMs() >= 0 ? " · 加载 " + vlmOcrEngine.loadMs() + "ms" : "")
                + (maskingEnabled ? " · 码区遮盖 " + maskedCount : " · 码区遮盖关闭")
                + " · " + vlm.timingSummary()
                + (vlm.stopped ? " · 已截断" : "");
        activity.runOnUiThread(() -> showModelText(vlm.text, header));
        if (parsed == null) {
            continueAfterVlmFailure(cascadeRecord, cascade, packageMatcher,
                    (jsonMode ? "VLM JSON 校验失败：" : "VLM 转写结果无效：") + parseError,
                    "GLM 结果无效，回退 PP-OCR · " + cascade.timingSummary());
            return;
        }
        ComponentRecord fusedRecord = ComponentRecordFusion.merge(
                cascadeRecord, parsed,
                host.recognitionSettings().fusionWeightLevel,
                packageMatcher,
                host.recognitionSettings().dictionaryStrongWeight);
        String qwenEvidence = cascade.fullText() + "\nGLM_OCR_TEXT:\n" + vlm.text;
        String baseTiming = "双通道融合（权重 " + host.recognitionSettings().fusionWeightLevel
                + " 档）· 字典强权重"
                + (host.recognitionSettings().dictionaryStrongWeight ? "开启" : "关闭") + " · "
                + (maskingEnabled ? "码区遮盖 " + maskedCount : "码区遮盖关闭")
                + " · " + vlm.timingSummary();
        activity.runOnUiThread(() -> toast("VLM 识别完成：" + vlm.totalMs + "ms"));
        continueWithQwen(fusedRecord, qwenEvidence, packageMatcher, baseTiming);
    }

    private void continueAfterVlmFailure(ComponentRecord ppRecord, OcrResult cascade,
                                         CategoryPackageMatcher packageMatcher,
                                         String detail, String timing) {
        activity.runOnUiThread(() -> {
            appendVlmNote(detail + "；将保留 PP-OCR 并继续 Qwen（如已启用）");
            toast(detail.startsWith("VLM 超时") ? "VLM 识别超时" : "VLM 识别失败，已安全回退");
        });
        if (ppRecord == null) {
            activity.runOnUiThread(() -> failLabelProcessing(
                    detail + "，且 PP-OCR 无有效结果", "未得到可用识别结果"));
        } else {
            continueWithQwen(ppRecord, cascade.fullText(), packageMatcher, timing);
        }
    }

    /** OCR/GLM 完成后的 Qwen3.5 text-only 严格补全；任何失败都保留 baseRecord。 */
    private void continueWithQwen(ComponentRecord baseRecord, String ocrEvidence,
                                  CategoryPackageMatcher packageMatcher, String baseTiming) {
        QwenSettings settings = QwenSettingsStore.load(activity.getApplicationContext());
        if (!settings.enabled) {
            activity.runOnUiThread(() -> {
                appendRecognitionLog("5/6 Qwen3.5 已在识别配置中关闭，跳过文本补全");
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen关闭");
            });
            return;
        }
        List<String> missingFields = QwenComponentPrompt.missingCoreFields(baseRecord);
        if (settings.onlyWhenCoreFieldsMissing && !settings.allowEvidenceBackedCorrections
                && missingFields.isEmpty()) {
            activity.runOnUiThread(() -> {
                appendRecognitionLog("5/6 Qwen3.5 跳过：核心字段已完整");
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen按配置跳过");
            });
            return;
        }

        // 进入 Qwen 前主动关闭 GLM，避免 0.9B 视觉模型与 2B 文本模型同时驻留。
        if (vlmOcrEngine.isReady()) vlmOcrEngine.close();
        long qwenLoadStart = System.currentTimeMillis();
        qwenTextOnlyEngine.load(activity.getApplicationContext(), settings);
        if (!qwenTextOnlyEngine.isReady()) {
            String reason = qwenTextOnlyEngine.initError();
            activity.runOnUiThread(() -> {
                appendRecognitionLog("5/6 Qwen3.5 不可用：" + reason + "；保留 OCR/GLM 结果");
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen不可用");
            });
            return;
        }
        String prompt = QwenComponentPrompt.build(baseRecord, ocrEvidence, settings);
        String forbidden = QwenTextOnlyEngine.forbiddenPromptReason(prompt);
        if (forbidden != null) {
            activity.runOnUiThread(() -> {
                appendRecognitionLog("5/6 Qwen3.5 请求被 text-only 门禁拒绝：" + forbidden);
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen请求拒绝");
            });
            return;
        }

        final long loadMs = qwenTextOnlyEngine.loadMs() >= 0
                ? qwenTextOnlyEngine.loadMs() : System.currentTimeMillis() - qwenLoadStart;
        activity.runOnUiThread(() -> {
            Button button = findScanButton();
            if (button != null) button.setText(settings.allowEvidenceBackedCorrections
                    ? "Qwen 校验纠错中…" : "Qwen 补全中…");
            showProcessingFrame(lastLabelFrame, settings.allowEvidenceBackedCorrections
                    ? "5/6 · Qwen3.5 text-only 明显错误校验中"
                    : "5/6 · Qwen3.5 text-only 文本补全中");
            appendRecognitionLog("5/6 Qwen3.5-2B text-only 已实际调用"
                    + " · CPU " + settings.threadCount + " 线程"
                    + " · Token 上限 " + settings.maxNewTokens
                    + " · 加载 " + loadMs + "ms"
                    + (settings.allowEvidenceBackedCorrections
                    ? " · 明显错误修正已开启" : " · 只补空字段")
                    + (missingFields.isEmpty() ? " · 当前核心字段完整，执行校验"
                    : " · 待补字段 " + missingFields));
        });

        StringBuilder streamed = new StringBuilder();
        AtomicReference<QwenTextResult> qwenRef = new AtomicReference<>();
        long[] lastUiRefresh = {0L};
        Thread qwenThread = new Thread(() -> qwenRef.set(qwenTextOnlyEngine.generate(prompt, chunk -> {
            streamed.append(chunk);
            long now = System.currentTimeMillis();
            if (now - lastUiRefresh[0] < 300L) return false;
            lastUiRefresh[0] = now;
            String partial = streamed.toString();
            activity.runOnUiThread(() -> showModelText(partial,
                    "Qwen3.5-2B · MNN text-only 补全中…"));
            return false;
        })), "qwen-text-only");
        qwenThread.start();
        boolean timedOut = false;
        try {
            qwenThread.join(settings.timeoutMs());
            timedOut = qwenThread.isAlive();
            if (timedOut) {
                qwenTextOnlyEngine.requestStop();
                qwenThread.join(QWEN_STOP_WAIT_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            timedOut = true;
            qwenTextOnlyEngine.requestStop();
        }
        QwenTextResult qwen = qwenRef.get();
        QwenComponentEnricher.Result enrichment = null;
        String validationError = null;
        if (!timedOut && qwen != null && qwen.isOk() && !qwen.stopped) {
            try {
                enrichment = QwenComponentEnricher.apply(baseRecord, qwen.text,
                        ocrEvidence, packageMatcher, settings);
            } catch (Exception invalid) {
                validationError = invalid.getMessage();
            }
        }
        final boolean qwenTimedOut = timedOut;
        final QwenTextResult qwenResult = qwen;
        final QwenComponentEnricher.Result enrichmentResult = enrichment;
        final String qwenValidationError = validationError;
        // GLM 开启时下一轮还要先读图；Qwen 本轮结束后释放，维持单大模型常驻边界。
        if (!qwenThread.isAlive() && host.recognitionSettings().vlmEnabled) {
            qwenTextOnlyEngine.release();
        }
        activity.runOnUiThread(() -> {
            if (qwenTimedOut) {
                appendRecognitionLog("5/6 Qwen3.5 超时（>" + settings.timeoutSeconds
                        + "s），已停止并保留 OCR/GLM 结果");
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen超时回退");
            } else if (qwenResult == null || !qwenResult.isOk() || qwenResult.stopped) {
                String reason = qwenResult == null ? "生成被中断"
                        : qwenResult.error != null ? qwenResult.error : "生成被停止";
                appendRecognitionLog("5/6 Qwen3.5 失败：" + reason + "；保留 OCR/GLM 结果");
                finalizeLabelRecognition(baseRecord, baseTiming + " · Qwen失败回退");
            } else {
                showModelText(qwenResult.text,
                        "Qwen3.5-2B · MNN text-only · vision=0ms · "
                                + qwenResult.timingSummary());
                if (enrichmentResult == null) {
                    appendRecognitionLog("5/6 Qwen 输出未通过严格 JSON/证据校验："
                            + qwenValidationError + "；保留 OCR/GLM 结果");
                    finalizeLabelRecognition(baseRecord,
                            baseTiming + " · Qwen校验拒绝 · " + qwenResult.timingSummary());
                } else {
                    appendRecognitionLog("5/6 Qwen3.5 完成：" + enrichmentResult.summary()
                            + " · vision=0ms · " + qwenResult.timingSummary());
                    finalizeLabelRecognition(enrichmentResult.record,
                            baseTiming + " · Qwen " + enrichmentResult.summary()
                                    + " · " + qwenResult.timingSummary());
                }
            }
        });
    }

    /** 所有模型都结束后才更新统一记录并生成模板。 */
    private void finalizeLabelRecognition(ComponentRecord record, String timing) {
        if (host.recognitionSettings().spaceRecognizedUnits) {
            record = UnitSpacingFormatter.format(record);
        }
        host.setComponentRecord(record);
        showFinalRecognition(ScanInputMode.LABEL, record.summary());
        appendRecognitionLog("6/6 " + timing);
        appendRecognitionLog("模板阶段：全部模型完成后生成");
        host.regenerateComponentTemplateIfNeeded();
        showProcessingFrame(lastLabelFrame, "6/6 · 全部模型完成，模板已生成");
        finishLabelProcessing();
    }

    /** VLM 流式输出只进入右侧识别日志，底部始终留给最终结构化结果。 */
    private void showModelText(String modelText, String header) {
        recognitionStream = "\n── " + header + " ──\n" + modelText;
        renderRecognitionLog();
    }

    /** VLM 状态统一追加到右侧日志。UI 线程调用。 */
    private void appendVlmNote(String note) {
        appendRecognitionLog(note);
    }

    public void prepareFinalRecognitionArea(ScanInputMode mode) {
        if (qrResultText != null) qrResultText.setText(mode.label);
        if (qrParseSummary != null) qrParseSummary.setText("暂无最终结果");
    }

    public void showFinalRecognition(ScanInputMode mode, String result) {
        if (qrResultText != null) qrResultText.setText(mode.label);
        if (qrParseSummary != null) {
            qrParseSummary.setText(result == null || result.trim().isEmpty()
                    ? "未识别到有效内容" : result.trim());
        }
    }

    public void resetRecognitionLog(String firstLine) {
        recognitionLog.setLength(0);
        recognitionStream = "";
        appendRecognitionLog(firstLine);
    }

    public void appendRecognitionLog(String note) {
        if (note == null || note.trim().isEmpty()) return;
        if (recognitionLog.length() > 0) recognitionLog.append('\n');
        recognitionLog.append(note.trim());
        renderRecognitionLog();
    }

    private void renderRecognitionLog() {
        if (componentRecordSummary == null) return;
        componentRecordSummary.setText(recognitionLog.toString() + recognitionStream);
        componentRecordSummary.post(() -> {
            int lineBottom = componentRecordSummary.getLayout() == null ? 0
                    : componentRecordSummary.getLayout().getLineBottom(
                    Math.max(0, componentRecordSummary.getLineCount() - 1));
            componentRecordSummary.scrollTo(0, Math.max(0,
                    lineBottom - componentRecordSummary.getHeight()));
        });
    }

    private Button findScanButton() {
        View button = activity.findViewById(R.id.scanButton);
        return button instanceof Button ? (Button) button : null;
    }

    /** PP-OCR 只展示中间结果，不提前改模板。 */
    private void showCascadeOcrPreview(Bitmap frame, OcrResult result,
                                       ComponentRecord record) {
        if (result.lines.isEmpty()) {
            String status = host.recognitionSettings().ppOcrEnabled
                    ? "PP-OCR 未识别到文字，继续等待 GLM-OCR"
                    : "PP-OCR 已关闭，直接进入 GLM-OCR";
            appendRecognitionLog("3/6 " + status);
            showProcessingFrame(frame, "3/6 · " + status);
            return;
        }
        String summary = record.summary() + "\n" + result.timingSummary()
                + "\nPP-OCR 为中间结果；模板等待全部模型完成";
        appendRecognitionLog("3/6 PP-OCR 原文：\n" + result.fullText());
        appendRecognitionLog(summary);
        showProcessingFrame(drawOcrRegions(frame, result),
                "3/6 · PP-OCR 已定位 " + result.lines.size() + " 行文字");
    }

    private void beginProcessingPreview(Bitmap frame, String stage) {
        cameraSuspendedForProcessing = true;
        if (barcodeScanner != null) barcodeScanner.pause();
        if (labelGuideOverlay != null) labelGuideOverlay.setVisibility(View.GONE);
        if (processingPreview != null) {
            processingPreview.setImageBitmap(frame);
            processingPreview.setVisibility(View.VISIBLE);
        }
        if (processingStageBadge != null) {
            processingStageBadge.setText(stage);
            processingStageBadge.setVisibility(View.VISIBLE);
        }
    }

    public void showCodeProcessingPreview(BarcodeResult result) {
        if (result == null || barcodeScanner == null) return;
        Bitmap resolved = result.getBitmapWithResultPoints(Color.rgb(234, 118, 91));
        if (resolved == null) return;
        cameraSuspendedForProcessing = true;
        barcodeScanner.pause();
        if (processingPreview != null) {
            processingPreview.setImageBitmap(resolved);
            processingPreview.setVisibility(View.VISIBLE);
        }
        if (processingStageBadge != null) {
            processingStageBadge.setText(host.scanInputMode().label + "已定位 · 正在本机解析");
            processingStageBadge.setVisibility(View.VISIBLE);
        }
        View anchor = processingPreview != null ? processingPreview : barcodeScanner;
        anchor.postDelayed(() -> {
            cameraSuspendedForProcessing = false;
            if (processingPreview != null) {
                processingPreview.setImageDrawable(null);
                processingPreview.setVisibility(View.GONE);
            }
            if (processingStageBadge != null) processingStageBadge.setVisibility(View.GONE);
            if (host.embeddedCameraActive()) {
                barcodeScanner.resume();
                if (host.recognitionSettings().isContinuous(host.scanInputMode())) host.startModeDecoding();
            }
            host.applyScanInputModeUi();
        }, 750L);
    }

    private void showProcessingFrame(Bitmap frame, String stage) {
        if (processingPreview != null && frame != null
                && (host.recognitionSettings().showProcessingPreview
                || processingPreview.getDrawable() == null || stage.startsWith("6/6"))) {
            processingPreview.setImageBitmap(frame);
            processingPreview.setVisibility(View.VISIBLE);
        }
        if (processingStageBadge != null) {
            processingStageBadge.setText(stage);
            processingStageBadge.setVisibility(View.VISIBLE);
        }
    }

    /** 完成后短暂保留最终处理图，再恢复实时摄像头。 */
    private void finishLabelProcessing() {
        View anchor = processingPreview != null ? processingPreview : barcodeScanner;
        if (anchor == null) return;
        anchor.postDelayed(() -> {
            ocrRunning = false;
            cameraSuspendedForProcessing = false;
            if (processingPreview != null) {
                processingPreview.setImageDrawable(null);
                processingPreview.setVisibility(View.GONE);
            }
            if (processingStageBadge != null) processingStageBadge.setVisibility(View.GONE);
            if (barcodeScanner != null && host.embeddedCameraActive()) {
                barcodeScanner.resume();
                host.startModeDecoding();
            }
            host.applyScanInputModeUi();
            setScanButtonBusy(false);
        }, 900L);
    }

    private void failLabelProcessing(String detail, String message) {
        appendRecognitionLog("识别未完成：" + detail);
        if (processingStageBadge != null) processingStageBadge.setText("识别未完成 · " + message);
        toast(message);
        finishLabelProcessing();
    }

    private static List<RectF> protectedTextRegions(OcrResult result) {
        List<RectF> regions = new ArrayList<>();
        for (OcrLine line : result.lines) {
            if (line.hasRect()) {
                regions.add(new RectF(line.left, line.top, line.right, line.bottom));
            }
        }
        return regions;
    }

    private static Bitmap drawOcrRegions(Bitmap source, OcrResult result) {
        Bitmap annotated = source.copy(Bitmap.Config.ARGB_8888, true);
        if (annotated == null) return source;
        Canvas canvas = new Canvas(annotated);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, source.getWidth() / 420f));
        paint.setColor(Color.rgb(234, 118, 91));
        for (OcrLine line : result.lines) {
            if (line.hasRect()) canvas.drawRect(line.left, line.top, line.right, line.bottom, paint);
        }
        return annotated;
    }

    /** 切入标签档时后台预热；GLM 开启时不同时预热 Qwen，维持单大模型常驻。 */
    public void warmUpLabelOcr() {
        if (host.recognitionSettings().ppOcrEnabled
                && !labelOcrEngine.isReady() && labelOcrEngine.initError() == null) {
            ocrExecutor.execute(() -> labelOcrEngine.load(activity.getApplicationContext()));
        }
        if (host.recognitionSettings().vlmEnabled
                && !vlmOcrEngine.isReady() && vlmOcrEngine.initError() == null) {
            ocrExecutor.execute(() -> vlmOcrEngine.load(activity.getApplicationContext()));
        } else if (!host.recognitionSettings().vlmEnabled) {
            QwenSettings qwenSettings = QwenSettingsStore.load(activity.getApplicationContext());
            if (qwenSettings.enabled && !qwenTextOnlyEngine.isReady()
                    && qwenTextOnlyEngine.initError() == null) {
                ocrExecutor.execute(() -> qwenTextOnlyEngine.load(
                        activity.getApplicationContext(), qwenSettings));
            }
        }
    }

    private void setScanButtonBusy(boolean busy) {
        View button = activity.findViewById(R.id.scanButton);
        if (!(button instanceof Button)) return;
        button.setEnabled(!busy);
        if (busy) ((Button) button).setText("识别中…");
        else host.updateScanButtonLabel();
    }

    private static TextureView findTextureView(View root) {
        if (root instanceof TextureView) return (TextureView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextureView found = findTextureView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}

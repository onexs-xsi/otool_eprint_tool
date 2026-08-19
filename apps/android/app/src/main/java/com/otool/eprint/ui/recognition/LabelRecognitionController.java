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
import com.otool.eprint.scan.GlmInformationExtractionParser;
import com.otool.eprint.scan.LabelFrameProcessor;
import com.otool.eprint.scan.LabelTextParser;
import com.otool.eprint.scan.RecognitionSettings;
import com.otool.eprint.scan.ScanInputMode;
import com.otool.eprint.scan.UnitSpacingFormatter;
import com.otool.eprint.scan.VlmTranscriptionParser;
import com.otool.eprint.scan.ocr.LabelOcrEngine;
import com.otool.eprint.scan.ocr.OcrLine;
import com.otool.eprint.scan.ocr.OcrResult;
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
 * 标签拍摄识别（OCR/VLM 流水线）控制器：抓帧 → 引导框 ROI → PP-OCR 级联 → GLM-OCR 信息提取
 * → 融合生成模板，以及处理过程预览与右侧识别日志。
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
    // GLM-OCR VLM 引擎（阶段 2b）：开发阶段独立展示，不启用自动回退。
    private final VlmOcrEngine vlmOcrEngine = new VlmOcrEngine();
    /** 超时后 requestStop 生效的等待上限。 */
    private static final long VLM_STOP_WAIT_MS = 10_000;
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

    /** 模型配置保存后释放 VLM 引擎，按新配置重新加载。 */
    public void closeVlmEngine() {
        vlmOcrEngine.close();
    }

    /** 重启应用前释放两个识别引擎。 */
    public void closeEngines() {
        vlmOcrEngine.close();
        labelOcrEngine.close();
    }

    /** onDestroy 委托：停止 VLM、关闭线程池与两个引擎。 */
    public void destroy() {
        vlmOcrEngine.requestStop();
        ocrExecutor.shutdown();
        labelOcrEngine.close();
        vlmOcrEngine.close();
    }

    /** 标签档「拍摄识别」：抓帧 → 引导框 ROI → 轮廓自校正。OCR 引擎在阶段 2 接入。 */
    public void captureAndProcessLabel() {
        if (!host.recognitionSettings().labelEnabled) {
            toast("标签识别已在模型配置中关闭");
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
        beginProcessingPreview(frame, "1/5 · 已抓取摄像头帧");
        appendRecognitionLog("1/5 已抓取摄像头帧");
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
                ? "2/5 · 真四边形透视已拉正"
                : processed.croppedToLabel
                ? "2/5 · 已清除背景（未确认透视）"
                : "2/5 · 已按引导框整理画面";
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
        appendRecognitionLog("2/5 " + summary);
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
            OcrResult result = new OcrResult(new ArrayList<>(), 0, 0, 0, 0);
            ComponentRecord cascadeRecord = null;
            if (host.recognitionSettings().ppOcrEnabled) {
                labelOcrEngine.load(activity.getApplicationContext());
                if (labelOcrEngine.isReady()) {
                    result = labelOcrEngine.recognize(frame);
                    if (!result.lines.isEmpty()) cascadeRecord = LabelTextParser.parse(result.lines);
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
                runLabelVlm(frame, cascade, ppRecord);
            } else {
                activity.runOnUiThread(() -> {
                    if (ppRecord == null) {
                        failLabelProcessing("PP-OCR 未识别到有效文字", "未识别到文本");
                    } else {
                        finalizeLabelRecognition(ppRecord,
                                "PP-OCR 最终结果 · " + cascade.timingSummary());
                    }
                });
            }
        });
    }

    /** GLM-OCR 信息提取通道：码区遮盖 → 严格 JSON → 统一器件记录；失败不冒充成功。 */
    private void runLabelVlm(Bitmap frame, OcrResult cascade,
                             ComponentRecord cascadeRecord) {
        vlmOcrEngine.load(activity.getApplicationContext());
        if (!vlmOcrEngine.isReady()) {
            final String reason = vlmOcrEngine.initError();
            activity.runOnUiThread(() -> {
                appendVlmNote("VLM（GLM-OCR）不可用：" + reason
                        + "；开发阶段未自动回退到 PP-OCR 模板");
                finishLabelProcessing();
            });
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
                    ? "4/5 · GLM 最终输入 " + inputSize + "：遮盖 " + maskedCount + " 处码区"
                    : "4/5 · GLM 最终输入 " + inputSize + "：完整标签");
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
                    activity.runOnUiThread(() -> showVlmText(cascade, partial,
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
        final boolean vlmTimedOut = timedOut && vlm == null;
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
                parsed = VlmTranscriptionParser.parse(vlm.text);
                if (parsed == null) parseError = "转写文本为空或均为退化字符";
            }
        }
        final ComponentRecord extractedRecord = parsed;
        final String extractionError = parseError;
        activity.runOnUiThread(() -> {
            if (vlmTimedOut) {
                appendVlmNote("VLM 超时（>" + (timeoutMs / 1000)
                        + "s），开发阶段未自动回退到 PP-OCR 模板");
                toast("VLM 识别超时");
                finishLabelProcessing();
            } else if (vlm == null || !vlm.isOk()) {
                String error = vlm != null ? vlm.error : "识别被中断";
                appendVlmNote("VLM 识别失败：" + error
                        + "；开发阶段未自动回退到 PP-OCR 模板");
                toast("VLM 识别失败");
                finishLabelProcessing();
            } else {
                String header = "VLM（GLM-OCR 0.9B · MNN）" + (jsonMode ? "信息提取 JSON" : "文字转写")
                        + (vlmOcrEngine.loadMs() >= 0 ? " · 加载 " + vlmOcrEngine.loadMs() + "ms" : "")
                        + (maskingEnabled ? " · 码区遮盖 " + maskedCount : " · 码区遮盖关闭")
                        + " · " + vlm.timingSummary()
                        + (vlm.stopped ? " · 超时截断" : "");
                showVlmText(cascade, vlm.text, header);
                if (extractedRecord == null) {
                    appendVlmNote((jsonMode ? "VLM JSON 校验失败" : "VLM 转写结果无效")
                            + "，未写入模板：" + extractionError);
                    toast(jsonMode ? "VLM 信息提取格式不完整" : "VLM 转写结果不可用");
                    finishLabelProcessing();
                } else {
                    ComponentRecord finalRecord = ComponentRecordFusion.merge(
                            cascadeRecord, extractedRecord,
                            host.recognitionSettings().fusionWeightLevel);
                    finalizeLabelRecognition(finalRecord,
                            "双通道融合（权重 " + host.recognitionSettings().fusionWeightLevel
                                    + " 档）· "
                                    + (maskingEnabled ? "码区遮盖 " + maskedCount : "码区遮盖关闭")
                                    + " · " + vlm.timingSummary());
                    toast("VLM 识别完成：" + vlm.totalMs + "ms");
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
        appendRecognitionLog("5/5 " + timing);
        appendRecognitionLog("模板阶段：全部模型完成后生成");
        host.regenerateComponentTemplateIfNeeded();
        showProcessingFrame(lastLabelFrame, "5/5 · 融合完成，模板已生成");
        finishLabelProcessing();
    }

    /** VLM 流式输出只进入右侧识别日志，底部始终留给最终结构化结果。 */
    private void showVlmText(OcrResult cascade, String vlmText, String header) {
        recognitionStream = "\n── " + header + " ──\n" + vlmText;
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
            appendRecognitionLog("3/5 " + status);
            showProcessingFrame(frame, "3/5 · " + status);
            return;
        }
        String summary = record.summary() + "\n" + result.timingSummary()
                + "\nPP-OCR 为中间结果；模板等待全部模型完成";
        appendRecognitionLog("3/5 PP-OCR 原文：\n" + result.fullText());
        appendRecognitionLog(summary);
        showProcessingFrame(drawOcrRegions(frame, result),
                "3/5 · PP-OCR 已定位 " + result.lines.size() + " 行文字");
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
                || processingPreview.getDrawable() == null || stage.startsWith("5/5"))) {
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

    /** 切入标签档时后台预热模型（幂等，失败只记录不崩溃）。VLM 模型大、加载慢，一并预热。 */
    public void warmUpLabelOcr() {
        if (host.recognitionSettings().ppOcrEnabled
                && !labelOcrEngine.isReady() && labelOcrEngine.initError() == null) {
            ocrExecutor.execute(() -> labelOcrEngine.load(activity.getApplicationContext()));
        }
        if (host.recognitionSettings().vlmEnabled
                && !vlmOcrEngine.isReady() && vlmOcrEngine.initError() == null) {
            ocrExecutor.execute(() -> vlmOcrEngine.load(activity.getApplicationContext()));
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

package com.otool.eprint.ui.scan;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.zxing.DecodeHintType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import com.otool.eprint.R;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.model.ComponentLabelFactory;
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.scan.ComponentLabelParser;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.QrContentParser;
import com.otool.eprint.scan.RecognitionSettings;
import com.otool.eprint.scan.ScanFormatCatalog;
import com.otool.eprint.scan.ScanInputMode;
import com.otool.eprint.scan.ScanModeStore;
import com.otool.eprint.scan.SupplierFormat;
import com.otool.eprint.ui.recognition.LabelRecognitionController;
import com.otool.eprint.ui.template.ComponentTemplateController;
import com.otool.eprint.view.BarcodeModeSwitch;
import com.otool.eprint.view.LabelCanvasView;
import com.otool.eprint.view.LabelGuideOverlay;

import java.util.EnumMap;
import java.util.List;

/**
 * 嵌入式扫描器与扫码流程控制器：ZXing 内嵌解码、扫描档位切换、扫码结果落画布/器件模板。
 * 文档、画布、工作模式、档案等共享状态经 {@link Host} 读写；
 * 识别流水线与模板面板经构造注入的 controller 协作。
 */
public final class EmbeddedScannerController {

    /** 宿主 Activity 提供的共享状态与动作。 */
    public interface Host {
        RecognitionSettings recognitionSettings();

        WorkMode workMode();

        PrinterProfile selectedProfile();

        LabelDocument document();

        void setDocument(LabelDocument document);

        LabelCanvasView labelCanvas();

        void setComponentRecord(ComponentRecord record);

        void showSelectedElement(@Nullable TextElement element);

        void refreshDocumentControls();

        void persistAndRedraw();
    }

    private final Activity activity;
    private final Host host;
    private final LabelRecognitionController recognitionController;
    private final ComponentTemplateController templateController;
    private final DecoratedBarcodeView barcodeScanner;
    private final LabelGuideOverlay labelGuideOverlay;
    private final BarcodeModeSwitch scanModeSwitch;
    private final ActivityResultLauncher<ScanOptions> scanLauncher;
    private final ActivityResultLauncher<String> cameraPermissionLauncher;

    private SupplierFormat supplierFormat = SupplierFormat.AUTO;
    private String lastScannedContent = "";
    private long lastScannedAt;
    private boolean singleDecodeComplete;
    private ScanInputMode scanInputMode;
    private boolean embeddedCameraActive = true;
    private BarcodeCallback embeddedScanCallback;

    public EmbeddedScannerController(Activity activity, Host host,
                                     LabelRecognitionController recognitionController,
                                     ComponentTemplateController templateController,
                                     @Nullable DecoratedBarcodeView barcodeScanner,
                                     @Nullable LabelGuideOverlay labelGuideOverlay,
                                     @Nullable BarcodeModeSwitch scanModeSwitch,
                                     ActivityResultLauncher<ScanOptions> scanLauncher,
                                     ActivityResultLauncher<String> cameraPermissionLauncher) {
        this.activity = activity;
        this.host = host;
        this.recognitionController = recognitionController;
        this.templateController = templateController;
        this.barcodeScanner = barcodeScanner;
        this.labelGuideOverlay = labelGuideOverlay;
        this.scanModeSwitch = scanModeSwitch;
        this.scanLauncher = scanLauncher;
        this.cameraPermissionLauncher = cameraPermissionLauncher;
        scanInputMode = ScanModeStore.load(activity, host.workMode());
    }

    public ScanInputMode scanInputMode() {
        return scanInputMode;
    }

    public boolean embeddedCameraActive() {
        return embeddedCameraActive;
    }

    public SupplierFormat supplierFormat() {
        return supplierFormat;
    }

    public void resetSingleDecode() {
        singleDecodeComplete = false;
    }

    public void onSupplierFormatSelected(int position) {
        supplierFormat = SupplierFormat.values()[position];
        if (host.workMode() == WorkMode.COMPONENT_ORGANIZATION
                && !lastScannedContent.isEmpty()) {
            applyComponentScan(lastScannedContent, false);
        }
    }

    /** switchWorkMode 尾段：恢复该工作页记住的扫描档位并按需重建器件标签。 */
    public void onWorkModeSwitched(WorkMode selected, ScanInputMode pageScanMode) {
        if (scanModeSwitch != null) scanModeSwitch.setMode(pageScanMode);
        changeScanMode(pageScanMode);
        if (selected == WorkMode.COMPONENT_ORGANIZATION && !lastScannedContent.isEmpty()) {
            applyComponentScan(lastScannedContent, false);
        }
    }

    public void configureEmbeddedScanner() {
        if (scanModeSwitch != null) {
            scanModeSwitch.setMode(scanInputMode);
            scanModeSwitch.setOnModeChangedListener(this::changeScanMode);
        }
        if (barcodeScanner == null) {
            updateScanButtonLabel();
            return;
        }
        barcodeScanner.setStatusText("");
        embeddedScanCallback = new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                String text = result == null ? null : result.getText();
                long now = System.currentTimeMillis();
                if (text == null || text.trim().isEmpty()) return;
                if (host.recognitionSettings().suppressDuplicates(scanInputMode)
                        && text.equals(lastScannedContent) && now - lastScannedAt < 2500L) return;
                recognitionController.showCodeProcessingPreview(result);
                consumeScannedContent(text);
                if (!host.recognitionSettings().isContinuous(scanInputMode)) {
                    singleDecodeComplete = true;
                    updateScanButtonLabel();
                }
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {}
        };
        applyDecoderFormats();
        startModeDecoding();
        applyScanInputModeUi();
    }

    public void changeScanMode(ScanInputMode mode) {
        if (host.workMode() == WorkMode.NORMAL && mode == ScanInputMode.LABEL) mode = ScanInputMode.QR;
        scanInputMode = mode;
        ScanModeStore.save(activity, host.workMode(), mode);
        lastScannedContent = "";
        singleDecodeComplete = false;
        if (mode == ScanInputMode.LABEL) {
            // 标签档暂停 ZXing 连续解码（保留预览供抓帧），避免二维码抢跑结束。
            if (barcodeScanner != null) barcodeScanner.getBarcodeView().stopDecoding();
            recognitionController.prepareFinalRecognitionArea(mode);
            // 后台预热 OCR 模型，首次拍摄不必等待加载。
            recognitionController.warmUpLabelOcr();
        } else {
            applyDecoderFormats();
            startModeDecoding();
            recognitionController.prepareFinalRecognitionArea(mode);
        }
        recognitionController.resetRecognitionLog(mode.label + "识别已就绪");
        applyScanInputModeUi();
        toast("已切换到" + mode.label + "识别");
    }

    public void applyScanInputModeUi() {
        if (labelGuideOverlay != null) {
            labelGuideOverlay.setVisibility(
                    scanInputMode == ScanInputMode.LABEL
                            && !recognitionController.isCameraSuspendedForProcessing()
                            ? View.VISIBLE : View.GONE);
        }
        updateScanButtonLabel();
    }

    public void updateScanButtonLabel() {
        View button = activity.findViewById(R.id.scanButton);
        if (!(button instanceof Button)) return;
        boolean modeEnabled = host.recognitionSettings() == null
                || host.recognitionSettings().isModeEnabled(scanInputMode);
        button.setEnabled(modeEnabled && !recognitionController.isOcrRunning());
        if (!modeEnabled) {
            ((Button) button).setText(scanInputMode.label + "已禁用");
            return;
        }
        if (scanInputMode == ScanInputMode.LABEL) {
            ((Button) button).setText("拍摄识别");
        } else if (barcodeScanner != null) {
            if (embeddedCameraActive && singleDecodeComplete
                    && !host.recognitionSettings().isContinuous(scanInputMode)) {
                ((Button) button).setText("再次识别");
            } else {
                ((Button) button).setText(embeddedCameraActive ? "暂停摄像头" : "启动摄像头");
            }
        } else {
            ((Button) button).setText("摄像头扫描");
        }
    }

    public void applyDecoderFormats() {
        if (barcodeScanner == null || scanInputMode == ScanInputMode.LABEL) return;
        EnumMap<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        if (host.recognitionSettings().tryHarder(scanInputMode)) {
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        }
        if (host.recognitionSettings().alsoInverted(scanInputMode)) {
            hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        }
        barcodeScanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(
                ScanFormatCatalog.formats(scanInputMode, host.recognitionSettings()),
                hints, null, Intents.Scan.MIXED_SCAN));
    }

    public void startModeDecoding() {
        if (barcodeScanner == null || embeddedScanCallback == null
                || scanInputMode == ScanInputMode.LABEL
                || !host.recognitionSettings().isModeEnabled(scanInputMode)
                || !embeddedCameraActive) {
            if (barcodeScanner != null) barcodeScanner.getBarcodeView().stopDecoding();
            return;
        }
        if (host.recognitionSettings().isContinuous(scanInputMode)) {
            barcodeScanner.decodeContinuous(embeddedScanCallback);
        } else {
            barcodeScanner.decodeSingle(embeddedScanCallback);
        }
    }

    public void requestScan() {
        if (!host.recognitionSettings().isModeEnabled(scanInputMode)) {
            toast(scanInputMode.label + "识别已在模型配置中关闭");
            return;
        }
        if (scanInputMode == ScanInputMode.LABEL) {
            if (barcodeScanner == null) {
                toast("请横屏使用拍摄识别");
                return;
            }
            recognitionController.captureAndProcessLabel();
            return;
        }
        if (barcodeScanner != null) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else if (embeddedCameraActive && singleDecodeComplete
                    && !host.recognitionSettings().isContinuous(scanInputMode)) {
                singleDecodeComplete = false;
                startModeDecoding();
                updateScanButtonLabel();
            } else if (embeddedCameraActive) {
                pauseEmbeddedScanner();
            } else {
                startEmbeddedScanner();
            }
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
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

    public void launchScanner() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanFormatCatalog.formatNames(
                        scanInputMode, host.recognitionSettings()))
                .setPrompt(scanInputMode == ScanInputMode.BARCODE ? "将条形码放入取景框" : "将二维码放入取景框")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setBarcodeImageEnabled(false);
        options.addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN);
        scanLauncher.launch(options);
    }

    public void handleScanResult(ScanIntentResult result) {
        String contents = result.getContents();
        if (contents == null) return;
        consumeScannedContent(contents);
    }

    private void consumeScannedContent(String contents) {
        lastScannedContent = contents;
        lastScannedAt = System.currentTimeMillis();
        if (host.workMode() == WorkMode.COMPONENT_ORGANIZATION) {
            applyComponentScan(contents, true);
            return;
        }
        QrContentParser.Parsed parsed = QrContentParser.parse(contents);
        recognitionController.resetRecognitionLog(scanInputMode.label + "识别完成");
        recognitionController.appendRecognitionLog("原始内容：" + contents);
        recognitionController.appendRecognitionLog("本机解析：" + parsed.summary());
        recognitionController.showFinalRecognition(scanInputMode,
                (scanInputMode == ScanInputMode.BARCODE ? "条形码内容：" : "二维码内容：")
                        + contents + "\n解析结果：" + parsed.summary());
        applyScannedContentToCanvas(contents);
    }

    private void applyComponentScan(String contents, boolean announce) {
        ComponentRecord record = ComponentLabelParser.parse(contents, supplierFormat);
        host.setComponentRecord(record);
        String summary = record.summary();
        recognitionController.resetRecognitionLog(scanInputMode.label + "本机解析完成");
        recognitionController.appendRecognitionLog("原始内容：" + contents);
        recognitionController.appendRecognitionLog(summary);
        recognitionController.showFinalRecognition(scanInputMode, summary);
        templateController.renderTemplatePanel();
        boolean printable = record.recognized
                || !record.supplierPartNumber.isEmpty()
                || !record.manufacturerPartNumber.isEmpty();
        if (!printable) {
            if (announce) toast("未识别供应商贴纸，已保留原始内容，请切换供应商后核对");
            return;
        }
        float width = host.document().widthMm;
        float height = host.document().heightMm;
        LabelDocument created = ComponentLabelFactory.create(record, host.selectedProfile().dpi,
                width, height,
                templateController.componentTemplate(), templateController.templateBoldHeadline(),
                templateController.templateLargeFont(), templateController.templateFields(),
                templateController.templateSeparators());
        host.setDocument(created);
        host.labelCanvas().setDocument(created);
        // 不默认选中首元素：选择框等用户点按后再出现。
        host.showSelectedElement(null);
        host.refreshDocumentControls();
        host.persistAndRedraw();
        if (announce) toast("已离线解析" + record.supplier.label + "贴纸并生成器件标签");
    }

    private void applyScannedContentToCanvas(String contents) {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) selected = host.document().addText(contents);
        else selected.text = contents;
        host.labelCanvas().select(selected.id);
        host.showSelectedElement(selected);
        host.persistAndRedraw();
        toast(scanInputMode == ScanInputMode.BARCODE
                ? "已识别条形码并放入右侧画布" : "已解析二维码并放入右侧画布");
    }

    public void startEmbeddedScanner() {
        if (barcodeScanner == null) return;
        embeddedCameraActive = true;
        singleDecodeComplete = false;
        barcodeScanner.resume();
        applyDecoderFormats();
        startModeDecoding();
        updateScanButtonLabel();
    }

    public void pauseEmbeddedScanner() {
        if (barcodeScanner == null) return;
        embeddedCameraActive = false;
        barcodeScanner.pause();
        updateScanButtonLabel();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}

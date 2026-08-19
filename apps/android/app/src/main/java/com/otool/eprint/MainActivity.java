package com.otool.eprint;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.DeviceHistoryStore;
import com.otool.eprint.device.DeviceSettingsStore;
import com.otool.eprint.device.PrinterIdentity;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.ProfileStore;
import com.otool.eprint.device.TransportType;
import com.otool.eprint.hardware.PrinterHardwareStatus;
import com.otool.eprint.job.PrintJob;
import com.otool.eprint.job.PrintJobState;
import com.otool.eprint.model.DocumentStore;
import com.otool.eprint.model.ComponentLabelFactory;
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.model.WorkModeStore;
import com.otool.eprint.print.BluetoothPrinterManager;
import com.otool.eprint.protocol.MonochromeRaster;
import com.otool.eprint.protocol.ProtocolRegistry;
import com.otool.eprint.protocol.ProtocolType;
import com.otool.eprint.protocol.DothanJobOptions;
import com.otool.eprint.protocol.DothanProtocolEncoder;
import com.otool.eprint.protocol.DothanRasterTransform;
import com.otool.eprint.render.LabelBitmapRenderer;
import com.otool.eprint.scan.ScanInputMode;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.RecognitionSettings;
import com.otool.eprint.scan.RecognitionSettingsStore;
import com.otool.eprint.scan.ScanModeStore;
import com.otool.eprint.scan.SupplierFormat;
import com.otool.eprint.scan.opencv.OpenCVSupport;
import com.otool.eprint.service.PrinterConnectionService;
import com.otool.eprint.service.ConnectionPhase;
import com.otool.eprint.service.ConnectionStatus;
import com.otool.eprint.ui.config.ModelConfigDialog;
import com.otool.eprint.ui.discovery.PrinterDiscoveryDialog;
import com.otool.eprint.ui.editor.ElementEditorController;
import com.otool.eprint.ui.profile.ProfileSettingsDialog;
import com.otool.eprint.ui.recognition.LabelRecognitionController;
import com.otool.eprint.ui.scan.EmbeddedScannerController;
import com.otool.eprint.ui.template.ComponentTemplateController;
import com.otool.eprint.view.BarcodeModeSwitch;
import com.otool.eprint.view.LabelCanvasView;
import com.otool.eprint.view.LabelGuideOverlay;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    private static final int ACTION_NONE = 0;
    private static final int ACTION_CHOOSE_PRINTER = 1;
    private static final int ACTION_PRINT = 2;
    private static final String JOB_OPTIONS_PREFS = "eprint_dothan_job_options";
    private static final float[][] LABEL_SIZE_PRESETS = {
            {20f, 10f},
            {25f, 15f},
            {40f, 30f}
    };

    private LabelDocument document;
    private WorkMode workMode;
    private ComponentRecord componentRecord;
    private RecognitionSettings recognitionSettings;
    private PrinterProfile selectedProfile;
    private PrinterConnectionService printerService;
    private LabelCanvasView labelCanvas;
    private TextView printerStatus;
    private TextView connectionIndicator;
    private TextView printerStatusDetail;
    private Button connectButton;
    private Button disconnectButton;
    private TextView profileSummary;
    private TextView compatibilityWarning;
    private EditText widthInput;
    private EditText heightInput;
    private TextView dpiSpinner;
    private Spinner profileSpinner;
    private Spinner labelSizeSpinner;
    private ArrayAdapter<String> labelSizeAdapter;
    private Spinner supplierSpinner;
    private TextView componentRecordSummary;
    private Button addTextButton;
    private Button printButton;
    private DecoratedBarcodeView barcodeScanner;
    private BarcodeModeSwitch scanModeSwitch;
    private ElementEditorController elementEditorController;
    private ComponentTemplateController templateController;
    private EmbeddedScannerController scannerController;
    private LabelGuideOverlay labelGuideOverlay;
    private LabelRecognitionController recognitionController;
    private final LabelRecognitionController.Host recognitionHost = new LabelRecognitionController.Host() {
        @Override
        public RecognitionSettings recognitionSettings() {
            return recognitionSettings;
        }

        @Override
        public ScanInputMode scanInputMode() {
            return scannerController.scanInputMode();
        }

        @Override
        public boolean embeddedCameraActive() {
            return scannerController.embeddedCameraActive();
        }

        @Override
        public void requestCameraPermission() {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        @Override
        public void startEmbeddedScanner() {
            scannerController.startEmbeddedScanner();
        }

        @Override
        public void startModeDecoding() {
            scannerController.startModeDecoding();
        }

        @Override
        public void applyScanInputModeUi() {
            scannerController.applyScanInputModeUi();
        }

        @Override
        public void updateScanButtonLabel() {
            scannerController.updateScanButtonLabel();
        }

        @Override
        public void setComponentRecord(ComponentRecord record) {
            componentRecord = record;
        }

        @Override
        public void regenerateComponentTemplateIfNeeded() {
            if (workMode == WorkMode.COMPONENT_ORGANIZATION) {
                templateController.renderTemplatePanel();
                templateController.regenerateComponentDocument();
            }
        }
    };

    private final ElementEditorController.Host editorHost = new ElementEditorController.Host() {
        @Override
        public LabelDocument document() {
            return document;
        }

        @Override
        public void setDocument(LabelDocument value) {
            document = value;
        }

        @Override
        public WorkMode workMode() {
            return workMode;
        }

        @Override
        public PrinterProfile selectedProfile() {
            return selectedProfile;
        }

        @Override
        public SupplierFormat supplierFormat() {
            return scannerController.supplierFormat();
        }

        @Override
        public LabelCanvasView labelCanvas() {
            return labelCanvas;
        }

        @Override
        public void updateCompatibilityWarning() {
            MainActivity.this.updateCompatibilityWarning();
        }

        @Override
        public void refreshDocumentControls() {
            MainActivity.this.refreshDocumentControls();
        }

        @Override
        public void switchWorkMode(WorkMode selected) {
            MainActivity.this.switchWorkMode(selected);
        }

        @Override
        public void onSupplierFormatSelected(int position) {
            scannerController.onSupplierFormatSelected(position);
        }

        @Override
        public void selectCurrentProfileInSpinner() {
            populatingProfile = true;
            profileSpinner.setSelection(DeviceCatalog.indexOf(selectedProfile.id));
            populatingProfile = false;
        }

        @Override
        public void onProfileSpinnerSelected(int position) {
            if (!populatingProfile) applyProfile(ProfileStore.load(MainActivity.this,
                    DeviceCatalog.profiles().get(position)), false);
        }

        @Override
        public void configureLabelSizeSpinner() {
            MainActivity.this.configureLabelSizeSpinner();
        }

        @Override
        public void requestScan() {
            scannerController.requestScan();
        }

        @Override
        public void requestPrint() {
            requestBluetoothAction(ACTION_PRINT);
        }

        @Override
        public void requestChoosePrinter() {
            requestBluetoothAction(ACTION_CHOOSE_PRINTER);
        }

        @Override
        public void showDiagnostics() {
            MainActivity.this.showDiagnostics();
        }

        @Override
        public void showProfileSettings() {
            MainActivity.this.showProfileSettings();
        }

        @Override
        public void showModelConfigDialog() {
            MainActivity.this.showModelConfigDialog();
        }

        @Override
        public void showCustomLabelSizeDialog() {
            MainActivity.this.showCustomLabelSizeDialog();
        }

        @Override
        public void applyCanvasSize() {
            MainActivity.this.applyCanvasSize();
        }

        @Override
        public void applyPreset(float widthMm, float heightMm) {
            MainActivity.this.applyPreset(widthMm, heightMm);
        }

        @Override
        public void showPreview() {
            MainActivity.this.showPreview();
        }

        @Override
        public void disconnectPrinter() {
            if (printerService != null) printerService.disconnect();
        }

        @Override
        public void cancelJob() {
            if (printerService == null || !printerService.cancelActiveOrLastQueuedJob()) {
                toast("当前没有可取消的任务");
            } else {
                toast("任务已取消；活动传输会重置通道");
            }
        }

        @Override
        public void retryJob() {
            if (printerService == null || !printerService.retryLastFailedJob()) toast("没有可重试的失败任务");
            else toast("失败任务已重新入队");
        }

        @Override
        public void launchImagePicker() {
            imagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }
    };

    private final ComponentTemplateController.Host templateHost = new ComponentTemplateController.Host() {
        @Override
        public LabelDocument document() {
            return document;
        }

        @Override
        public void setDocument(LabelDocument value) {
            document = value;
        }

        @Override
        public WorkMode workMode() {
            return workMode;
        }

        @Override
        public PrinterProfile selectedProfile() {
            return selectedProfile;
        }

        @Override
        public ComponentRecord componentRecord() {
            return componentRecord;
        }

        @Override
        public LabelCanvasView labelCanvas() {
            return labelCanvas;
        }

        @Override
        public void showSelectedElement(@Nullable TextElement element) {
            MainActivity.this.showSelectedElement(element);
        }

        @Override
        public void refreshDocumentControls() {
            MainActivity.this.refreshDocumentControls();
        }

        @Override
        public void persistAndRedraw() {
            MainActivity.this.persistAndRedraw();
        }
    };

    private final EmbeddedScannerController.Host scannerHost = new EmbeddedScannerController.Host() {
        @Override
        public RecognitionSettings recognitionSettings() {
            return recognitionSettings;
        }

        @Override
        public WorkMode workMode() {
            return workMode;
        }

        @Override
        public PrinterProfile selectedProfile() {
            return selectedProfile;
        }

        @Override
        public LabelDocument document() {
            return document;
        }

        @Override
        public void setDocument(LabelDocument value) {
            document = value;
        }

        @Override
        public LabelCanvasView labelCanvas() {
            return labelCanvas;
        }

        @Override
        public void setComponentRecord(ComponentRecord record) {
            componentRecord = record;
        }

        @Override
        public void showSelectedElement(@Nullable TextElement element) {
            MainActivity.this.showSelectedElement(element);
        }

        @Override
        public void refreshDocumentControls() {
            MainActivity.this.refreshDocumentControls();
        }

        @Override
        public void persistAndRedraw() {
            MainActivity.this.persistAndRedraw();
        }
    };
    private boolean populatingProfile;
    private boolean populatingLabelSize;
    private boolean serviceBound;
    private int pendingBluetoothAction;
    private ConnectionStatus lastConnectionStatus = ConnectionStatus.idle();
    private PrinterHardwareStatus lastHardwareStatus;
    private String lastJobSummary = "";

    private final PrinterConnectionService.Listener hardwareListener = new PrinterConnectionService.Listener() {
        @Override
        public void onConnectionStateChanged(ConnectionStatus status) {
            lastConnectionStatus = status;
            if (status.phase == ConnectionPhase.MANUAL_DISCONNECTED) {
                lastHardwareStatus = null;
                lastJobSummary = "";
            }
            renderConnectionStatus();
        }

        @Override
        public void onHardwareStatusChanged(PrinterHardwareStatus status) {
            lastHardwareStatus = status;
            renderConnectionStatus();
        }

        @Override
        public void onJobUpdated(PrintJob job) {
            lastJobSummary = job.state.label + " " + job.progress + "% · " + job.message;
            renderConnectionStatus();
        }
    };

    private final ServiceConnection hardwareServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            printerService = ((PrinterConnectionService.LocalBinder) binder).service();
            serviceBound = true;
            PrinterProfile active = printerService.activeProfile();
            if (active != null) {
                selectedProfile = active;
                if (profileSpinner != null) {
                    populatingProfile = true;
                    profileSpinner.setSelection(DeviceCatalog.indexOf(active.id));
                    populatingProfile = false;
                }
                document.dpi = active.dpi;
                refreshDocumentControls();
                refreshProfileSummary();
                persistAndRedraw();
            }
            printerService.addListener(hardwareListener);
            if (pendingBluetoothAction != ACTION_NONE) requestBluetoothAction(pendingBluetoothAction);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            printerService = null;
            lastConnectionStatus = new ConnectionStatus(ConnectionPhase.SERVICE_STOPPED,
                    "打印硬件服务已停止", "", "", "", false, 0, 0);
            renderConnectionStatus();
        }
    };

    private final ActivityResultLauncher<ScanOptions> scanLauncher = registerForActivityResult(
            new ScanContract(), result -> scannerController.handleScanResult(result));

    private final ActivityResultLauncher<PickVisualMediaRequest> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) elementEditorController.importImageToSelected(uri);
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && barcodeScanner != null) scannerController.startEmbeddedScanner();
                else if (granted && scannerController.scanInputMode() == ScanInputMode.LABEL) toast("请横屏使用拍摄识别");
                else if (granted) scannerController.launchScanner();
                else toast("需要相机权限才能扫描二维码或条形码");
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) toast("后台连接仍可运行，但通知栏不会显示实时打印进度");
            });

    private final ActivityResultLauncher<String[]> bluetoothPermissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = true;
                for (Boolean value : result.values()) granted &= Boolean.TRUE.equals(value);
                if (granted) runPendingBluetoothAction();
                else {
                    if (pendingBluetoothAction == ACTION_CHOOSE_PRINTER
                            && printerService != null && !printerService.usbDevices().isEmpty()) {
                        pendingBluetoothAction = ACTION_NONE;
                        toast("未授权蓝牙，将只搜索 USB 打印机");
                        showAutomaticDiscoveryDialog();
                    } else {
                        pendingBluetoothAction = ACTION_NONE;
                        toast("需要附近设备权限才能搜索或连接蓝牙打印机");
                    }
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) runPendingBluetoothAction();
                else {
                    pendingBluetoothAction = ACTION_NONE;
                    toast("蓝牙未开启");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 15+（API 35）对 targetSdk 35+ 强制边到边布局，需要自行处理系统栏 inset，
        // 否则底部操作栏会被手势导航区域遮挡。低版本保持系统默认的内嵌布局。
        if (Build.VERSION.SDK_INT >= 35) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        }
        setContentView(R.layout.activity_main);

        // 预热 OpenCV native 加载，避免首次抓帧时阻塞；加载失败时相关增强路径自动回退 Java 实现。
        OpenCVSupport.isAvailable();

        workMode = WorkModeStore.load(this);
        recognitionSettings = RecognitionSettingsStore.load(this);
        document = DocumentStore.load(this, workMode);
        selectedProfile = ProfileStore.load(this);
        document.dpi = selectedProfile.dpi;
        document.normalize();
        bindViews();
        recognitionController = new LabelRecognitionController(this, recognitionHost,
                barcodeScanner, labelGuideOverlay,
                findViewById(R.id.processingPreview), findViewById(R.id.processingStageBadge),
                findViewById(R.id.qrResultText), findViewById(R.id.qrParseSummary),
                componentRecordSummary);
        elementEditorController = new ElementEditorController(this, editorHost);
        templateController = new ComponentTemplateController(this, templateHost,
                elementEditorController, componentRecordSummary);
        scannerController = new EmbeddedScannerController(this, scannerHost,
                recognitionController, templateController,
                barcodeScanner, labelGuideOverlay, scanModeSwitch,
                scanLauncher, cameraPermissionLauncher);
        applyEdgeToEdgeInsets();
        renderConnectionStatus();
        scannerController.configureEmbeddedScanner();
        elementEditorController.configureSpinners();
        configureCanvas();
        elementEditorController.configureActions();
        elementEditorController.configureEditor();
        elementEditorController.configureTypeEditors();
        templateController.configureReservedPanels();
        renderWorkMode();
        refreshDocumentControls();
        refreshProfileSummary();
        elementEditorController.refreshElementChips();

        // 默认不选中任何元素：编辑面板与选择框只在用户点按元素后出现。
        showSelectedElement(null);

        Intent serviceIntent = new Intent(this, PrinterConnectionService.class);
        bindService(serviceIntent, hardwareServiceConnection, Context.BIND_AUTO_CREATE);
        elementEditorController.initHistory();
    }

    private void bindViews() {
        labelCanvas = findViewById(R.id.labelCanvas);
        printerStatus = findViewById(R.id.printerStatus);
        connectionIndicator = findViewById(R.id.connectionIndicator);
        printerStatusDetail = findViewById(R.id.printerStatusDetail);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        profileSummary = findViewById(R.id.profileSummary);
        compatibilityWarning = findViewById(R.id.compatibilityWarning);
        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        dpiSpinner = findViewById(R.id.dpiSpinner);
        profileSpinner = findViewById(R.id.profileSpinner);
        labelSizeSpinner = findViewById(R.id.labelSizeSpinner);
        barcodeScanner = findViewById(R.id.barcodeScanner);
        scanModeSwitch = findViewById(R.id.scanModeSwitch);
        labelGuideOverlay = findViewById(R.id.labelGuideOverlay);
        supplierSpinner = findViewById(R.id.supplierSpinner);
        componentRecordSummary = findViewById(R.id.componentRecordSummary);
        if (componentRecordSummary != null) {
            componentRecordSummary.setMovementMethod(new ScrollingMovementMethod());
        }
        addTextButton = findViewById(R.id.addTextButton);
        printButton = findViewById(R.id.printButton);
    }

    private void applyEdgeToEdgeInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        // 内容整体避开状态栏、导航栏和挖孔；衬底区域显示窗口背景色（eprint_surface）。
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void renderConnectionStatus() {
        if (printerStatus == null || connectionIndicator == null || printerStatusDetail == null) return;
        ConnectionStatus status = lastConnectionStatus == null
                ? ConnectionStatus.idle() : lastConnectionStatus;
        connectionIndicator.setText(status.indicatorLabel());
        GradientDrawable badge = new GradientDrawable();
        badge.setCornerRadius(dp(14));
        int color;
        switch (status.phase) {
            case PRINTER_READY:
            case CONNECTED:
                color = 0xFF2E8B57;
                break;
            case TRANSPORT_CONNECTED:
            case PROTOCOL_NEGOTIATING:
            case CONNECTING:
                color = 0xFF2F80B7;
                break;
            case SENDING:
                color = 0xFF247F82;
                break;
            case WAITING_RECEIPT:
            case RECONNECT_WAIT:
                color = 0xFFE07A2F;
                break;
            case PRINT_FAILED:
            case WAITING_FOR_HARDWARE:
            case SERVICE_STOPPED:
                color = 0xFFA33A3A;
                break;
            default:
                color = 0xFF667670;
                break;
        }
        badge.setColor(color);
        connectionIndicator.setBackground(badge);
        printerStatus.setText(status.message);

        StringBuilder detail = new StringBuilder();
        if (!status.deviceName.isEmpty()) {
            detail.append(status.deviceName);
            if (!status.transport.isEmpty()) detail.append(" · ").append(status.transport);
            if (!status.address.isEmpty()) detail.append(" · ").append(status.address);
            PrinterIdentity identity = DeviceCatalog.identify(status.deviceName,
                    transportFromLabel(status.transport));
            if (identity != null) {
                detail.append("\n").append(identity.manufacturer).append(" · ")
                        .append(identity.specification());
            }
        } else {
            detail.append("点击“自动找打印机”，扫描完成后由用户选择设备");
        }
        if (status.phase == ConnectionPhase.RECONNECT_WAIT) {
            detail.append("\n已尝试 ").append(status.reconnectAttempt)
                    .append(" 次；持续重连直到手动断开");
        } else if (!lastJobSummary.isEmpty()) {
            detail.append("\n").append(lastJobSummary);
        } else if (lastHardwareStatus != null && lastHardwareStatus.connected
                && lastHardwareStatus.responseConfirmed) {
            detail.append("\n纸张 ").append(lastHardwareStatus.paper)
                    .append(" · 上盖 ").append(lastHardwareStatus.cover)
                    .append(" · 温度 ").append(lastHardwareStatus.temperature);
        }
        printerStatusDetail.setText(detail.toString());
        if (connectButton != null) {
            connectButton.setText(status.connectionDesired ? "更换打印机" : "自动找打印机");
        }
        if (disconnectButton != null) {
            disconnectButton.setEnabled(status.connectionDesired);
            disconnectButton.setAlpha(status.connectionDesired ? 1f : 0.5f);
        }
        if (printButton != null) {
            boolean ready = printerService != null && printerService.isPrinterReady();
            printButton.setEnabled(ready);
            printButton.setAlpha(ready ? 1f : 0.55f);
            printButton.setText(ready ? "打印标签" : "等待打印机就绪");
        }
    }

    private static TransportType transportFromLabel(String label) {
        for (TransportType value : TransportType.values()) {
            if (value.label.equals(label)) return value;
        }
        return TransportType.BLE_GATT;
    }

    private void configureLabelSizeSpinner() {
        if (labelSizeSpinner == null) return;
        labelSizeAdapter = new ArrayAdapter<>(this, R.layout.spinner_item_compact,
                new ArrayList<>());
        labelSizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        labelSizeSpinner.setAdapter(labelSizeAdapter);
        refreshLabelSizeSpinner();
        labelSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (populatingLabelSize) return;
                if (position >= 0 && position < LABEL_SIZE_PRESETS.length) {
                    float[] preset = LABEL_SIZE_PRESETS[position];
                    applyPreset(preset[0], preset[1]);
                } else if (labelSizeAdapter != null
                        && position == labelSizeAdapter.getCount() - 1) {
                    showCustomLabelSizeDialog();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void configureCanvas() {
        labelCanvas.setDocument(document);
        labelCanvas.setListener(new LabelCanvasView.Listener() {
            @Override
            public void onSelectionChanged(@Nullable TextElement element) {
                showSelectedElement(element);
            }

            @Override
            public void onElementMoved(TextElement element) {
                persistAndRedraw();
            }

            @Override
            public void onElementResized(TextElement element) {
                showSelectedElement(element);
                persistAndRedraw();
            }
        });
    }

    private void switchWorkMode(WorkMode selected) {
        DocumentStore.save(this, workMode, document);
        workMode = selected;
        WorkModeStore.save(this, selected);
        document = DocumentStore.load(this, selected);
        document.dpi = selectedProfile.dpi;
        document.normalize();
        elementEditorController.resetHistory();
        labelCanvas.setDocument(document);
        showSelectedElement(null);
        refreshDocumentControls();
        renderWorkMode();
        persistAndRedraw();
        ScanInputMode pageScanMode = ScanModeStore.load(this, selected);
        scannerController.onWorkModeSwitched(selected, pageScanMode);
        toast("已切换到“" + selected.label + "”模式");
    }

    private void renderWorkMode() {
        boolean component = workMode == WorkMode.COMPONENT_ORGANIZATION;
        if (supplierSpinner != null) supplierSpinner.setVisibility(component ? View.VISIBLE : View.GONE);
        if (scanModeSwitch != null) scanModeSwitch.setLabelModeVisible(component);
        if (addTextButton != null) addTextButton.setVisibility(component ? View.GONE : View.VISIBLE);
        templateController.renderWorkModePanels(component, recognitionController.isRecognitionLogEmpty());
        elementEditorController.updateEditorCardVisibility(component);
    }

    private void applyProfile(PrinterProfile profile, boolean automatic) {
        applyProfile(profile, automatic, true);
    }

    private void applyProfile(PrinterProfile profile, boolean automatic,
                              boolean reconnectExistingTarget) {
        boolean changed = selectedProfile == null || !selectedProfile.id.equals(profile.id);
        DeviceCandidate reconnectTarget = changed && reconnectExistingTarget
                && printerService != null && printerService.connectionStatus().connectionDesired
                ? printerService.targetDevice() : null;
        selectedProfile = profile;
        ProfileStore.save(this, profile);
        document.dpi = profile.dpi;
        document.normalize();
        refreshDocumentControls();
        refreshProfileSummary();
        persistAndRedraw();
        if (automatic) toast("已按设备名称匹配：" + profile.protocol.label);
        if (reconnectTarget != null && printerService != null) {
            PrinterProfile effective = DeviceSettingsStore.apply(this, reconnectTarget, profile);
            selectedProfile = effective;
            refreshDocumentControls();
            refreshProfileSummary();
            printerService.connect(reconnectTarget, effective, new BluetoothPrinterManager.Callback() {
                @Override public void onSuccess(String message) {
                    toast("协议档案已切换并重新连接");
                }
                @Override public void onError(String message) {
                    toast("协议档案已切换；连接服务将继续重试");
                }
            });
        }
    }

    private void selectProfile(PrinterProfile profile, boolean automatic) {
        populatingProfile = true;
        profileSpinner.setSelection(DeviceCatalog.indexOf(profile.id));
        populatingProfile = false;
        applyProfile(profile, automatic, false);
    }

    private void refreshProfileSummary() {
        List<String> supported = new ArrayList<>();
        if (selectedProfile.supportsSpp) supported.add("SPP");
        if (selectedProfile.supportsBle) supported.add("BLE");
        if (selectedProfile.supportsUsb) supported.add("USB");
        String transports = TextUtils.join(" / ", supported);
        profileSummary.setText(selectedProfile.protocol.label + " · " + transports + " · 最大 "
                + String.format(Locale.US, "%.1f", selectedProfile.printableWidthMm()) + " mm / "
                + selectedProfile.printableWidthDots + " 点\n"
                + selectedProfile.paperType.label + " · 浓度 " + selectedProfile.printDensity
                + " · 速度 " + selectedProfile.printSpeed + " · 间隙 "
                + String.format(Locale.US, "%.1f", selectedProfile.gapLengthTenthsMm / 10f) + " mm"
                + (selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE
                ? "\n私有协议压缩：" + selectedProfile.compression.label
                + " · 电机 " + selectedProfile.motorMode
                + " · 语言 " + selectedProfile.language
                + (selectedProfile.helloTokenHex.trim().isEmpty()
                ? (selectedProfile.useDefaultDothanAuth ? " · 默认离线授权已启用" : " · 授权关闭")
                : " · 自定义授权已启用")
                + (selectedProfile.applyDothanExtras ? " · 实验参数下发已启用" : "") : ""));
        updateCompatibilityWarning();
    }

    private void updateCompatibilityWarning() {
        int widthDots = LabelDocument.mmToDots(document.widthMm, selectedProfile.dpi);
        boolean tooWide = widthDots > selectedProfile.printableWidthDots;
        compatibilityWarning.setVisibility(tooWide ? View.VISIBLE : View.GONE);
        if (tooWide) {
            compatibilityWarning.setText("当前画布 " + widthDots + " 点，超过该档案最大 "
                    + selectedProfile.printableWidthDots + " 点，打印已禁用");
        }
    }

    private void applyCanvasSize() {
        if (widthInput == null || heightInput == null) {
            showCustomLabelSizeDialog();
            return;
        }
        Float width = parseNumber(widthInput.getText().toString());
        Float height = parseNumber(heightInput.getText().toString());
        if (width == null || height == null) {
            toast("请输入有效的宽度和高度");
            return;
        }
        resizeDocument(width, height);
        refreshDocumentControls();
        persistAndRedraw();
    }

    private void applyPreset(float width, float height) {
        resizeDocument(width, height);
        refreshDocumentControls();
        persistAndRedraw();
    }

    private void resizeDocument(float width, float height) {
        if (workMode == WorkMode.COMPONENT_ORGANIZATION && componentRecord != null) {
            document = ComponentLabelFactory.create(componentRecord, selectedProfile.dpi,
                    width, height, templateController.componentTemplate(),
                    templateController.templateBoldHeadline(),
                    templateController.templateLargeFont(), templateController.templateFields());
            labelCanvas.setDocument(document);
            showSelectedElement(null);
        } else {
            document.widthMm = width;
            document.heightMm = height;
            document.dpi = selectedProfile.dpi;
            document.normalize();
        }
    }

    private void refreshDocumentControls() {
        if (widthInput != null) widthInput.setText(formatNumber(document.widthMm));
        if (heightInput != null) heightInput.setText(formatNumber(document.heightMm));
        if (dpiSpinner != null) dpiSpinner.setText(selectedProfile.dpi + " DPI");
        refreshLabelSizeSpinner();
        elementEditorController.refreshBorderStyleButton();
        updateCompatibilityWarning();
    }

    private void refreshLabelSizeSpinner() {
        if (labelSizeSpinner == null || labelSizeAdapter == null || document == null) return;
        int selection = LABEL_SIZE_PRESETS.length;
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < LABEL_SIZE_PRESETS.length; index++) {
            float[] preset = LABEL_SIZE_PRESETS[index];
            labels.add(formatNumber(preset[0]) + "×" + formatNumber(preset[1]) + " mm");
            if (Math.abs(document.widthMm - preset[0]) < 0.01f
                    && Math.abs(document.heightMm - preset[1]) < 0.01f) {
                selection = index;
            }
        }
        if (selection == LABEL_SIZE_PRESETS.length) {
            labels.add("当前 " + formatNumber(document.widthMm) + "×"
                    + formatNumber(document.heightMm) + " mm");
        }
        labels.add("自定义配置…");
        populatingLabelSize = true;
        labelSizeAdapter.clear();
        labelSizeAdapter.addAll(labels);
        labelSizeAdapter.notifyDataSetChanged();
        labelSizeSpinner.setSelection(selection, false);
        populatingLabelSize = false;
    }

    private void showCustomLabelSizeDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = dp(20);
        form.setPadding(horizontalPadding, dp(6), horizontalPadding, 0);

        TextView note = new TextView(this);
        note.setText("宽度 " + formatNumber(LabelDocument.MIN_WIDTH_MM) + "–"
                + formatNumber(LabelDocument.MAX_WIDTH_MM) + " mm，高度 "
                + formatNumber(LabelDocument.MIN_HEIGHT_MM) + "–"
                + formatNumber(LabelDocument.MAX_HEIGHT_MM) + " mm");
        note.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        form.addView(note);

        LinearLayout fields = new LinearLayout(this);
        fields.setGravity(android.view.Gravity.CENTER_VERTICAL);
        EditText customWidth = elementEditorController.geometryInput(document.widthMm);
        customWidth.setHint("宽 mm");
        EditText customHeight = elementEditorController.geometryInput(document.heightMm);
        customHeight.setHint("高 mm");
        fields.addView(customWidth, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView separator = new TextView(this);
        separator.setText("×");
        separator.setGravity(android.view.Gravity.CENTER);
        fields.addView(separator, new LinearLayout.LayoutParams(dp(36), dp(52)));
        fields.addView(customHeight, new LinearLayout.LayoutParams(0, dp(52), 1f));
        form.addView(fields);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义标签大小")
                .setView(form)
                .setNegativeButton("取消", (ignored, which) -> refreshLabelSizeSpinner())
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Float width = parseNumber(customWidth.getText().toString());
                    Float height = parseNumber(customHeight.getText().toString());
                    if (width == null || height == null
                            || width < LabelDocument.MIN_WIDTH_MM
                            || width > LabelDocument.MAX_WIDTH_MM
                            || height < LabelDocument.MIN_HEIGHT_MM
                            || height > LabelDocument.MAX_HEIGHT_MM) {
                        toast("请输入范围内的有效宽度和高度");
                        return;
                    }
                    resizeDocument(width, height);
                    refreshDocumentControls();
                    persistAndRedraw();
                    dialog.dismiss();
                }));
        dialog.setOnDismissListener(ignored -> refreshLabelSizeSpinner());
        dialog.show();
    }

    private void showSelectedElement(@Nullable TextElement element) {
        elementEditorController.showSelectedElement(element);
    }

    private void showPreview() {
        MonochromeRaster raster = renderRasterForSelectedPrinter(true);
        Bitmap bitmap = raster.toBitmap();
        ImageView image = new ImageView(this);
        int padding = dp(20);
        image.setPadding(padding, padding, padding, padding);
        image.setBackgroundColor(0xFFE7E4DB);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        new AlertDialog.Builder(this)
                .setTitle(document.widthMm + " × " + document.heightMm + " mm · 1-bit 黑白 · 阈值 "
                        + selectedProfile.threshold
                        + (loadDothanJobOptions().antiColor ? " · 已反色" : ""))
                .setView(image)
                .setPositiveButton("关闭", null)
                .show();
    }

    private MonochromeRaster renderRasterForSelectedPrinter(boolean includeJobTransform) {
        Bitmap source = LabelBitmapRenderer.render(document);
        MonochromeRaster raster = MonochromeRaster.fromBitmap(source, selectedProfile.threshold);
        source.recycle();
        DothanJobOptions options = loadDothanJobOptions();
        if (selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE) {
            return includeJobTransform ? DothanRasterTransform.apply(raster, options) : raster;
        }
        return options.antiColor ? raster.inverted() : raster;
    }

    private void requestBluetoothAction(int action) {
        pendingBluetoothAction = action;
        if (printerService == null) {
            toast("打印硬件服务正在启动，请稍候");
            return;
        }
        if (action == ACTION_PRINT) {
            pendingBluetoothAction = ACTION_NONE;
            sendPrint();
            return;
        }
        if (!printerService.isBluetoothAvailable() && printerService.usbDevices().isEmpty()) {
            pendingBluetoothAction = ACTION_NONE;
            toast("未发现可用的蓝牙或 USB Host 硬件");
            return;
        }
        String[] missing = missingBluetoothPermissions(action);
        if (printerService.isBluetoothAvailable() && missing.length > 0) {
            bluetoothPermissionsLauncher.launch(missing);
            return;
        }
        runPendingBluetoothAction();
    }

    private String[] missingBluetoothPermissions(int action) {
        List<String> required = new ArrayList<>();
        boolean needsScan = action == ACTION_CHOOSE_PRINTER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (needsScan) required.add(Manifest.permission.BLUETOOTH_SCAN);
        } else if (needsScan) {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    private void runPendingBluetoothAction() {
        int action = pendingBluetoothAction;
        if (action == ACTION_NONE || printerService == null) return;
        if (action == ACTION_CHOOSE_PRINTER && printerService.isBluetoothAvailable()
                && !printerService.isBluetoothEnabled() && printerService.usbDevices().isEmpty()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        pendingBluetoothAction = ACTION_NONE;
        if (action == ACTION_PRINT) sendPrint();
        else showAutomaticDiscoveryDialog();
    }

    private void showAutomaticDiscoveryDialog() {
        PrinterDiscoveryDialog.show(this, printerService, selectedProfile,
                (candidate, profile, automatic) -> {
                    selectProfile(profile, automatic);
                    connectWithProfile(candidate, profile);
                });
    }

    private void connectWithProfile(DeviceCandidate candidate, PrinterProfile profile) {
        if (printerService == null) {
            toast("打印硬件服务尚未就绪");
            return;
        }
        PrinterProfile effectiveProfile = DeviceSettingsStore.apply(this, candidate, profile);
        selectedProfile = effectiveProfile;
        document.dpi = effectiveProfile.dpi;
        refreshDocumentControls();
        refreshProfileSummary();
        persistAndRedraw();
        printerService.connect(candidate, effectiveProfile, new BluetoothPrinterManager.Callback() {
            @Override
            public void onSuccess(String message) {
                DeviceHistoryStore.record(MainActivity.this, candidate, effectiveProfile);
                toast("打印机通道已就绪");
                requestNotificationPermissionIfNeeded();
            }

            @Override
            public void onError(String message) {
                toast("首次连接失败，后台将持续重连：" + message);
            }
        });
    }

    private DothanJobOptions loadDothanJobOptions() {
        SharedPreferences preferences = getSharedPreferences(JOB_OPTIONS_PREFS, MODE_PRIVATE);
        int copies = preferences.getInt("copies", 1);
        int leftTenthsMm = preferences.getInt("left_tenths_mm", 0);
        int rightTenthsMm = preferences.getInt("right_tenths_mm", 0);
        int topTenthsMm = preferences.getInt("top_tenths_mm", 0);
        int bottomTenthsMm = preferences.getInt("bottom_tenths_mm", 0);
        int direction = preferences.getInt("direction", 0);
        boolean flip = preferences.getBoolean("flip_horizontal", false);
        boolean anti = preferences.getBoolean("anti_color", false);
        return new DothanJobOptions(copies,
                LabelDocument.mmToDots(leftTenthsMm / 10f, selectedProfile.dpi),
                LabelDocument.mmToDots(rightTenthsMm / 10f, selectedProfile.dpi),
                LabelDocument.mmToDots(topTenthsMm / 10f, selectedProfile.dpi),
                LabelDocument.mmToDots(bottomTenthsMm / 10f, selectedProfile.dpi),
                DothanJobOptions.ALIGN_LEFT, direction, flip, anti);
    }

    private void saveDothanJobOptions(int copies, int leftTenthsMm, int rightTenthsMm,
                                      int topTenthsMm, int bottomTenthsMm,
                                      int direction,
                                      boolean flip, boolean anti) {
        getSharedPreferences(JOB_OPTIONS_PREFS, MODE_PRIVATE).edit()
                .putInt("copies", copies)
                .putInt("left_tenths_mm", leftTenthsMm)
                .putInt("right_tenths_mm", rightTenthsMm)
                .putInt("top_tenths_mm", topTenthsMm)
                .putInt("bottom_tenths_mm", bottomTenthsMm)
                .putInt("direction", direction)
                .putBoolean("flip_horizontal", flip)
                .putBoolean("anti_color", anti)
                .apply();
    }

    private void saveCommonAntiColor(boolean anti) {
        getSharedPreferences(JOB_OPTIONS_PREFS, MODE_PRIVATE).edit()
                .putBoolean("anti_color", anti)
                .apply();
    }

    private void sendPrint() {
        if (printerService == null || printerService.connectedDevice() == null) {
            toast("请先连接打印机");
            requestBluetoothAction(ACTION_CHOOSE_PRINTER);
            return;
        }
        if (!printerService.isPrinterReady()) {
            toast("传输通道已连接，但打印机协议尚未确认；不会盲目发送数据");
            return;
        }
        if (selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE
                && (Math.abs(document.widthMm - 25f) > 0.01f
                || Math.abs(document.heightMm - 15f) > 0.01f)) {
            new AlertDialog.Builder(this)
                    .setTitle("当前实机只安装 25×15 mm 标签")
                    .setMessage("20×10 和 40×30 仅允许预览及编码验证。本轮 P1 实际打印只允许 25×15 mm，未发送任何数据。")
                    .setPositiveButton("切换到 25×15", (dialog, which) -> applyPreset(25f, 15f))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        int widthDots = LabelDocument.mmToDots(document.widthMm, selectedProfile.dpi);
        if (widthDots > selectedProfile.printableWidthDots) {
            new AlertDialog.Builder(this)
                    .setTitle("画布超过打印宽度")
                    .setMessage("当前为 " + widthDots + " 点，设备档案上限为 "
                            + selectedProfile.printableWidthDots + " 点。请缩小画布或选择正确档案。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        try {
            Bitmap bitmap = LabelBitmapRenderer.render(document);
            MonochromeRaster raster = MonochromeRaster.fromBitmap(bitmap, selectedProfile.threshold);
            bitmap.recycle();
            byte[] data;
            if (selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE) {
                DothanJobOptions options = loadDothanJobOptions();
                data = new DothanProtocolEncoder().encode(raster, document.widthMm,
                        document.heightMm, selectedProfile, options);
            } else {
                if (loadDothanJobOptions().antiColor) raster = raster.inverted();
                data = ProtocolRegistry.encoder(selectedProfile)
                        .encode(raster, document.widthMm, document.heightMm, selectedProfile);
            }
            lastJobSummary = "编码完成 · " + selectedProfile.protocol.label + " · "
                    + data.length + " 字节";
            PrintJob job = printerService.enqueue(data, selectedProfile, sha256(data));
            lastJobSummary = job.state.label + " · " + job.message;
            int copies = selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE
                    ? loadDothanJobOptions().copies : 1;
            boolean serialAdvanced = false;
            for (TextElement element : document.elements) {
                if (TextElement.TYPE_SERIAL.equals(element.type)) {
                    element.advanceSerial(copies);
                    serialAdvanced = true;
                }
            }
            if (serialAdvanced) persistAndRedraw();
            renderConnectionStatus();
            toast(job.state == PrintJobState.QUEUED || job.state == PrintJobState.SENDING
                    ? "打印任务已进入可靠队列" : job.message);
        } catch (IllegalArgumentException | OutOfMemoryError error) {
            lastJobSummary = "打印编码失败：" + error.getMessage();
            renderConnectionStatus();
        }
    }

    private void showDiagnostics() {
        if (printerService == null) {
            toast("打印硬件服务尚未就绪");
            return;
        }
        String rememberedAddress = DeviceHistoryStore.lastAddress(this);
        String remembered = rememberedAddress.isEmpty() ? "无"
                : rememberedAddress + " / " + DeviceHistoryStore.lastTransport(this).label
                + " / " + DeviceHistoryStore.lastProfile(this).label;
        String support = "\n\n档案能力："
                + "\n- 支持通道：" + (selectedProfile.supportsSpp ? "SPP " : "")
                + (selectedProfile.supportsBle ? "BLE " : "") + (selectedProfile.supportsUsb ? "USB" : "")
                + "\n- SPP/BLE/USB 分包：" + selectedProfile.sppChunkBytes + " / "
                + selectedProfile.bleChunkBytes + " / " + selectedProfile.usbChunkBytes
                + "\n- 写入节流/重试：" + selectedProfile.writeDelayMs + " ms / " + selectedProfile.maxWriteRetries
                + "\n- 二值化阈值：" + selectedProfile.threshold
                + "\n- 制造商/类型：" + selectedProfile.manufacturer + " / " + selectedProfile.deviceType
                + "\n- 浓度/速度：" + selectedProfile.printDensity + " / " + selectedProfile.printSpeed
                + "\n- 纸张/间隙：" + selectedProfile.paperType.label + " / "
                + String.format(Locale.US, "%.1f", selectedProfile.gapLengthTenthsMm / 10f) + " mm"
                + "\n- 自动出纸/提示音/关机：" + selectedProfile.autoFeed + " / "
                + selectedProfile.beep + " / " + selectedProfile.autoPowerOffMinutes + " 分钟"
                + "\n- 上次成功设备：" + remembered
                + "\n\nP1 使用专用私有帧、0x77 流控和 0x70 完成回执；"
                + "RLE、授权与厂商/耗材解析已提供实验实现，默认路径不自动启用；"
                + "USB Host 已支持 Bulk OUT/可选 Bulk IN，Wi-Fi 暂不宣称兼容。";
        new AlertDialog.Builder(this)
                .setTitle("v" + BuildConfig.VERSION_NAME + " 硬件诊断")
                .setMessage(printerService.diagnostics(selectedProfile) + support)
                .setNeutralButton("查询设备状态", (dialog, which) -> printerService.probeStatus())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showModelConfigDialog() {
        ModelConfigDialog.show(this, scannerController.scanInputMode(), new ModelConfigDialog.Listener() {
            @Override
            public void onSettingsSaved(RecognitionSettings saved) {
                recognitionSettings = saved;
                scannerController.resetSingleDecode();
                recognitionController.closeVlmEngine();
                scannerController.applyDecoderFormats();
                scannerController.startModeDecoding();
                scannerController.applyScanInputModeUi();
                if (scannerController.scanInputMode() == ScanInputMode.LABEL) recognitionController.warmUpLabelOcr();
            }

            @Override
            public void onRestartRequested() {
                restartApplication();
            }
        });
    }

    private void restartApplication() {
        recognitionController.closeEngines();
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            toast("无法获取应用启动入口");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launch);
        overridePendingTransition(0, 0);
    }

    private void showProfileSettings() {
        ProfileSettingsDialog.show(this, printerService, selectedProfile,
                new ProfileSettingsDialog.Listener() {
                    @Override
                    public void onWorkingProfileAdjusted(PrinterProfile profile) {
                        selectedProfile = profile;
                    }

                    @Override
                    public DothanJobOptions loadDothanJobOptions() {
                        return MainActivity.this.loadDothanJobOptions();
                    }

                    @Override
                    public void onCommonAntiColorSaved(boolean anti) {
                        saveCommonAntiColor(anti);
                    }

                    @Override
                    public void onDothanJobOptionsSaved(int copies, int leftTenthsMm, int rightTenthsMm,
                                                        int topTenthsMm, int bottomTenthsMm,
                                                        int direction, boolean flip, boolean anti) {
                        saveDothanJobOptions(copies, leftTenthsMm, rightTenthsMm,
                                topTenthsMm, bottomTenthsMm, direction, flip, anti);
                    }

                    @Override
                    public void onProfileSaved(PrinterProfile effectiveProfile,
                                               @Nullable DeviceCandidate target, AlertDialog dialog) {
                        selectedProfile = effectiveProfile;
                        if (target == null) {
                            ProfileStore.save(MainActivity.this, effectiveProfile);
                        } else {
                            ProfileStore.saveHardwareOnly(MainActivity.this, effectiveProfile);
                            DeviceSettingsStore.save(MainActivity.this, target, effectiveProfile);
                        }
                        document.dpi = effectiveProfile.dpi;
                        document.normalize();
                        refreshDocumentControls();
                        refreshProfileSummary();
                        persistAndRedraw();
                        dialog.dismiss();
                        if (printerService != null && target != null) {
                            printerService.connect(target, effectiveProfile, new BluetoothPrinterManager.Callback() {
                                @Override public void onSuccess(String message) {
                                    if (effectiveProfile.protocol != ProtocolType.DOTHAN_PRIVATE) {
                                        toast("配置已保存，打印机已按新档案重连");
                                        return;
                                    }
                                    printerService.applyP1DeviceSettings(effectiveProfile,
                                            new BluetoothPrinterManager.Callback() {
                                                @Override public void onSuccess(String detail) {
                                                    toast("P1 配置已写入并回读确认");
                                                }
                                                @Override public void onError(String detail) {
                                                    toast("配置已离线保存；P1 回读未确认，请查看诊断");
                                                }
                                            });
                                }
                                @Override public void onError(String message) {
                                    toast("配置已保存；连接服务会继续重试");
                                }
                            });
                        } else {
                            toast("设备配置与协议参数已离线保存");
                        }
                    }
                });
    }

    private void persistAndRedraw() {
        elementEditorController.persistAndRedraw();
    }

    @Nullable
    private static Float parseNumber(String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatNumber(float value) {
        if (value == Math.round(value)) return Integer.toString(Math.round(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(data);
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 16; index++) {
                result.append(String.format(Locale.US, "%02x", value[index] & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeScanner != null && scannerController.embeddedCameraActive()
                && !recognitionController.isCameraSuspendedForProcessing()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            scannerController.startEmbeddedScanner();
        }
    }

    @Override
    protected void onPause() {
        if (barcodeScanner != null) barcodeScanner.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (printerService != null) printerService.removeListener(hardwareListener);
        if (serviceBound) unbindService(hardwareServiceConnection);
        recognitionController.destroy();
        super.onDestroy();
    }

}

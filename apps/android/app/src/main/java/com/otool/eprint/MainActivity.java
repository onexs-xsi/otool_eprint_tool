package com.otool.eprint;

import android.Manifest;
import android.app.Dialog;
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
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
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

import com.google.zxing.client.android.Intents;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;
import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.DeviceHistoryStore;
import com.otool.eprint.device.DeviceSettingsStore;
import com.otool.eprint.device.PaperType;
import com.otool.eprint.device.PrinterIdentity;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.PrinterDiscoveryEngine;
import com.otool.eprint.device.PrinterMatch;
import com.otool.eprint.device.ProfileStore;
import com.otool.eprint.device.TransportType;
import com.otool.eprint.hardware.PrinterHardwareStatus;
import com.otool.eprint.job.PrintJob;
import com.otool.eprint.job.PrintJobState;
import com.otool.eprint.model.DocumentStore;
import com.otool.eprint.model.ComponentLabelFactory;
import com.otool.eprint.model.ComponentTemplate;
import com.otool.eprint.model.ComponentTemplateStore;
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.TimeElementFormat;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.model.WorkModeStore;
import com.otool.eprint.print.BluetoothPrinterManager;
import com.otool.eprint.protocol.MonochromeRaster;
import com.otool.eprint.protocol.ProtocolRegistry;
import com.otool.eprint.protocol.ProtocolType;
import com.otool.eprint.protocol.DothanCompressionMode;
import com.otool.eprint.protocol.DothanJobOptions;
import com.otool.eprint.protocol.DothanProtocolEncoder;
import com.otool.eprint.protocol.DothanRasterTransform;
import com.otool.eprint.render.BarcodeRasterizer;
import com.otool.eprint.render.IconCatalog;
import com.otool.eprint.render.ImageElementRenderer;
import com.otool.eprint.render.LabelBitmapRenderer;
import com.otool.eprint.scan.QrContentParser;
import com.otool.eprint.scan.ScanFormatCatalog;
import com.otool.eprint.scan.ComponentLabelParser;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.SupplierFormat;
import com.otool.eprint.service.PrinterConnectionService;
import com.otool.eprint.service.ConnectionPhase;
import com.otool.eprint.service.ConnectionStatus;
import com.otool.eprint.view.BarcodeModeSwitch;
import com.otool.eprint.view.LabelCanvasView;
import com.otool.eprint.view.SignatureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends AppCompatActivity {
    private static final int ACTION_NONE = 0;
    private static final int ACTION_CHOOSE_PRINTER = 1;
    private static final int ACTION_PRINT = 2;
    private static final float NUDGE_STEP_MM = 0.5f;
    private static final String JOB_OPTIONS_PREFS = "eprint_dothan_job_options";
    private static final float[][] LABEL_SIZE_PRESETS = {
            {20f, 10f},
            {25f, 15f},
            {40f, 30f}
    };

    private LabelDocument document;
    private WorkMode workMode;
    private SupplierFormat supplierFormat = SupplierFormat.AUTO;
    private ComponentRecord componentRecord;
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
    private LinearLayout textEditorCard;
    private EditText widthInput;
    private EditText heightInput;
    private EditText elementXInput;
    private EditText elementYInput;
    private EditText elementWidthInput;
    private EditText elementHeightInput;
    private EditText textInput;
    private EditText fontSizeInput;
    private CheckBox boldCheck;
    private TextView dpiSpinner;
    private Spinner profileSpinner;
    private Spinner labelSizeSpinner;
    private ArrayAdapter<String> labelSizeAdapter;
    private Spinner alignmentSpinner;
    private Spinner workModeSpinner;
    private Spinner supplierSpinner;
    private TextView editorTitle;
    private View rowFont;
    private View rowBarcode;
    private View rowQr;
    private View rowCodeLayout;
    private View rowLine;
    private View rowRect;
    private View rowTime;
    private View rowArc;
    private Spinner barcodeFormatSpinner;
    private Spinner barcodeTextPositionSpinner;
    private Spinner qrFormatSpinner;
    private Spinner qrErrorCorrectionSpinner;
    private Spinner codeQuietZoneSpinner;
    private Spinner codeRotationSpinner;
    private Spinner lineOrientationSpinner;
    private Spinner arcDirectionSpinner;
    private Spinner timeFormatSpinner;
    private EditText lineWidthInput;
    private EditText rectWidthInput;
    private CheckBox rectFilledCheck;
    private CheckBox lockCheck;
    private View rowSerial;
    private EditText serialValueInput;
    private EditText serialStepInput;
    private EditText serialDigitsInput;
    private View undoButton;
    private View redoButton;
    private Button borderStyleButton;
    private View rowImage;
    private EditText imageWidthInput;
    private EditText imageHeightInput;
    private Spinner imageFitSpinner;
    private View rowIcon;
    private Spinner iconStyleSpinner;
    private EditText iconLineWidthInput;
    private View rowTable;
    private EditText tableRowsInput;
    private EditText tableColsInput;
    private EditText tableLineWidthInput;
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private String lastSnapshot;
    private long lastSnapshotAt;
    private boolean restoringSnapshot;
    private LinearLayout componentPanel;
    private TextView componentRecordSummary;
    private TextView modeContextTitle;
    private TextView offlineStatus;
    private Button addTextButton;
    private Button customLabelSizeButton;
    private Button printButton;
    private DecoratedBarcodeView barcodeScanner;
    private TextView qrResultText;
    private TextView qrParseSummary;
    private BarcodeModeSwitch scanModeSwitch;
    private View normalReservedPanel;
    private View componentReservedPanel;
    private LinearLayout elementChipsRow;
    private HorizontalScrollView elementChipsScroll;
    private LinearLayout templateSelectorRow;
    private TextView templateDescription;
    private CheckBox templateBoldCheck;
    private CheckBox templateTraceCheck;
    private CheckBox templateLargeFontCheck;
    private ComponentTemplate componentTemplate = ComponentTemplate.STANDARD;
    private boolean templateBoldHeadline = true;
    private boolean templateShowTrace = true;
    private boolean templateLargeFont;
    private boolean populatingTemplate;
    private String lastScannedContent = "";
    private boolean barcodeScanMode;
    private boolean embeddedCameraActive = true;
    private boolean populatingEditor;
    private boolean populatingProfile;
    private boolean populatingLabelSize;
    private boolean serviceBound;
    private int pendingBluetoothAction;
    private ConnectionStatus lastConnectionStatus = ConnectionStatus.idle();
    private PrinterHardwareStatus lastHardwareStatus;
    private String lastJobSummary = "";
    private Dialog elementEditorDialog;

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
            new ScanContract(), this::handleScanResult);

    private final ActivityResultLauncher<PickVisualMediaRequest> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) importImageToSelected(uri);
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && barcodeScanner != null) startEmbeddedScanner();
                else if (granted) launchScanner();
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

        workMode = WorkModeStore.load(this);
        document = DocumentStore.load(this, workMode);
        selectedProfile = ProfileStore.load(this);
        document.dpi = selectedProfile.dpi;
        document.normalize();
        componentTemplate = ComponentTemplateStore.loadTemplate(this);
        templateBoldHeadline = ComponentTemplateStore.loadBoldHeadline(this);
        templateShowTrace = ComponentTemplateStore.loadShowTrace(this);
        templateLargeFont = ComponentTemplateStore.loadLargeFont(this);
        bindViews();
        applyEdgeToEdgeInsets();
        renderConnectionStatus();
        configureEmbeddedScanner();
        configureSpinners();
        configureCanvas();
        configureActions();
        configureEditor();
        configureTypeEditors();
        configureReservedPanels();
        renderWorkMode();
        refreshDocumentControls();
        refreshProfileSummary();
        refreshElementChips();

        TextElement initial = document.elements.isEmpty() ? null : document.elements.get(0);
        if (initial != null) labelCanvas.select(initial.id);
        showSelectedElement(initial);

        Intent serviceIntent = new Intent(this, PrinterConnectionService.class);
        bindService(serviceIntent, hardwareServiceConnection, Context.BIND_AUTO_CREATE);
        try {
            lastSnapshot = document.toJson().toString();
        } catch (org.json.JSONException ignored) {
            lastSnapshot = null;
        }
        // 0 表示尚未开始修改分组，确保启动后的首次操作立即产生可撤销快照。
        lastSnapshotAt = 0L;
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
        textEditorCard = findViewById(R.id.textEditorCard);
        widthInput = findViewById(R.id.widthInput);
        heightInput = findViewById(R.id.heightInput);
        elementXInput = findViewById(R.id.elementXInput);
        elementYInput = findViewById(R.id.elementYInput);
        elementWidthInput = findViewById(R.id.elementWidthInput);
        elementHeightInput = findViewById(R.id.elementHeightInput);
        textInput = findViewById(R.id.textInput);
        fontSizeInput = findViewById(R.id.fontSizeInput);
        boldCheck = findViewById(R.id.boldCheck);
        dpiSpinner = findViewById(R.id.dpiSpinner);
        profileSpinner = findViewById(R.id.profileSpinner);
        labelSizeSpinner = findViewById(R.id.labelSizeSpinner);
        alignmentSpinner = findViewById(R.id.alignmentSpinner);
        editorTitle = findViewById(R.id.editorTitle);
        rowFont = findViewById(R.id.rowFont);
        rowBarcode = findViewById(R.id.rowBarcode);
        rowQr = findViewById(R.id.rowQr);
        rowCodeLayout = findViewById(R.id.rowCodeLayout);
        rowLine = findViewById(R.id.rowLine);
        rowRect = findViewById(R.id.rowRect);
        rowTime = findViewById(R.id.rowTime);
        rowArc = findViewById(R.id.rowArc);
        barcodeFormatSpinner = findViewById(R.id.barcodeFormatSpinner);
        barcodeTextPositionSpinner = findViewById(R.id.barcodeTextPositionSpinner);
        qrFormatSpinner = findViewById(R.id.qrFormatSpinner);
        qrErrorCorrectionSpinner = findViewById(R.id.qrErrorCorrectionSpinner);
        codeQuietZoneSpinner = findViewById(R.id.codeQuietZoneSpinner);
        codeRotationSpinner = findViewById(R.id.codeRotationSpinner);
        lineOrientationSpinner = findViewById(R.id.lineOrientationSpinner);
        arcDirectionSpinner = findViewById(R.id.arcDirectionSpinner);
        timeFormatSpinner = findViewById(R.id.timeFormatSpinner);
        lineWidthInput = findViewById(R.id.lineWidthInput);
        rectWidthInput = findViewById(R.id.rectWidthInput);
        rectFilledCheck = findViewById(R.id.rectFilledCheck);
        lockCheck = findViewById(R.id.lockCheck);
        rowSerial = findViewById(R.id.rowSerial);
        serialValueInput = findViewById(R.id.serialValueInput);
        serialStepInput = findViewById(R.id.serialStepInput);
        serialDigitsInput = findViewById(R.id.serialDigitsInput);
        undoButton = findViewById(R.id.undoButton);
        redoButton = findViewById(R.id.redoButton);
        borderStyleButton = findViewById(R.id.borderCycleButton);
        rowImage = findViewById(R.id.rowImage);
        imageWidthInput = findViewById(R.id.imageWidthInput);
        imageHeightInput = findViewById(R.id.imageHeightInput);
        imageFitSpinner = findViewById(R.id.imageFitSpinner);
        rowIcon = findViewById(R.id.rowIcon);
        iconStyleSpinner = findViewById(R.id.iconStyleSpinner);
        iconLineWidthInput = findViewById(R.id.iconLineWidthInput);
        rowTable = findViewById(R.id.rowTable);
        tableRowsInput = findViewById(R.id.tableRowsInput);
        tableColsInput = findViewById(R.id.tableColsInput);
        tableLineWidthInput = findViewById(R.id.tableLineWidthInput);
        barcodeScanner = findViewById(R.id.barcodeScanner);
        qrResultText = findViewById(R.id.qrResultText);
        qrParseSummary = findViewById(R.id.qrParseSummary);
        scanModeSwitch = findViewById(R.id.scanModeSwitch);
        workModeSpinner = findViewById(R.id.workModeSpinner);
        supplierSpinner = findViewById(R.id.supplierSpinner);
        componentPanel = findViewById(R.id.componentPanel);
        componentRecordSummary = findViewById(R.id.componentRecordSummary);
        modeContextTitle = findViewById(R.id.modeContextTitle);
        offlineStatus = findViewById(R.id.offlineStatus);
        addTextButton = findViewById(R.id.addTextButton);
        customLabelSizeButton = findViewById(R.id.customLabelSizeButton);
        printButton = findViewById(R.id.printButton);
        if (offlineStatus != null) {
            offlineStatus.setText("● v" + BuildConfig.VERSION_NAME + " · 本机离线工作");
        }
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

    private void configureEmbeddedScanner() {
        if (scanModeSwitch != null) {
            scanModeSwitch.setBarcodeMode(barcodeScanMode);
            scanModeSwitch.setOnModeChangedListener(this::changeScanMode);
        }
        if (barcodeScanner == null) return;
        barcodeScanner.setStatusText("");
        applyDecoderFormats();
        barcodeScanner.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                String text = result == null ? null : result.getText();
                if (text == null || text.trim().isEmpty() || text.equals(lastScannedContent)) return;
                consumeScannedContent(text);
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {}
        });
    }

    private void changeScanMode(boolean barcodeMode) {
        barcodeScanMode = barcodeMode;
        lastScannedContent = "";
        applyDecoderFormats();
        if (qrResultText != null) {
            qrResultText.setText(barcodeMode
                    ? "等待条形码进入取景框…" : "等待二维码进入取景框…");
        }
        if (qrParseSummary != null) {
            qrParseSummary.setText("全部解析在本机完成，不上传扫码内容");
        }
        toast(barcodeMode ? "已切换到条形码识别" : "已切换到二维码识别");
    }

    private void applyDecoderFormats() {
        if (barcodeScanner == null) return;
        barcodeScanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(
                ScanFormatCatalog.formats(barcodeScanMode)));
    }

    private void configureSpinners() {
        if (workModeSpinner != null) {
            ArrayAdapter<WorkMode> modeAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, WorkMode.values());
            modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            workModeSpinner.setAdapter(modeAdapter);
            workModeSpinner.setSelection(workMode.ordinal());
            workModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    WorkMode selected = WorkMode.values()[position];
                    if (selected != workMode) switchWorkMode(selected);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (supplierSpinner != null) {
            ArrayAdapter<SupplierFormat> supplierAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, SupplierFormat.values());
            supplierAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            supplierSpinner.setAdapter(supplierAdapter);
            supplierSpinner.setSelection(supplierFormat.ordinal());
            supplierSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    supplierFormat = SupplierFormat.values()[position];
                    if (workMode == WorkMode.COMPONENT_ORGANIZATION
                            && !lastScannedContent.isEmpty()) {
                        applyComponentScan(lastScannedContent, false);
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (dpiSpinner != null) dpiSpinner.setText(selectedProfile.dpi + " DPI");

        ArrayAdapter<String> alignmentAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"左对齐", "居中", "右对齐"});
        alignmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alignmentSpinner.setAdapter(alignmentAdapter);

        if (barcodeFormatSpinner != null) {
            ArrayAdapter<String> barcodeAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    BarcodeRasterizer.BARCODE_FORMAT_LABELS);
            barcodeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            barcodeFormatSpinner.setAdapter(barcodeAdapter);
        }
        if (barcodeTextPositionSpinner != null) {
            ArrayAdapter<String> textPositionAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"不显示", "上方显示", "下方显示"});
            textPositionAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            barcodeTextPositionSpinner.setAdapter(textPositionAdapter);
        }
        if (qrFormatSpinner != null) {
            ArrayAdapter<String> qrFormatAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    BarcodeRasterizer.TWO_DIMENSIONAL_FORMAT_LABELS);
            qrFormatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            qrFormatSpinner.setAdapter(qrFormatAdapter);
        }
        if (lineOrientationSpinner != null) {
            ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"横向", "纵向"});
            orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            lineOrientationSpinner.setAdapter(orientationAdapter);
        }
        if (timeFormatSpinner != null) {
            ArrayAdapter<TimeElementFormat> timeAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, TimeElementFormat.values());
            timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            timeFormatSpinner.setAdapter(timeAdapter);
        }
        if (qrErrorCorrectionSpinner != null) {
            ArrayAdapter<String> qrCorrectionAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"L · 低", "M · 中", "Q · 高", "H · 最高"});
            qrCorrectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            qrErrorCorrectionSpinner.setAdapter(qrCorrectionAdapter);
        }
        if (codeQuietZoneSpinner != null) {
            ArrayAdapter<String> quietZoneAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"0 码元", "2 码元", "4 码元"});
            quietZoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            codeQuietZoneSpinner.setAdapter(quietZoneAdapter);
        }
        if (codeRotationSpinner != null) {
            ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"0°", "90°", "180°", "270°"});
            rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            codeRotationSpinner.setAdapter(rotationAdapter);
        }
        if (arcDirectionSpinner != null) {
            ArrayAdapter<String> arcAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"上弧 ∩", "下弧 ∪"});
            arcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            arcDirectionSpinner.setAdapter(arcAdapter);
        }
        if (imageFitSpinner != null) {
            ArrayAdapter<String> fitAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, ImageElementRenderer.FIT_MODE_LABELS);
            fitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            imageFitSpinner.setAdapter(fitAdapter);
        }
        if (iconStyleSpinner != null) {
            ArrayAdapter<String> iconAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, IconCatalog.LABELS);
            iconAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            iconStyleSpinner.setAdapter(iconAdapter);
        }

        ArrayAdapter<PrinterProfile> profileAdapter = new ArrayAdapter<>(this,
                R.layout.spinner_item_compact, DeviceCatalog.profiles());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        populatingProfile = true;
        profileSpinner.setSelection(DeviceCatalog.indexOf(selectedProfile.id));
        populatingProfile = false;
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!populatingProfile) applyProfile(ProfileStore.load(MainActivity.this,
                        DeviceCatalog.profiles().get(position)), false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        configureLabelSizeSpinner();
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
        undoStack.clear();
        redoStack.clear();
        try {
            lastSnapshot = document.toJson().toString();
        } catch (org.json.JSONException ignored) {
            lastSnapshot = null;
        }
        lastSnapshotAt = 0L;
        refreshUndoState();
        labelCanvas.setDocument(document);
        TextElement first = document.elements.isEmpty() ? null : document.elements.get(0);
        labelCanvas.select(first == null ? null : first.id);
        showSelectedElement(first);
        refreshDocumentControls();
        renderWorkMode();
        persistAndRedraw();
        if (selected == WorkMode.COMPONENT_ORGANIZATION && !lastScannedContent.isEmpty()) {
            applyComponentScan(lastScannedContent, false);
        }
        toast("已切换到“" + selected.label + "”模式");
    }

    private void renderWorkMode() {
        boolean component = workMode == WorkMode.COMPONENT_ORGANIZATION;
        if (componentPanel != null) componentPanel.setVisibility(component ? View.VISIBLE : View.GONE);
        if (addTextButton != null) addTextButton.setVisibility(component ? View.GONE : View.VISIBLE);
        if (normalReservedPanel != null) {
            normalReservedPanel.setVisibility(component ? View.GONE : View.VISIBLE);
        }
        if (componentReservedPanel != null) {
            componentReservedPanel.setVisibility(component ? View.VISIBLE : View.GONE);
        }
        if (component) renderTemplatePanel();
        if (modeContextTitle != null) modeContextTitle.setText(component
                ? "器件整理 · 标签工作台" : "普通标签工作台");
        TextElement selected = labelCanvas == null ? null : labelCanvas.getSelectedElement();
        if (textEditorCard != null) {
            textEditorCard.setVisibility(!component && selected != null ? View.VISIBLE : View.GONE);
        }
        if (componentRecordSummary != null && componentRecord == null) {
            componentRecordSummary.setText("等待供应商贴纸二维码；识别后在本机生成器件标签");
        }
    }

    private void configureActions() {
        findViewById(R.id.scanButton).setOnClickListener(view -> requestScan());
        if (addTextButton != null) {
            addTextButton.setOnClickListener(view -> showElementEditorDialog());
        }
        if (customLabelSizeButton != null) {
            customLabelSizeButton.setOnClickListener(view -> showCustomLabelSizeDialog());
        }
        findViewById(R.id.deleteTextButton).setOnClickListener(view -> deleteSelected());
        View applySize = findViewById(R.id.applySizeButton);
        if (applySize != null) applySize.setOnClickListener(view -> applyCanvasSize());
        View presetSmall = findViewById(R.id.presetSmallButton);
        if (presetSmall != null) presetSmall.setOnClickListener(view -> applyPreset(20f, 10f));
        View presetMedium = findViewById(R.id.presetMediumButton);
        if (presetMedium != null) presetMedium.setOnClickListener(view -> applyPreset(25f, 15f));
        View presetLarge = findViewById(R.id.presetLargeButton);
        if (presetLarge != null) presetLarge.setOnClickListener(view -> applyPreset(40f, 30f));
        findViewById(R.id.previewButton).setOnClickListener(view -> showPreview());
        printButton.setOnClickListener(view -> requestBluetoothAction(ACTION_PRINT));
        findViewById(R.id.connectButton).setOnClickListener(view -> requestBluetoothAction(ACTION_CHOOSE_PRINTER));
        findViewById(R.id.diagnosticsButton).setOnClickListener(view -> showDiagnostics());
        findViewById(R.id.profileSettingsButton).setOnClickListener(view -> showProfileSettings());
        findViewById(R.id.disconnectButton).setOnClickListener(view -> {
            if (printerService != null) printerService.disconnect();
        });
        findViewById(R.id.cancelJobButton).setOnClickListener(view -> {
            if (printerService == null || !printerService.cancelActiveOrLastQueuedJob()) {
                toast("当前没有可取消的任务");
            } else {
                toast("任务已取消；活动传输会重置通道");
            }
        });
        findViewById(R.id.retryJobButton).setOnClickListener(view -> {
            if (printerService == null || !printerService.retryLastFailedJob()) toast("没有可重试的失败任务");
            else toast("失败任务已重新入队");
        });
        View fontDecrease = findViewById(R.id.fontDecreaseButton);
        if (fontDecrease != null) fontDecrease.setOnClickListener(view -> stepFontSize(-1f));
        View fontIncrease = findViewById(R.id.fontIncreaseButton);
        if (fontIncrease != null) fontIncrease.setOnClickListener(view -> stepFontSize(1f));
        bindNudge(R.id.nudgeLeftButton, -NUDGE_STEP_MM, 0f);
        bindNudge(R.id.nudgeRightButton, NUDGE_STEP_MM, 0f);
        bindNudge(R.id.nudgeUpButton, 0f, -NUDGE_STEP_MM);
        bindNudge(R.id.nudgeDownButton, 0f, NUDGE_STEP_MM);
        View centerHorizontal = findViewById(R.id.centerHorizontalButton);
        if (centerHorizontal != null) {
            centerHorizontal.setOnClickListener(view -> centerSelectedHorizontally());
        }
        View centerVertical = findViewById(R.id.centerVerticalButton);
        if (centerVertical != null) {
            centerVertical.setOnClickListener(view -> centerSelectedVertically());
        }
        View duplicate = findViewById(R.id.duplicateTextButton);
        if (duplicate != null) duplicate.setOnClickListener(view -> duplicateSelected());
    }

    /** 把现有完整属性面板移入近全屏对话框，并增加通用坐标/尺寸编辑。 */
    private void showElementEditorDialog() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) {
            toast("请先选择要编辑的画布元素");
            return;
        }
        if (elementEditorDialog != null && elementEditorDialog.isShowing()) return;
        if (!(textEditorCard.getParent() instanceof ViewGroup)) return;

        showSelectedElement(selected);
        ViewGroup originalParent = (ViewGroup) textEditorCard.getParent();
        int originalIndex = originalParent.indexOfChild(textEditorCard);
        ViewGroup.LayoutParams originalParams = textEditorCard.getLayoutParams();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFF9F7F0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("编辑元素 · " + titleFor(selected));
        title.setTextColor(ContextCompat.getColor(this, R.color.eprint_text));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button done = new Button(this);
        done.setText("完成");
        done.setAllCaps(false);
        done.setBackgroundResource(R.drawable.button_primary);
        done.setTextColor(ContextCompat.getColor(this, R.color.eprint_paper));
        header.addView(done, new LinearLayout.LayoutParams(dp(100), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView geometryHint = new TextView(this);
        geometryHint.setText(elementXInput == null
                ? "位置与尺寸（mm）· 可输入负坐标，元素可超出标签边界"
                : "所有属性即时生效；X/Y/宽/高使用 mm，可输入负坐标并允许越界");
        geometryHint.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        geometryHint.setTextSize(13);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        root.addView(geometryHint, hintParams);

        if (elementXInput == null) {
            LinearLayout geometryRow = new LinearLayout(this);
            geometryRow.setGravity(android.view.Gravity.BOTTOM);
            EditText xInput = geometryInput(selected.xMm);
            EditText yInput = geometryInput(selected.yMm);
            EditText widthInput = geometryInput(selected.widthMm);
            EditText heightInput = geometryInput(selected.heightMm);
            geometryRow.addView(geometryField("X", xInput), weightedGeometryParams());
            geometryRow.addView(geometryField("Y", yInput), weightedGeometryParams());
            geometryRow.addView(geometryField("宽", widthInput), weightedGeometryParams());
            geometryRow.addView(geometryField("高", heightInput), weightedGeometryParams());
            Button applyGeometry = new Button(this);
            applyGeometry.setText("应用位置/尺寸");
            applyGeometry.setAllCaps(false);
            applyGeometry.setBackgroundResource(R.drawable.button_secondary);
            LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(dp(150), dp(48));
            applyParams.setMarginStart(dp(8));
            geometryRow.addView(applyGeometry, applyParams);
            applyGeometry.setOnClickListener(view -> applyEditorGeometry(
                    xInput, yInput, widthInput, heightInput));
            root.addView(geometryRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout editorHost = new FrameLayout(this);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostParams.topMargin = dp(8);
        root.addView(editorHost, hostParams);
        originalParent.removeView(textEditorCard);
        editorHost.addView(textEditorCard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        textEditorCard.setVisibility(View.VISIBLE);

        Dialog dialog = new Dialog(this);
        elementEditorDialog = dialog;
        dialog.setContentView(root);
        done.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (textEditorCard.getParent() instanceof ViewGroup) {
                ((ViewGroup) textEditorCard.getParent()).removeView(textEditorCard);
            }
            int targetIndex = Math.min(originalIndex, originalParent.getChildCount());
            originalParent.addView(textEditorCard, targetIndex, originalParams);
            elementEditorDialog = null;
            showSelectedElement(labelCanvas.getSelectedElement());
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            window.setLayout(Math.round(metrics.widthPixels * 0.94f),
                    Math.round(metrics.heightPixels * 0.92f));
        }
    }

    private EditText geometryInput(float value) {
        EditText input = new EditText(this);
        input.setText(formatNumber(value));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setGravity(android.view.Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private LinearLayout geometryField(String label, EditText input) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        TextView caption = new TextView(this);
        caption.setText(label + " mm");
        caption.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        caption.setGravity(android.view.Gravity.CENTER);
        field.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        field.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return field;
    }

    private LinearLayout.LayoutParams weightedGeometryParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(6));
        return params;
    }

    private void applyEditorGeometry(EditText xInput, EditText yInput,
                                     EditText widthInput, EditText heightInput) {
        TextElement selected = labelCanvas.getSelectedElement();
        Float x = parseNumber(xInput.getText().toString());
        Float y = parseNumber(yInput.getText().toString());
        Float width = parseNumber(widthInput.getText().toString());
        Float height = parseNumber(heightInput.getText().toString());
        if (selected == null || x == null || y == null || width == null || height == null
                || width <= 0f || height <= 0f) {
            toast("请输入有效的位置和大于 0 的宽高");
            return;
        }
        selected.xMm = x;
        selected.yMm = y;
        selected.widthMm = width;
        selected.heightMm = height;
        persistAndRedraw();
        xInput.setText(formatNumber(selected.xMm));
        yInput.setText(formatNumber(selected.yMm));
        widthInput.setText(formatNumber(selected.widthMm));
        heightInput.setText(formatNumber(selected.heightMm));
        toast("位置与尺寸已应用");
    }

    private void bindNudge(int buttonId, float dxMm, float dyMm) {
        View button = findViewById(buttonId);
        if (button != null) button.setOnClickListener(view -> nudgeSelected(dxMm, dyMm));
    }

    private void nudgeSelected(float dxMm, float dyMm) {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.xMm += dxMm;
        selected.yMm += dyMm;
        persistAndRedraw();
    }

    private void centerSelectedHorizontally() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.xMm = (document.widthMm - selected.widthMm) / 2f;
        persistAndRedraw();
    }

    private void centerSelectedVertically() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.yMm = (document.heightMm - selected.heightMm) / 2f;
        persistAndRedraw();
    }

    private void stepFontSize(float deltaPt) {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null || !selected.isTextual()) return;
        selected.fontSizePt = LabelDocument.clamp(selected.fontSizePt + deltaPt, 6f, 72f);
        populatingEditor = true;
        fontSizeInput.setText(formatNumber(selected.fontSizePt));
        populatingEditor = false;
        persistAndRedraw();
    }

    private void duplicateSelected() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) {
            toast("请先选择要复制的元素");
            return;
        }
        TextElement copy = selected.copyWithNewId();
        copy.yMm = selected.yMm + 3f;
        document.elements.add(copy);
        document.normalize();
        labelCanvas.select(copy.id);
        showSelectedElement(copy);
        persistAndRedraw();
    }

    private void configureReservedPanels() {
        normalReservedPanel = findViewById(R.id.normalReservedPanel);
        componentReservedPanel = findViewById(R.id.componentReservedPanel);
        elementChipsRow = findViewById(R.id.elementChipsRow);
        elementChipsScroll = findViewById(R.id.elementChipsScroll);
        templateDescription = findViewById(R.id.templateDescription);
        templateBoldCheck = findViewById(R.id.templateBoldCheck);
        templateTraceCheck = findViewById(R.id.templateTraceCheck);
        templateLargeFontCheck = findViewById(R.id.templateLargeFontCheck);
        bindInsert(R.id.insertTextButton, TextElement.TYPE_TEXT);
        bindInsert(R.id.insertBarcodeButton, TextElement.TYPE_BARCODE);
        bindInsert(R.id.insertQrButton, TextElement.TYPE_QRCODE);
        bindInsert(R.id.insertLineButton, TextElement.TYPE_LINE);
        bindInsert(R.id.insertRectButton, TextElement.TYPE_RECT);
        bindInsert(R.id.insertTimeButton, TextElement.TYPE_TIME);
        bindInsert(R.id.insertSerialButton, TextElement.TYPE_SERIAL);
        bindInsert(R.id.insertArcTextButton, TextElement.TYPE_ARCTEXT);
        bindInsert(R.id.insertIconButton, TextElement.TYPE_ICON);
        bindInsert(R.id.insertTableButton, TextElement.TYPE_TABLE);
        View insertImage = findViewById(R.id.insertImageButton);
        if (insertImage != null) insertImage.setOnClickListener(view -> insertImageElement());
        View insertSignature = findViewById(R.id.insertSignatureButton);
        if (insertSignature != null) insertSignature.setOnClickListener(view -> showSignatureDialog());
        View replaceImage = findViewById(R.id.replaceImageButton);
        if (replaceImage != null) replaceImage.setOnClickListener(view -> launchImagePicker());
        if (borderStyleButton != null) {
            borderStyleButton.setOnClickListener(view -> showBorderStyleDialog());
        }
        if (undoButton != null) undoButton.setOnClickListener(view -> undo());
        if (redoButton != null) redoButton.setOnClickListener(view -> redo());
        View clear = findViewById(R.id.clearCanvasButton);
        if (clear != null) clear.setOnClickListener(view -> clearCanvasElements());
        templateSelectorRow = findViewById(R.id.templateSelectorRow);
        if (templateBoldCheck != null) {
            templateBoldCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateBoldHeadline = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
        if (templateTraceCheck != null) {
            templateTraceCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateShowTrace = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
        if (templateLargeFontCheck != null) {
            templateLargeFontCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateLargeFont = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
    }

    private void bindInsert(int buttonId, String type) {
        View button = findViewById(buttonId);
        if (button != null) button.setOnClickListener(view -> insertElement(type));
    }

    private void insertElement(String type) {
        TextElement element = document.addElement(type);
        labelCanvas.select(element.id);
        showSelectedElement(element);
        persistAndRedraw();
    }

    private void insertImageElement() {
        insertElement(TextElement.TYPE_IMAGE);
        launchImagePicker();
    }

    private void launchImagePicker() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)) {
            toast("请先选中一个图片元素");
            return;
        }
        imagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void importImageToSelected(android.net.Uri uri) {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)) return;
        String path = copyImageToInternal(uri);
        if (path == null) {
            toast("图片读取失败");
            return;
        }
        selected.text = path;
        // 按图片宽高比修正盒高，避免拉伸
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            selected.heightMm = LabelDocument.clamp(
                    selected.widthMm * bounds.outHeight / (float) bounds.outWidth,
                    3f, LabelDocument.MAX_ELEMENT_SIZE_MM);
        }
        ImageElementRenderer.invalidate(path);
        persistAndRedraw();
        toast("图片已加入画布");
    }

    @Nullable
    private String copyImageToInternal(android.net.Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) return null;
            File directory = new File(getFilesDir(), "images");
            if (!directory.exists() && !directory.mkdirs()) return null;
            File file = new File(directory, "img_" + System.currentTimeMillis() + ".jpg");
            try (OutputStream out = new FileOutputStream(file)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            }
            bitmap.recycle();
            return file.getAbsolutePath();
        } catch (IOException | OutOfMemoryError error) {
            return null;
        }
    }

    private void showSignatureDialog() {
        SignatureView board = new SignatureView(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手写签名（保存后生成图片元素）")
                .setView(board)
                .setNeutralButton("清除", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> board.clear());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Bitmap bitmap = board.createBitmap();
                if (bitmap == null) {
                    toast("签名为空");
                    return;
                }
                Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                saveButton.setEnabled(false);
                File directory = new File(getFilesDir(), "images");
                String fileName = "sign_" + System.currentTimeMillis() + ".png";
                new Thread(() -> {
                    String path = SignatureView.saveBitmap(bitmap, directory, fileName);
                    runOnUiThread(() -> {
                        if (path == null) {
                            saveButton.setEnabled(true);
                            toast("签名保存失败");
                            return;
                        }
                        TextElement element = document.addElement(TextElement.TYPE_IMAGE);
                        element.text = path;
                        element.heightMm = LabelDocument.clamp(element.widthMm / 2f,
                                3f, LabelDocument.MAX_ELEMENT_SIZE_MM);
                        labelCanvas.select(element.id);
                        showSelectedElement(element);
                        persistAndRedraw();
                        dialog.dismiss();
                    });
                }, "eprint-signature-save").start();
            });
        });
        dialog.show();
    }

    private void showBorderStyleDialog() {
        String[] styles = {"无边框", "单线边框", "双线边框"};
        new AlertDialog.Builder(this)
                .setTitle("标签边框")
                .setSingleChoiceItems(styles, document.borderStyle, (dialog, which) -> {
                    document.borderStyle = which;
                    persistAndRedraw();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshBorderStyleButton() {
        if (borderStyleButton == null) return;
        borderStyleButton.setText(document.borderStyle == 0 ? "边框:无"
                : document.borderStyle == 1 ? "边框:单" : "边框:双");
    }

    private void trackSnapshot() {
        if (restoringSnapshot) return;
        String json;
        try {
            json = document.toJson().toString();
        } catch (org.json.JSONException error) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        // 与上一快照间隔小于 800ms 的连续修改合并为一步；栈上限 50
        if (lastSnapshot == null) {
            lastSnapshot = json;
            lastSnapshotAt = 0L;
        } else if (!lastSnapshot.equals(json)) {
            if (lastSnapshotAt == 0L || now - lastSnapshotAt > 800) {
                if (undoStack.size() >= 50) undoStack.removeLast();
                undoStack.push(lastSnapshot);
            }
            // 任意新修改都会分叉历史，连续修改仍在 800ms 窗口内合并。
            redoStack.clear();
            lastSnapshot = json;
            lastSnapshotAt = now;
        }
        refreshUndoState();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("没有可撤销的操作");
            return;
        }
        restoringSnapshot = true;
        redoStack.push(lastSnapshot);
        lastSnapshot = undoStack.pop();
        restoreSnapshot(lastSnapshot);
        lastSnapshotAt = 0L;
        restoringSnapshot = false;
        refreshUndoState();
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            toast("没有可重做的操作");
            return;
        }
        restoringSnapshot = true;
        undoStack.push(lastSnapshot);
        lastSnapshot = redoStack.pop();
        restoreSnapshot(lastSnapshot);
        lastSnapshotAt = 0L;
        restoringSnapshot = false;
        refreshUndoState();
    }

    private void restoreSnapshot(String json) {
        document = LabelDocument.fromJson(json);
        document.dpi = selectedProfile.dpi;
        document.normalize();
        DocumentStore.save(this, workMode, document);
        labelCanvas.setDocument(document);
        TextElement first = document.elements.isEmpty() ? null : document.elements.get(0);
        labelCanvas.select(first == null ? null : first.id);
        showSelectedElement(first);
        refreshDocumentControls();
        labelCanvas.invalidate();
        updateCompatibilityWarning();
        refreshElementChips();
    }

    private void refreshUndoState() {
        if (undoButton != null) undoButton.setAlpha(undoStack.isEmpty() ? 0.4f : 1f);
        if (redoButton != null) redoButton.setAlpha(redoStack.isEmpty() ? 0.4f : 1f);
    }

    private void selectTemplate(ComponentTemplate template) {
        if (componentTemplate == template) return;
        componentTemplate = template;
        saveTemplateOptions();
        renderTemplatePanel();
        regenerateComponentDocument();
    }

    private void saveTemplateOptions() {
        ComponentTemplateStore.save(this, componentTemplate, templateBoldHeadline,
                templateShowTrace, templateLargeFont);
    }

    private void renderTemplatePanel() {
        if (templateDescription == null) return;
        populatingTemplate = true;
        templateDescription.setText(componentTemplate.label + " · " + componentTemplate.description);
        if (templateBoldCheck != null) templateBoldCheck.setChecked(templateBoldHeadline);
        if (templateTraceCheck != null) templateTraceCheck.setChecked(templateShowTrace);
        if (templateLargeFontCheck != null) templateLargeFontCheck.setChecked(templateLargeFont);
        populatingTemplate = false;
        rebuildTemplateSelector();
    }

    private void rebuildTemplateSelector() {
        if (templateSelectorRow == null) return;
        templateSelectorRow.removeAllViews();
        ComponentRecord preview = componentRecord != null ? componentRecord : sampleComponentRecord();
        for (ComponentTemplate template : ComponentTemplate.values()) {
            templateSelectorRow.addView(buildTemplateTile(template, preview));
        }
    }

    /** 用真实扫描数据（未扫码时用立创样本）把每个模板渲染成可点选的缩略图。 */
    private View buildTemplateTile(ComponentTemplate template, ComponentRecord record) {
        boolean selected = componentTemplate == template;
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xFFFFFFFF);
        border.setCornerRadius(dp(6));
        border.setStroke(dp(selected ? 2 : 1), ContextCompat.getColor(this,
                selected ? R.color.eprint_coral : R.color.eprint_line));
        tile.setBackground(border);
        tile.setPadding(dp(4), dp(4), dp(4), dp(2));

        LabelDocument previewDocument = ComponentLabelFactory.create(record, 203, 25f, 15f,
                template, templateBoldHeadline, templateShowTrace, templateLargeFont);
        ImageView image = new ImageView(this);
        image.setImageBitmap(LabelBitmapRenderer.render(previewDocument));
        tile.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView caption = new TextView(this);
        caption.setText(template.label);
        caption.setTextSize(10f);
        caption.setGravity(android.view.Gravity.CENTER);
        caption.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.eprint_coral_dark : R.color.eprint_muted));
        tile.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tile.setContentDescription("模板" + template.label + (selected ? "，已选择" : ""));
        tile.setOnClickListener(view -> selectTemplate(template));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginEnd(dp(6));
        tile.setLayoutParams(params);
        return tile;
    }

    private static ComponentRecord sampleComponentRecord() {
        return ComponentLabelParser.parse(
                "{on:SO26063011291,pc:C45359894,pm:CGA0402C0G120J500GT,"
                        + "qty:100,mc:,cc:1,pdi:222571873,hp:12}",
                SupplierFormat.AUTO);
    }

    private void regenerateComponentDocument() {
        if (workMode != WorkMode.COMPONENT_ORGANIZATION || componentRecord == null) return;
        document = ComponentLabelFactory.create(componentRecord, selectedProfile.dpi,
                document.widthMm, document.heightMm, componentTemplate,
                templateBoldHeadline, templateShowTrace, templateLargeFont);
        labelCanvas.setDocument(document);
        TextElement first = document.elements.isEmpty() ? null : document.elements.get(0);
        labelCanvas.select(first == null ? null : first.id);
        showSelectedElement(first);
        refreshDocumentControls();
        persistAndRedraw();
    }

    private void clearCanvasElements() {
        if (document.elements.isEmpty()) {
            toast("画布上没有元素");
            return;
        }
        document.elements.clear();
        labelCanvas.select(null);
        showSelectedElement(null);
        persistAndRedraw();
        toast("已清空画布元素");
    }

    private static String chipLabel(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_LINE:
                return "v".equals(element.format) ? "竖线" : "横线";
            case TextElement.TYPE_RECT:
                return element.filled ? "矩形·填充" : "矩形";
            case TextElement.TYPE_TIME:
                return "时间";
            case TextElement.TYPE_SERIAL:
                return "序列:" + element.serialText();
            case TextElement.TYPE_ARCTEXT:
                String arc = element.text == null ? "" : element.text.trim();
                return compactElementLabel("弧形", arc, 10);
            case TextElement.TYPE_IMAGE:
                return "图片";
            case TextElement.TYPE_ICON:
                return "图标:" + IconCatalog.LABELS[IconCatalog.indexOf(element.format)];
            case TextElement.TYPE_TABLE:
                return "表格" + element.tableRows + "×" + element.tableCols;
            case TextElement.TYPE_BARCODE:
                String content = element.text == null ? "" : element.text.trim();
                return "一维码 · " + BarcodeRasterizer.BARCODE_FORMAT_LABELS[
                        barcodeFormatIndex(element.format)] + " · " + content.length() + "字";
            case TextElement.TYPE_QRCODE:
                String qrContent = element.text == null ? "" : element.text.trim();
                return "二维码 · " + BarcodeRasterizer.TWO_DIMENSIONAL_FORMAT_LABELS[
                        twoDimensionalFormatIndex(element.format)] + " · " + qrContent.length() + "字";
            case TextElement.TYPE_TEXT:
            default:
                String label = element.text == null ? "" : element.text.trim();
                if (label.isEmpty()) label = "（空文本）";
                return (element.locked ? "🔒" : "")
                        + compactElementLabel("文本", label, 12);
        }
    }

    private static String compactElementLabel(String type, String content, int visibleLimit) {
        if (content == null || content.isEmpty()) return type + " · 空";
        return content.length() <= visibleLimit ? type + " · " + content
                : type + " · " + content.length() + "字";
    }

    private void refreshElementChips() {
        if (elementChipsRow == null) return;
        elementChipsRow.removeAllViews();
        TextElement selected = labelCanvas == null ? null : labelCanvas.getSelectedElement();
        Button activeChip = null;
        for (TextElement element : document.elements) {
            String label = chipLabel(element);
            Button chip = new Button(this);
            chip.setText(label);
            chip.setAllCaps(false);
            chip.setTextSize(11);
            chip.setSingleLine(true);
            chip.setIncludeFontPadding(true);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(10), 0, dp(10), 0);
            chip.setMinHeight(0);
            chip.setMinWidth(0);
            boolean active = selected != null && element.id.equals(selected.id);
            if (active) activeChip = chip;
            chip.setBackgroundResource(active
                    ? R.drawable.element_chip_primary : R.drawable.element_chip_secondary);
            chip.setTextColor(ContextCompat.getColor(this,
                    active ? R.color.eprint_paper : R.color.eprint_text));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            params.setMarginEnd(dp(6));
            chip.setLayoutParams(params);
            final String elementId = element.id;
            chip.setOnClickListener(view -> {
                labelCanvas.select(elementId);
                showSelectedElement(labelCanvas.getSelectedElement());
                refreshElementChips();
            });
            elementChipsRow.addView(chip);
        }
        if (elementChipsScroll != null && activeChip != null) {
            Button target = activeChip;
            elementChipsScroll.post(() -> {
                int left = Math.max(0, target.getLeft() - dp(6));
                elementChipsScroll.smoothScrollTo(left, 0);
            });
        }
    }

    private void configureEditor() {
        textInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected != null) {
                    selected.text = editable.toString();
                    persistAndRedraw();
                }
            }
        });
        fontSizeInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected != null && value != null) {
                    selected.fontSizePt = LabelDocument.clamp(value, 6f, 72f);
                    persistAndRedraw();
                }
            }
        });
        boldCheck.setOnCheckedChangeListener((button, checked) -> {
            if (populatingEditor) return;
            TextElement selected = labelCanvas.getSelectedElement();
            if (selected != null) {
                selected.bold = checked;
                persistAndRedraw();
            }
        });
        alignmentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected != null) {
                    selected.alignment = position;
                    persistAndRedraw();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** 类型特有属性行（码制/边长/线宽/填充/时间格式/锁定）的监听。 */
    private void configureTypeEditors() {
        bindElementGeometry(elementXInput, 0);
        bindElementGeometry(elementYInput, 1);
        bindElementGeometry(elementWidthInput, 2);
        bindElementGeometry(elementHeightInput, 3);
        if (barcodeFormatSpinner != null) {
            barcodeFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected == null || !TextElement.TYPE_BARCODE.equals(selected.type)) return;
                    selected.format = BarcodeRasterizer.BARCODE_FORMATS[position];
                    if (!BarcodeRasterizer.isValid(selected.text, selected.format)) {
                        toast("当前内容不符合 " + selected.format + " 码制，将显示为占位框");
                    }
                    persistAndRedraw();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (barcodeTextPositionSpinner != null) {
            barcodeTextPositionSpinner.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_BARCODE.equals(selected.type)) {
                        selected.barcodeTextPosition = new String[]{"none", "top", "bottom"}[position];
                        selected.showLabel = position != 0;
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (qrFormatSpinner != null) {
            qrFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_QRCODE.equals(selected.type)) {
                        selected.format = BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS[position];
                        if (qrErrorCorrectionSpinner != null) {
                            qrErrorCorrectionSpinner.setEnabled("QR_CODE".equals(selected.format));
                        }
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (qrErrorCorrectionSpinner != null) {
            qrErrorCorrectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_QRCODE.equals(selected.type)) {
                        selected.qrErrorCorrection = new String[]{"L", "M", "Q", "H"}[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (codeQuietZoneSpinner != null) {
            codeQuietZoneSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && (TextElement.TYPE_BARCODE.equals(selected.type)
                            || TextElement.TYPE_QRCODE.equals(selected.type))) {
                        selected.quietZoneModules = new int[]{0, 2, 4}[position];
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (codeRotationSpinner != null) {
            codeRotationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && (TextElement.TYPE_BARCODE.equals(selected.type)
                            || TextElement.TYPE_QRCODE.equals(selected.type))) {
                        selected.rotationDegrees = position * 90;
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (lineOrientationSpinner != null) {
            lineOrientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected == null || !TextElement.TYPE_LINE.equals(selected.type)) return;
                    selected.format = position == 1 ? "v" : "h";
                    if ("v".equals(selected.format)) {
                        selected.heightMm = Math.max(selected.heightMm, selected.widthMm);
                    }
                    persistAndRedraw();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        bindLineWidth(lineWidthInput, TextElement.TYPE_LINE);
        bindLineWidth(rectWidthInput, TextElement.TYPE_RECT);
        if (rectFilledCheck != null) {
            rectFilledCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected != null && TextElement.TYPE_RECT.equals(selected.type)) {
                    selected.filled = checked;
                    persistAndRedraw();
                }
            });
        }
        if (timeFormatSpinner != null) {
            timeFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_TIME.equals(selected.type)) {
                        selected.format = TimeElementFormat.values()[position].pattern;
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (arcDirectionSpinner != null) {
            arcDirectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_ARCTEXT.equals(selected.type)) {
                        selected.format = position == 1 ? "down" : "up";
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (lockCheck != null) {
            lockCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected != null) {
                    selected.locked = checked;
                    persistAndRedraw();
                    toast(checked ? "已锁定，画布拖拽与微调失效" : "已解锁");
                }
            });
        }
        bindSerialInt(serialValueInput, 0);
        bindSerialInt(serialStepInput, 1);
        bindSerialInt(serialDigitsInput, 2);
        if (iconStyleSpinner != null) {
            iconStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_ICON.equals(selected.type)) {
                        selected.format = IconCatalog.IDS[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (imageFitSpinner != null) {
            imageFitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = labelCanvas.getSelectedElement();
                    if (selected != null && TextElement.TYPE_IMAGE.equals(selected.type)) {
                        selected.format = ImageElementRenderer.FIT_MODE_IDS[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        bindBoxSize(imageWidthInput, true);
        bindBoxSize(imageHeightInput, false);
        bindLineWidth(iconLineWidthInput, TextElement.TYPE_ICON);
        bindLineWidth(tableLineWidthInput, TextElement.TYPE_TABLE);
        bindTableInt(tableRowsInput, true);
        bindTableInt(tableColsInput, false);
    }

    /** 通用位置/尺寸字段：0=X、1=Y、2=宽、3=高。 */
    private void bindElementGeometry(@Nullable EditText input, int field) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected == null || value == null) return;
                if (field == 0) {
                    selected.xMm = LabelDocument.clamp(value,
                            -LabelDocument.MAX_ELEMENT_OFFSET_MM,
                            LabelDocument.MAX_ELEMENT_OFFSET_MM);
                } else if (field == 1) {
                    selected.yMm = LabelDocument.clamp(value,
                            -LabelDocument.MAX_ELEMENT_OFFSET_MM,
                            LabelDocument.MAX_ELEMENT_OFFSET_MM);
                } else if (field == 2) {
                    selected.widthMm = LabelDocument.clamp(value,
                            LabelDocument.MIN_ELEMENT_SIZE_MM,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                } else {
                    selected.heightMm = LabelDocument.clamp(value,
                            LabelDocument.MIN_ELEMENT_SIZE_MM,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                }
                persistAndRedraw();
            }
        });
    }

    /** 图片盒尺寸：width 为 true 改 widthMm，false 改 heightMm。 */
    private void bindBoxSize(@Nullable EditText input, boolean width) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)
                        || value == null) return;
                if (width) {
                    selected.widthMm = LabelDocument.clamp(value, 1f,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                } else {
                    selected.heightMm = LabelDocument.clamp(value, 1f,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                }
                persistAndRedraw();
            }
        });
    }

    private void bindTableInt(@Nullable EditText input, boolean rows) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected == null || !TextElement.TYPE_TABLE.equals(selected.type)) return;
                try {
                    int value = Integer.parseInt(editable.toString().trim());
                    if (rows) selected.tableRows = (int) LabelDocument.clamp(value, 1, 8);
                    else selected.tableCols = (int) LabelDocument.clamp(value, 1, 8);
                    persistAndRedraw();
                } catch (NumberFormatException ignored) {
                    // 输入中途的非法片段不落状态
                }
            }
        });
    }

    /** 序列号整数字段：field 0=当前值 1=步长 2=位数。 */
    private void bindSerialInt(@Nullable EditText input, int field) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                if (selected == null || !TextElement.TYPE_SERIAL.equals(selected.type)) return;
                try {
                    int value = Integer.parseInt(editable.toString().trim());
                    if (field == 0) selected.serialCounter = Math.max(0, value);
                    else if (field == 1) selected.serialStep = (int) LabelDocument.clamp(value, 1, 999);
                    else selected.serialDigits = (int) LabelDocument.clamp(value, 1, 10);
                    persistAndRedraw();
                } catch (NumberFormatException ignored) {
                    // 输入中途的非法片段不落状态
                }
            }
        });
    }

    private void bindLineWidth(@Nullable EditText input, String expectedType) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = labelCanvas.getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected != null && expectedType.equals(selected.type) && value != null) {
                    selected.lineWidthMm = LabelDocument.clamp(value, 0.1f, 3f);
                    persistAndRedraw();
                }
            }
        });
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

    private void requestScan() {
        if (barcodeScanner != null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else if (embeddedCameraActive) {
                pauseEmbeddedScanner();
            } else {
                startEmbeddedScanner();
            }
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchScanner() {
        ScanOptions options = new ScanOptions()
                .setDesiredBarcodeFormats(ScanFormatCatalog.formatNames(barcodeScanMode))
                .setPrompt(barcodeScanMode ? "将条形码放入取景框" : "将二维码放入取景框")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setBarcodeImageEnabled(false);
        options.addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN);
        scanLauncher.launch(options);
    }

    private void handleScanResult(ScanIntentResult result) {
        String contents = result.getContents();
        if (contents == null) return;
        consumeScannedContent(contents);
    }

    private void consumeScannedContent(String contents) {
        lastScannedContent = contents;
        if (qrResultText != null) qrResultText.setText(contents);
        if (workMode == WorkMode.COMPONENT_ORGANIZATION) {
            applyComponentScan(contents, true);
            return;
        }
        QrContentParser.Parsed parsed = QrContentParser.parse(contents);
        if (qrParseSummary != null) qrParseSummary.setText(parsed.summary());
        applyScannedContentToCanvas(contents);
    }

    private void applyComponentScan(String contents, boolean announce) {
        componentRecord = ComponentLabelParser.parse(contents, supplierFormat);
        String summary = componentRecord.summary();
        if (qrParseSummary != null) qrParseSummary.setText(summary);
        if (componentRecordSummary != null) componentRecordSummary.setText(summary);
        renderTemplatePanel();
        boolean printable = componentRecord.recognized
                || !componentRecord.supplierPartNumber.isEmpty()
                || !componentRecord.manufacturerPartNumber.isEmpty();
        if (!printable) {
            if (announce) toast("未识别供应商贴纸，已保留原始内容，请切换供应商后核对");
            return;
        }
        float width = document.widthMm;
        float height = document.heightMm;
        document = ComponentLabelFactory.create(componentRecord, selectedProfile.dpi, width, height,
                componentTemplate, templateBoldHeadline, templateShowTrace, templateLargeFont);
        labelCanvas.setDocument(document);
        TextElement first = document.elements.isEmpty() ? null : document.elements.get(0);
        labelCanvas.select(first == null ? null : first.id);
        showSelectedElement(first);
        refreshDocumentControls();
        persistAndRedraw();
        if (announce) toast("已离线解析" + componentRecord.supplier.label + "贴纸并生成器件标签");
    }

    private void applyScannedContentToCanvas(String contents) {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) selected = document.addText(contents);
        else selected.text = contents;
        labelCanvas.select(selected.id);
        showSelectedElement(selected);
        persistAndRedraw();
        toast(barcodeScanMode
                ? "已识别条形码并放入右侧画布" : "已解析二维码并放入右侧画布");
    }

    private void startEmbeddedScanner() {
        if (barcodeScanner == null) return;
        embeddedCameraActive = true;
        barcodeScanner.resume();
        View button = findViewById(R.id.scanButton);
        if (button instanceof android.widget.Button) ((android.widget.Button) button).setText("暂停摄像头");
    }

    private void pauseEmbeddedScanner() {
        if (barcodeScanner == null) return;
        embeddedCameraActive = false;
        barcodeScanner.pause();
        View button = findViewById(R.id.scanButton);
        if (button instanceof android.widget.Button) ((android.widget.Button) button).setText("启动摄像头");
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
                    width, height, componentTemplate, templateBoldHeadline, templateShowTrace,
                    templateLargeFont);
            labelCanvas.setDocument(document);
            TextElement first = document.elements.isEmpty() ? null : document.elements.get(0);
            labelCanvas.select(first == null ? null : first.id);
            showSelectedElement(first);
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
        refreshBorderStyleButton();
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
        EditText customWidth = geometryInput(document.widthMm);
        customWidth.setHint("宽 mm");
        EditText customHeight = geometryInput(document.heightMm);
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
        populatingEditor = true;
        textEditorCard.setVisibility(element == null
                || workMode == WorkMode.COMPONENT_ORGANIZATION ? View.GONE : View.VISIBLE);
        if (element != null) {
            if (editorTitle != null) editorTitle.setText(titleFor(element));
            if (elementXInput != null) elementXInput.setText(formatNumber(element.xMm));
            if (elementYInput != null) elementYInput.setText(formatNumber(element.yMm));
            if (elementWidthInput != null) elementWidthInput.setText(formatNumber(element.widthMm));
            if (elementHeightInput != null) elementHeightInput.setText(formatNumber(element.heightMm));
            boolean textual = element.isTextual();
            boolean codeContent = TextElement.TYPE_BARCODE.equals(element.type)
                    || TextElement.TYPE_QRCODE.equals(element.type);
            boolean hasContent = (textual && !TextElement.TYPE_TIME.equals(element.type))
                    || codeContent;
            textInput.setVisibility(hasContent ? View.VISIBLE : View.GONE);
            if (hasContent) {
                textInput.setHint(contentHintFor(element));
                textInput.setText(element.text);
                textInput.setSelection(textInput.length());
            }
            if (rowFont != null) rowFont.setVisibility(textual ? View.VISIBLE : View.GONE);
            fontSizeInput.setText(formatNumber(element.fontSizePt));
            boldCheck.setChecked(element.bold);
            alignmentSpinner.setSelection(element.alignment);
            if (rowBarcode != null) {
                rowBarcode.setVisibility(TextElement.TYPE_BARCODE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (barcodeFormatSpinner != null && TextElement.TYPE_BARCODE.equals(element.type)) {
                barcodeFormatSpinner.setSelection(barcodeFormatIndex(element.format));
            }
            if (barcodeTextPositionSpinner != null) {
                barcodeTextPositionSpinner.setSelection(barcodeTextPositionIndex(
                        element.effectiveBarcodeTextPosition()));
            }
            if (rowQr != null) {
                rowQr.setVisibility(TextElement.TYPE_QRCODE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            boolean code = TextElement.TYPE_BARCODE.equals(element.type)
                    || TextElement.TYPE_QRCODE.equals(element.type);
            if (rowCodeLayout != null) {
                rowCodeLayout.setVisibility(code ? View.VISIBLE : View.GONE);
            }
            if (qrFormatSpinner != null && TextElement.TYPE_QRCODE.equals(element.type)) {
                qrFormatSpinner.setSelection(twoDimensionalFormatIndex(element.format));
            }
            if (qrErrorCorrectionSpinner != null) {
                qrErrorCorrectionSpinner.setSelection(qrErrorCorrectionIndex(
                        element.qrErrorCorrection));
                qrErrorCorrectionSpinner.setEnabled(TextElement.TYPE_QRCODE.equals(element.type)
                        && "QR_CODE".equals(element.format));
            }
            if (codeQuietZoneSpinner != null) {
                codeQuietZoneSpinner.setSelection(quietZoneIndex(element.quietZoneModules));
            }
            if (codeRotationSpinner != null) {
                codeRotationSpinner.setSelection(element.rotationDegrees / 90);
            }
            if (rowLine != null) {
                rowLine.setVisibility(TextElement.TYPE_LINE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (lineOrientationSpinner != null) {
                lineOrientationSpinner.setSelection("v".equals(element.format) ? 1 : 0);
            }
            if (lineWidthInput != null) lineWidthInput.setText(formatNumber(element.lineWidthMm));
            if (rowRect != null) {
                rowRect.setVisibility(TextElement.TYPE_RECT.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (rectWidthInput != null) rectWidthInput.setText(formatNumber(element.lineWidthMm));
            if (rectFilledCheck != null) rectFilledCheck.setChecked(element.filled);
            if (rowTime != null) {
                rowTime.setVisibility(TextElement.TYPE_TIME.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (timeFormatSpinner != null) {
                timeFormatSpinner.setSelection(
                        TimeElementFormat.byPattern(element.format).ordinal());
            }
            if (rowArc != null) {
                rowArc.setVisibility(TextElement.TYPE_ARCTEXT.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (arcDirectionSpinner != null) {
                arcDirectionSpinner.setSelection("down".equals(element.format) ? 1 : 0);
            }
            if (rowSerial != null) {
                rowSerial.setVisibility(TextElement.TYPE_SERIAL.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_SERIAL.equals(element.type)) {
                if (serialValueInput != null) serialValueInput.setText(Integer.toString(element.serialCounter));
                if (serialStepInput != null) serialStepInput.setText(Integer.toString(element.serialStep));
                if (serialDigitsInput != null) serialDigitsInput.setText(Integer.toString(element.serialDigits));
            }
            if (rowImage != null) {
                rowImage.setVisibility(TextElement.TYPE_IMAGE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_IMAGE.equals(element.type)) {
                if (imageWidthInput != null) imageWidthInput.setText(formatNumber(element.widthMm));
                if (imageHeightInput != null) imageHeightInput.setText(formatNumber(element.heightMm));
                if (imageFitSpinner != null) {
                    imageFitSpinner.setSelection(ImageElementRenderer.fitModeIndex(element.format));
                }
            }
            if (rowIcon != null) {
                rowIcon.setVisibility(TextElement.TYPE_ICON.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (iconStyleSpinner != null) {
                iconStyleSpinner.setSelection(IconCatalog.indexOf(element.format));
            }
            if (iconLineWidthInput != null) {
                iconLineWidthInput.setText(formatNumber(element.lineWidthMm));
            }
            if (rowTable != null) {
                rowTable.setVisibility(TextElement.TYPE_TABLE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_TABLE.equals(element.type)) {
                if (tableRowsInput != null) tableRowsInput.setText(Integer.toString(element.tableRows));
                if (tableColsInput != null) tableColsInput.setText(Integer.toString(element.tableCols));
                if (tableLineWidthInput != null) {
                    tableLineWidthInput.setText(formatNumber(element.lineWidthMm));
                }
            }
            if (lockCheck != null) lockCheck.setChecked(element.locked);
        }
        populatingEditor = false;
    }

    private static String contentHintFor(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_BARCODE: return "一维码内容";
            case TextElement.TYPE_QRCODE: return "二维码内容";
            case TextElement.TYPE_SERIAL: return "序列号前缀";
            case TextElement.TYPE_TABLE: return "表格内容（每行一个单元格）";
            case TextElement.TYPE_TIME: return "时间元素按下方格式自动生成";
            case TextElement.TYPE_ARCTEXT: return "弧形文字内容";
            default: return "文本内容";
        }
    }

    private static String titleFor(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_BARCODE: return "所选一维码";
            case TextElement.TYPE_QRCODE: return "所选二维码";
            case TextElement.TYPE_LINE: return "所选线条";
            case TextElement.TYPE_RECT: return "所选矩形";
            case TextElement.TYPE_TIME: return "所选时间";
            case TextElement.TYPE_SERIAL: return "所选序列号（前缀在上方编辑）";
            case TextElement.TYPE_ARCTEXT: return "所选弧形文字";
            case TextElement.TYPE_IMAGE: return "所选图片";
            case TextElement.TYPE_ICON: return "所选图标";
            case TextElement.TYPE_TABLE: return "所选表格（上方每行一个单元格）";
            case TextElement.TYPE_TEXT:
            default: return "所选文本";
        }
    }

    private static int barcodeFormatIndex(String format) {
        if ("ITF".equals(format)) format = "ITF_25";
        for (int index = 0; index < BarcodeRasterizer.BARCODE_FORMATS.length; index++) {
            if (BarcodeRasterizer.BARCODE_FORMATS[index].equals(format)) return index;
        }
        return 0;
    }

    private static int twoDimensionalFormatIndex(String format) {
        for (int index = 0; index < BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS.length; index++) {
            if (BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS[index].equals(format)) return index;
        }
        return 0;
    }

    private static int barcodeTextPositionIndex(String value) {
        if ("top".equals(value)) return 1;
        if ("bottom".equals(value)) return 2;
        return 0;
    }

    private static int quietZoneIndex(int value) {
        if (value >= 4) return 2;
        if (value >= 2) return 1;
        return 0;
    }

    private static int qrErrorCorrectionIndex(String value) {
        if ("L".equals(value)) return 0;
        if ("Q".equals(value)) return 2;
        if ("H".equals(value)) return 3;
        return 1;
    }

    private void deleteSelected() {
        TextElement selected = labelCanvas.getSelectedElement();
        if (selected == null) return;
        document.remove(selected.id);
        TextElement replacement = document.elements.isEmpty() ? null : document.elements.get(0);
        labelCanvas.select(replacement == null ? null : replacement.id);
        showSelectedElement(replacement);
        persistAndRedraw();
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
        Map<String, DeviceCandidate> candidates = new LinkedHashMap<>();
        List<RankedPrinter> displayed = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        TextView status = new TextView(this);
        status.setText("正在检查 USB、已配对 SPP 并扫描附近 BLE（10 秒）…");
        status.setPadding(dp(18), dp(12), dp(18), dp(8));
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(status);
        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自动寻找打印机")
                .setView(container)
                .setNeutralButton("显示全部设备", null)
                .setNegativeButton("取消", null)
                .create();
        boolean[] manualMode = {false};
        String lastAddress = DeviceHistoryStore.lastAddress(this);
        TransportType lastTransport = DeviceHistoryStore.lastTransport(this);
        PrinterProfile lastProfile = DeviceHistoryStore.lastProfile(this);
        Runnable refresh = () -> refreshDiscoveryList(candidates, displayed, labels, adapter,
                status, manualMode[0], lastAddress, lastTransport, lastProfile);

        if (printerService == null) return;
        for (DeviceCandidate candidate : printerService.usbDevices()) {
            candidates.put(candidateKey(candidate), candidate);
        }
        if (printerService.isBluetoothEnabled()) for (DeviceCandidate candidate : printerService.bondedClassicDevices()) {
            candidates.put(candidateKey(candidate), candidate);
        }
        refresh.run();
        dialog.setOnDismissListener(value -> printerService.stopBleScan());
        list.setOnItemClickListener((parent, view, position, id) -> {
            RankedPrinter ranked = displayed.get(position);
            showPrinterCandidateDetails(ranked, dialog);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            manualMode[0] = true;
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            refresh.run();
        });

        BluetoothPrinterManager.ScanListener scanListener = new BluetoothPrinterManager.ScanListener() {
            @Override
            public void onDevice(DeviceCandidate candidate) {
                candidates.put(candidateKey(candidate), candidate);
                refresh.run();
            }

            @Override
            public void onFinished() {
                refresh.run();
                if (displayed.isEmpty()) {
                    status.setText("未自动识别到打印机；可显示全部设备手动选择");
                } else {
                    status.setText("扫描完成：已按可信度排序。请点击目标打印机，确认后才会连接");
                }
            }

            @Override
            public void onError(String message) {
                status.setText(displayed.isEmpty() ? message : message + "；仍可选择已列出的端点");
            }
        };
        if (printerService.isBluetoothEnabled()) printerService.startBleScan(scanListener);
        else scanListener.onFinished();
    }

    private void refreshDiscoveryList(Map<String, DeviceCandidate> candidates,
                                      List<RankedPrinter> displayed, List<String> labels,
                                      ArrayAdapter<String> adapter, TextView status,
                                      boolean manualMode, String lastAddress,
                                      TransportType lastTransport, PrinterProfile lastProfile) {
        List<RankedPrinter> ranked = new ArrayList<>();
        for (DeviceCandidate candidate : candidates.values()) {
            boolean remembered = candidate.transport == lastTransport
                    && candidate.address.equalsIgnoreCase(lastAddress);
            PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, remembered ? lastAddress : null);
            PrinterProfile profile;
            if (match.suggestedProfile != null
                    && match.suggestedProfile.protocol == ProtocolType.DOTHAN_PRIVATE) {
                profile = ProfileStore.load(this, match.suggestedProfile);
            } else if (remembered) {
                profile = lastProfile;
            } else if (match.suggestedProfile != null) {
                profile = ProfileStore.load(this, match.suggestedProfile);
            } else if (match.identity != null && match.identity.dpi == 203
                    && match.identity.widthDots == 384) {
                profile = ProfileStore.load(this, DeviceCatalog.defaultProfile());
            } else {
                profile = selectedProfile;
            }
            if (manualMode || match.isLikelyPrinter()) ranked.add(new RankedPrinter(candidate, match, profile));
        }
        ranked.sort(Comparator
                .comparingInt((RankedPrinter value) -> value.match.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (RankedPrinter value) -> value.candidate.rssi).reversed()));
        displayed.clear();
        displayed.addAll(ranked);
        labels.clear();
        for (RankedPrinter value : displayed) labels.add(formatRankedPrinter(value));
        adapter.notifyDataSetChanged();
        if (!manualMode) {
            status.setText(displayed.isEmpty()
                    ? "扫描中，暂未发现具有打印机特征的设备…"
                    : "已识别 " + displayed.size() + " 个候选；只发现不连接，请点击目标打印机");
        } else {
            status.setText("手动模式：显示全部 " + displayed.size() + " 个硬件端点");
        }
    }

    private static String formatRankedPrinter(RankedPrinter value) {
        String marker = value.match.privateProtocolRisk ? "◆ 已识别（协议待确认）"
                : value.match.score >= 85 ? "★ 推荐（需点击）"
                : value.match.score >= 60 ? "● 候选" : "○ 待确认";
        String risk = value.match.privateProtocolRisk ? " · 私有协议风险" : "";
        String identity = value.match.identity == null ? ""
                : " · " + value.match.identity.manufacturer;
        return marker + " · " + value.match.confidence + " " + value.match.score + " 分" + risk
                + "\n" + value.candidate.name + " · " + value.candidate.transport.label
                + identity + "\n" + value.profile.protocol.label + " · " + value.match.reason;
    }

    private static String candidateKey(DeviceCandidate candidate) {
        return candidate.transport.name() + '|' + candidate.address;
    }

    private void showPrinterCandidateDetails(RankedPrinter ranked, AlertDialog discoveryDialog) {
        DeviceCandidate candidate = ranked.candidate;
        PrinterIdentity identity = ranked.match.identity;
        StringBuilder details = new StringBuilder()
                .append("设备名称：").append(candidate.name)
                .append("\n连接方式：").append(candidate.transport.label)
                .append("\n设备地址：").append(candidate.address);
        if (candidate.transport == TransportType.BLE_GATT && candidate.rssi != 0) {
            details.append("\n信号强度：").append(candidate.rssi).append(" dBm");
        }
        if (identity != null) {
            details.append("\n\n识别系列：").append(identity.family)
                    .append("\n制造商：").append(identity.manufacturer)
                    .append("\n设备类型：").append(identity.deviceType)
                    .append("\n打印规格：").append(identity.specification());
        }
        details.append("\n\n识别可信度：").append(ranked.match.confidence)
                .append("（").append(ranked.match.score).append(" 分）")
                .append("\n识别依据：").append(ranked.match.reason)
                .append("\n拟用档案：").append(ranked.profile.label);
        if (ranked.match.privateProtocolRisk) {
            details.append("\n\n注意：该系列使用厂商私有协议。当前只确认硬件身份和 BLE 通道，")
                    .append("不会把它伪装成已验证的公开打印协议。");
        }
        new AlertDialog.Builder(this)
                .setTitle("确认目标打印机")
                .setMessage(details.toString())
                .setNegativeButton("返回列表", null)
                .setPositiveButton("连接此打印机", (dialog, which) -> {
                    discoveryDialog.dismiss();
                    connectRankedPrinter(ranked);
                })
                .show();
    }

    private void connectRankedPrinter(RankedPrinter ranked) {
        if (ranked.match.privateProtocolRisk) {
            new AlertDialog.Builder(this)
                    .setTitle("可能是厂商私有协议设备")
                    .setMessage("已确认设备身份和 203 DPI / 384 点尺寸，但尚未确认其上层打印协议。"
                            + "当前版本不绕过厂商授权；只有设备同时接受所选公开档案时才能打印。")
                    .setPositiveButton("按尺寸档案连接", (dialog, which) -> {
                        selectProfile(ranked.profile, false);
                        connectWithProfile(ranked.candidate, ranked.profile);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        selectProfile(ranked.profile, true);
        connectWithProfile(ranked.candidate, ranked.profile);
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

    private void showProfileSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(12));
        DeviceCandidate target = printerService == null ? null : printerService.targetDevice();
        if (target != null) selectedProfile = DeviceSettingsStore.apply(this, target, selectedProfile);
        PrinterIdentity identity = target == null ? null
                : DeviceCatalog.identify(target.name, target.transport);
        TextView deviceInfo = new TextView(this);
        String maker = identity == null ? selectedProfile.manufacturer : identity.manufacturer;
        String type = identity == null ? selectedProfile.deviceType : identity.deviceType;
        String specification = identity == null
                ? selectedProfile.dpi + " DPI · " + selectedProfile.printableWidthDots
                + " 点 / " + String.format(Locale.US, "%.1f", selectedProfile.printableWidthMm()) + " mm"
                : identity.specification();
        deviceInfo.setText("设备名称：" + (target == null ? "未选择设备" : target.name)
                + "\n制造商：" + maker
                + "\n设备类型：" + type
                + "\n打印规格：" + specification
                + "\n协议档案：" + selectedProfile.label);
        deviceInfo.setTextColor(ContextCompat.getColor(this, R.color.eprint_text));
        deviceInfo.setPadding(0, dp(6), 0, dp(8));
        form.addView(deviceInfo);

        boolean p1Config = selectedProfile.protocol == ProtocolType.DOTHAN_PRIVATE;
        int densityMinimum = p1Config ? 1 : 0;
        SeekBar density = addSettingSlider(form,
                p1Config ? "打印浓度（P1 显示档位 1-15）" : "打印浓度（0-15）",
                densityMinimum, 15, selectedProfile.printDensity);
        SeekBar speed = addSettingSlider(form, "打印速度（1-14）",
                1, 14, selectedProfile.printSpeed);
        DothanJobOptions currentJob = loadDothanJobOptions();
        CheckBox antiColor = new CheckBox(this);
        antiColor.setText("反色打印（黑白互换）");
        antiColor.setChecked(currentJob.antiColor);
        antiColor.setPadding(0, dp(4), 0, dp(4));
        form.addView(antiColor);
        TextView paperTitle = new TextView(this);
        paperTitle.setText("纸张类型");
        paperTitle.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        paperTitle.setPadding(0, dp(8), 0, 0);
        form.addView(paperTitle);
        Spinner paper = new Spinner(this);
        ArrayAdapter<PaperType> paperAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PaperType.values());
        paperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        paper.setAdapter(paperAdapter);
        paper.setSelection(selectedProfile.paperType.ordinal());
        form.addView(paper);
        EditText gap = addSettingField(form, "间隙长度（0.1 mm；30 表示 3.0 mm）",
                selectedProfile.gapLengthTenthsMm, true);
        CheckBox autoFeed = new CheckBox(this);
        autoFeed.setText("打印后自动出纸");
        autoFeed.setChecked(selectedProfile.autoFeed);
        form.addView(autoFeed);
        CheckBox beep = new CheckBox(this);
        beep.setText("打印机提示音（需设备协议支持）");
        beep.setChecked(selectedProfile.beep);
        form.addView(beep);
        EditText powerOff = addSettingField(form, "自动关机时间（分钟；0 表示关闭）",
                selectedProfile.autoPowerOffMinutes, true);

        TextView protocolNote = new TextView(this);
        protocolNote.setText("P1 会写入并回读确认浓度、速度、纸张/间隙、自动出纸、提示音和自动关机；"
                + "TSPL 会在打印任务中下发浓度、速度、纸张/间隙；ESC/POS 会应用自动出纸。"
                + "未验证协议的厂商扩展只离线保存，不会盲目发送。");
        protocolNote.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        protocolNote.setPadding(0, dp(8), 0, dp(8));
        form.addView(protocolNote);

        Button advancedToggle = new Button(this);
        advancedToggle.setText("展开高级设置");
        advancedToggle.setAllCaps(false);
        advancedToggle.setBackgroundResource(R.drawable.button_secondary);
        form.addView(advancedToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        form.addView(advanced);

        Spinner compression = null;
        CheckBox defaultAuth = null;
        EditText helloToken = null;
        EditText loginToken = null;
        EditText motorMode = null;
        EditText language = null;
        CheckBox applyExtras = null;
        EditText jobCopies = null;
        EditText jobLeft = null;
        EditText jobRight = null;
        EditText jobTop = null;
        EditText jobBottom = null;
        Spinner jobDirection = null;
        CheckBox jobFlip = null;
        if (p1Config) {
            TextView compressionTitle = new TextView(this);
            compressionTitle.setText("德佟私有协议位图压缩");
            compressionTitle.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
            compressionTitle.setPadding(0, dp(8), 0, 0);
            advanced.addView(compressionTitle);
            compression = new Spinner(this);
            ArrayAdapter<DothanCompressionMode> compressionAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, DothanCompressionMode.values());
            compressionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            compression.setAdapter(compressionAdapter);
            compression.setSelection(selectedProfile.compression.ordinal());
            advanced.addView(compression);
            defaultAuth = new CheckBox(this);
            defaultAuth.setText("无自定义令牌时使用内置离线授权（实验性，默认关闭）");
            defaultAuth.setChecked(selectedProfile.useDefaultDothanAuth);
            advanced.addView(defaultAuth);
            helloToken = addSettingField(advanced, "自定义 helloToken（16 进制；留空表示不用自定义令牌）",
                    selectedProfile.helloTokenHex, false);
            loginToken = addSettingField(advanced, "loginToken（16 进制，4 字节）",
                    selectedProfile.loginTokenHex, false);
            motorMode = addSettingField(advanced, "电机模式（整数）",
                    selectedProfile.motorMode, true);
            language = addSettingField(advanced, "语言（整数）",
                    selectedProfile.language, true);
            applyExtras = new CheckBox(this);
            applyExtras.setText("向设备下发电机模式和语言（实验性，默认仅离线保存）");
            applyExtras.setChecked(selectedProfile.applyDothanExtras);
            advanced.addView(applyExtras);

            TextView jobTitle = new TextView(this);
            jobTitle.setText("打印任务选项（份数 / 边距 / 方向 / 翻转，均待真机复验）");
            jobTitle.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
            jobTitle.setPadding(0, dp(12), 0, 0);
            advanced.addView(jobTitle);

            jobCopies = addSettingField(advanced, "打印份数（1-20）", currentJob.copies, true);
            jobLeft = addSettingField(advanced, "左边距（0.1 mm）",
                    Math.round(currentJob.leftDots * 254f / selectedProfile.dpi), true);
            jobRight = addSettingField(advanced, "右边距（0.1 mm）",
                    Math.round(currentJob.rightDots * 254f / selectedProfile.dpi), true);
            jobTop = addSettingField(advanced, "上边距（0.1 mm）",
                    Math.round(currentJob.topDots * 254f / selectedProfile.dpi), true);
            jobBottom = addSettingField(advanced, "下边距（0.1 mm）",
                    Math.round(currentJob.bottomDots * 254f / selectedProfile.dpi), true);

            TextView directionTitle = new TextView(this);
            directionTitle.setText("旋转方向");
            directionTitle.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
            directionTitle.setPadding(0, dp(8), 0, 0);
            advanced.addView(directionTitle);
            jobDirection = new Spinner(this);
            ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"正常", "旋转 180°"});
            directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            jobDirection.setAdapter(directionAdapter);
            jobDirection.setSelection(currentJob.direction == 180 ? 1 : 0);
            advanced.addView(jobDirection);

            jobFlip = new CheckBox(this);
            jobFlip.setText("水平翻转");
            jobFlip.setChecked(currentJob.flipHorizontal);
            advanced.addView(jobFlip);
        }

        EditText width = addSettingField(advanced, "最大可打印点宽", selectedProfile.printableWidthDots, true);
        EditText spp = addSettingField(advanced, "SPP 分包字节", selectedProfile.sppChunkBytes, true);
        EditText ble = addSettingField(advanced, "BLE 分包字节", selectedProfile.bleChunkBytes, true);
        EditText usb = addSettingField(advanced, "USB Bulk 分包字节", selectedProfile.usbChunkBytes, true);
        EditText delay = addSettingField(advanced, "分包间隔（毫秒）", selectedProfile.writeDelayMs, true);
        EditText retries = addSettingField(advanced, "单包写入重试次数", selectedProfile.maxWriteRetries, true);
        EditText threshold = addSettingField(advanced, "点阵黑白阈值（1-254）", selectedProfile.threshold, true);
        EditText services = addSettingField(advanced, "首选 BLE Service UUID（逗号分隔）",
                TextUtils.join(",\n", selectedProfile.preferredBleServiceStrings()), false);
        EditText writes = addSettingField(advanced, "首选 BLE Write UUID（逗号分隔）",
                TextUtils.join(",\n", selectedProfile.preferredBleWriteStrings()), false);
        advancedToggle.setOnClickListener(view -> {
            boolean visible = advanced.getVisibility() == View.VISIBLE;
            advanced.setVisibility(visible ? View.GONE : View.VISIBLE);
            advancedToggle.setText(visible ? "展开高级设置" : "收起高级设置");
        });
        Spinner compressionView = compression;
        CheckBox defaultAuthView = defaultAuth;
        EditText helloTokenView = helloToken;
        EditText loginTokenView = loginToken;
        EditText motorModeView = motorMode;
        EditText languageView = language;
        CheckBox applyExtrasView = applyExtras;
        EditText jobCopiesView = jobCopies;
        EditText jobLeftView = jobLeft;
        EditText jobRightView = jobRight;
        EditText jobTopView = jobTop;
        EditText jobBottomView = jobBottom;
        Spinner jobDirectionView = jobDirection;
        CheckBox jobFlipView = jobFlip;
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("打印机配置与协议 · 离线保存")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        PrinterProfile customized = selectedProfile.withHardwareSettings(
                                integerInRange(width, 128, 2400, "点宽"),
                                integerInRange(spp, 64, 8192, "SPP 分包"),
                                integerInRange(ble, 20, 512, "BLE 分包"),
                                integerInRange(usb, 64, 65536, "USB 分包"),
                                integerInRange(delay, 0, 500, "分包间隔"),
                                integerInRange(retries, 0, 5, "重试次数"),
                                integerInRange(threshold, 1, 254, "黑白阈值"),
                                splitUuidInput(services.getText().toString()),
                                splitUuidInput(writes.getText().toString()))
                                .withDeviceSettings(maker, type,
                                        densityMinimum + density.getProgress(),
                                        1 + speed.getProgress(),
                                        (PaperType) paper.getSelectedItem(),
                                        integerInRange(gap, 0, 3000, "间隙长度"),
                                        autoFeed.isChecked(), beep.isChecked(),
                                        integerInRange(powerOff, 0, 1440, "自动关机时间"));
                        PrinterProfile effectiveProfile = p1Config
                                ? customized.withDothanOptions(
                                        (DothanCompressionMode) compressionView.getSelectedItem(),
                                        defaultAuthView.isChecked(),
                                        helloTokenView.getText().toString().trim(),
                                        loginTokenView.getText().toString().trim())
                                .withDothanExtras(
                                        integerInRange(motorModeView, 0, 255, "电机模式"),
                                        integerInRange(languageView, 0, 255, "语言"),
                                        applyExtrasView.isChecked())
                                : customized;
                        saveCommonAntiColor(antiColor.isChecked());
                        if (p1Config) {
                            saveDothanJobOptions(
                                    integerInRange(jobCopiesView, 1, 20, "打印份数"),
                                    integerInRange(jobLeftView, 0, 3000, "左边距"),
                                    integerInRange(jobRightView, 0, 3000, "右边距"),
                                    integerInRange(jobTopView, 0, 3000, "上边距"),
                                    integerInRange(jobBottomView, 0, 3000, "下边距"),
                                    jobDirectionView.getSelectedItemPosition() == 1 ? 180 : 0,
                                    jobFlipView.isChecked(),
                                    antiColor.isChecked());
                        }
                        selectedProfile = effectiveProfile;
                        if (target == null) {
                            ProfileStore.save(this, effectiveProfile);
                        } else {
                            ProfileStore.saveHardwareOnly(this, effectiveProfile);
                            DeviceSettingsStore.save(this, target, effectiveProfile);
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
                    } catch (IllegalArgumentException invalid) {
                        toast(invalid.getMessage());
                    }
                }));
        dialog.show();
    }

    private SeekBar addSettingSlider(LinearLayout form, String label,
                                     int minimum, int maximum, int value) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, 0);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView current = new TextView(this);
        current.setTextColor(ContextCompat.getColor(this, R.color.eprint_text));
        current.setTextSize(16);
        current.setTypeface(current.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(current);
        form.addView(header);

        SeekBar slider = new SeekBar(this);
        slider.setMax(Math.max(0, maximum - minimum));
        slider.setProgress(Math.max(0, Math.min(maximum, value) - minimum));
        slider.setContentDescription(label);
        current.setText(Integer.toString(minimum + slider.getProgress()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                current.setText(Integer.toString(minimum + progress));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        return slider;
    }

    private EditText addSettingField(LinearLayout form, String label, int value, boolean numeric) {
        return addSettingField(form, label, Integer.toString(value), numeric);
    }

    private EditText addSettingField(LinearLayout form, String label, String value, boolean numeric) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(ContextCompat.getColor(this, R.color.eprint_muted));
        title.setPadding(0, dp(8), 0, 0);
        form.addView(title);
        EditText input = new EditText(this);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (!numeric) input.setMinLines(2);
        form.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static int integerInRange(EditText input, int minimum, int maximum, String label) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static String[] splitUuidInput(String value) {
        String[] items = value.trim().split("[,\\s]+");
        List<String> result = new ArrayList<>();
        for (String item : items) if (!item.trim().isEmpty()) result.add(item.trim());
        return result.toArray(new String[0]);
    }

    private void persistAndRedraw() {
        document.normalize();
        DocumentStore.save(this, workMode, document);
        labelCanvas.invalidate();
        updateCompatibilityWarning();
        refreshBorderStyleButton();
        refreshElementChips();
        trackSnapshot();
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
        if (barcodeScanner != null && embeddedCameraActive
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startEmbeddedScanner();
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
        super.onDestroy();
    }

    private static final class RankedPrinter {
        final DeviceCandidate candidate;
        final PrinterMatch match;
        final PrinterProfile profile;

        RankedPrinter(DeviceCandidate candidate, PrinterMatch match, PrinterProfile profile) {
            this.candidate = candidate;
            this.match = match;
            this.profile = profile;
        }
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence value, int start, int before, int count) {}
    }
}

package com.otool.eprint;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.model.WorkModeStore;
import com.otool.eprint.print.BluetoothPrinterManager;
import com.otool.eprint.protocol.MonochromeRaster;
import com.otool.eprint.protocol.ProtocolRegistry;
import com.otool.eprint.protocol.ProtocolType;
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
    private EditText textInput;
    private EditText fontSizeInput;
    private CheckBox boldCheck;
    private Spinner dpiSpinner;
    private Spinner profileSpinner;
    private Spinner alignmentSpinner;
    private Spinner workModeSpinner;
    private Spinner supplierSpinner;
    private LinearLayout componentPanel;
    private TextView componentRecordSummary;
    private TextView modeContextTitle;
    private TextView offlineStatus;
    private Button addTextButton;
    private Button printButton;
    private DecoratedBarcodeView barcodeScanner;
    private TextView qrResultText;
    private TextView qrParseSummary;
    private BarcodeModeSwitch scanModeSwitch;
    private String lastScannedContent = "";
    private boolean barcodeScanMode;
    private boolean embeddedCameraActive = true;
    private boolean populatingEditor;
    private boolean populatingProfile;
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
            new ScanContract(), this::handleScanResult);

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
        setContentView(R.layout.activity_main);

        workMode = WorkModeStore.load(this);
        document = DocumentStore.load(this, workMode);
        selectedProfile = ProfileStore.load(this);
        document.dpi = selectedProfile.dpi;
        document.normalize();
        bindViews();
        renderConnectionStatus();
        configureEmbeddedScanner();
        configureSpinners();
        configureCanvas();
        configureActions();
        configureEditor();
        renderWorkMode();
        refreshDocumentControls();
        refreshProfileSummary();

        TextElement initial = document.elements.isEmpty() ? null : document.elements.get(0);
        if (initial != null) labelCanvas.select(initial.id);
        showSelectedElement(initial);

        Intent serviceIntent = new Intent(this, PrinterConnectionService.class);
        bindService(serviceIntent, hardwareServiceConnection, Context.BIND_AUTO_CREATE);
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
        textInput = findViewById(R.id.textInput);
        fontSizeInput = findViewById(R.id.fontSizeInput);
        boldCheck = findViewById(R.id.boldCheck);
        dpiSpinner = findViewById(R.id.dpiSpinner);
        profileSpinner = findViewById(R.id.profileSpinner);
        alignmentSpinner = findViewById(R.id.alignmentSpinner);
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
        printButton = findViewById(R.id.printButton);
        if (offlineStatus != null) {
            offlineStatus.setText("● v" + BuildConfig.VERSION_NAME + " · 本机离线工作");
        }
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
        ArrayAdapter<String> dpiAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"203 DPI", "300 DPI"});
        dpiAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dpiSpinner.setAdapter(dpiAdapter);
        dpiSpinner.setEnabled(false);

        ArrayAdapter<String> alignmentAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"左对齐", "居中", "右对齐"});
        alignmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alignmentSpinner.setAdapter(alignmentAdapter);

        ArrayAdapter<PrinterProfile> profileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, DeviceCatalog.profiles());
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
        });
    }

    private void switchWorkMode(WorkMode selected) {
        DocumentStore.save(this, workMode, document);
        workMode = selected;
        WorkModeStore.save(this, selected);
        document = DocumentStore.load(this, selected);
        document.dpi = selectedProfile.dpi;
        document.normalize();
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
        addTextButton.setOnClickListener(view -> {
            TextElement element = document.addText("新文本");
            labelCanvas.select(element.id);
            showSelectedElement(element);
            persistAndRedraw();
        });
        findViewById(R.id.deleteTextButton).setOnClickListener(view -> deleteSelected());
        findViewById(R.id.applySizeButton).setOnClickListener(view -> applyCanvasSize());
        findViewById(R.id.presetSmallButton).setOnClickListener(view -> applyPreset(20f, 10f));
        findViewById(R.id.presetMediumButton).setOnClickListener(view -> applyPreset(25f, 15f));
        findViewById(R.id.presetLargeButton).setOnClickListener(view -> applyPreset(40f, 30f));
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
                + String.format(Locale.US, "%.1f", selectedProfile.gapLengthTenthsMm / 10f) + " mm");
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
        boolean printable = componentRecord.recognized
                || !componentRecord.supplierPartNumber.isEmpty()
                || !componentRecord.manufacturerPartNumber.isEmpty();
        if (!printable) {
            if (announce) toast("未识别供应商贴纸，已保留原始内容，请切换供应商后核对");
            return;
        }
        float width = document.widthMm;
        float height = document.heightMm;
        document = ComponentLabelFactory.create(componentRecord, selectedProfile.dpi, width, height);
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
                    width, height);
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
        widthInput.setText(formatNumber(document.widthMm));
        heightInput.setText(formatNumber(document.heightMm));
        dpiSpinner.setSelection(selectedProfile.dpi == 300 ? 1 : 0);
        updateCompatibilityWarning();
    }

    private void showSelectedElement(@Nullable TextElement element) {
        populatingEditor = true;
        textEditorCard.setVisibility(element == null
                || workMode == WorkMode.COMPONENT_ORGANIZATION ? View.GONE : View.VISIBLE);
        if (element != null) {
            textInput.setText(element.text);
            textInput.setSelection(textInput.length());
            fontSizeInput.setText(formatNumber(element.fontSizePt));
            boldCheck.setChecked(element.bold);
            alignmentSpinner.setSelection(element.alignment);
        }
        populatingEditor = false;
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
        Bitmap bitmap = LabelBitmapRenderer.render(document);
        ImageView image = new ImageView(this);
        int padding = dp(20);
        image.setPadding(padding, padding, padding, padding);
        image.setBackgroundColor(0xFFE7E4DB);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(bitmap);
        new AlertDialog.Builder(this)
                .setTitle(document.widthMm + " × " + document.heightMm + " mm · " + selectedProfile.protocol.label)
                .setView(image)
                .setPositiveButton("关闭", null)
                .show();
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

    private void sendPrint() {
        if (printerService == null || printerService.connectedDevice() == null) {
            toast("请先连接打印机");
            requestBluetoothAction(ACTION_CHOOSE_PRINTER);
            return;
        }
        if (!printerService.isPrinterReady()) {
            toast("蓝牙通道已连接，但打印机协议尚未确认；不会盲目发送数据");
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
            byte[] data = ProtocolRegistry.encoder(selectedProfile)
                    .encode(raster, document.widthMm, document.heightMm, selectedProfile);
            lastJobSummary = "编码完成 · " + selectedProfile.protocol.label + " · "
                    + data.length + " 字节";
            PrintJob job = printerService.enqueue(data, selectedProfile, sha256(data));
            lastJobSummary = job.state.label + " · " + job.message;
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
        EditText density = addSettingField(form,
                p1Config ? "打印浓度（P1 显示档位 1-15）" : "打印浓度（0-15）",
                selectedProfile.printDensity, true);
        EditText speed = addSettingField(form, "打印速度（1-14）",
                selectedProfile.printSpeed, true);
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

        EditText width = addSettingField(form, "最大可打印点宽", selectedProfile.printableWidthDots, true);
        EditText spp = addSettingField(form, "SPP 分包字节", selectedProfile.sppChunkBytes, true);
        EditText ble = addSettingField(form, "BLE 分包字节", selectedProfile.bleChunkBytes, true);
        EditText usb = addSettingField(form, "USB Bulk 分包字节", selectedProfile.usbChunkBytes, true);
        EditText delay = addSettingField(form, "分包间隔（毫秒）", selectedProfile.writeDelayMs, true);
        EditText retries = addSettingField(form, "单包写入重试次数", selectedProfile.maxWriteRetries, true);
        EditText threshold = addSettingField(form, "点阵黑白阈值（1-254）", selectedProfile.threshold, true);
        EditText services = addSettingField(form, "首选 BLE Service UUID（逗号分隔）",
                TextUtils.join(",\n", selectedProfile.preferredBleServiceStrings()), false);
        EditText writes = addSettingField(form, "首选 BLE Write UUID（逗号分隔）",
                TextUtils.join(",\n", selectedProfile.preferredBleWriteStrings()), false);
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
                                        integerInRange(density, p1Config ? 1 : 0, 15, "打印浓度"),
                                        integerInRange(speed, 1, 14, "打印速度"),
                                        (PaperType) paper.getSelectedItem(),
                                        integerInRange(gap, 0, 3000, "间隙长度"),
                                        autoFeed.isChecked(), beep.isChecked(),
                                        integerInRange(powerOff, 0, 1440, "自动关机时间"));
                        selectedProfile = customized;
                        if (target == null) {
                            ProfileStore.save(this, customized);
                        } else {
                            ProfileStore.saveHardwareOnly(this, customized);
                            DeviceSettingsStore.save(this, target, customized);
                        }
                        document.dpi = customized.dpi;
                        document.normalize();
                        refreshDocumentControls();
                        refreshProfileSummary();
                        persistAndRedraw();
                        dialog.dismiss();
                        if (printerService != null && target != null) {
                            printerService.connect(target, customized, new BluetoothPrinterManager.Callback() {
                                @Override public void onSuccess(String message) {
                                    if (customized.protocol != ProtocolType.DOTHAN_PRIVATE) {
                                        toast("配置已保存，打印机已按新档案重连");
                                        return;
                                    }
                                    printerService.applyP1DeviceSettings(customized,
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

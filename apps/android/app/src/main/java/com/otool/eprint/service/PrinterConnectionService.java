package com.otool.eprint.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.otool.eprint.MainActivity;
import com.otool.eprint.R;
import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.DeviceHistoryStore;
import com.otool.eprint.device.DeviceSettingsStore;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.ProfileStore;
import com.otool.eprint.device.TransportType;
import com.otool.eprint.hardware.PrinterHardwareStatus;
import com.otool.eprint.hardware.PrinterStatusController;
import com.otool.eprint.job.PrintJob;
import com.otool.eprint.job.PrintJobPolicy;
import com.otool.eprint.job.PrintJobQueue;
import com.otool.eprint.job.PrintJobState;
import com.otool.eprint.print.BluetoothPrinterManager;
import com.otool.eprint.print.UsbPrinterManager;
import com.otool.eprint.protocol.DothanCommandStream;
import com.otool.eprint.protocol.DothanDeviceSettingsEncoder;
import com.otool.eprint.protocol.DothanFrame;
import com.otool.eprint.protocol.DothanFrameCodec;
import com.otool.eprint.protocol.DothanFrameStreamParser;
import com.otool.eprint.protocol.ProtocolType;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Owns printer transports independently from Activity lifecycle. All public methods are intended
 * to be called on the main thread; transport managers marshal callbacks back to that thread.
 */
public final class PrinterConnectionService extends Service {
    public interface Listener {
        default void onConnectionStateChanged(ConnectionStatus status) {}
        default void onHardwareStatusChanged(PrinterHardwareStatus status) {}
        default void onJobUpdated(PrintJob job) {}
        default void onDevicesChanged() {}
    }

    public final class LocalBinder extends Binder {
        public PrinterConnectionService service() {
            return PrinterConnectionService.this;
        }
    }

    private static final String CHANNEL_ID = "printer_connection";
    private static final int NOTIFICATION_ID = 1203;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final PrintJobQueue jobQueue = new PrintJobQueue();
    private final DothanFrameStreamParser dothanParser = new DothanFrameStreamParser();
    private final Deque<String> protocolTrace = new ArrayDeque<>();

    private BluetoothPrinterManager bluetooth;
    private UsbPrinterManager usb;
    private PrinterStatusController statusController;
    private PrinterHardwareStatus hardwareStatus = PrinterHardwareStatus.disconnected("未连接");
    private DeviceCandidate lastCandidate;
    private PrinterProfile activeProfile;
    private PrintJob currentJob;
    private ConnectionStatus connectionStatus = ConnectionStatus.idle();
    private boolean foreground;
    private boolean manualDisconnect;
    private boolean connecting;
    private boolean reconnectScheduled;
    private int reconnectAttempt;
    private long lastNotificationUpdateAt;
    private long connectionGeneration;
    private boolean notificationUpdateScheduled;
    private boolean restoreAttempted;
    private BroadcastReceiver bluetoothStateReceiver;
    private PowerManager.WakeLock transferWakeLock;
    private boolean dothanProtocolReady;
    private int dothanProtocolGeneration;
    private DothanPrintTask dothanPrintTask;
    private Runnable dothanTimeoutRunnable;
    private int p1ObservedDpi = -1;
    private int p1ObservedWidthDots = -1;
    private int p1ObservedDensity = -1;
    private int p1ObservedSpeed = -1;
    private int p1ObservedGapType = -1;
    private int p1ObservedGapHundredthsMm = -1;
    private int p1ObservedAutoFeed = -1;
    private int p1ObservedBeep = -1;
    private int p1ObservedAutoPowerOffMinutes = -1;
    private int p1ObservedBufferBytes = -1;
    private PrinterProfile pendingP1Settings;
    private BluetoothPrinterManager.Callback pendingP1SettingsCallback;
    private boolean p1SettingsInFlight;

    @Override
    public void onCreate() {
        super.onCreate();
        bluetooth = new BluetoothPrinterManager(this);
        usb = new UsbPrinterManager(this);
        bluetooth.setStateListener(this::handleBluetoothState);
        bluetooth.setDataListener(this::handleReceivedBytes);
        usb.setDataListener(this::handleReceivedBytes);
        usb.setDeviceListener(() -> {
            if (lastCandidate != null && lastCandidate.transport == TransportType.USB_HOST
                    && usb.connectedDevice() == null && !manualDisconnect) {
                setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE,
                        "USB 设备已断开，等待重新接入");
                setDisconnectedHardware("USB 设备已拔出");
                scheduleReconnect("USB 设备变化");
            }
            notifyDevicesChanged();
        });
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            transferWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "eprint:printer-transfer");
            transferWakeLock.setReferenceCounted(false);
        }
        registerBluetoothStateReceiver();
        createNotificationChannel();
        mainHandler.post(this::restoreDesiredConnection);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        restoreDesiredConnection();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        bluetooth.close();
        usb.close();
        if (bluetoothStateReceiver != null) {
            try {
                unregisterReceiver(bluetoothStateReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
        }
        super.onDestroy();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onConnectionStateChanged(connectionStatus);
        listener.onHardwareStatusChanged(hardwareStatus);
        if (currentJob != null) listener.onJobUpdated(currentJob);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isBluetoothAvailable() {
        return bluetooth.isAvailable();
    }

    public boolean isBluetoothEnabled() {
        return bluetooth.isEnabled();
    }

    public List<DeviceCandidate> bondedClassicDevices() {
        return bluetooth.bondedClassicDevices();
    }

    public List<DeviceCandidate> usbDevices() {
        return usb.devices();
    }

    public void startBleScan(BluetoothPrinterManager.ScanListener listener) {
        bluetooth.startBleScan(listener);
    }

    public void stopBleScan() {
        bluetooth.stopBleScan();
    }

    @Nullable
    public DeviceCandidate connectedDevice() {
        DeviceCandidate candidate = usb.connectedDevice();
        return candidate == null ? bluetooth.connectedDevice() : candidate;
    }

    @Nullable
    public PrinterProfile activeProfile() {
        return activeProfile;
    }

    public PrinterHardwareStatus hardwareStatus() {
        return hardwareStatus;
    }

    public ConnectionStatus connectionStatus() {
        return connectionStatus;
    }

    public boolean isPrinterReady() {
        return currentJob == null && activeProfile != null && connectedDevice() != null
                && (activeProfile.protocol != ProtocolType.DOTHAN_PRIVATE
                || (dothanProtocolReady && !p1SettingsInFlight && pendingP1Settings == null));
    }

    public void applyP1DeviceSettings(PrinterProfile profile,
                                      BluetoothPrinterManager.Callback callback) {
        if (profile == null || profile.protocol != ProtocolType.DOTHAN_PRIVATE) {
            callback.onError("当前档案不是 P1 私有协议档案");
            return;
        }
        if (activeProfile == null || !activeProfile.id.equals(profile.id)
                || connectedDevice() == null) {
            callback.onError("P1 尚未连接，配置未发送");
            return;
        }
        if (currentJob != null) {
            callback.onError("打印任务进行中，不能同时修改设备配置");
            return;
        }
        activeProfile = profile;
        pendingP1Settings = profile;
        pendingP1SettingsCallback = callback;
        if (dothanProtocolReady) {
            applyPendingP1Settings();
        } else {
            setConnectionState(ConnectionPhase.PROTOCOL_NEGOTIATING,
                    "P1 配置已排队，等待协议确认后写入设备");
        }
    }

    @Nullable
    public DeviceCandidate targetDevice() {
        return lastCandidate;
    }

    @Nullable
    public PrintJob currentJob() {
        return currentJob;
    }

    public List<PrintJob> jobs() {
        return jobQueue.history();
    }

    public void connect(DeviceCandidate candidate, PrinterProfile profile,
                        BluetoothPrinterManager.Callback callback) {
        long generation = ++connectionGeneration;
        mainHandler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
        reconnectAttempt = 0;
        manualDisconnect = false;
        connecting = true;
        // Bluetooth permission is already granted by discovery. USB must wait for the
        // system's per-device permission callback before starting a connectedDevice FGS.
        if (candidate.transport != TransportType.USB_HOST) startAsForegroundService();
        disconnectManagers();
        lastCandidate = candidate;
        activeProfile = profile;
        DeviceHistoryStore.recordSelection(this, candidate, profile);
        statusController = new PrinterStatusController(profile);
        setConnectionState(ConnectionPhase.CONNECTING,
                "正在连接 " + candidate.name + " · " + candidate.transport.label);
        BluetoothPrinterManager.Callback internal = new BluetoothPrinterManager.Callback() {
            @Override
            public void onSuccess(String message) {
                if (generation != connectionGeneration || manualDisconnect) {
                    if (manualDisconnect) disconnectManagers();
                    return;
                }
                connecting = false;
                reconnectScheduled = false;
                reconnectAttempt = 0;
                startAsForegroundService();
                DeviceHistoryStore.record(PrinterConnectionService.this, candidate, profile);
                statusController.onConnected();
                updateHardware(statusController.current());
                callback.onSuccess(message);
                if (profile.protocol == ProtocolType.DOTHAN_PRIVATE) {
                    beginDothanNegotiation(message);
                } else {
                    setConnectionState(ConnectionPhase.CONNECTED, message);
                    probeStatus();
                    processNextJob();
                }
            }

            @Override
            public void onError(String message) {
                if (generation != connectionGeneration || manualDisconnect) return;
                connecting = false;
                setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE, message);
                setDisconnectedHardware(message);
                callback.onError(message);
                scheduleReconnect(message);
            }
        };
        if (candidate.transport == TransportType.USB_HOST) {
            usb.connect(candidate, profile, new UsbPrinterManager.Callback() {
                @Override public void onSuccess(String message) { internal.onSuccess(message); }
                @Override public void onError(String message) { internal.onError(message); }
            });
        } else {
            bluetooth.connect(candidate, profile, internal);
        }
    }

    public void disconnect() {
        connectionGeneration++;
        manualDisconnect = true;
        DeviceHistoryStore.clearConnectionIntent(this);
        connecting = false;
        reconnectScheduled = false;
        pendingP1Settings = null;
        pendingP1SettingsCallback = null;
        p1SettingsInFlight = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        PrintJob job = currentJob;
        if (job != null) {
            job.state = PrintJobState.CANCELLED;
            job.message = job.progress > 0
                    ? "用户主动断开；设备可能已收到部分数据"
                    : "用户主动断开，任务已取消";
            notifyJob(job);
            currentJob = null;
            releaseWakeLock();
        }
        disconnectManagers();
        setConnectionState(ConnectionPhase.MANUAL_DISCONNECTED,
                "已手动断开；不会再自动重连");
        lastCandidate = null;
        activeProfile = null;
        statusController = null;
        setDisconnectedHardware("用户主动断开");
        if (currentJob == null && jobQueue.isEmpty()) stopForegroundService();
    }

    public PrintJob enqueue(byte[] data, PrinterProfile profile, String idempotencyKey) {
        PrintJobQueue.EnqueueResult result = jobQueue.enqueue(data, profile, idempotencyKey,
                System.currentTimeMillis());
        if (result.created) {
            notifyJob(result.job);
            processNextJob();
        }
        return result.job;
    }

    public boolean cancelCurrentJob() {
        PrintJob job = currentJob;
        if (job == null) return false;
        job.state = PrintJobState.CANCELLED;
        job.message = job.progress > 0
                ? "已取消；打印机可能已收到部分数据，请检查走纸"
                : "已取消";
        notifyJob(job);
        currentJob = null;
        releaseWakeLock();
        disconnectManagers();
        setDisconnectedHardware("任务取消导致通道重置");
        scheduleReconnect("取消任务后恢复连接");
        return true;
    }

    public boolean cancelActiveOrLastQueuedJob() {
        if (currentJob != null) return cancelCurrentJob();
        PrintJob job = jobQueue.cancelLast();
        if (job == null) return false;
        notifyJob(job);
        return true;
    }

    public boolean cancelQueuedJob(String jobId) {
        PrintJob job = jobQueue.cancel(jobId);
        if (job == null) return false;
        notifyJob(job);
        return true;
    }

    public boolean retryLastFailedJob() {
        PrintJob job = jobQueue.retryLastFailed();
        if (job == null) return false;
        notifyJob(job);
        processNextJob();
        return true;
    }

    public void probeStatus() {
        PrinterStatusController controller = statusController;
        PrinterProfile profile = activeProfile;
        if (controller == null || profile == null || connectedDevice() == null) return;
        if (profile.protocol == ProtocolType.DOTHAN_PRIVATE) {
            byte[] query = DothanFrameCodec.join(
                    DothanFrameCodec.encode(0x84, (byte) 1),
                    DothanFrameCodec.encode(0x71),
                    DothanFrameCodec.encode(0x72),
                    DothanFrameCodec.encode(0x42),
                    DothanFrameCodec.encode(0x43),
                    DothanFrameCodec.encode(0x44),
                    DothanFrameCodec.encode(0x45),
                    DothanFrameCodec.encode(0x48),
                    DothanFrameCodec.encode(0x4D),
                    DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0));
            traceProtocol("TX PROBE " + query.length + " bytes");
            sendRaw(query, profile, new BluetoothPrinterManager.Callback() {
                @Override public void onSuccess(String message) {
                    traceProtocol("PROBE transport accepted");
                }
                @Override public void onError(String message) {
                    traceProtocol("PROBE failed " + message);
                }
            });
            return;
        }
        byte[] probe = controller.createProbe();
        if (probe.length == 0) return;
        long previousUpdate = controller.current().updatedAt;
        sendRaw(probe, profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                mainHandler.postDelayed(() -> {
                    if (statusController != controller) return;
                    PrinterHardwareStatus current = controller.current();
                    if (current.updatedAt <= previousUpdate) {
                        String detail = profile.protocol == com.otool.eprint.protocol.ProtocolType.ZPL
                                ? "ZPL 状态未返回；可能是缺纸、缺碳带、打印头打开、过温，或端点不支持回读"
                                : "状态查询未返回；当前端点可能不支持回读";
                        updateHardware(current.with(current.busy, current.paper, current.cover,
                                current.temperature, false, detail));
                    }
                }, 2_500);
            }
            @Override public void onError(String message) {
                PrinterHardwareStatus current = controller.current();
                updateHardware(current.with(current.busy, current.paper, current.cover,
                        current.temperature, false, "状态查询未获响应：" + message));
            }
        });
    }

    public String diagnostics(PrinterProfile selectedProfile) {
        DeviceCandidate connected = connectedDevice();
        String transportDiagnostics = connected != null && connected.transport == TransportType.USB_HOST
                ? usb.diagnostics() : bluetooth.diagnostics(selectedProfile);
        StringBuilder jobs = new StringBuilder();
        List<PrintJob> values = jobQueue.history();
        int start = Math.max(0, values.size() - 5);
        for (int index = start; index < values.size(); index++) {
            PrintJob job = values.get(index);
            jobs.append("\n- ").append(job.id, 0, 8).append(" · ")
                    .append(job.state.label).append(" · ").append(job.progress).append('%');
        }
        if (jobs.length() == 0) jobs.append("\n- 无");
        return transportDiagnostics
                + "\n\n硬件状态：" + hardwareSummary()
                + "\nP1 协议：" + (activeProfile != null
                && activeProfile.protocol == ProtocolType.DOTHAN_PRIVATE
                ? (dothanProtocolReady ? "已确认，可打印" : "未确认，禁止打印") : "不适用")
                + "\nP1 设备实测配置：" + p1ObservedConfiguration()
                + "\nP1 收包解析：缓存 " + dothanParser.bufferedBytes()
                + " 字节 / 拒绝 " + dothanParser.rejectedFrames() + " 帧"
                + "\n自动重连：" + (manualDisconnect ? "关闭" : "开启")
                + (lastCandidate == null ? "" : " · 已尝试 " + reconnectAttempt
                + " 次（持续到手动断开）")
                + "\n排队任务：" + jobQueue.size()
                + "\n最近任务：" + jobs
                + "\n\nP1 最近协议轨迹：" + protocolTraceSummary();
    }

    private void processNextJob() {
        if (currentJob != null) return;
        DeviceCandidate connected = connectedDevice();
        if (connected == null || activeProfile == null) return;
        if (activeProfile.protocol == ProtocolType.DOTHAN_PRIVATE
                && (!dothanProtocolReady || p1SettingsInFlight || pendingP1Settings != null)) return;
        PrintJob job = jobQueue.pollFirst();
        if (job == null) {
            releaseWakeLock();
            return;
        }
        if (!job.profile.id.equals(activeProfile.id)) {
            job.state = PrintJobState.FAILED;
            job.message = "任务档案与当前连接档案不一致，请切换档案后重试";
            notifyJob(job);
            processNextJob();
            return;
        }
        currentJob = job;
        job.attempts++;
        job.state = PrintJobState.SENDING;
        job.message = "第 " + job.attempts + " 次发送";
        acquireWakeLock();
        notifyJob(job);
        if (job.profile.protocol == ProtocolType.DOTHAN_PRIVATE) {
            startDothanPrint(job);
            return;
        }
        send(job.data, job.profile, new BluetoothPrinterManager.TransferCallback() {
            private long lastProgressNotificationAt;

            @Override
            public void onProgress(int sentBytes, int totalBytes) {
                if (currentJob != job || job.state == PrintJobState.CANCELLED) return;
                job.progress = totalBytes == 0 ? 100 : Math.round(sentBytes * 100f / totalBytes);
                job.message = "正在发送 " + sentBytes + "/" + totalBytes + " 字节";
                long now = SystemClock.elapsedRealtime();
                if (job.progress < 100 && now - lastProgressNotificationAt < 100) return;
                lastProgressNotificationAt = now;
                notifyJob(job);
            }

            @Override
            public void onSuccess(String message) {
                if (currentJob != job || job.state == PrintJobState.CANCELLED) return;
                job.progress = 100;
                job.state = PrintJobState.SENT_TO_DEVICE;
                job.message = message;
                notifyJob(job);
                // Public raw printer protocols generally confirm transport writes, not physical print completion.
                job.state = PrintJobState.COMPLETED_UNCONFIRMED;
                job.message = "数据已完整写入通道；设备未提供逐任务完成回执";
                notifyJob(job);
                currentJob = null;
                releaseWakeLock();
                probeStatus();
                processNextJob();
            }

            @Override
            public void onError(String message) {
                if (currentJob != job || job.state == PrintJobState.CANCELLED) return;
                currentJob = null;
                releaseWakeLock();
                if (PrintJobPolicy.shouldAutomaticallyRetry(job.progress, job.attempts)) {
                    job.state = PrintJobState.RETRY_WAIT;
                    job.message = message + "；尚未发送数据，将在重连后自动重试";
                    jobQueue.requeueFirst(job);
                } else {
                    job.state = PrintJobState.FAILED;
                    job.message = job.progress > 0
                            ? message + "；已发送部分数据，为防重复打印需人工确认后重试"
                            : message;
                }
                notifyJob(job);
                setDisconnectedHardware(message);
                scheduleReconnect(message);
            }
        });
    }

    private void send(byte[] data, PrinterProfile profile,
                      BluetoothPrinterManager.TransferCallback callback) {
        DeviceCandidate connected = connectedDevice();
        if (connected != null && connected.transport == TransportType.USB_HOST) {
            usb.send(data, profile, new UsbPrinterManager.TransferCallback() {
                @Override public void onProgress(int sentBytes, int totalBytes) {
                    callback.onProgress(sentBytes, totalBytes);
                }
                @Override public void onSuccess(String message) { callback.onSuccess(message); }
                @Override public void onError(String message) { callback.onError(message); }
            });
        } else {
            bluetooth.print(data, profile, callback);
        }
    }

    private void sendRaw(byte[] data, PrinterProfile profile, BluetoothPrinterManager.Callback callback) {
        DeviceCandidate connected = connectedDevice();
        if (connected != null && connected.transport == TransportType.USB_HOST) {
            usb.sendRaw(data, profile, new UsbPrinterManager.Callback() {
                @Override public void onSuccess(String message) { callback.onSuccess(message); }
                @Override public void onError(String message) { callback.onError(message); }
            });
        } else {
            bluetooth.sendRaw(data, profile, callback);
        }
    }

    private void beginDothanNegotiation(String transportMessage) {
        resetDothanProtocol();
        int generation = ++dothanProtocolGeneration;
        setConnectionState(ConnectionPhase.TRANSPORT_CONNECTED,
                transportMessage + " · BLE 通道已建立");
        setConnectionState(ConnectionPhase.PROTOCOL_NEGOTIATING,
                "正在确认 P1 私有协议与命令缓冲回执");
        byte[] query = DothanFrameCodec.join(
                DothanFrameCodec.encode(0x84, (byte) 1),
                DothanFrameCodec.encode(0x71),
                DothanFrameCodec.encode(0x72),
                DothanFrameCodec.encode(0x42),
                DothanFrameCodec.encode(0x43),
                DothanFrameCodec.encode(0x44),
                DothanFrameCodec.encode(0x45),
                DothanFrameCodec.encode(0x48),
                DothanFrameCodec.encode(0x4D),
                DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0));
        traceProtocol("NEGOTIATE TX " + query.length + " bytes");
        sendRaw(query, activeProfile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (generation != dothanProtocolGeneration) return;
                traceProtocol("NEGOTIATE transport accepted");
                scheduleDothanTimeout(4_500, () -> {
                    if (generation != dothanProtocolGeneration || dothanProtocolReady) return;
                    setConnectionState(ConnectionPhase.TRANSPORT_CONNECTED,
                            "P1 蓝牙已连接，但未收到 0x77 协议回执；打印保持禁用");
                    traceProtocol("NEGOTIATE timeout: no 0x77");
                });
            }

            @Override public void onError(String message) {
                if (generation != dothanProtocolGeneration) return;
                traceProtocol("NEGOTIATE failed " + message);
                setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE,
                        "P1 协议确认发送失败：" + message);
            }
        });
    }

    private void markDothanReady(int bufferBytes) {
        p1ObservedBufferBytes = bufferBytes;
        if ((p1ObservedDpi >= 0 && p1ObservedDpi != 203)
                || (p1ObservedWidthDots >= 0 && p1ObservedWidthDots != 384)) {
            dothanProtocolReady = false;
            cancelDothanTimeout();
            String message = "P1 协议已响应，但实测规格不是已验证的 203 DPI / 384 点；打印保持禁用 · "
                    + p1ObservedConfiguration();
            setConnectionState(ConnectionPhase.TRANSPORT_CONNECTED, message);
            traceProtocol("READY REJECTED profile mismatch " + p1ObservedConfiguration());
            return;
        }
        if (!dothanProtocolReady) {
            dothanProtocolReady = true;
            cancelDothanTimeout();
            setConnectionState(ConnectionPhase.PRINTER_READY,
                    "P1 私有协议已确认 · " + p1ObservedConfiguration());
            traceProtocol("READY buffer=" + bufferBytes);
            if (pendingP1Settings != null) applyPendingP1Settings();
            else processNextJob();
        }
    }

    private void applyPendingP1Settings() {
        PrinterProfile profile = pendingP1Settings;
        BluetoothPrinterManager.Callback callback = pendingP1SettingsCallback;
        if (profile == null || callback == null || p1SettingsInFlight
                || !dothanProtocolReady || connectedDevice() == null) return;
        p1SettingsInFlight = true;
        int generation = dothanProtocolGeneration;
        byte[] settings = DothanDeviceSettingsEncoder.encode(profile);
        setConnectionState(ConnectionPhase.PROTOCOL_NEGOTIATING,
                "正在写入 P1 浓度、速度、介质、间隙、出纸、提示音与自动关机配置");
        traceProtocol("SETTINGS TX " + settings.length + " bytes");
        sendRaw(settings, profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (generation != dothanProtocolGeneration || pendingP1Settings != profile) return;
                traceProtocol("SETTINGS transport accepted; verifying by readback");
                mainHandler.postDelayed(() -> {
                    if (generation != dothanProtocolGeneration || pendingP1Settings != profile) return;
                    probeStatus();
                    mainHandler.postDelayed(() -> verifyP1Settings(profile, callback, generation), 900);
                }, 120);
            }

            @Override public void onError(String message) {
                if (generation != dothanProtocolGeneration || pendingP1Settings != profile) return;
                p1SettingsInFlight = false;
                pendingP1Settings = null;
                pendingP1SettingsCallback = null;
                String detail = "P1 配置写入失败：" + message;
                setConnectionState(ConnectionPhase.PRINTER_READY,
                        detail + "；协议仍可用，打印任务未自动执行");
                traceProtocol("SETTINGS FAILED " + message);
                callback.onError(detail);
            }
        });
    }

    private void verifyP1Settings(PrinterProfile profile,
                                  BluetoothPrinterManager.Callback callback,
                                  int generation) {
        if (generation != dothanProtocolGeneration || pendingP1Settings != profile) return;
        boolean matches = p1ObservedDensity == profile.printDensity
                && p1ObservedSpeed == profile.printSpeed
                && p1ObservedGapType == dothanGapType(profile)
                && p1ObservedGapHundredthsMm == profile.gapLengthTenthsMm * 10
                && p1ObservedAutoFeed == (profile.autoFeed ? 1 : 0)
                && p1ObservedBeep == (profile.beep ? 1 : 0)
                && p1ObservedAutoPowerOffMinutes == profile.autoPowerOffMinutes;
        p1SettingsInFlight = false;
        pendingP1Settings = null;
        pendingP1SettingsCallback = null;
        if (matches) {
            String detail = "P1 配置已写入并回读确认 · " + p1ObservedConfiguration();
            setConnectionState(ConnectionPhase.PRINTER_READY, detail);
            traceProtocol("SETTINGS VERIFIED " + p1ObservedConfiguration());
            callback.onSuccess(detail);
            processNextJob();
        } else {
            String detail = "P1 配置回读与期望不一致 · 实测：" + p1ObservedConfiguration();
            setConnectionState(ConnectionPhase.PRINTER_READY,
                    detail + "；请在诊断中核对，未自动打印");
            traceProtocol("SETTINGS VERIFY MISMATCH " + p1ObservedConfiguration());
            callback.onError(detail);
        }
    }

    private static int dothanGapType(PrinterProfile profile) {
        switch (profile.paperType) {
            case RECEIPT: return 0;
            case GAP_LABEL: return 2;
            case CARDSTOCK: return 3;
            case TRANSPARENT: return 4;
            default: return 2;
        }
    }

    private void handleDothanFrame(DothanFrame frame) {
        traceProtocol(String.format(Locale.US, "FRAME 0x%02X payload=%d %s",
                frame.type, frame.payload.length, hexPrefix(frame.payload)));
        observeDothanConfiguration(frame);
        if (frame.type == 0x77) {
            int units = DothanFrameCodec.readVariableShort(frame.payload, 0);
            int bufferBytes = units < 0 ? 0 : units == 1 ? 1000 : units * 500;
            markDothanReady(bufferBytes);
            DothanPrintTask task = dothanPrintTask;
            if (task != null) {
                task.availableBytes = Math.max(task.availableBytes, bufferBytes);
                sendNextDothanChunk(task);
            }
            return;
        }
        if (frame.type == 0x70) {
            handleDothanReceipt(frame.payload);
        }
    }

    private void startDothanPrint(PrintJob job) {
        DothanPrintTask task = new DothanPrintTask(job);
        dothanPrintTask = task;
        if (!dothanProtocolReady) {
            failDothanJob(task, "P1 协议尚未就绪，未发送任何打印数据");
            return;
        }
        setConnectionState(ConnectionPhase.SENDING, "正在初始化 P1 打印会话");
        byte[] prelude = DothanFrameCodec.join(
                DothanFrameCodec.encode(0x70),
                DothanFrameCodec.encode(0x80, (byte) 0x49),
                DothanFrameCodec.encode(0x84, (byte) 1),
                DothanFrameCodec.encode(0x80, (byte) 0),
                DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0));
        traceProtocol("PRINT " + shortJob(job) + " PRELUDE TX " + prelude.length);
        sendRaw(prelude, job.profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (dothanPrintTask != task) return;
                task.controlAccepted = true;
                job.message = "P1 会话已建立，等待 0x77 缓冲许可";
                notifyJob(job);
                scheduleDothanTimeout(6_000,
                        () -> failDothanJob(task, "等待 P1 0x77 缓冲回执超时；未盲目发送位图"));
                sendNextDothanChunk(task);
            }

            @Override public void onError(String message) {
                failDothanJob(task, "P1 会话初始化失败：" + message);
            }
        });
    }

    private void sendNextDothanChunk(DothanPrintTask task) {
        if (dothanPrintTask != task || !task.controlAccepted || !task.preflightReady
                || task.transmitting
                || task.awaitingReceipt || task.availableBytes <= 0) return;
        if (task.offset >= task.job.data.length) {
            finishDothanData(task);
            return;
        }
        int allowance = Math.min(9_000, task.availableBytes);
        int count = DothanCommandStream.chunkLength(task.job.data, task.offset, allowance);
        if (count <= 0) {
            int next = DothanCommandStream.nextCommandLength(task.job.data, task.offset);
            failDothanJob(task, "P1 缓冲 " + allowance + " 字节不足以容纳下一条完整命令 "
                    + next + " 字节；已停止以避免破坏位图流");
            return;
        }
        byte[] chunk = Arrays.copyOfRange(task.job.data, task.offset, task.offset + count);
        task.availableBytes = 0;
        task.transmitting = true;
        cancelDothanTimeout();
        setConnectionState(ConnectionPhase.SENDING,
                "正在按 P1 0x77 流控发送 " + (task.offset + count) + "/"
                        + task.job.data.length + " 字节");
        if (!task.physicalAttemptStarted) {
            task.physicalAttemptStarted = true;
            traceProtocol("PHYSICAL_ATTEMPT START " + shortJob(task.job));
        }
        traceProtocol("PRINT DATA TX offset=" + task.offset + " count=" + count);
        sendRaw(chunk, task.job.profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (dothanPrintTask != task) return;
                task.transmitting = false;
                task.offset += count;
                task.job.progress = Math.min(99,
                        Math.round(task.offset * 100f / task.job.data.length));
                task.job.message = "P1 已接收 " + task.offset + "/" + task.job.data.length
                        + " 字节；等待下一次缓冲许可";
                notifyJob(task.job);
                if (task.offset >= task.job.data.length) {
                    finishDothanData(task);
                } else if (task.availableBytes > 0) {
                    sendNextDothanChunk(task);
                } else {
                    requestDothanBuffer(task);
                }
            }

            @Override public void onError(String message) {
                task.transmitting = false;
                failDothanJob(task, "P1 位图发送失败：" + message);
            }
        });
    }

    private void requestDothanBuffer(DothanPrintTask task) {
        if (dothanPrintTask != task) return;
        byte[] query = DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0);
        traceProtocol("PRINT BUFFER QUERY");
        sendRaw(query, task.job.profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (dothanPrintTask != task) return;
                // A notification may beat this transport callback. Do not arm a stale timeout
                // after the next data chunk has already started from that notification.
                if (task.transmitting || task.availableBytes > 0) return;
                scheduleDothanTimeout(6_000,
                        () -> failDothanJob(task, "P1 在传输中未继续上报 0x77 缓冲；已停止"));
            }

            @Override public void onError(String message) {
                failDothanJob(task, "P1 缓冲查询失败：" + message);
            }
        });
    }

    private void finishDothanData(DothanPrintTask task) {
        if (dothanPrintTask != task || task.awaitingReceipt) return;
        task.awaitingReceipt = true;
        task.receiptDeadlineAt = SystemClock.elapsedRealtime() + 30_000;
        task.transmitting = true;
        byte[] finish = DothanFrameCodec.join(
                DothanFrameCodec.encode(0x70),
                DothanFrameCodec.encode(0x77, (byte) 0, (byte) 0));
        traceProtocol("PRINT DATA ENDED; request 0x70 receipt");
        sendRaw(finish, task.job.profile, new BluetoothPrinterManager.Callback() {
            @Override public void onSuccess(String message) {
                if (dothanPrintTask != task) return;
                task.transmitting = false;
                task.job.state = PrintJobState.SENT_TO_DEVICE;
                task.job.progress = 99;
                task.job.message = "位图已发送，正在等待 P1 物理打印回执 0x70";
                notifyJob(task.job);
                setConnectionState(ConnectionPhase.WAITING_RECEIPT,
                        "位图已发送，等待 P1 确认实际打印结果");
                scheduleDothanReceiptPoll(task);
            }

            @Override public void onError(String message) {
                task.transmitting = false;
                failDothanJob(task, "P1 完成回执查询失败：" + message);
            }
        });
    }

    private void scheduleDothanReceiptPoll(DothanPrintTask task) {
        if (dothanPrintTask != task || !task.awaitingReceipt) return;
        long remaining = task.receiptDeadlineAt - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            int lastStatus = task.lastReceiptStatus;
            String suffix = lastStatus < 0 ? "未收到有效状态"
                    : "最后状态 " + lastStatus + "（" + receiptStatus(lastStatus) + "）";
            failDothanJob(task, "位图已发送，但 30 秒内未取得 P1 完成状态；" + suffix
                    + "；结果未知，禁止自动重试");
            return;
        }
        scheduleDothanTimeout(Math.min(600, remaining), () -> requestDothanReceipt(task));
    }

    private void requestDothanReceipt(DothanPrintTask task) {
        if (dothanPrintTask != task || !task.awaitingReceipt) return;
        traceProtocol("PRINT RECEIPT POLL 0x70");
        sendRaw(DothanFrameCodec.encode(0x70), task.job.profile,
                new BluetoothPrinterManager.Callback() {
                    @Override public void onSuccess(String message) {
                        if (dothanPrintTask != task || !task.awaitingReceipt) return;
                        // A notification can arrive before this transport callback. Scheduling
                        // again is intentional and keeps polling until a terminal 0x70 status.
                        scheduleDothanReceiptPoll(task);
                    }

                    @Override public void onError(String message) {
                        failDothanJob(task, "P1 完成回执轮询失败：" + message);
                    }
                });
    }

    private void handleDothanReceipt(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        int status = payload[0] & 0xFF;
        DothanPrintTask task = dothanPrintTask;
        traceProtocol("RECEIPT status=" + status + " " + receiptStatus(status));
        if (task == null) return;
        task.lastReceiptStatus = status;
        if (!task.awaitingReceipt) {
            // The session prelude deliberately queries 0x70. Its idle/previous-page reply
            // must never be mistaken for completion of the new page. It is instead a
            // preflight gate: no bitmap byte is sent until the printer reports a safe state.
            if (status <= 11 && status != 1 && status != 2) {
                task.preflightReady = true;
                task.job.message = "P1 打印前状态已确认，等待/使用 0x77 缓冲许可";
                notifyJob(task.job);
                traceProtocol("PREFLIGHT READY status=" + status);
                sendNextDothanChunk(task);
            } else if (status == 1 || status == 2) {
                task.job.message = "P1 仍在处理上一任务（" + receiptStatus(status)
                        + "）；本任务尚未发送位图";
                notifyJob(task.job);
                traceProtocol("PREFLIGHT BUSY status=" + status);
            } else {
                failDothanJob(task, "P1 打印前检查未通过，状态 " + status + "："
                        + receiptStatus(status) + "；未发送位图");
            }
            return;
        }
        if (status == 1 || status == 2 || status == 30 || status == 33 || status == 39) {
            task.job.message = "P1 正在处理：" + receiptStatus(status);
            notifyJob(task.job);
            scheduleDothanReceiptPoll(task);
            return;
        }
        if (status <= 11) {
            completeDothanJob(task, status);
        } else {
            failDothanJob(task, "P1 拒绝打印，状态 " + status + "：" + receiptStatus(status));
        }
    }

    private void completeDothanJob(DothanPrintTask task, int status) {
        if (dothanPrintTask != task) return;
        cancelDothanTimeout();
        task.job.progress = 100;
        task.job.state = PrintJobState.COMPLETED_CONFIRMED;
        task.job.message = "P1 已返回 0x70 完成回执（" + status + "）";
        notifyJob(task.job);
        traceProtocol("PHYSICAL_ATTEMPT CONFIRMED " + shortJob(task.job));
        dothanPrintTask = null;
        currentJob = null;
        releaseWakeLock();
        setConnectionState(ConnectionPhase.PRINTER_READY, "P1 打印完成并已确认");
        processNextJob();
    }

    private void failDothanJob(@Nullable DothanPrintTask expected, String message) {
        DothanPrintTask task = dothanPrintTask;
        if (expected != null && task != expected) return;
        cancelDothanTimeout();
        if (task != null) {
            task.job.state = PrintJobState.FAILED;
            task.job.message = message;
            notifyJob(task.job);
            traceProtocol("PRINT FAILED " + shortJob(task.job) + " " + message);
        }
        dothanPrintTask = null;
        if (task == null || currentJob == task.job) currentJob = null;
        releaseWakeLock();
        setConnectionState(ConnectionPhase.PRINT_FAILED, message);
    }

    private void scheduleDothanTimeout(long delayMillis, Runnable action) {
        cancelDothanTimeout();
        dothanTimeoutRunnable = () -> {
            dothanTimeoutRunnable = null;
            action.run();
        };
        mainHandler.postDelayed(dothanTimeoutRunnable, delayMillis);
    }

    private void cancelDothanTimeout() {
        if (dothanTimeoutRunnable != null) mainHandler.removeCallbacks(dothanTimeoutRunnable);
        dothanTimeoutRunnable = null;
    }

    private void resetDothanProtocol() {
        cancelDothanTimeout();
        dothanProtocolGeneration++;
        dothanProtocolReady = false;
        p1SettingsInFlight = false;
        dothanPrintTask = null;
        dothanParser.reset();
        p1ObservedDpi = -1;
        p1ObservedWidthDots = -1;
        p1ObservedDensity = -1;
        p1ObservedSpeed = -1;
        p1ObservedGapType = -1;
        p1ObservedGapHundredthsMm = -1;
        p1ObservedAutoFeed = -1;
        p1ObservedBeep = -1;
        p1ObservedAutoPowerOffMinutes = -1;
        p1ObservedBufferBytes = -1;
    }

    private void observeDothanConfiguration(DothanFrame frame) {
        byte[] payload = frame.payload;
        if (payload.length == 0) return;
        switch (frame.type) {
            case 0x71:
                p1ObservedDpi = payload[0] & 0xFF;
                break;
            case 0x72:
                if (payload.length >= 2) {
                    p1ObservedWidthDots = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                }
                break;
            case 0x42:
                p1ObservedGapType = payload[0] & 0xFF;
                break;
            case 0x43:
                p1ObservedDensity = (payload[0] & 0xFF) + 1;
                break;
            case 0x44:
                p1ObservedSpeed = (payload[0] & 0xFF) + 1;
                break;
            case 0x45:
                p1ObservedGapHundredthsMm = DothanFrameCodec.readVariableShort(payload, 0);
                break;
            case 0x48:
                p1ObservedAutoPowerOffMinutes = DothanFrameCodec.readVariableShort(payload, 0);
                break;
            case 0x4D:
                if (payload.length >= 3 && (payload[2] & 0x02) == 0) {
                    p1ObservedAutoFeed = (payload[2] & 0x04) != 0 ? 1 : 0;
                    p1ObservedBeep = (payload[2] & 0x08) == 0 ? 1 : 0;
                }
                break;
            default:
                break;
        }
    }

    private String p1ObservedConfiguration() {
        if (p1ObservedDpi < 0 && p1ObservedWidthDots < 0 && p1ObservedBufferBytes < 0) {
            return "尚未返回";
        }
        StringBuilder value = new StringBuilder();
        if (p1ObservedDpi >= 0) value.append(p1ObservedDpi).append(" DPI");
        if (p1ObservedWidthDots >= 0) appendObserved(value, p1ObservedWidthDots + " 点");
        if (p1ObservedDensity >= 0) appendObserved(value, "浓度 " + p1ObservedDensity);
        if (p1ObservedSpeed >= 0) appendObserved(value, "速度 " + p1ObservedSpeed);
        if (p1ObservedGapType >= 0) appendObserved(value, p1GapTypeName(p1ObservedGapType));
        if (p1ObservedGapHundredthsMm >= 0) {
            appendObserved(value, "间隙 "
                    + String.format(Locale.US, "%.2f", p1ObservedGapHundredthsMm / 100f)
                    + " mm");
        }
        if (p1ObservedAutoFeed >= 0) {
            appendObserved(value, "自动出纸" + (p1ObservedAutoFeed == 1 ? "开" : "关"));
        }
        if (p1ObservedBeep >= 0) {
            appendObserved(value, "提示音" + (p1ObservedBeep == 1 ? "开" : "关"));
        }
        if (p1ObservedAutoPowerOffMinutes >= 0) {
            appendObserved(value, "自动关机 " + p1ObservedAutoPowerOffMinutes + " 分钟");
        }
        if (p1ObservedBufferBytes >= 0) {
            appendObserved(value, "缓冲 " + p1ObservedBufferBytes + " 字节");
        }
        return value.toString();
    }

    private static void appendObserved(StringBuilder target, String item) {
        if (target.length() > 0) target.append(" · ");
        target.append(item);
    }

    private static String p1GapTypeName(int value) {
        switch (value) {
            case 0: return "连续纸";
            case 1: return "孔标";
            case 2: return "不干胶间隙";
            case 3: return "黑标/卡纸";
            case 4: return "透明贴";
            default: return "介质类型 " + value;
        }
    }

    private void traceProtocol(String message) {
        String entry = String.format(Locale.US, "%tT.%tL %s",
                System.currentTimeMillis(), System.currentTimeMillis(), message);
        synchronized (protocolTrace) {
            protocolTrace.addLast(entry);
            while (protocolTrace.size() > 120) protocolTrace.removeFirst();
        }
        Log.i("EPrint/P1", entry);
    }

    private String protocolTraceSummary() {
        StringBuilder value = new StringBuilder();
        synchronized (protocolTrace) {
            int skip = Math.max(0, protocolTrace.size() - 18);
            int index = 0;
            for (String item : protocolTrace) {
                if (index++ < skip) continue;
                value.append("\n- ").append(item);
            }
        }
        return value.length() == 0 ? "\n- 无" : value.toString();
    }

    private static String hexPrefix(byte[] data) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < data.length && index < 24; index++) {
            if (index > 0) value.append(' ');
            value.append(String.format(Locale.US, "%02X", data[index] & 0xFF));
        }
        if (data.length > 24) value.append(" …");
        return value.toString();
    }

    private static String shortJob(PrintJob job) {
        return job.id.substring(0, Math.min(8, job.id.length()));
    }

    private static String receiptStatus(int status) {
        switch (status) {
            case 0: return "完成";
            case 1: return "正在打印";
            case 2: return "正在走纸";
            case 12: return "已取消";
            case 20: return "环境未就绪";
            case 30: return "电压过低";
            case 31: return "电压过高";
            case 32: return "未检测到打印头";
            case 33: return "打印头过热";
            case 34: return "上盖打开";
            case 35: return "缺纸";
            case 36: return "打印头打开";
            case 37: return "缺碳带";
            case 38: return "碳带不匹配";
            case 39: return "打印头过冷";
            case 40: return "碳带用尽";
            case 41: return "碳带用尽";
            case 42: return "未检测到标签";
            case 43: return "标签不匹配";
            case 44: return "标签用尽";
            case 45: return "缺碳带";
            case 46: return "碳带不匹配";
            case 50: return "标签仓打开";
            default: return status <= 11 ? "设备完成状态" : "其他设备错误";
        }
    }

    private static final class DothanPrintTask {
        final PrintJob job;
        int offset;
        int availableBytes;
        int lastReceiptStatus = -1;
        boolean controlAccepted;
        boolean preflightReady;
        boolean transmitting;
        boolean awaitingReceipt;
        boolean physicalAttemptStarted;
        long receiptDeadlineAt;

        DothanPrintTask(PrintJob job) {
            this.job = job;
        }
    }

    private void handleReceivedBytes(byte[] data) {
        if (activeProfile != null && activeProfile.protocol == ProtocolType.DOTHAN_PRIVATE) {
            traceProtocol("RX " + data.length + " bytes " + hexPrefix(data));
            List<DothanFrame> frames = dothanParser.feed(data);
            for (DothanFrame frame : frames) handleDothanFrame(frame);
            if (!frames.isEmpty() && statusController != null) {
                PrinterHardwareStatus current = statusController.current();
                updateHardware(current.with(current.busy, current.paper, current.cover,
                        current.temperature, true,
                        "P1 已响应，最近包 0x" + String.format(Locale.US, "%02X", frames.get(frames.size() - 1).type)));
            }
            return;
        }
        PrinterStatusController controller = statusController;
        if (controller != null) updateHardware(controller.onBytes(data));
    }

    private void handleBluetoothState(String state) {
        if (lastCandidate == null || lastCandidate.transport == TransportType.USB_HOST) return;
        if (connecting && "未连接".equals(state)) return;
        if (state.startsWith("已连接")) {
            setConnectionState(ConnectionPhase.CONNECTED, state);
        } else if (state.startsWith("正在连接")) {
            setConnectionState(ConnectionPhase.CONNECTING, state);
        } else {
            setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE, state);
        }
        if (!manualDisconnect && !connecting
                && (state.contains("断开") || state.contains("读取失败") || state.startsWith("错误"))) {
            setDisconnectedHardware(state);
            scheduleReconnect(state);
        }
    }

    private void scheduleReconnect(String reason) {
        if (manualDisconnect || lastCandidate == null || activeProfile == null || reconnectScheduled) return;
        long delay = ReconnectPolicy.delayMillis(reconnectAttempt);
        reconnectAttempt++;
        reconnectScheduled = true;
        long nextRetryAt = System.currentTimeMillis() + delay;
        setConnectionState(ConnectionPhase.RECONNECT_WAIT,
                "连接中断，" + (delay / 1000) + " 秒后第 " + reconnectAttempt
                        + " 次重连；将持续重试直到手动断开", nextRetryAt);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private final Runnable reconnectRunnable = () -> {
        reconnectScheduled = false;
        if (manualDisconnect || lastCandidate == null || activeProfile == null) return;
        DeviceCandidate candidate = lastCandidate.transport == TransportType.USB_HOST
                ? resolveUsbCandidate(lastCandidate) : lastCandidate;
        if (candidate == null) {
            scheduleReconnect("等待 USB 打印机重新接入");
            return;
        }
        PrinterProfile profile = activeProfile;
        connecting = true;
        long generation = ++connectionGeneration;
        disconnectManagers();
        setConnectionState(ConnectionPhase.CONNECTING,
                "正在第 " + reconnectAttempt + " 次重连 " + candidate.name + "…");
        BluetoothPrinterManager.Callback callback = new BluetoothPrinterManager.Callback() {
            @Override
            public void onSuccess(String message) {
                if (generation != connectionGeneration || manualDisconnect) {
                    if (manualDisconnect) disconnectManagers();
                    return;
                }
                connecting = false;
                reconnectScheduled = false;
                reconnectAttempt = 0;
                startAsForegroundService();
                lastCandidate = candidate;
                statusController = new PrinterStatusController(profile);
                statusController.onConnected();
                updateHardware(statusController.current());
                if (profile.protocol == ProtocolType.DOTHAN_PRIVATE) {
                    beginDothanNegotiation(message + " · 自动重连成功");
                } else {
                    setConnectionState(ConnectionPhase.CONNECTED, message + " · 自动重连成功");
                    probeStatus();
                    processNextJob();
                }
            }

            @Override
            public void onError(String message) {
                if (generation != connectionGeneration || manualDisconnect) return;
                connecting = false;
                setDisconnectedHardware(message);
                scheduleReconnect(message);
            }
        };
        if (candidate.transport == TransportType.USB_HOST) {
            usb.connect(candidate, profile, new UsbPrinterManager.Callback() {
                @Override public void onSuccess(String message) { callback.onSuccess(message); }
                @Override public void onError(String message) { callback.onError(message); }
            });
        } else {
            bluetooth.connect(candidate, profile, callback);
        }
    };

    @Nullable
    private DeviceCandidate resolveUsbCandidate(DeviceCandidate remembered) {
        for (DeviceCandidate candidate : usb.devices()) {
            if (candidate.vendorId == remembered.vendorId && candidate.productId == remembered.productId) {
                return candidate;
            }
        }
        return null;
    }

    private void restoreDesiredConnection() {
        if (restoreAttempted || lastCandidate != null || !DeviceHistoryStore.connectionDesired(this)) return;
        restoreAttempted = true;
        String address = DeviceHistoryStore.lastAddress(this);
        String name = DeviceHistoryStore.lastName(this);
        TransportType transport = DeviceHistoryStore.lastTransport(this);
        PrinterProfile profile = ProfileStore.load(this, DeviceHistoryStore.lastProfile(this));
        PrinterProfile detected = DeviceCatalog.matchKnown(name, transport);
        if (detected != null && detected.protocol == ProtocolType.DOTHAN_PRIVATE) {
            profile = ProfileStore.load(this, detected);
        }
        if (address.isEmpty()) {
            DeviceHistoryStore.clearConnectionIntent(this);
            return;
        }
        if (transport == TransportType.USB_HOST) {
            DeviceCandidate remembered = DeviceCandidate.rememberedUsb(name, address,
                    DeviceHistoryStore.lastVendorId(this), DeviceHistoryStore.lastProductId(this));
            DeviceCandidate available = resolveUsbCandidate(remembered);
            if (available != null) {
                connect(available, DeviceSettingsStore.apply(this, available, profile),
                        NO_OP_CALLBACK);
            } else {
                restoreWaitingTarget(remembered,
                        DeviceSettingsStore.apply(this, remembered, profile),
                        "等待已选择的 USB 打印机重新接入");
            }
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            DeviceHistoryStore.clearConnectionIntent(this);
            setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE, "此设备没有蓝牙硬件");
            return;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            DeviceCandidate candidate = new DeviceCandidate(device, transport, name, address,
                    0, false);
            connect(candidate, DeviceSettingsStore.apply(this, candidate, profile), NO_OP_CALLBACK);
        } catch (SecurityException permissionMissing) {
            setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE,
                    "已保留目标打印机；恢复附近设备权限后继续连接");
        } catch (IllegalArgumentException invalidAddress) {
            DeviceHistoryStore.clearConnectionIntent(this);
            setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE,
                    "已保存的蓝牙地址无效，请重新选择打印机");
        }
    }

    private void restoreWaitingTarget(DeviceCandidate candidate, PrinterProfile profile,
                                      String message) {
        manualDisconnect = false;
        lastCandidate = candidate;
        activeProfile = profile;
        statusController = new PrinterStatusController(profile);
        if (candidate.transport != TransportType.USB_HOST) startAsForegroundService();
        setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE, message);
        scheduleReconnect(message);
    }

    private static final BluetoothPrinterManager.Callback NO_OP_CALLBACK =
            new BluetoothPrinterManager.Callback() {
                @Override public void onSuccess(String message) {}
                @Override public void onError(String message) {}
            };

    private void disconnectManagers() {
        resetDothanProtocol();
        bluetooth.disconnect();
        usb.disconnect();
    }

    private void setDisconnectedHardware(String detail) {
        if (statusController != null) statusController.onDisconnected(detail);
        updateHardware(PrinterHardwareStatus.disconnected(detail));
    }

    private void updateHardware(PrinterHardwareStatus status) {
        hardwareStatus = status;
        for (Listener listener : listeners) listener.onHardwareStatusChanged(status);
        updateNotification();
    }

    private void setConnectionState(ConnectionPhase phase, String state) {
        setConnectionState(phase, state, 0);
    }

    private void setConnectionState(ConnectionPhase phase, String state, long nextRetryAtMillis) {
        DeviceCandidate candidate = lastCandidate;
        connectionStatus = new ConnectionStatus(phase, state,
                candidate == null ? "" : candidate.name,
                candidate == null ? "" : candidate.address,
                candidate == null ? "" : candidate.transport.label,
                !manualDisconnect && candidate != null, reconnectAttempt, nextRetryAtMillis);
        for (Listener listener : listeners) listener.onConnectionStateChanged(connectionStatus);
        updateNotification();
    }

    private void notifyJob(PrintJob job) {
        for (Listener listener : listeners) listener.onJobUpdated(job);
        updateNotification();
    }

    private void notifyDevicesChanged() {
        for (Listener listener : listeners) listener.onDevicesChanged();
    }

    private void registerBluetoothStateReceiver() {
        bluetoothStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    notifyDevicesChanged();
                    if (state == BluetoothAdapter.STATE_ON && !manualDisconnect) {
                        scheduleReconnect("蓝牙已重新开启");
                    } else if (state == BluetoothAdapter.STATE_OFF && lastCandidate != null
                            && lastCandidate.transport != TransportType.USB_HOST) {
                        setConnectionState(ConnectionPhase.WAITING_FOR_HARDWARE,
                                "蓝牙已关闭，等待用户开启；开启后自动重连");
                        setDisconnectedHardware("蓝牙已关闭");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, filter);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "打印机连接",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持蓝牙或 USB 打印连接与任务传输");
        manager.createNotificationChannel(channel);
    }

    private void ensureForeground() {
        if (foreground) {
            updateNotification();
            return;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private void startAsForegroundService() {
        Intent serviceIntent = new Intent(this, PrinterConnectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);
    }

    private void updateNotification() {
        if (!foreground) return;
        long now = SystemClock.elapsedRealtime();
        long remaining = 500 - (now - lastNotificationUpdateAt);
        if (remaining > 0) {
            if (!notificationUpdateScheduled) {
                notificationUpdateScheduled = true;
                mainHandler.postDelayed(notificationUpdateRunnable, remaining);
            }
            return;
        }
        publishNotification();
    }

    private final Runnable notificationUpdateRunnable = () -> {
        notificationUpdateScheduled = false;
        if (foreground) publishNotification();
    };

    private void publishNotification() {
        lastNotificationUpdateAt = SystemClock.elapsedRealtime();
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (manager != null && allowed) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PrintJob job = currentJob;
        String text = job == null ? connectionStatus.message : job.state.label + " · " + job.progress + "%";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("EPrint 打印机硬件服务")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(connectionStatus.connectionDesired || job != null)
                .setContentIntent(contentIntent)
                .build();
    }

    private void stopForegroundService() {
        if (!foreground) return;
        mainHandler.removeCallbacks(notificationUpdateRunnable);
        notificationUpdateScheduled = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        foreground = false;
        stopSelf();
    }

    private void acquireWakeLock() {
        if (transferWakeLock != null && !transferWakeLock.isHeld()) {
            transferWakeLock.acquire(10 * 60_000L);
        }
    }

    private void releaseWakeLock() {
        if (transferWakeLock != null && transferWakeLock.isHeld()) transferWakeLock.release();
    }

    private String hardwareSummary() {
        return (hardwareStatus.connected ? "已连接" : "未连接")
                + " · 纸张 " + hardwareStatus.paper
                + " · 上盖 " + hardwareStatus.cover
                + " · 温度 " + hardwareStatus.temperature
                + " · " + hardwareStatus.detail;
    }
}

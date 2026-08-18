package com.otool.eprint.print;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.TransportType;
import com.otool.eprint.protocol.ProtocolType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class BluetoothPrinterManager {
    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    public interface TransferCallback extends Callback {
        void onProgress(int sentBytes, int totalBytes);
    }

    public interface ScanListener {
        void onDevice(DeviceCandidate candidate);
        void onFinished();
        void onError(String message);
    }

    public interface StateListener {
        void onStateChanged(String state);
    }

    public interface DataListener {
        void onBytes(byte[] data);
    }

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final UUID P1_MICROCHIP_SERVICE = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455");
    private static final UUID P1_NOTIFY_UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616");
    private static final UUID DOTHAN_FF00_SERVICE = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB");
    private static final UUID DOTHAN_FF01_NOTIFY = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB");
    private static final UUID FFE0_SERVICE = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID FFE1_CHARACTERISTIC = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID DEVICE_INFORMATION_SERVICE = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB");
    private static final UUID MODEL_NUMBER = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB");
    private static final UUID FIRMWARE_REVISION = UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB");
    private static final long CONNECT_TIMEOUT_SECONDS = 14;
    private static final long BLE_SCAN_MILLIS = 10_000;
    private static final long BLE_WRITE_TIMEOUT_SECONDS = 5;
    private static final long DEVICE_INFO_READ_TIMEOUT_MILLIS = 1_200;

    private final Context appContext;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService readerWorker = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<Integer> bleWriteResults = new LinkedBlockingQueue<>();
    private final Map<String, DeviceCandidate> scanResults = new LinkedHashMap<>();

    private volatile BluetoothSocket socket;
    private volatile BluetoothSocket connectingSocket;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeCharacteristic;
    private volatile BluetoothGattCharacteristic notifyCharacteristic;
    private volatile DeviceCandidate connectedCandidate;
    private volatile PrinterProfile activeProfile;
    private volatile int negotiatedMtu = 23;
    private volatile String connectionState = "未连接";
    private volatile String lastError = "无";
    private volatile int lastTransferBytes;
    private volatile int lastReceivedBytes;
    private volatile boolean readingClassic;
    private volatile String discoveredGattSummary = "未发现";
    private volatile boolean notificationsSubscribed;
    private volatile String deviceInfoModel = "未读取";
    private volatile String deviceInfoSoftware = "未读取";
    private StateListener stateListener;
    private DataListener dataListener;
    private ScanCallback scanCallback;
    private Runnable scanTimeout;
    private ScanListener scanListener;
    private ScheduledFuture<?> bleConnectTimeout;

    public BluetoothPrinterManager(Context context) {
        appContext = context.getApplicationContext();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public void setStateListener(@Nullable StateListener listener) {
        stateListener = listener;
    }

    public void setDataListener(@Nullable DataListener listener) {
        dataListener = listener;
    }

    public boolean isAvailable() {
        return adapter != null;
    }

    @SuppressLint("MissingPermission")
    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public List<DeviceCandidate> bondedClassicDevices() {
        List<DeviceCandidate> result = new ArrayList<>();
        if (adapter == null || !hasConnectPermission()) return result;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            result.add(candidate(device, TransportType.CLASSIC_SPP, 0, new ArrayList<>()));
        }
        result.sort(Comparator.comparing(item -> item.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    @SuppressLint("MissingPermission")
    public void startBleScan(ScanListener listener) {
        if (adapter == null) {
            listener.onError("此设备不支持蓝牙");
            return;
        }
        if (!hasScanPermission()) {
            listener.onError("缺少附近设备扫描权限");
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("BLE 扫描器不可用，请确认蓝牙已开启");
            return;
        }
        stopBleScanInternal(false);
        scanListener = listener;
        scanResults.clear();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                publishScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) publishScanResult(result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                ScanListener current = scanListener;
                stopBleScanInternal(false);
                if (current != null) current.onError("BLE 扫描失败，错误码 " + errorCode);
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCallback);
        scanTimeout = () -> stopBleScanInternal(true);
        mainHandler.postDelayed(scanTimeout, BLE_SCAN_MILLIS);
    }

    public void stopBleScan() {
        mainHandler.post(() -> stopBleScanInternal(true));
    }

    public void connect(DeviceCandidate candidate, PrinterProfile profile, Callback callback) {
        if (!profile.supports(candidate.transport)) {
            error(callback, profile.label + " 不支持 " + candidate.transport.label);
            return;
        }
        disconnect();
        activeProfile = profile;
        updateState("正在连接 " + candidate.name + " · " + candidate.transport.label);
        lastError = "无";
        if (candidate.transport == TransportType.CLASSIC_SPP) {
            worker.execute(() -> connectClassic(candidate, callback));
        } else {
            connectBle(candidate, callback);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectClassic(DeviceCandidate candidate, Callback callback) {
        if (!hasConnectPermission()) {
            error(callback, "缺少附近设备连接权限");
            return;
        }
        try {
            adapter.cancelDiscovery();
            BluetoothSocket connected = tryClassicSocket(candidate.device, true);
            if (connected == null) connected = tryClassicSocket(candidate.device, false);
            if (connected == null) throw new IOException("安全和兼容 SPP 连接均失败");
            socket = connected;
            connectedCandidate = candidate;
            startClassicReader(connected);
            updateState("已连接 " + candidate.name + " · SPP");
            success(callback, "已连接 " + candidate.name + " · SPP");
        } catch (IOException | SecurityException exception) {
            closeClassic();
            fail(callback, "SPP 连接失败：" + readableMessage(exception));
        }
    }

    @SuppressLint("MissingPermission")
    @Nullable
    private BluetoothSocket tryClassicSocket(BluetoothDevice device, boolean secure) {
        BluetoothSocket attempt = null;
        ScheduledFuture<?> timeout = null;
        try {
            attempt = secure
                    ? device.createRfcommSocketToServiceRecord(SPP_UUID)
                    : device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            connectingSocket = attempt;
            BluetoothSocket socketToClose = attempt;
            timeout = scheduler.schedule(() -> closeQuietly(socketToClose),
                    CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            attempt.connect();
            return attempt;
        } catch (IOException ignored) {
            closeQuietly(attempt);
            return null;
        } finally {
            connectingSocket = null;
            if (timeout != null) timeout.cancel(false);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectBle(DeviceCandidate candidate, Callback callback) {
        if (!hasConnectPermission()) {
            error(callback, "缺少附近设备连接权限");
            return;
        }
        mainHandler.post(() -> {
            try {
                BluetoothGatt newGatt = candidate.device.connectGatt(appContext, false,
                        createGattCallback(candidate, callback), BluetoothDevice.TRANSPORT_LE);
                gatt = newGatt;
                if (newGatt == null) {
                    fail(callback, "BLE connectGatt 返回空连接");
                } else if (connectedCandidate == null) {
                    bleConnectTimeout = scheduler.schedule(() -> {
                        if (gatt == newGatt && connectedCandidate == null) {
                            closeBle(newGatt);
                            fail(callback, "BLE 连接超时（" + CONNECT_TIMEOUT_SECONDS + " 秒）");
                        }
                    }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (SecurityException exception) {
                fail(callback, "BLE 连接失败：" + readableMessage(exception));
            }
        });
    }

    private BluetoothGattCallback createGattCallback(DeviceCandidate candidate, Callback callback) {
        return new BluetoothGattCallback() {
            private boolean readyDelivered;
            private boolean deviceInfoStarted;
            private volatile int expectedDeviceInfoRead;
            private volatile int deviceInfoReadGeneration;

            @SuppressLint("MissingPermission")
            @Override
            public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
                if (gatt != bluetoothGatt) {
                    closeBle(bluetoothGatt);
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    updateState("BLE 已连接，正在发现 GATT 服务");
                    if (!bluetoothGatt.discoverServices()) fail(callback, "BLE 服务发现无法启动");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasConnected = connectedCandidate != null;
                    closeBle(bluetoothGatt);
                    updateState(wasConnected ? "BLE 连接已断开 · GATT " + status : "未连接");
                    if (!wasConnected) fail(callback, "BLE 连接断开，GATT 状态 " + status);
                }
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
                if (gatt != bluetoothGatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(callback, "BLE 服务发现失败，GATT 状态 " + status);
                    return;
                }
                BluetoothGattCharacteristic characteristic = selectWriteCharacteristic(bluetoothGatt, activeProfile);
                if (characteristic == null) {
                    fail(callback, "未找到可写 BLE 特征；请查看连接诊断并选择正确设备档案");
                    closeBle(bluetoothGatt);
                    return;
                }
                writeCharacteristic = characteristic;
                notifyCharacteristic = selectNotifyCharacteristic(bluetoothGatt, characteristic,
                        activeProfile);
                discoveredGattSummary = summarizeGatt(bluetoothGatt);
                notificationsSubscribed = false;
                negotiatedMtu = 23;
                bluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                if (!bluetoothGatt.requestMtu(247)) enableNotificationsThenReady(bluetoothGatt);
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onCharacteristicRead(BluetoothGatt bluetoothGatt,
                                             BluetoothGattCharacteristic characteristic,
                                             int status) {
                handleDeviceInfoRead(bluetoothGatt, characteristic,
                        characteristic.getValue(), status);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt bluetoothGatt,
                                             BluetoothGattCharacteristic characteristic,
                                             byte[] value, int status) {
                handleDeviceInfoRead(bluetoothGatt, characteristic, value, status);
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
                enableNotificationsThenReady(bluetoothGatt);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt bluetoothGatt,
                                          BluetoothGattDescriptor descriptor, int status) {
                if (CCCD_UUID.equals(descriptor.getUuid())) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        lastError = "BLE 通知订阅失败，GATT 状态 " + status;
                        if (requiresNotifications()) {
                            fail(callback, lastError);
                            closeBle(bluetoothGatt);
                            return;
                        }
                    } else {
                        notificationsSubscribed = true;
                    }
                    readDeviceInfoThenReady(bluetoothGatt);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                              BluetoothGattCharacteristic characteristic, int status) {
                bleWriteResults.offer(status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                                BluetoothGattCharacteristic characteristic) {
                publishReceived(characteristic.getValue());
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                                BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                publishReceived(value);
            }

            @SuppressLint("MissingPermission")
            private void enableNotificationsThenReady(BluetoothGatt bluetoothGatt) {
                if (gatt != bluetoothGatt || readyDelivered) return;
                if (!enableNotifications(bluetoothGatt, notifyCharacteristic)) {
                    if (requiresNotifications()) {
                        fail(callback, "P1 私有协议需要 BLE Notify，但通知特征或 CCCD 未就绪");
                        closeBle(bluetoothGatt);
                    } else {
                        readDeviceInfoThenReady(bluetoothGatt);
                    }
                }
            }

            @SuppressLint("MissingPermission")
            private void readDeviceInfoThenReady(BluetoothGatt bluetoothGatt) {
                if (gatt != bluetoothGatt || readyDelivered || deviceInfoStarted) return;
                deviceInfoStarted = true;
                BluetoothGattService service = bluetoothGatt.getService(DEVICE_INFORMATION_SERVICE);
                BluetoothGattCharacteristic model = service == null
                        ? null : service.getCharacteristic(MODEL_NUMBER);
                if (model != null) {
                    expectedDeviceInfoRead = 1;
                    if (bluetoothGatt.readCharacteristic(model)) {
                        scheduleDeviceInfoReadTimeout(bluetoothGatt);
                        return;
                    }
                    expectedDeviceInfoRead = 0;
                }
                readSoftwareThenReady(bluetoothGatt);
            }

            @SuppressLint("MissingPermission")
            private void readSoftwareThenReady(BluetoothGatt bluetoothGatt) {
                BluetoothGattService service = bluetoothGatt.getService(DEVICE_INFORMATION_SERVICE);
                BluetoothGattCharacteristic software = service == null
                        ? null : service.getCharacteristic(FIRMWARE_REVISION);
                if (software != null) {
                    expectedDeviceInfoRead = 2;
                    if (bluetoothGatt.readCharacteristic(software)) {
                        scheduleDeviceInfoReadTimeout(bluetoothGatt);
                        return;
                    }
                    expectedDeviceInfoRead = 0;
                }
                finishReady(bluetoothGatt);
            }

            private void scheduleDeviceInfoReadTimeout(BluetoothGatt bluetoothGatt) {
                int generation = ++deviceInfoReadGeneration;
                mainHandler.postDelayed(() -> {
                    if (gatt != bluetoothGatt || readyDelivered
                            || generation != deviceInfoReadGeneration) return;
                    expectedDeviceInfoRead = 0;
                    finishReady(bluetoothGatt);
                }, DEVICE_INFO_READ_TIMEOUT_MILLIS);
            }

            private void handleDeviceInfoRead(BluetoothGatt bluetoothGatt,
                                              BluetoothGattCharacteristic characteristic,
                                              @Nullable byte[] value, int status) {
                if (gatt != bluetoothGatt || readyDelivered) return;
                UUID uuid = characteristic.getUuid();
                if ((expectedDeviceInfoRead == 1 && !MODEL_NUMBER.equals(uuid))
                        || (expectedDeviceInfoRead == 2 && !FIRMWARE_REVISION.equals(uuid))
                        || expectedDeviceInfoRead == 0) return;
                expectedDeviceInfoRead = 0;
                deviceInfoReadGeneration++;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    String text = decodeGattText(value);
                    if (MODEL_NUMBER.equals(uuid)) {
                        deviceInfoModel = text;
                    } else if (FIRMWARE_REVISION.equals(uuid)) {
                        deviceInfoSoftware = text;
                    }
                }
                if (MODEL_NUMBER.equals(uuid)) {
                    readSoftwareThenReady(bluetoothGatt);
                } else if (FIRMWARE_REVISION.equals(uuid)) {
                    finishReady(bluetoothGatt);
                }
            }

            private void finishReady(BluetoothGatt bluetoothGatt) {
                if (gatt != bluetoothGatt || readyDelivered) return;
                readyDelivered = true;
                connectedCandidate = candidate;
                cancelBleConnectTimeout();
                updateState("已连接 " + candidate.name + " · BLE");
                success(callback, "已连接 " + candidate.name + " · BLE · "
                        + writeCharacteristic.getUuid());
            }

            private boolean requiresNotifications() {
                return activeProfile != null
                        && activeProfile.protocol == ProtocolType.DOTHAN_PRIVATE;
            }
        };
    }

    public void print(byte[] data, PrinterProfile profile, TransferCallback callback) {
        DeviceCandidate candidate = connectedCandidate;
        if (candidate == null) {
            error(callback, "请先连接打印机");
            return;
        }
        if (!profile.id.equals(activeProfile == null ? "" : activeProfile.id)) {
            error(callback, "当前连接使用的设备档案已变化，请重新连接后打印");
            return;
        }
        worker.execute(() -> {
            try {
                if (candidate.transport == TransportType.CLASSIC_SPP) writeClassic(data, profile, callback);
                else writeBle(data, profile, callback);
                lastTransferBytes = data.length;
                success(callback, "打印数据已发送 · " + data.length + " 字节");
            } catch (IOException exception) {
                fail(callback, "发送失败：" + readableMessage(exception));
                disconnect();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                fail(callback, "发送被中断");
                disconnect();
            }
        });
    }

    public void sendRaw(byte[] data, PrinterProfile profile, Callback callback) {
        print(data, profile, new TransferCallback() {
            @Override public void onProgress(int sentBytes, int totalBytes) {}
            @Override public void onSuccess(String message) { callback.onSuccess(message); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    private void writeClassic(byte[] data, PrinterProfile profile, TransferCallback callback) throws IOException, InterruptedException {
        BluetoothSocket activeSocket = socket;
        if (activeSocket == null || !activeSocket.isConnected()) throw new IOException("SPP 连接已断开");
        OutputStream output = activeSocket.getOutputStream();
        int chunkSize = profile.chunkBytes(TransportType.CLASSIC_SPP, 0);
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int count = Math.min(chunkSize, data.length - offset);
            output.write(data, offset, count);
            output.flush();
            progress(callback, offset + count, data.length);
            if (profile.writeDelayMs > 0) Thread.sleep(profile.writeDelayMs);
        }
    }

    @SuppressLint("MissingPermission")
    private void writeBle(byte[] data, PrinterProfile profile, TransferCallback callback) throws IOException, InterruptedException {
        BluetoothGatt activeGatt = gatt;
        BluetoothGattCharacteristic characteristic = writeCharacteristic;
        if (activeGatt == null || characteristic == null) throw new IOException("BLE 写通道未就绪");
        int properties = characteristic.getProperties();
        boolean noResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int writeType = noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        int chunkSize = profile.chunkBytes(TransportType.BLE_GATT, negotiatedMtu);
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int count = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[count];
            System.arraycopy(data, offset, chunk, 0, count);
            writeBleChunk(activeGatt, characteristic, chunk, writeType, noResponse, profile);
            progress(callback, offset + count, data.length);
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void writeBleChunk(BluetoothGatt activeGatt, BluetoothGattCharacteristic characteristic,
                               byte[] chunk, int writeType, boolean noResponse,
                               PrinterProfile profile) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= profile.maxWriteRetries; attempt++) {
            bleWriteResults.clear();
            boolean accepted;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                accepted = activeGatt.writeCharacteristic(characteristic, chunk, writeType)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(writeType);
                characteristic.setValue(chunk);
                accepted = activeGatt.writeCharacteristic(characteristic);
            }
            if (!accepted) {
                lastFailure = new IOException("GATT 写请求忙");
            } else if (noResponse) {
                Thread.sleep(Math.max(8, profile.writeDelayMs));
                return;
            } else {
                Integer status = bleWriteResults.poll(BLE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (status != null && status == BluetoothGatt.GATT_SUCCESS) return;
                lastFailure = new IOException(status == null ? "GATT 写入超时" : "GATT 写入状态 " + status);
            }
            Thread.sleep(Math.max(20, profile.writeDelayMs * (attempt + 1L)));
        }
        throw lastFailure == null ? new IOException("GATT 写入失败") : lastFailure;
    }

    @Nullable
    public DeviceCandidate connectedDevice() {
        return connectedCandidate;
    }

    public String diagnostics(PrinterProfile selectedProfile) {
        DeviceCandidate candidate = connectedCandidate;
        String device = candidate == null ? "无" : candidate.name + " / " + candidate.address;
        String transport = candidate == null ? "无" : candidate.transport.label;
        BluetoothGattCharacteristic characteristic = writeCharacteristic;
        return "状态：" + connectionState
                + "\n设备：" + device
                + "\n传输：" + transport
                + "\n设备档案：" + selectedProfile.label
                + "\n打印语言：" + selectedProfile.protocol.label
                + "\nDPI / 最大点宽：" + selectedProfile.dpi + " / " + selectedProfile.printableWidthDots
                + "\nBLE MTU / 写特征：" + negotiatedMtu + " / "
                + (characteristic == null ? "无" : characteristic.getUuid())
                + "\nBLE 通知特征：" + (notifyCharacteristic == null ? "无" : notifyCharacteristic.getUuid())
                + "\nBLE 通知订阅：" + (notificationsSubscribed ? "已确认" : "未确认")
                + "\n设备型号 / 软件版本：" + deviceInfoModel + " / " + deviceInfoSoftware
                + "\n可写 GATT 特征：" + discoveredGattSummary
                + "\n最近发送：" + lastTransferBytes + " 字节"
                + "\n最近接收：" + lastReceivedBytes + " 字节"
                + "\n最近错误：" + lastError;
    }

    public void disconnect() {
        cancelBleConnectTimeout();
        closeClassic();
        BluetoothGatt currentGatt = gatt;
        if (currentGatt != null) closeBle(currentGatt);
        connectedCandidate = null;
        activeProfile = null;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        notificationsSubscribed = false;
        negotiatedMtu = 23;
        discoveredGattSummary = "未发现";
        deviceInfoModel = "未读取";
        deviceInfoSoftware = "未读取";
        updateState("未连接");
    }

    public void close() {
        stopBleScan();
        disconnect();
        worker.shutdownNow();
        scheduler.shutdownNow();
        readerWorker.shutdownNow();
    }

    @SuppressLint("MissingPermission")
    private void publishScanResult(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        String address = safeAddress(device);
        if (address.isEmpty()) return;
        List<String> serviceUuids = new ArrayList<>();
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) serviceUuids.add(uuid.toString());
        }
        DeviceCandidate candidate = candidate(device, TransportType.BLE_GATT, result.getRssi(), serviceUuids);
        DeviceCandidate previous = scanResults.put(address, candidate);
        if (previous == null || previous.rssi != candidate.rssi) {
            ScanListener listener = scanListener;
            if (listener != null) listener.onDevice(candidate);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBleScanInternal(boolean notifyFinished) {
        if (scanTimeout != null) mainHandler.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (adapter != null && scanCallback != null && hasScanPermission()) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(scanCallback);
        }
        scanCallback = null;
        ScanListener listener = scanListener;
        scanListener = null;
        if (notifyFinished && listener != null) listener.onFinished();
    }

    @Nullable
    private static BluetoothGattCharacteristic selectWriteCharacteristic(BluetoothGatt gatt,
                                                                          PrinterProfile profile) {
        if (profile != null) {
            for (UUID serviceUuid : profile.preferredBleServices) {
                BluetoothGattService service = gatt.getService(serviceUuid);
                BluetoothGattCharacteristic found = findPreferredWrite(service, profile);
                if (found != null) return found;
            }
        }
        BluetoothGattCharacteristic fallback = null;
        for (BluetoothGattService service : gatt.getServices()) {
            BluetoothGattCharacteristic preferred = findPreferredWrite(service, profile);
            if (preferred != null) return preferred;
            if (profile != null && profile.protocol == ProtocolType.DOTHAN_PRIVATE
                    && FFE0_SERVICE.equals(service.getUuid())) {
                BluetoothGattCharacteristic ffe1 = service.getCharacteristic(FFE1_CHARACTERISTIC);
                if (ffe1 != null && isWritable(ffe1)) return ffe1;
                continue;
            }
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (isWritable(characteristic)) {
                    if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        return characteristic;
                    }
                    fallback = characteristic;
                }
            }
        }
        return fallback;
    }

    @Nullable
    private static BluetoothGattCharacteristic findPreferredWrite(@Nullable BluetoothGattService service,
                                                                   @Nullable PrinterProfile profile) {
        if (service == null || profile == null) return null;
        for (UUID uuid : profile.preferredBleWrites) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
            if (characteristic != null && isWritable(characteristic)) return characteristic;
        }
        return null;
    }

    private static boolean isWritable(BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    @Nullable
    private static BluetoothGattCharacteristic selectNotifyCharacteristic(BluetoothGatt bluetoothGatt,
                                                                           BluetoothGattCharacteristic write,
                                                                           @Nullable PrinterProfile profile) {
        if (profile != null && profile.protocol == ProtocolType.DOTHAN_PRIVATE) {
            UUID serviceUuid = write.getService().getUuid();
            if (P1_MICROCHIP_SERVICE.equals(serviceUuid)) {
                BluetoothGattCharacteristic exact = write.getService()
                        .getCharacteristic(P1_NOTIFY_UUID);
                return exact != null && isNotifiable(exact) ? exact : null;
            }
            if (DOTHAN_FF00_SERVICE.equals(serviceUuid)) {
                BluetoothGattCharacteristic ff01 = write.getService()
                        .getCharacteristic(DOTHAN_FF01_NOTIFY);
                return ff01 != null && isNotifyOnly(ff01) ? ff01 : null;
            }
            if (FFE0_SERVICE.equals(serviceUuid)) {
                BluetoothGattCharacteristic ffe1 = write.getService()
                        .getCharacteristic(FFE1_CHARACTERISTIC);
                return ffe1 != null && isNotifiable(ffe1) ? ffe1 : null;
            }
        }
        BluetoothGattCharacteristic fallback = null;
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (!isNotifiable(characteristic)) continue;
                if (service.getUuid().equals(write.getService().getUuid())) return characteristic;
                if (fallback == null) fallback = characteristic;
            }
        }
        return fallback;
    }

    private static boolean isNotifiable(BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    private static boolean isNotifyOnly(BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                && (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0;
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private static boolean enableNotifications(BluetoothGatt bluetoothGatt,
                                               @Nullable BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return false;
        if (!bluetoothGatt.setCharacteristicNotification(characteristic, true)) return false;
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) return false;
        boolean indicate = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        byte[] value = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return bluetoothGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        } else {
            descriptor.setValue(value);
            return bluetoothGatt.writeDescriptor(descriptor);
        }
    }

    private static String summarizeGatt(BluetoothGatt bluetoothGatt) {
        StringBuilder summary = new StringBuilder();
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (!isWritable(characteristic)) continue;
                if (summary.length() > 0) summary.append(", ");
                summary.append(service.getUuid()).append('/').append(characteristic.getUuid());
                if (summary.length() > 320) return summary.append("…").toString();
            }
        }
        return summary.length() == 0 ? "无" : summary.toString();
    }

    private static String decodeGattText(@Nullable byte[] value) {
        if (value == null || value.length == 0) return "未返回";
        String text = new String(value, StandardCharsets.UTF_8)
                .replace("\u0000", "").trim();
        return text.isEmpty() ? "未返回" : text;
    }

    @SuppressLint("MissingPermission")
    private void closeBle(BluetoothGatt bluetoothGatt) {
        try {
            bluetoothGatt.disconnect();
        } catch (SecurityException ignored) {
            // Continue closing the local GATT resources.
        }
        bluetoothGatt.close();
        if (gatt == bluetoothGatt) {
            gatt = null;
            writeCharacteristic = null;
            notifyCharacteristic = null;
            connectedCandidate = null;
        }
    }

    private void closeClassic() {
        readingClassic = false;
        BluetoothSocket current = socket;
        socket = null;
        closeQuietly(current);
        BluetoothSocket pending = connectingSocket;
        connectingSocket = null;
        closeQuietly(pending);
        if (connectedCandidate != null && connectedCandidate.transport == TransportType.CLASSIC_SPP) {
            connectedCandidate = null;
        }
    }

    private void startClassicReader(BluetoothSocket connected) {
        readingClassic = true;
        readerWorker.execute(() -> {
            try {
                InputStream input = connected.getInputStream();
                byte[] buffer = new byte[1024];
                while (readingClassic && socket == connected) {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count > 0) publishReceived(java.util.Arrays.copyOf(buffer, count));
                }
            } catch (IOException exception) {
                if (readingClassic && socket == connected) {
                    lastError = "SPP 读取失败：" + readableMessage(exception);
                    updateState(lastError);
                }
            }
        });
    }

    private void publishReceived(@Nullable byte[] value) {
        if (value == null || value.length == 0) return;
        byte[] copy = java.util.Arrays.copyOf(value, value.length);
        lastReceivedBytes = copy.length;
        Log.i("EPrint/P1", "RX BLE " + copy.length + " bytes " + hexPrefix(copy));
        DataListener listener = dataListener;
        if (listener != null) mainHandler.post(() -> listener.onBytes(copy));
    }

    private static String hexPrefix(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length && index < 24; index++) {
            if (index > 0) result.append(' ');
            result.append(String.format(java.util.Locale.US, "%02X", value[index] & 0xFF));
        }
        if (value.length > 24) result.append(" …");
        return result.toString();
    }

    private static void closeQuietly(@Nullable BluetoothSocket value) {
        if (value == null) return;
        try {
            value.close();
        } catch (IOException ignored) {
            // Socket already closed or unusable.
        }
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private static DeviceCandidate candidate(BluetoothDevice device, TransportType transport, int rssi,
                                             List<String> serviceUuids) {
        String address = safeAddress(device);
        String name = displayName(device);
        BluetoothClass bluetoothClass = device.getBluetoothClass();
        int majorClass = bluetoothClass == null ? 0 : bluetoothClass.getMajorDeviceClass();
        boolean rendering = bluetoothClass != null && bluetoothClass.hasService(BluetoothClass.Service.RENDER);
        return new DeviceCandidate(device, transport, name, address, rssi,
                device.getBondState() == BluetoothDevice.BOND_BONDED, majorClass,
                rendering, serviceUuids);
    }

    @SuppressLint("MissingPermission")
    public static String displayName(BluetoothDevice device) {
        String name = device.getName();
        String address = safeAddress(device);
        return name == null || name.trim().isEmpty() ? (address.isEmpty() ? "未命名蓝牙设备" : address) : name;
    }

    @SuppressLint("MissingPermission")
    private static String safeAddress(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private void progress(TransferCallback callback, int sent, int total) {
        mainHandler.post(() -> callback.onProgress(sent, total));
    }

    private void success(Callback callback, String message) {
        mainHandler.post(() -> callback.onSuccess(message));
    }

    private void error(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    private void fail(Callback callback, String message) {
        cancelBleConnectTimeout();
        lastError = message;
        updateState("错误 · " + message);
        error(callback, message);
    }

    private void updateState(String state) {
        connectionState = state;
        StateListener listener = stateListener;
        if (listener != null) mainHandler.post(() -> listener.onStateChanged(state));
    }

    private void cancelBleConnectTimeout() {
        ScheduledFuture<?> timeout = bleConnectTimeout;
        bleConnectTimeout = null;
        if (timeout != null) timeout.cancel(false);
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }
}

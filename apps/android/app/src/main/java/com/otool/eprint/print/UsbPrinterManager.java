package com.otool.eprint.print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.TransportType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UsbPrinterManager {
    public interface Callback {
        void onSuccess(String message);
        void onError(String message);
    }

    public interface TransferCallback extends Callback {
        void onProgress(int sentBytes, int totalBytes);
    }

    public interface DataListener {
        void onBytes(byte[] data);
    }

    public interface DeviceListener {
        void onDevicesChanged();
    }

    private static final String USB_PERMISSION = "com.otool.eprint.USB_PERMISSION";
    private final Context appContext;
    private final UsbManager usbManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver permissionReceiver;
    private final BroadcastReceiver deviceReceiver;
    private volatile UsbDeviceConnection connection;
    private volatile UsbInterface activeInterface;
    private volatile UsbEndpoint outputEndpoint;
    private volatile UsbEndpoint inputEndpoint;
    private volatile DeviceCandidate connectedCandidate;
    private volatile boolean reading;
    private volatile String lastError = "无";
    private volatile int lastTransferBytes;
    private DataListener dataListener;
    private DeviceListener deviceListener;
    private DeviceCandidate pendingCandidate;
    private Callback pendingCallback;

    public UsbPrinterManager(Context context) {
        appContext = context.getApplicationContext();
        usbManager = appContext.getSystemService(UsbManager.class);
        permissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handlePermission(intent);
            }
        };
        deviceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) handleDetached(intent);
                DeviceListener listener = deviceListener;
                if (listener != null) listener.onDevicesChanged();
            }
        };
        ContextCompat.registerReceiver(appContext, permissionReceiver,
                new IntentFilter(USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);
        IntentFilter deviceFilter = new IntentFilter();
        deviceFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        deviceFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(appContext, deviceReceiver, deviceFilter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    public void setDataListener(@Nullable DataListener listener) {
        dataListener = listener;
    }

    public void setDeviceListener(@Nullable DeviceListener listener) {
        deviceListener = listener;
    }

    public List<DeviceCandidate> devices() {
        List<DeviceCandidate> result = new ArrayList<>();
        if (usbManager == null) return result;
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            EndpointSelection selection = findEndpoints(device);
            if (selection == null) continue;
            String product = safeProductName(device);
            String name = product.isEmpty()
                    ? String.format(java.util.Locale.US, "USB %04X:%04X", device.getVendorId(), device.getProductId())
                    : product;
            String address = "usb:" + device.getVendorId() + ':' + device.getProductId() + ':' + device.getDeviceId();
            result.add(new DeviceCandidate(device, name, address, selection.printerClass));
        }
        result.sort(Comparator.comparing(value -> value.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void connect(DeviceCandidate candidate, PrinterProfile profile, Callback callback) {
        if (candidate.transport != TransportType.USB_HOST || candidate.usbDevice == null) {
            callback.onError("不是 USB 打印机端点");
            return;
        }
        if (!profile.supportsUsb) {
            callback.onError(profile.label + " 未启用 USB 传输");
            return;
        }
        UsbDevice device = candidate.usbDevice;
        if (!usbManager.hasPermission(device)) {
            pendingCandidate = candidate;
            pendingCallback = callback;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(appContext, 0,
                    new Intent(USB_PERMISSION).setPackage(appContext.getPackageName()), flags);
            usbManager.requestPermission(device, permissionIntent);
            return;
        }
        open(candidate, callback);
    }

    public void send(byte[] data, PrinterProfile profile, TransferCallback callback) {
        writer.execute(() -> {
            UsbDeviceConnection activeConnection = connection;
            UsbEndpoint endpoint = outputEndpoint;
            if (activeConnection == null || endpoint == null) {
                error(callback, "USB 打印机未连接");
                return;
            }
            int chunkSize = profile.chunkBytes(TransportType.USB_HOST, 0);
            for (int offset = 0; offset < data.length; offset += chunkSize) {
                int count = Math.min(chunkSize, data.length - offset);
                int written = activeConnection.bulkTransfer(endpoint, data, offset, count, 5000);
                if (written != count) {
                    lastError = "USB Bulk OUT 仅写入 " + written + "/" + count + " 字节";
                    error(callback, lastError);
                    return;
                }
                int sent = offset + count;
                mainHandler.post(() -> callback.onProgress(sent, data.length));
            }
            lastTransferBytes = data.length;
            success(callback, "USB 数据已发送 · " + data.length + " 字节");
        });
    }

    public void sendRaw(byte[] data, PrinterProfile profile, Callback callback) {
        send(data, profile, new TransferCallback() {
            @Override public void onProgress(int sentBytes, int totalBytes) {}
            @Override public void onSuccess(String message) { callback.onSuccess(message); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    @Nullable
    public DeviceCandidate connectedDevice() {
        return connectedCandidate;
    }

    public String diagnostics() {
        DeviceCandidate candidate = connectedCandidate;
        return "USB 设备：" + (candidate == null ? "无" : candidate.name + " / " + candidate.address)
                + "\nUSB 接口：" + (activeInterface == null ? "无" : activeInterface.getId())
                + "\nBulk OUT / IN：" + endpointName(outputEndpoint) + " / " + endpointName(inputEndpoint)
                + "\n最近发送：" + lastTransferBytes + " 字节"
                + "\n最近错误：" + lastError;
    }

    public synchronized void disconnect() {
        reading = false;
        UsbDeviceConnection current = connection;
        UsbInterface usbInterface = activeInterface;
        connection = null;
        activeInterface = null;
        outputEndpoint = null;
        inputEndpoint = null;
        connectedCandidate = null;
        if (current != null) {
            if (usbInterface != null) current.releaseInterface(usbInterface);
            current.close();
        }
    }

    public void close() {
        disconnect();
        writer.shutdownNow();
        reader.shutdownNow();
        try {
            appContext.unregisterReceiver(permissionReceiver);
            appContext.unregisterReceiver(deviceReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver already unregistered.
        }
    }

    private void handlePermission(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        DeviceCandidate candidate = pendingCandidate;
        Callback callback = pendingCallback;
        pendingCandidate = null;
        pendingCallback = null;
        if (candidate == null || callback == null || device == null) return;
        if (!granted) callback.onError("用户拒绝 USB 设备权限");
        else open(candidate, callback);
    }

    private void handleDetached(Intent intent) {
        UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        DeviceCandidate current = connectedCandidate;
        if (detached != null && current != null && current.usbDevice != null
                && detached.getDeviceId() == current.usbDevice.getDeviceId()) {
            lastError = "USB 设备已拔出";
            disconnect();
        }
    }

    private synchronized void open(DeviceCandidate candidate, Callback callback) {
        disconnect();
        EndpointSelection selection = findEndpoints(candidate.usbDevice);
        if (selection == null) {
            callback.onError("未找到 USB Bulk OUT 端点");
            return;
        }
        UsbDeviceConnection opened = usbManager.openDevice(candidate.usbDevice);
        if (opened == null) {
            callback.onError("无法打开 USB 设备");
            return;
        }
        if (!opened.claimInterface(selection.usbInterface, true)) {
            opened.close();
            callback.onError("无法占用 USB 打印接口");
            return;
        }
        connection = opened;
        activeInterface = selection.usbInterface;
        outputEndpoint = selection.output;
        inputEndpoint = selection.input;
        connectedCandidate = candidate;
        lastError = "无";
        startReader();
        callback.onSuccess("已连接 " + candidate.name + " · USB Host");
    }

    private void startReader() {
        if (inputEndpoint == null) return;
        reading = true;
        reader.execute(() -> {
            byte[] buffer = new byte[Math.max(64, inputEndpoint.getMaxPacketSize())];
            while (reading) {
                UsbDeviceConnection active = connection;
                UsbEndpoint endpoint = inputEndpoint;
                if (active == null || endpoint == null) return;
                int count = active.bulkTransfer(endpoint, buffer, buffer.length, 300);
                if (count > 0) {
                    byte[] received = java.util.Arrays.copyOf(buffer, count);
                    DataListener listener = dataListener;
                    if (listener != null) mainHandler.post(() -> listener.onBytes(received));
                }
            }
        });
    }

    @Nullable
    private static EndpointSelection findEndpoints(@Nullable UsbDevice device) {
        if (device == null) return null;
        EndpointSelection fallback = null;
        for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
            UsbInterface usbInterface = device.getInterface(interfaceIndex);
            UsbEndpoint output = null;
            UsbEndpoint input = null;
            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(endpointIndex);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) output = endpoint;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) input = endpoint;
            }
            if (output == null) continue;
            boolean printerClass = usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER
                    || device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER;
            EndpointSelection selection = new EndpointSelection(usbInterface, output, input, printerClass);
            if (printerClass) return selection;
            if (fallback == null) fallback = selection;
        }
        return fallback;
    }

    private static String safeProductName(UsbDevice device) {
        try {
            String manufacturer = device.getManufacturerName();
            String product = device.getProductName();
            String value = ((manufacturer == null ? "" : manufacturer + " ")
                    + (product == null ? "" : product)).trim();
            return value;
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static String endpointName(@Nullable UsbEndpoint endpoint) {
        return endpoint == null ? "无" : "0x" + Integer.toHexString(endpoint.getAddress());
    }

    private void success(Callback callback, String message) {
        mainHandler.post(() -> callback.onSuccess(message));
    }

    private void error(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    private static final class EndpointSelection {
        final UsbInterface usbInterface;
        final UsbEndpoint output;
        @Nullable final UsbEndpoint input;
        final boolean printerClass;

        EndpointSelection(UsbInterface usbInterface, UsbEndpoint output,
                          @Nullable UsbEndpoint input, boolean printerClass) {
            this.usbInterface = usbInterface;
            this.output = output;
            this.input = input;
            this.printerClass = printerClass;
        }
    }
}

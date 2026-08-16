package com.otool.eprint.device;

import android.bluetooth.BluetoothDevice;
import android.hardware.usb.UsbDevice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeviceCandidate {
    @Nullable public final BluetoothDevice device;
    @Nullable public final UsbDevice usbDevice;
    public final TransportType transport;
    public final String name;
    public final String address;
    public final int rssi;
    public final boolean bonded;
    public final int majorDeviceClass;
    public final boolean hasRenderingService;
    public final List<String> advertisedServiceUuids;
    public final boolean usbPrinterClass;
    public final int vendorId;
    public final int productId;

    public DeviceCandidate(BluetoothDevice device, TransportType transport, String name,
                           String address, int rssi, boolean bonded) {
        this(device, transport, name, address, rssi, bonded, 0, false,
                Collections.emptyList());
    }

    public DeviceCandidate(BluetoothDevice device, TransportType transport, String name,
                           String address, int rssi, boolean bonded, int majorDeviceClass,
                           boolean hasRenderingService, List<String> advertisedServiceUuids) {
        this.device = device;
        this.usbDevice = null;
        this.transport = transport;
        this.name = name;
        this.address = address;
        this.rssi = rssi;
        this.bonded = bonded;
        this.majorDeviceClass = majorDeviceClass;
        this.hasRenderingService = hasRenderingService;
        this.advertisedServiceUuids = Collections.unmodifiableList(new ArrayList<>(advertisedServiceUuids));
        this.usbPrinterClass = false;
        this.vendorId = 0;
        this.productId = 0;
    }

    public DeviceCandidate(UsbDevice usbDevice, String name, String address,
                           boolean usbPrinterClass) {
        this(usbDevice, name, address, usbPrinterClass,
                usbDevice.getVendorId(), usbDevice.getProductId());
    }

    DeviceCandidate(String name, String address, boolean usbPrinterClass,
                    int vendorId, int productId) {
        this(null, name, address, usbPrinterClass, vendorId, productId);
    }

    public static DeviceCandidate rememberedUsb(String name, String address,
                                                int vendorId, int productId) {
        return new DeviceCandidate(null, name, address, false, vendorId, productId);
    }

    private DeviceCandidate(@Nullable UsbDevice usbDevice, String name, String address,
                            boolean usbPrinterClass, int vendorId, int productId) {
        this.device = null;
        this.usbDevice = usbDevice;
        this.transport = TransportType.USB_HOST;
        this.name = name;
        this.address = address;
        this.rssi = 0;
        this.bonded = true;
        this.majorDeviceClass = 0;
        this.hasRenderingService = false;
        this.advertisedServiceUuids = Collections.emptyList();
        this.usbPrinterClass = usbPrinterClass;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    public String displayLabel() {
        String signal = transport == TransportType.BLE_GATT && rssi != 0 ? " · " + rssi + " dBm" : "";
        return name + "\n" + transport.label + " · " + address + signal;
    }
}

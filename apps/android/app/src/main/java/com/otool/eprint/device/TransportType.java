package com.otool.eprint.device;

public enum TransportType {
    CLASSIC_SPP("经典蓝牙 SPP"),
    BLE_GATT("低功耗蓝牙 BLE"),
    USB_HOST("USB Host");

    public final String label;

    TransportType(String label) {
        this.label = label;
    }
}

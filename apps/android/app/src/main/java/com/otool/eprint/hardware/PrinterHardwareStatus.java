package com.otool.eprint.hardware;

public final class PrinterHardwareStatus {
    public final boolean connected;
    public final boolean busy;
    public final HardwareValue paper;
    public final HardwareValue cover;
    public final HardwareValue temperature;
    public final int batteryPercent;
    public final boolean responseConfirmed;
    public final String detail;
    public final long updatedAt;

    public PrinterHardwareStatus(boolean connected, boolean busy, HardwareValue paper,
                                 HardwareValue cover, HardwareValue temperature,
                                 int batteryPercent, boolean responseConfirmed,
                                 String detail, long updatedAt) {
        this.connected = connected;
        this.busy = busy;
        this.paper = paper;
        this.cover = cover;
        this.temperature = temperature;
        this.batteryPercent = batteryPercent;
        this.responseConfirmed = responseConfirmed;
        this.detail = detail;
        this.updatedAt = updatedAt;
    }

    public static PrinterHardwareStatus disconnected(String detail) {
        return new PrinterHardwareStatus(false, false, HardwareValue.UNKNOWN,
                HardwareValue.UNKNOWN, HardwareValue.UNKNOWN, -1, false,
                detail, System.currentTimeMillis());
    }

    public static PrinterHardwareStatus connected(String detail) {
        return new PrinterHardwareStatus(true, false, HardwareValue.UNKNOWN,
                HardwareValue.UNKNOWN, HardwareValue.UNKNOWN, -1, false,
                detail, System.currentTimeMillis());
    }

    public PrinterHardwareStatus with(boolean busy, HardwareValue paper, HardwareValue cover,
                                      HardwareValue temperature, boolean confirmed, String detail) {
        return new PrinterHardwareStatus(connected, busy, paper, cover, temperature,
                batteryPercent, confirmed, detail, System.currentTimeMillis());
    }
}

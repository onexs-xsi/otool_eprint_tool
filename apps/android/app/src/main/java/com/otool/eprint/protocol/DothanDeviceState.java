package com.otool.eprint.protocol;

/** Mutable device state assembled from Dothan receive commands. */
public final class DothanDeviceState {
    public int dpi = -1;
    public int widthDots = -1;
    public int density = -1;
    public int speed = -1;
    public int gapType = -1;
    public int gapHundredthsMm = -1;
    public int autoFeed = -1;
    public int beep = -1;
    public int autoPowerOffMinutes = -1;
    public int motorMode = -1;
    public int language = -1;
    public int bufferBytes = -1;
    public int singleStatus = -1;
    public int addressType = -1;
    public String macAddress = "";
    public String firmwareVersion = "";
    public int paramByte1 = -1;
    public int paramByte6 = -1;
    public int paramByte9 = -1;
    public int printerStatus = -1;
    public int batteryCount = -1;
    public boolean isConnectedAdapter;
    public int chargeStatus = -1;
    public int manufacturerFieldD = -1;
    public int manufacturerFieldE = -1;
    public boolean flagJ;
    public boolean flagI;
    public boolean flagH;
    public int[] lastArray = new int[0];
    public int[] counters = new int[0];
    public String deviceName = "";
    public int receiptStatus = -1;
    public int receiptPage = -1;
    public int receiptCopies = -1;
    public DothanManufacturerInfo manufacturerInfo;
    public byte[] authReply = new byte[0];
    public byte[] fullParameters = new byte[0];

    public void reset() {
        dpi = -1;
        widthDots = -1;
        density = -1;
        speed = -1;
        gapType = -1;
        gapHundredthsMm = -1;
        autoFeed = -1;
        beep = -1;
        autoPowerOffMinutes = -1;
        motorMode = -1;
        language = -1;
        bufferBytes = -1;
        singleStatus = -1;
        addressType = -1;
        macAddress = "";
        firmwareVersion = "";
        paramByte1 = -1;
        paramByte6 = -1;
        paramByte9 = -1;
        printerStatus = -1;
        batteryCount = -1;
        isConnectedAdapter = false;
        chargeStatus = -1;
        manufacturerFieldD = -1;
        manufacturerFieldE = -1;
        flagJ = false;
        flagI = false;
        flagH = false;
        lastArray = new int[0];
        counters = new int[0];
        deviceName = "";
        receiptStatus = -1;
        receiptPage = -1;
        receiptCopies = -1;
        manufacturerInfo = null;
        authReply = new byte[0];
        fullParameters = new byte[0];
    }

    public String addressTypeName() {
        if (addressType >= 248) return "USB";
        if (addressType >= 240) return "WiFi";
        if (addressType == 31) return "DUAL";
        if (addressType == 20) return "BLE";
        if (addressType == 16) return "SPP";
        return "未知(" + addressType + ")";
    }
}

package com.otool.eprint.device;

import com.otool.eprint.protocol.ProtocolType;
import com.otool.eprint.protocol.DothanCompressionMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class PrinterProfile {
    public final String id;
    public final String label;
    public final ProtocolType protocol;
    public final int dpi;
    public final int printableWidthDots;
    public final boolean supportsSpp;
    public final boolean supportsBle;
    public final boolean supportsUsb;
    public final int sppChunkBytes;
    public final int bleChunkBytes;
    public final int usbChunkBytes;
    public final int writeDelayMs;
    public final int maxWriteRetries;
    public final int threshold;
    public final int feedLines;
    public final String manufacturer;
    public final String deviceType;
    public final int printDensity;
    public final int printSpeed;
    public final PaperType paperType;
    public final int gapLengthTenthsMm;
    public final boolean autoFeed;
    public final boolean beep;
    public final int autoPowerOffMinutes;
    public final int motorMode;
    public final int language;
    public final boolean applyDothanExtras;
    public final DothanCompressionMode compression;
    public final boolean useDefaultDothanAuth;
    public final String helloTokenHex;
    public final String loginTokenHex;
    public final List<String> nameTokens;
    public final List<UUID> preferredBleServices;
    public final List<UUID> preferredBleWrites;

    public PrinterProfile(String id, String label, ProtocolType protocol, int dpi,
                          int printableWidthDots, boolean supportsSpp, boolean supportsBle,
                          int sppChunkBytes, int bleChunkBytes, int writeDelayMs,
                          int maxWriteRetries, int threshold, int feedLines,
                          String[] nameTokens, String[] serviceUuids, String[] writeUuids) {
        this(id, label, protocol, dpi, printableWidthDots, supportsSpp, supportsBle,
                true, sppChunkBytes, bleChunkBytes, 16_384, writeDelayMs, maxWriteRetries,
                threshold, feedLines, nameTokens, serviceUuids, writeUuids);
    }

    public PrinterProfile(String id, String label, ProtocolType protocol, int dpi,
                          int printableWidthDots, boolean supportsSpp, boolean supportsBle,
                          boolean supportsUsb, int sppChunkBytes, int bleChunkBytes,
                          int usbChunkBytes, int writeDelayMs, int maxWriteRetries,
                          int threshold, int feedLines, String[] nameTokens,
                          String[] serviceUuids, String[] writeUuids) {
        this.id = id;
        this.label = label;
        this.protocol = protocol;
        this.dpi = dpi;
        this.printableWidthDots = printableWidthDots;
        this.supportsSpp = supportsSpp;
        this.supportsBle = supportsBle;
        this.supportsUsb = supportsUsb;
        this.sppChunkBytes = sppChunkBytes;
        this.bleChunkBytes = bleChunkBytes;
        this.usbChunkBytes = usbChunkBytes;
        this.writeDelayMs = writeDelayMs;
        this.maxWriteRetries = maxWriteRetries;
        this.threshold = threshold;
        this.feedLines = feedLines;
        this.manufacturer = "待设备识别";
        this.deviceType = protocol == ProtocolType.ESC_POS ? "热敏票据打印机" : "热敏标签打印机";
        this.printDensity = protocol == ProtocolType.TSPL ? 8 : 15;
        this.printSpeed = protocol == ProtocolType.TSPL ? 4 : 1;
        this.paperType = protocol == ProtocolType.ESC_POS ? PaperType.RECEIPT : PaperType.GAP_LABEL;
        this.gapLengthTenthsMm = protocol == ProtocolType.ESC_POS ? 0 : 30;
        this.autoFeed = true;
        this.beep = false;
        this.autoPowerOffMinutes = 0;
        this.motorMode = 0;
        this.language = 0;
        this.applyDothanExtras = false;
        this.compression = DothanCompressionMode.UNCOMPRESSED;
        this.useDefaultDothanAuth = false;
        this.helloTokenHex = "";
        this.loginTokenHex = "";
        this.nameTokens = Collections.unmodifiableList(Arrays.asList(nameTokens));
        this.preferredBleServices = toUuids(serviceUuids);
        this.preferredBleWrites = toUuids(writeUuids);
    }

    private PrinterProfile(PrinterProfile base, int widthDots, int sppChunk, int bleChunk,
                           int usbChunk, int delayMs, int retries, int rasterThreshold,
                           String[] serviceUuids, String[] writeUuids, String manufacturer,
                           String deviceType, int printDensity, int printSpeed,
                           PaperType paperType, int gapLengthTenthsMm, boolean autoFeed,
                           boolean beep, int autoPowerOffMinutes, int motorMode, int language,
                           boolean applyDothanExtras, DothanCompressionMode compression,
                           boolean useDefaultDothanAuth, String helloTokenHex,
                           String loginTokenHex) {
        this.id = base.id;
        this.label = base.label;
        this.protocol = base.protocol;
        this.dpi = base.dpi;
        this.printableWidthDots = widthDots;
        this.supportsSpp = base.supportsSpp;
        this.supportsBle = base.supportsBle;
        this.supportsUsb = base.supportsUsb;
        this.sppChunkBytes = sppChunk;
        this.bleChunkBytes = bleChunk;
        this.usbChunkBytes = usbChunk;
        this.writeDelayMs = delayMs;
        this.maxWriteRetries = retries;
        this.threshold = rasterThreshold;
        this.feedLines = base.feedLines;
        this.manufacturer = manufacturer;
        this.deviceType = deviceType;
        this.printDensity = printDensity;
        this.printSpeed = printSpeed;
        this.paperType = paperType;
        this.gapLengthTenthsMm = gapLengthTenthsMm;
        this.autoFeed = autoFeed;
        this.beep = beep;
        this.autoPowerOffMinutes = autoPowerOffMinutes;
        this.motorMode = motorMode;
        this.language = language;
        this.applyDothanExtras = applyDothanExtras;
        this.compression = compression;
        this.useDefaultDothanAuth = useDefaultDothanAuth;
        this.helloTokenHex = helloTokenHex == null ? "" : helloTokenHex;
        this.loginTokenHex = loginTokenHex == null ? "" : loginTokenHex;
        this.nameTokens = base.nameTokens;
        this.preferredBleServices = toUuids(serviceUuids);
        this.preferredBleWrites = toUuids(writeUuids);
    }

    public float printableWidthMm() {
        return printableWidthDots * 25.4f / dpi;
    }

    public boolean supports(TransportType transport) {
        if (transport == TransportType.CLASSIC_SPP) return supportsSpp;
        if (transport == TransportType.BLE_GATT) return supportsBle;
        return supportsUsb;
    }

    public int chunkBytes(TransportType transport, int negotiatedMtu) {
        if (transport == TransportType.CLASSIC_SPP) return sppChunkBytes;
        if (transport == TransportType.USB_HOST) return usbChunkBytes;
        int mtuPayload = Math.max(20, negotiatedMtu - 3);
        return Math.max(20, Math.min(bleChunkBytes, mtuPayload));
    }

    @Override
    public String toString() {
        return label;
    }

    public PrinterProfile withHardwareSettings(int widthDots, int sppChunk, int bleChunk,
                                               int usbChunk, int delayMs, int retries,
                                               int rasterThreshold, String[] serviceUuids,
                                               String[] writeUuids) {
        return new PrinterProfile(this, widthDots, sppChunk, bleChunk, usbChunk, delayMs,
                retries, rasterThreshold, serviceUuids, writeUuids, manufacturer, deviceType,
                printDensity, printSpeed, paperType, gapLengthTenthsMm, autoFeed, beep,
                autoPowerOffMinutes, motorMode, language, applyDothanExtras, compression,
                useDefaultDothanAuth, helloTokenHex, loginTokenHex);
    }

    public PrinterProfile withDeviceSettings(String maker, String type, int density, int speed,
                                              PaperType media, int gapTenthsMm,
                                              boolean shouldAutoFeed, boolean shouldBeep,
                                              int powerOffMinutes) {
        return new PrinterProfile(this, printableWidthDots, sppChunkBytes, bleChunkBytes,
                usbChunkBytes, writeDelayMs, maxWriteRetries, threshold,
                preferredBleServiceStrings(), preferredBleWriteStrings(), maker, type,
                density, speed, media, gapTenthsMm, shouldAutoFeed, shouldBeep,
                powerOffMinutes, motorMode, language, applyDothanExtras, compression,
                useDefaultDothanAuth, helloTokenHex, loginTokenHex);
    }

    public PrinterProfile withDothanOptions(DothanCompressionMode compression,
                                            String helloTokenHex, String loginTokenHex) {
        return withDothanOptions(compression, useDefaultDothanAuth,
                helloTokenHex, loginTokenHex);
    }

    public PrinterProfile withDothanOptions(DothanCompressionMode compression,
                                            boolean useDefaultAuth,
                                            String helloTokenHex, String loginTokenHex) {
        return new PrinterProfile(this, printableWidthDots, sppChunkBytes, bleChunkBytes,
                usbChunkBytes, writeDelayMs, maxWriteRetries, threshold,
                preferredBleServiceStrings(), preferredBleWriteStrings(), manufacturer,
                deviceType, printDensity, printSpeed, paperType, gapLengthTenthsMm, autoFeed,
                beep, autoPowerOffMinutes, motorMode, language, applyDothanExtras,
                compression == null ? DothanCompressionMode.UNCOMPRESSED : compression,
                useDefaultAuth, helloTokenHex, loginTokenHex);
    }

    public PrinterProfile withDothanExtras(int motorMode, int language) {
        return withDothanExtras(motorMode, language, applyDothanExtras);
    }

    public PrinterProfile withDothanExtras(int motorMode, int language,
                                           boolean shouldApplyExtras) {
        return new PrinterProfile(this, printableWidthDots, sppChunkBytes, bleChunkBytes,
                usbChunkBytes, writeDelayMs, maxWriteRetries, threshold,
                preferredBleServiceStrings(), preferredBleWriteStrings(), manufacturer,
                deviceType, printDensity, printSpeed, paperType, gapLengthTenthsMm, autoFeed,
                beep, autoPowerOffMinutes, motorMode, language, shouldApplyExtras, compression,
                useDefaultDothanAuth, helloTokenHex, loginTokenHex);
    }

    public String[] preferredBleServiceStrings() {
        return uuidStrings(preferredBleServices);
    }

    public String[] preferredBleWriteStrings() {
        return uuidStrings(preferredBleWrites);
    }

    private static List<UUID> toUuids(String[] values) {
        java.util.ArrayList<UUID> result = new java.util.ArrayList<>();
        for (String value : values) result.add(UUID.fromString(value));
        return Collections.unmodifiableList(result);
    }

    private static String[] uuidStrings(List<UUID> values) {
        String[] result = new String[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index).toString();
        return result;
    }
}

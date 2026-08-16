package com.otool.eprint.device;

import com.otool.eprint.protocol.ProtocolType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DeviceCatalog {
    private static final PrinterIdentity DOTHAN_P1 = new PrinterIdentity(
            "P1 系列", "德佟电子（DothanTech）", "热敏标签打印机",
            203, 384, 48f, true);
    private static final String[] BLE_SERVICES = {
            "0000FFE0-0000-1000-8000-00805F9B34FB",
            "0000FF00-0000-1000-8000-00805F9B34FB",
            "000018F0-0000-1000-8000-00805F9B34FB",
            "49535343-FE7D-4AE5-8FA9-9FAFD205E455"
    };
    private static final String[] BLE_WRITES = {
            "0000FFE1-0000-1000-8000-00805F9B34FB",
            "0000FF01-0000-1000-8000-00805F9B34FB",
            "0000FF02-0000-1000-8000-00805F9B34FB",
            "00002AF1-0000-1000-8000-00805F9B34FB",
            "49535343-8841-43F4-A8D4-ECBE34729BB3"
    };
    private static final String[] P1_BLE_SERVICES = {
            "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
            "0000FF00-0000-1000-8000-00805F9B34FB"
    };
    private static final String[] P1_BLE_WRITES = {
            "49535343-8841-43F4-A8D4-ECBE34729BB3",
            "0000FF01-0000-1000-8000-00805F9B34FB",
            "0000FF02-0000-1000-8000-00805F9B34FB"
    };

    private static final List<PrinterProfile> PROFILES = Collections.unmodifiableList(Arrays.asList(
            p1Profile(),
            profile("escpos_58_203", "通用 58 mm · ESC/POS · 203 DPI", ProtocolType.ESC_POS,
                    203, 384, true, true, 1024, 180, 8, 2, 180, 3,
                    new String[]{"POS58", "POS-58", "BT58", "RPP02", "MPT-II", "PT-210"}),
            profile("escpos_80_203", "通用 80 mm · ESC/POS · 203 DPI", ProtocolType.ESC_POS,
                    203, 576, true, true, 1024, 180, 8, 2, 180, 3,
                    new String[]{"POS80", "POS-80", "BT80", "RPP300", "TM-T", "RP80"}),
            profile("escpos_80_300", "80 mm 高分辨率 · ESC/POS · 300 DPI", ProtocolType.ESC_POS,
                    300, 864, true, true, 1024, 180, 10, 2, 180, 3,
                    new String[]{"300DPI POS", "POS300"}),
            profile("tspl_4in_203", "TSC/兼容 4 英寸 · TSPL · 203 DPI", ProtocolType.TSPL,
                    203, 832, true, true, 1024, 180, 10, 2, 180, 0,
                    new String[]{"TSC", "TSPL", "DA200", "DA210", "DA220", "TE200", "TE210", "TTP-", "TDP-"}),
            profile("cpcl_3in_203", "Zebra 移动 3 英寸 · CPCL · 203 DPI", ProtocolType.CPCL,
                    203, 576, true, true, 1024, 180, 12, 2, 180, 0,
                    new String[]{"ZQ610", "ZQ620", "QLN320", "QL320", "RW320"}),
            profile("cpcl_4in_203", "Zebra 移动 4 英寸 · CPCL · 203 DPI", ProtocolType.CPCL,
                    203, 832, true, true, 1024, 180, 12, 2, 180, 0,
                    new String[]{"ZQ510", "ZQ520", "ZQ630", "QLN420", "QL420", "RW420"}),
            profile("zpl_4in_203", "Zebra 桌面 4 英寸 · ZPL · 203 DPI", ProtocolType.ZPL,
                    203, 832, true, true, 1024, 180, 10, 2, 180, 0,
                    new String[]{"ZEBRA", "ZD", "ZT", "GK", "GX", "LP2844"})
    ));

    private DeviceCatalog() {}

    public static List<PrinterProfile> profiles() {
        return PROFILES;
    }

    public static PrinterProfile defaultProfile() {
        return PROFILES.get(1);
    }

    public static PrinterProfile find(String id) {
        for (PrinterProfile profile : PROFILES) {
            if (profile.id.equals(id)) return profile;
        }
        return defaultProfile();
    }

    public static int indexOf(String id) {
        for (int index = 0; index < PROFILES.size(); index++) {
            if (PROFILES.get(index).id.equals(id)) return index;
        }
        return indexOf(defaultProfile().id);
    }

    public static PrinterProfile match(String deviceName, TransportType transport) {
        PrinterProfile known = matchKnown(deviceName, transport);
        return known == null ? defaultProfile() : known;
    }

    public static PrinterProfile matchKnown(String deviceName, TransportType transport) {
        String normalized = deviceName == null ? "" : deviceName.toUpperCase(Locale.ROOT);
        for (PrinterProfile profile : PROFILES) {
            if (!profile.supports(transport)) continue;
            for (String token : profile.nameTokens) {
                if (normalized.contains(token)) return profile;
            }
        }
        return null;
    }

    public static PrinterIdentity identify(String deviceName, TransportType transport) {
        String normalized = deviceName == null ? "" : deviceName.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("P1[-_][0-9]{6,}")) return DOTHAN_P1;
        PrinterProfile profile = matchKnown(deviceName, transport);
        if (profile == null) return null;
        return new PrinterIdentity(profile.label, profile.manufacturer, profile.deviceType,
                profile.dpi, profile.printableWidthDots, profile.printableWidthMm(), false);
    }

    public static boolean looksPrivateProtocol(String deviceName) {
        if (deviceName == null) return false;
        String value = deviceName.toUpperCase(Locale.ROOT);
        return value.matches("P1[-_][0-9]{6,}") || value.startsWith("DP")
                || value.startsWith("D11") || value.contains("WEPRINT");
    }

    private static PrinterProfile profile(String id, String label, ProtocolType protocol, int dpi,
                                          int widthDots, boolean spp, boolean ble, int sppChunk,
                                          int bleChunk, int delay, int retries, int threshold,
                                          int feedLines, String[] tokens) {
        PrinterProfile base = new PrinterProfile(id, label, protocol, dpi, widthDots, spp, ble,
                sppChunk, bleChunk, delay, retries, threshold, feedLines, tokens,
                BLE_SERVICES, BLE_WRITES);
        String manufacturer;
        String type;
        if (id.startsWith("tspl")) {
            manufacturer = "TSC / TSPL 兼容厂商";
            type = "热敏标签打印机";
        } else if (id.startsWith("cpcl") || id.startsWith("zpl")) {
            manufacturer = "Zebra / 兼容厂商";
            type = id.startsWith("cpcl") ? "移动热敏标签打印机" : "桌面热敏标签打印机";
        } else {
            manufacturer = "通用 ESC/POS 厂商";
            type = "热敏票据打印机";
        }
        return base.withDeviceSettings(manufacturer, type, base.printDensity, base.printSpeed,
                base.paperType, base.gapLengthTenthsMm, base.autoFeed, base.beep,
                base.autoPowerOffMinutes);
    }

    private static PrinterProfile p1Profile() {
        PrinterProfile base = new PrinterProfile("p1_private_203",
                "P1 · 德佟私有协议 · 203 DPI", ProtocolType.DOTHAN_PRIVATE,
                203, 384, true, true, false,
                1024, 180, 16_384, 8, 2, 192, 0,
                new String[]{"P1-", "P1_"}, P1_BLE_SERVICES, P1_BLE_WRITES);
        return base.withDeviceSettings("德佟电子（DothanTech）", "热敏标签打印机",
                15, 1, PaperType.GAP_LABEL, 30, true, true, 15);
    }
}

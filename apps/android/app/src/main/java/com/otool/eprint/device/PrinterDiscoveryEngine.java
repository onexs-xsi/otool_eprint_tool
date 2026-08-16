package com.otool.eprint.device;

import android.bluetooth.BluetoothClass;

import androidx.annotation.Nullable;

import com.otool.eprint.protocol.ProtocolType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PrinterDiscoveryEngine {
    private static final String[] PRINTER_WORDS = {
            "PRINTER", "PRINT", "THERMAL", "LABEL", "POS", "TSC", "ZEBRA",
            "EPSON", "BIXOLON", "STAR", "CITIZEN", "SEWOO", "GPRINTER",
            "HPRT", "MUNBYN", "PERIPAGE", "CAYSN", "RONGTA", "XPRINTER"
    };
    private static final String[] NON_PRINTER_WORDS = {
            "HEADPHONE", "HEADSET", "EARBUD", "AIRPOD", "SPEAKER", "WATCH",
            "BAND", "TV", "KEYBOARD", "MOUSE", "GAMEPAD", "CAR AUDIO"
    };
    private static final String[] COMMON_PRINT_SERVICES = {
            "0000FFE0", "0000FF00", "000018F0"
    };

    private PrinterDiscoveryEngine() {}

    public static PrinterMatch evaluate(DeviceCandidate candidate, @Nullable String lastAddress) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        String normalized = candidate.name == null ? "" : candidate.name.toUpperCase(Locale.ROOT);
        boolean lastUsed = lastAddress != null && !lastAddress.isEmpty()
                && lastAddress.equalsIgnoreCase(candidate.address);
        PrinterProfile profile = DeviceCatalog.matchKnown(candidate.name, candidate.transport);
        PrinterIdentity identity = DeviceCatalog.identify(candidate.name, candidate.transport);
        boolean privateRisk = DeviceCatalog.looksPrivateProtocol(candidate.name)
                && (profile == null || profile.protocol != ProtocolType.DOTHAN_PRIVATE);

        if (lastUsed) {
            score += 90;
            reasons.add("上次成功连接");
        }
        if (identity != null) {
            score += 85;
            reasons.add("识别为" + identity.manufacturer + " " + identity.family);
        } else if (profile != null) {
            score += 85;
            reasons.add("命中设备档案名称族");
        } else if (containsAny(normalized, PRINTER_WORDS)) {
            score += 42;
            reasons.add("名称包含打印机特征");
        }
        if (privateRisk) {
            score += 45;
            reasons.add("疑似私有协议打印机");
        }
        if (candidate.transport == TransportType.USB_HOST && candidate.usbPrinterClass) {
            score += 90;
            reasons.add("USB Printer Class 接口");
        } else if (candidate.transport == TransportType.USB_HOST) {
            score += 28;
            reasons.add("USB 设备具备 Bulk 输出端点");
        }
        if (candidate.majorDeviceClass == BluetoothClass.Device.Major.IMAGING) {
            score += 24;
            reasons.add("蓝牙类别为成像设备");
        }
        if (candidate.hasRenderingService) {
            score += 22;
            reasons.add("声明 Rendering 服务");
        }
        if (hasKnownPrintService(candidate.advertisedServiceUuids)) {
            score += 32;
            reasons.add("广播常见 BLE 打印服务");
        }
        if (candidate.bonded && candidate.transport != TransportType.USB_HOST) {
            score += 5;
            reasons.add("已配对");
        }
        if (candidate.transport == TransportType.BLE_GATT && candidate.rssi >= -65) {
            score += 5;
            reasons.add("信号较强");
        }
        if (containsAny(normalized, NON_PRINTER_WORDS)) {
            score -= 90;
            reasons.add("名称更像非打印外设");
        }
        int major = candidate.majorDeviceClass;
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO
                || major == BluetoothClass.Device.Major.PHONE
                || major == BluetoothClass.Device.Major.COMPUTER
                || major == BluetoothClass.Device.Major.WEARABLE) {
            score -= 55;
            reasons.add("蓝牙类别不是打印设备");
        }

        score = Math.max(0, Math.min(100, score));
        String confidence = score >= 85 ? "高可信" : score >= 60 ? "较可信"
                : score >= 40 ? "可能" : "低可信";
        String reason = reasons.isEmpty() ? "未发现明确打印机特征" : join(reasons);
        return new PrinterMatch(score, confidence, reason, profile, identity, privateRisk);
    }

    private static boolean hasKnownPrintService(List<String> values) {
        for (String value : values) {
            String normalized = value.toUpperCase(Locale.ROOT);
            for (String prefix : COMMON_PRINT_SERVICES) {
                if (normalized.contains(prefix)) return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String[] tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("、");
            result.append(value);
        }
        return result.toString();
    }
}

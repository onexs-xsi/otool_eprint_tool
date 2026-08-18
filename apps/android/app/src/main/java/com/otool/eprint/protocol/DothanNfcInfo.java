package com.otool.eprint.protocol;

import java.util.Locale;

/**
 * Decodes the Dothan NFC "tap-to-connect" payload described in the WePrint analysis.
 * The payload starts with 0xAA and ends with 0xBB; the second byte is a length check.
 */
public final class DothanNfcInfo {
    public int deviceType;
    public String macAddress = "";
    public int addressType;
    public int dpi;
    public int widthDots;

    public static boolean looksLikeNfcPayload(byte[] payload) {
        return payload != null && payload.length >= 14
                && (payload[0] & 0xFF) == 0xAA
                && (payload[payload.length - 1] & 0xFF) == 0xBB;
    }

    public boolean parse(byte[] payload) {
        if (!looksLikeNfcPayload(payload)) return false;
        int expectedLength = payload[1] & 0xFF;
        if (expectedLength != payload.length - 3) return false;
        if (payload.length <= 25) return false;
        deviceType = payload[10] & 0xFF;
        StringBuilder mac = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            if (index > 0) mac.append(':');
            mac.append(String.format(Locale.US, "%02X", payload[7 - index] & 0xFF));
        }
        macAddress = mac.toString();
        addressType = payload[15] & 0xFF;
        dpi = ((payload[11] & 0xFF) << 8) | (payload[12] & 0xFF);
        widthDots = ((payload[13] & 0xFF) << 8) | (payload[14] & 0xFF);
        return true;
    }

    public String summary() {
        return "NFC 设备类型 " + deviceType + " · MAC " + macAddress
                + " · 通道类型 " + addressType + " · " + dpi + " DPI / "
                + widthDots + " 点";
    }
}

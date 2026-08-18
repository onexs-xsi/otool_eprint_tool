package com.otool.eprint.protocol;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Central receive-command parser for the Dothan private protocol. */
public final class DothanReceiveParser {
    private DothanReceiveParser() {}

    public static void parse(DothanFrame frame, DothanDeviceState state) {
        if (frame == null || state == null || frame.isText()) return;
        byte[] payload = frame.payload;
        switch (frame.type) {
            case DothanCommandCatalog.STATUS_REPORT:
                parseStatusReport(payload, state);
                break;
            case DothanCommandCatalog.GAP_TYPE:
                if (payload.length >= 1) state.gapType = payload[0] & 0xFF;
                break;
            case DothanCommandCatalog.PRINT_DENSITY:
                if (payload.length >= 1) state.density = (payload[0] & 0xFF) + 1;
                break;
            case DothanCommandCatalog.PRINT_SPEED:
                if (payload.length >= 1) state.speed = (payload[0] & 0xFF) + 1;
                break;
            case DothanCommandCatalog.GAP_LENGTH:
                state.gapHundredthsMm = DothanFrameCodec.readVariableInteger(payload, 0);
                break;
            case DothanCommandCatalog.MOTOR_MODE:
                if (payload.length >= 1) state.motorMode = payload[0] & 0xFF;
                break;
            case DothanCommandCatalog.AUTO_POWER_OFF:
                state.autoPowerOffMinutes = variableShort(payload);
                break;
            case DothanCommandCatalog.LANGUAGE:
                if (payload.length >= 1) state.language = payload[0] & 0xFF;
                break;
            case DothanCommandCatalog.FLAGS:
                parseFlags(payload, state);
                break;
            case DothanCommandCatalog.ARRAY_REPORT_1:
            case DothanCommandCatalog.ARRAY_REPORT_2:
            case DothanCommandCatalog.ARRAY_REPORT_3:
                state.lastArray = toUnsigned(payload);
                break;
            case DothanCommandCatalog.PRINT_RECEIPT:
                parseReceipt(payload, state);
                break;
            case DothanCommandCatalog.PRINTER_DPI:
                if (payload.length >= 1) state.dpi = payload[0] & 0xFF;
                break;
            case DothanCommandCatalog.PRINTER_WIDTH:
                parseWidth(payload, state);
                break;
            case DothanCommandCatalog.COUNTERS:
                state.counters = parseInts(payload, 4);
                break;
            case DothanCommandCatalog.DEVICE_NAME:
            case DothanCommandCatalog.DEVICE_NAME_REPORT:
                state.deviceName = deviceText(payload);
                break;
            case DothanCommandCatalog.BUFFER_SPACE:
            case DothanCommandCatalog.BUFFER_SPACE_2:
                state.bufferBytes = parseBufferBytes(payload);
                break;
            case DothanCommandCatalog.SINGLE_BYTE_STATUS:
                if (payload.length >= 1) state.singleStatus = payload[0] & 0xFF;
                break;
            case DothanCommandCatalog.FULL_PARAMETERS:
                state.fullParameters = Arrays.copyOf(payload, payload.length);
                parseAddressRecords(payload, state);
                break;
            case DothanCommandCatalog.SESSION_PARAMETERS:
                state.batteryCount = frame.rawByte23 >= 0 ? frame.rawByte23 : 2;
                parseManufacturerFields(payload, state);
                break;
            case DothanCommandCatalog.AUTH_LOGIN_REPLY:
                state.authReply = Arrays.copyOf(payload, payload.length);
                break;
            default:
                break;
        }
    }

    private static void parseStatusReport(byte[] payload, DothanDeviceState state) {
        if (payload.length < 1) return;
        int subType = payload[0] & 0xFF;
        if (subType == 1 && payload.length >= 7) {
            state.batteryCount = payload[1] & 0xFF;
            if (payload.length >= 4) {
                state.widthDots = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            }
            state.firmwareVersion = Integer.toString((payload[4] & 0xFF) - 1);
            state.isConnectedAdapter = (payload[5] & 0xFF) != 0;
            state.printerStatus = payload[6] & 0xFF;
            if (payload.length > 9) state.chargeStatus = payload[9] & 0xFF;
            return;
        }
        if (subType == 4 && payload.length > 1) {
            parseManufacturerInfo(payload, state);
        }
    }

    private static void parseFlags(byte[] payload, DothanDeviceState state) {
        if (payload.length < 1) return;
        int value = payload[0] & 0xFF;
        state.flagJ = (value & 0x01) != 0;
        state.flagI = (value & 0x02) != 0;
        state.flagH = (value & 0x04) != 0;
        if (payload.length >= 3 && (payload[2] & 0x02) == 0) {
            state.autoFeed = (payload[2] & 0x04) != 0 ? 1 : 0;
            state.beep = (payload[2] & 0x08) == 0 ? 1 : 0;
        }
    }

    private static void parseReceipt(byte[] payload, DothanDeviceState state) {
        if (payload.length < 1) return;
        state.receiptStatus = payload[0] & 0xFF;
        if (payload.length >= 3) {
            state.receiptPage = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
        }
        if (payload.length >= 4) {
            state.receiptCopies = payload[3] & 0xFF;
        }
    }

    private static void parseWidth(byte[] payload, DothanDeviceState state) {
        if (payload.length >= 2) {
            state.widthDots = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }
        if (payload.length >= 3) state.density = (payload[2] & 0xFF) + 1;
        if (payload.length >= 4) state.speed = (payload[3] & 0xFF) + 1;
    }

    private static int parseBufferBytes(byte[] payload) {
        int units = variableShort(payload);
        if (units < 0) return 0;
        return units == 1 ? 1000 : units * 500;
    }

    private static int variableShort(byte[] payload) {
        return DothanFrameCodec.readVariableShort(payload, 0);
    }

    private static int[] parseInts(byte[] payload, int count) {
        int[] values = new int[count];
        int offset = 0;
        for (int index = 0; index < count && offset + 4 <= payload.length; index++) {
            values[index] = ((payload[offset] & 0xFF) << 24)
                    | ((payload[offset + 1] & 0xFF) << 16)
                    | ((payload[offset + 2] & 0xFF) << 8)
                    | (payload[offset + 3] & 0xFF);
            offset += 4;
        }
        return values;
    }

    private static int[] toUnsigned(byte[] payload) {
        int[] values = new int[payload.length];
        for (int index = 0; index < payload.length; index++) values[index] = payload[index] & 0xFF;
        return values;
    }

    private static void parseAddressRecords(byte[] payload, DothanDeviceState state) {
        if (payload.length <= 1) return;
        int recordCount = payload.length / 7;
        for (int index = 0; index < recordCount; index++) {
            int offset = index * 7;
            int type = payload[offset] & 0xFF;
            if (type == 0) continue;
            byte[] mac = Arrays.copyOfRange(payload, offset + 1, offset + 7);
            String macText = hex(mac);
            if (macText.isEmpty()) continue;
            state.addressType = type;
            state.macAddress = macText;
            return;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) {
            if (value.length() > 0) value.append(':');
            value.append(String.format(java.util.Locale.US, "%02X", item & 0xFF));
        }
        return value.toString();
    }

    private static void parseManufacturerInfo(byte[] payload, DothanDeviceState state) {
        DothanManufacturerInfo info = new DothanManufacturerInfo();
        if (info.parse(payload)) state.manufacturerInfo = info;
    }

    private static void parseManufacturerFields(byte[] payload, DothanDeviceState state) {
        if (payload.length >= 4) {
            state.manufacturerFieldD = ((payload[0] & 0xFF) << 24)
                    | ((payload[1] & 0xFF) << 16)
                    | ((payload[2] & 0xFF) << 8)
                    | (payload[3] & 0xFF);
        }
        if (payload.length >= 8) {
            state.manufacturerFieldE = ((payload[4] & 0xFF) << 24)
                    | ((payload[5] & 0xFF) << 16)
                    | ((payload[6] & 0xFF) << 8)
                    | (payload[7] & 0xFF);
        } else if (state.manufacturerFieldD >= 0) {
            state.manufacturerFieldE = (state.manufacturerFieldD & 16) | 1;
        }
    }

    private static String deviceText(byte[] payload) {
        try {
            return new String(payload, Charset.forName("GBK")).replace("\u0000", "").trim();
        } catch (RuntimeException unsupportedCharset) {
            return new String(payload, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
        }
    }
}

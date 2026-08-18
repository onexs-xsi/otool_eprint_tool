package com.otool.eprint.protocol;

import com.otool.eprint.device.PaperType;
import java.util.Calendar;

/** Central catalogue for the Dothan/WePrint private protocol command set. */
public final class DothanCommandCatalog {
    public static final int PAGE_HEADER = 0x20;
    public static final int FEED_ROWS = 0x22;
    public static final int BAND_STATS = 0x25;
    public static final int PAGE_HEIGHT = 0x26;
    public static final int ROW_WIDTH_BYTES = 0x27;
    public static final int PAGE_END = 0x28;
    public static final int BYTE_RLE_ROW = 0x29;
    public static final int RAW_ROW = 0x2B;
    public static final int BIT_RLE_5_ROW = 0x2C;
    public static final int BIT_RLE_5_XOR_ROW = 0x2D;
    public static final int REPEAT_ROW = 0x2E;
    public static final int BIT_RLE_4_ROW = 0x3A;
    public static final int BIT_RLE_4_XOR_ROW = 0x3B;
    public static final int BIT_RLE_6_ROW = 0x3C;
    public static final int BIT_RLE_6_XOR_ROW = 0x3D;
    public static final int STATUS_REPORT = 0x40;
    public static final int ARRAY_REPORT_1 = 0x52;
    public static final int ARRAY_REPORT_2 = 0x57;
    public static final int ARRAY_REPORT_3 = 0x59;
    public static final int GAP_TYPE = 0x42;
    public static final int PRINT_DENSITY = 0x43;
    public static final int PRINT_SPEED = 0x44;
    public static final int GAP_LENGTH = 0x45;
    public static final int MOTOR_MODE = 0x47;
    public static final int AUTO_POWER_OFF = 0x48;
    public static final int LANGUAGE = 0x49;
    public static final int FLAGS = 0x4D;
    public static final int PRINT_RECEIPT = 0x70;
    public static final int PRINTER_DPI = 0x71;
    public static final int PRINTER_WIDTH = 0x72;
    public static final int COUNTERS = 0x73;
    public static final int DEVICE_NAME = 0x75;
    public static final int BUFFER_SPACE = 0x77;
    public static final int SINGLE_BYTE_STATUS = 0x78;
    public static final int DEVICE_NAME_REPORT = 0x79;
    public static final int BUFFER_SPACE_2 = 0x7B;
    public static final int FULL_PARAMETERS = 0x7D;
    public static final int SESSION_START = 0x80;
    public static final int SESSION_PARAMETERS = 0x84;
    public static final int AUTH_HELLO = 0x9E;
    public static final int AUTH_LOGIN_REPLY = 0x9F;

    public static final int SESSION_START_OLD = 0x7F;
    public static final int SESSION_START_NEW = 0x49;
    public static final int SESSION_PARAMETER_VALUE = 1;
    public static final int NO_CHECKSUM = 0x88;

    private DothanCommandCatalog() {}

    public static byte[] pageHeader(int pageKey, boolean separateLine) {
        return DothanFrameCodec.encode(PAGE_HEADER,
                (byte) (pageKey >>> 8), (byte) pageKey, (byte) 0, (byte) 0,
                (byte) 0, (byte) 0, (byte) (separateLine ? 1 : 0), (byte) 0);
    }

    public static byte[] feedRows(int rowCount) {
        return DothanFrameCodec.encode(FEED_ROWS,
                DothanFrameCodec.variableShort(Math.max(0, rowCount - 1)));
    }

    public static byte[] bandStats(int maxBlackDots, int maxAverageBlackDots) {
        return DothanFrameCodec.encode(BAND_STATS,
                (byte) (0xC0 | (maxBlackDots >>> 8)), (byte) maxBlackDots,
                (byte) (0xC0 | (maxAverageBlackDots >>> 8)), (byte) maxAverageBlackDots);
    }

    public static byte[] pageHeight(int heightDots) {
        return DothanFrameCodec.encode(PAGE_HEIGHT, DothanFrameCodec.variableShort(heightDots));
    }

    public static byte[] rowWidthBytes(int widthBytes) {
        return DothanFrameCodec.encode(ROW_WIDTH_BYTES,
                DothanFrameCodec.variableShort(widthBytes));
    }

    public static byte[] pageEnd() {
        return DothanFrameCodec.encode(PAGE_END);
    }

    public static byte[] density(int displayedLevel) {
        return DothanFrameCodec.encode(PRINT_DENSITY,
                (byte) DothanProtocolEncoder.wireLevel(displayedLevel));
    }

    public static byte[] speed(int displayedLevel) {
        return DothanFrameCodec.encode(PRINT_SPEED,
                (byte) DothanProtocolEncoder.wireLevel(displayedLevel));
    }

    public static byte[] gapType(PaperType paperType) {
        return DothanFrameCodec.encode(GAP_TYPE,
                (byte) DothanProtocolEncoder.gapType(paperType));
    }

    public static byte[] gapLength(int gapLengthTenthsMm) {
        return DothanFrameCodec.encode(GAP_LENGTH,
                DothanProtocolEncoder.distanceHundredthsMm(gapLengthTenthsMm));
    }

    public static byte[] motorMode(int motorMode) {
        return DothanFrameCodec.encode(MOTOR_MODE, (byte) motorMode);
    }

    public static byte[] autoPowerOffMinutes(int minutes) {
        return DothanFrameCodec.encode(AUTO_POWER_OFF,
                DothanFrameCodec.variableShort(minutes));
    }

    public static byte[] language(int language) {
        return DothanFrameCodec.encode(LANGUAGE, (byte) language);
    }

    public static byte[] flags(int value, int subType) {
        return DothanFrameCodec.encode(FLAGS, (byte) value, (byte) 0, (byte) 0,
                (byte) subType, (byte) 0);
    }

    public static byte[] sessionStart(boolean newFirmware) {
        return DothanFrameCodec.encode(SESSION_START,
                (byte) (newFirmware ? SESSION_START_NEW : SESSION_START_OLD));
    }

    public static byte[] sessionClose() {
        return DothanFrameCodec.encode(SESSION_START, (byte) 0);
    }

    public static byte[] sessionParameters() {
        return DothanFrameCodec.encode(SESSION_PARAMETERS, (byte) SESSION_PARAMETER_VALUE);
    }

    public static byte[] bufferQuery() {
        return DothanFrameCodec.encode(BUFFER_SPACE, (byte) 0, (byte) 0);
    }

    public static byte[] query(int command) {
        return DothanFrameCodec.encode(command);
    }

    public static byte[] printReceiptQuery() {
        return DothanFrameCodec.encode(PRINT_RECEIPT);
    }

    public static byte[] speedAndDateTime(short speed, Calendar calendar) {
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        int weekday = dayOfWeek >= 2 ? dayOfWeek - 1 : 7;
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        return DothanFrameCodec.encode(SINGLE_BYTE_STATUS,
                (byte) (speed & 0xFF),
                (byte) ((speed >>> 8) & 0xFF),
                (byte) ((year >>> 8) & 0xFF),
                (byte) (year & 0xFF),
                (byte) month, (byte) day, (byte) weekday,
                (byte) hour, (byte) minute, (byte) second);
    }
}

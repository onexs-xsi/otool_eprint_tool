package com.otool.eprint.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decodes the manufacturer/consumable payload carried by Dothan 0x40 subtype 4. */
public final class DothanManufacturerInfo {
    public int consumableVersion;
    public int consumableType;
    public String product = "";
    public final List<Consumable> consumables = new ArrayList<>();

    public boolean parse(byte[] payload) {
        consumables.clear();
        if (payload == null || payload.length == 0) return false;
        int cursor = 7;
        if (payload.length > 1) {
            int value = payload[1] & 0xFF;
            consumableVersion = value & 0xE0;
            consumableType = value & 0x0F;
        }
        if (payload.length > 3) {
            product = ascii(payload, 2, 4);
        }
        boolean littleEndian = payload.length > 6 && payload[6] == 18;
        if (payload.length <= 7) return true;
        while (cursor < payload.length) {
            int recordLength = payload[cursor] & 0xFF;
            int end = cursor + 1 + recordLength;
            if (end > payload.length || recordLength < 22) return false;
            byte[] record = java.util.Arrays.copyOfRange(payload, cursor, end);
            Consumable value = new Consumable();
            value.busyFlag = record[1] & 0xFF;
            value.readResult = record[2] & 0xFF;
            if (value.readResult != 0) return false;
            value.chipType = record[3] & 0xFF;
            value.mark = record[4] & 0xFF;
            int colorByte = record[6] & 0xFF;
            value.ribbonColor = colorByte & 0x1F;
            value.consumableType = (colorByte & 0xE0) >> 5;
            value.serialNo = readInt(record, 8, 4, littleEndian);
            value.customer = readInt(record, 12, 2, littleEndian);
            value.subType = record[14] & 0xFF;
            int total = record[15] & 0xFF;
            value.totalLength = total <= 200 ? total * 0.5 : (total - 200) * 10.0 + 100.0;
            value.leftLength = readInt(record, 16, 4, littleEndian);
            value.product = ascii(record, 20, 22);
            value.size = record[22] & 0xFF;
            if (record.length >= 64) {
                value.templateId = ascii(record, 24, 44);
                int tradeBits = readInt(record, 44, 2, littleEndian);
                value.caizhiType = (tradeBits & 0xF000) >> 12;
                value.trade = (tradeBits & 0x07F0) >> 4;
                value.subTrade = tradeBits & 0x0F;
                value.addInfo = readInt(record, 46, 2, littleEndian);
                value.totalPages = readInt(record, 48, 2, littleEndian);
                int leftPages = readInt(record, 50, 2, littleEndian);
                value.leftPages = leftPages == 65535 ? -1 : leftPages;
                value.pageWidth = readInt(record, 52, 2, littleEndian) * 0.1;
                value.pageHeight = readInt(record, 54, 2, littleEndian) * 0.1;
                int gap = record[56] & 0xFF;
                value.gapType = gap & 0x07;
                value.gapLength = ((gap & 0xF8) >> 3)
                        + ((gap & 0x80) == 0 ? 0.0 : 0.5);
                value.darkAdjust = record[58] & 0xFF;
                int dark = record[59] & 0xFF;
                if (dark != 0 && dark != 255) {
                    value.maxDarkness = (dark & 0xF0) >> 4;
                    value.minDarkness = dark & 0x0F;
                }
                value.rfidParams = record[60] & 0xFF;
                int rfid = record[62] & 0xFF;
                value.rfidCenterDiffType = (rfid & 0xC0) >> 6;
                int diff = rfid & 0x3F;
                value.rfidCenterDiffValue = diff <= 50 ? diff * 3 : (diff - 50) * 10 + 150;
            }
            consumables.add(value);
            cursor = end;
        }
        return true;
    }

    public String summary() {
        StringBuilder value = new StringBuilder();
        value.append("产品 ").append(product.isEmpty() ? "未知" : productName(product))
                .append(" · 耗材类型 ").append(consumableTypeName(consumableType))
                .append(" · 版本 ").append(consumableVersion);
        if (consumables.isEmpty()) {
            value.append(" · 无耗材记录");
        } else {
            value.append(" · 耗材记录 ").append(consumables.size());
            for (int index = 0; index < consumables.size(); index++) {
                Consumable item = consumables.get(index);
                value.append("\n- ").append(item.product.isEmpty() ? "耗材" : item.product)
                        .append(" 序列号 ").append(item.serialNo)
                        .append(" · 碳带色 ").append(item.ribbonColor)
                        .append(" · 余量 ").append(String.format(Locale.US, "%.1f", item.leftLength))
                        .append(" · 页宽/高 ")
                        .append(String.format(Locale.US, "%.1f", item.pageWidth)).append("/")
                        .append(String.format(Locale.US, "%.1f", item.pageHeight)).append(" mm");
            }
        }
        return value.toString();
    }

    public static String productName(String productCode) {
        if (productCode == null) return "";
        switch (productCode.trim().toUpperCase(Locale.ROOT)) {
            case "R1": return "DT20/DT60/DT61";
            case "R2": return "DT7330";
            case "R3": return "DT6250";
            case "R4": return "DT9600";
            case "U1": return "DT210/iT-3100";
            case "U2": return "DT5150/DT5153";
            case "U3": return "DT-320";
            case "U4": return "DT5200";
            case "U5": return "DT5250";
            case "U6": return "DT5330";
            case "U7": return "DT5670";
            case "U8": return "DT5153";
            case "U9": return "T0";
            case "UA": return "T1";
            case "P0": return "P0";
            case "P1": return "P1";
            case "P2": return "DP100/YLF";
            case "P4": return "DP40/DP43/DP45";
            case "P8": return "DP9210";
            default: return productCode;
        }
    }

    private static int readInt(byte[] data, int offset, int length, boolean littleEndian) {
        if (offset < 0 || length < 0 || offset + length > data.length) return 0;
        int value = 0;
        if (littleEndian) {
            for (int index = offset + length - 1; index >= offset; index--) {
                value = (value << 8) | (data[index] & 0xFF);
            }
        } else {
            for (int index = offset; index < offset + length; index++) {
                value = (value << 8) | (data[index] & 0xFF);
            }
        }
        return value;
    }

    private static String ascii(byte[] data, int from, int to) {
        if (from < 0 || to > data.length || to < from) return "";
        return new String(java.util.Arrays.copyOfRange(data, from, to), StandardCharsets.US_ASCII)
                .replace("\u0000", "").trim();
    }

    private static String consumableTypeName(int type) {
        switch (type) {
            case 0: return "热转印碳带";
            case 1: return "热转印标签";
            case 2: return "热转印一体耗材";
            case 3: return "热敏标签";
            case 4: return "线号机套管/热缩管";
            default: return "类型 " + type;
        }
    }

    public static final class Consumable {
        public int busyFlag;
        public int readResult;
        public int chipType;
        public int mark;
        public int ribbonColor;
        public int consumableType;
        public int serialNo;
        public int customer;
        public int subType;
        public double totalLength;
        public int leftLength;
        public String product = "";
        public int size;
        public String templateId = "";
        public int caizhiType;
        public int trade;
        public int subTrade;
        public int addInfo;
        public int totalPages;
        public int leftPages;
        public double pageWidth;
        public double pageHeight;
        public int gapType;
        public double gapLength;
        public int darkAdjust;
        public int maxDarkness;
        public int minDarkness;
        public int rfidParams;
        public int rfidCenterDiffType;
        public int rfidCenterDiffValue;
    }
}

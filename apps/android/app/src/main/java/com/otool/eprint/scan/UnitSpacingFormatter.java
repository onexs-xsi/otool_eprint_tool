package com.otool.eprint.scan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化器件字段的单位排版：只处理“规格/参数”，避免把型号、料号或封装中的字母数字拆开。
 * 例如 {@code 0Ω333.333mW} → {@code 0 Ω 333.333 mW}。
 */
public final class UnitSpacingFormatter {
    // 长单位必须放在短单位前；匹配后还会检查相邻字符，避免 VISHAY、ROHS 等单词误命中。
    private static final Pattern UNIT = Pattern.compile(
            "mAh|µAh|μAh|uAh|Ah|GWh|MWh|kWh|mWh|Wh|"
                    + "GHz|MHz|kHz|Hz|"
                    + "GΩ|MΩ|kΩ|mΩ|µΩ|μΩ|uΩ|Ω|"
                    + "GW|MW|kW|mW|µW|μW|uW|W|"
                    + "GV|MV|kV|mV|µV|μV|uV|V|"
                    + "GA|MA|kA|mA|µA|μA|uA|A|"
                    + "GF|MF|kF|mF|µF|μF|uF|nF|pF|F|"
                    + "GH|MH|kH|mH|µH|μH|uH|nH|H|"
                    + "Gbps|Mbps|kbps|bps|ppm|°C|℃|dB|"
                    + "km|cm|mm|µm|μm|um|nm|m",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private UnitSpacingFormatter() {}

    /** 返回字段已格式化的新记录；身份、原文、置信度和告警保持不变。 */
    public static ComponentRecord format(ComponentRecord record) {
        if (record == null) return null;
        Map<String, String> fields = new LinkedHashMap<>(record.fields);
        formatField(fields, "规格");
        formatField(fields, "参数");
        return new ComponentRecord(record.supplier, record.recognized, record.confidence,
                record.orderNumber, record.supplierPartNumber,
                record.manufacturerPartNumber, record.quantity, record.trackingId,
                record.raw, fields, record.warning);
    }

    private static void formatField(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value != null && !value.isEmpty()) fields.put(key, formatValue(value));
    }

    /** 只在单位紧邻数字时插入空格，已有空格保持不变，重复调用结果稳定。 */
    static String formatValue(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        Matcher matcher = UNIT.matcher(value);
        StringBuilder output = new StringBuilder(value.length() + 8);
        int copiedUntil = 0;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            char before = start > 0 ? value.charAt(start - 1) : '\0';
            char after = end < value.length() ? value.charAt(end) : '\0';
            // 单位任一侧若仍与拉丁单词相连，说明它位于型号/单词内部，而不是独立单位。
            // 典型反例：CRCW0603 中的末尾 W 不能被解释成瓦特。
            if (isAsciiLetter(before) || isAsciiLetter(after)) continue;
            boolean numberBefore = Character.isDigit(before);
            boolean numberAfter = Character.isDigit(after)
                    || ((after == '+' || after == '-') && end + 1 < value.length()
                    && Character.isDigit(value.charAt(end + 1)));
            if (!numberBefore && !numberAfter) continue;

            output.append(value, copiedUntil, start);
            if (numberBefore && output.length() > 0
                    && !Character.isWhitespace(output.charAt(output.length() - 1))) {
                output.append(' ');
            }
            output.append(value, start, end);
            if (numberAfter && !Character.isWhitespace(after)) output.append(' ');
            copiedUntil = end;
        }
        if (copiedUntil == 0) return value;
        output.append(value, copiedUntil, value.length());
        return output.toString();
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }
}

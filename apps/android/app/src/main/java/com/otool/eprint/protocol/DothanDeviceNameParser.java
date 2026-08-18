package com.otool.eprint.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

/** Offline Dothan device-name parser reconstructed from BluetoothUtils.w(). */
public final class DothanDeviceNameParser {
    public enum Channel {
        NONE, DUAL, SPP, BLE
    }

    /** DPI values encoded by the fifth numeric suffix digit in BluetoothUtils.w(). */
    public static final int[] DPI_TABLE = {305, 180, 203, 300, 600};
    private static final Pattern SUFFIX_PATTERN =
            Pattern.compile("^[A-Z]{0,2}[0-9]{4,5}[0-9A-Z]{2,5}[0-9]{2}$");
    private static final String CHECK_TABLE = "5682904137";

    public static final class Result {
        public final boolean valid;
        public final Channel channel;
        public final int dpi;
        public final int productIndex;
        public final String prefix;

        Result(boolean valid, Channel channel, int dpi, int productIndex, String prefix) {
            this.valid = valid;
            this.channel = channel;
            this.dpi = dpi;
            this.productIndex = productIndex;
            this.prefix = prefix;
        }

        public static Result invalid() {
            return new Result(false, Channel.NONE, -1, -1, "");
        }
    }

    private DothanDeviceNameParser() {}

    public static Result parse(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) return Result.invalid();
        String full = deviceName.trim().toUpperCase(Locale.ROOT);
        int dash = full.lastIndexOf('-');
        String candidate = dash >= 0 ? full.substring(dash + 1) : full;
        int at = candidate.lastIndexOf('@');
        int productIndex = 0;
        if (at > 0 && candidate.length() - at - 1 > 0) {
            try {
                productIndex = Integer.parseInt(candidate.substring(at + 1));
            } catch (NumberFormatException ignored) {
                productIndex = 0;
            }
            candidate = candidate.substring(0, at);
        }
        if (candidate.length() < 8 || !SUFFIX_PATTERN.matcher(candidate).matches()) {
            return Result.invalid();
        }
        boolean digitsOnly = isDigits(candidate);
        String prefix = "";
        int weighted = 0;
        if (!Character.isDigit(candidate.charAt(1))) {
            prefix = candidate.substring(0, 2);
            candidate = candidate.substring(2);
            weighted = candidate.isEmpty() ? 0
                    : (prefix.charAt(0) * 11) + (prefix.charAt(1) * 13);
        } else if (Character.isDigit(candidate.charAt(0))) {
            weighted = 0;
        } else {
            prefix = candidate.substring(0, 1);
            candidate = candidate.substring(1);
            weighted = candidate.isEmpty() ? 0 : prefix.charAt(0) * 17;
        }
        if (candidate.length() < 8) return Result.invalid();
        boolean skipChecksum = digitsOnly && candidate.length() < 9
                && candidate.charAt(3) == '0';
        if (!skipChecksum) {
            int sum = weighted;
            if (digitsOnly) {
                sum += (candidate.charAt(0) - '0') * 2
                        + (candidate.charAt(1) - '0') * 3
                        + (candidate.charAt(2) - '0') * 5;
                for (int index = 4; index < candidate.length(); index++) {
                    sum += (candidate.charAt(index) - '0') * ((index & 1) == 0 ? 7 : 9);
                }
            } else {
                sum += candidate.charAt(0) * 2
                        + candidate.charAt(1) * 3
                        + candidate.charAt(2) * 5;
                for (int index = 4; index < candidate.length(); index++) {
                    sum += candidate.charAt(index) * ((index & 1) == 0 ? 7 : 9);
                }
            }
            if (CHECK_TABLE.charAt(sum % 10) != candidate.charAt(3)) return Result.invalid();
        }
        int channelValue;
        try {
            channelValue = Integer.parseInt(candidate.substring(1, 3)) / 20;
        } catch (NumberFormatException invalid) {
            return Result.invalid();
        }
        Channel channel;
        if (channelValue == 0) channel = Channel.DUAL;
        else if (channelValue == 1) channel = Channel.SPP;
        else if (channelValue == 2) channel = Channel.BLE;
        else return Result.invalid();
        int dpi;
        if (candidate.length() < 9 || !Character.isDigit(candidate.charAt(4))) {
            dpi = prefix.isEmpty() ? 203 : 300;
        } else {
            int digit = candidate.charAt(4) - '0';
            dpi = DPI_TABLE[digit % DPI_TABLE.length];
        }
        return new Result(true, channel, dpi, productIndex, prefix);
    }

    private static boolean isDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) return false;
        }
        return true;
    }
}

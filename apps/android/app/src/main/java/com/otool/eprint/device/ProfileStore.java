package com.otool.eprint.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.otool.eprint.protocol.DothanCompressionMode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ProfileStore {
    private static final String PREFERENCES = "eprint_native";
    private static final String PROFILE_KEY = "printer_profile_v2";

    private ProfileStore() {}

    public static PrinterProfile load(Context context) {
        String id = preferences(context)
                .getString(PROFILE_KEY, DeviceCatalog.defaultProfile().id);
        return load(context, DeviceCatalog.find(id));
    }

    public static PrinterProfile load(Context context, PrinterProfile base) {
        SharedPreferences values = preferences(context);
        String prefix = "hardware_profile_" + base.id + '_';
        try {
            int width = values.getInt(prefix + "width", base.printableWidthDots);
            int spp = values.getInt(prefix + "spp_chunk", base.sppChunkBytes);
            int ble = values.getInt(prefix + "ble_chunk", base.bleChunkBytes);
            int usb = values.getInt(prefix + "usb_chunk", base.usbChunkBytes);
            int delay = values.getInt(prefix + "delay", base.writeDelayMs);
            int retries = values.getInt(prefix + "retries", base.maxWriteRetries);
            int threshold = values.getInt(prefix + "threshold", base.threshold);
            String services = values.getString(prefix + "ble_services",
                    TextUtils.join(",", base.preferredBleServiceStrings()));
            String writes = values.getString(prefix + "ble_writes",
                    TextUtils.join(",", base.preferredBleWriteStrings()));
            String compressionName = values.getString(prefix + "dothan_compression",
                    base.compression.name());
            DothanCompressionMode compression = parseCompression(
                    compressionName, base.compression);
            String helloToken = values.getString(prefix + "dothan_hello_token",
                    base.helloTokenHex);
            String loginToken = values.getString(prefix + "dothan_login_token",
                    base.loginTokenHex);
            int motorMode = values.getInt(prefix + "dothan_motor_mode", base.motorMode);
            int language = values.getInt(prefix + "dothan_language", base.language);
            boolean applyExtras = values.getBoolean(prefix + "dothan_apply_extras",
                    base.applyDothanExtras);
            int density = values.getInt(prefix + "print_density", base.printDensity);
            int speed = values.getInt(prefix + "print_speed", base.printSpeed);
            int gap = values.getInt(prefix + "gap_tenths_mm", base.gapLengthTenthsMm);
            int powerOff = values.getInt(prefix + "auto_power_off", base.autoPowerOffMinutes);
            PaperType paper = PaperType.valueOf(values.getString(prefix + "paper_type",
                    base.paperType.name()));
            PrinterProfile hardware = base.withHardwareSettings(width, spp, ble, usb, delay,
                    retries, threshold,
                    mergeUuids(base.preferredBleServiceStrings(), splitUuids(services)),
                    mergeUuids(base.preferredBleWriteStrings(), splitUuids(writes)))
                    .withDothanOptions(compression, helloToken, loginToken)
                    .withDothanExtras(motorMode, language, applyExtras);
            return hardware.withDeviceSettings(base.manufacturer, base.deviceType, density, speed,
                    paper, gap, values.getBoolean(prefix + "auto_feed", base.autoFeed),
                    values.getBoolean(prefix + "beep", base.beep), powerOff);
        } catch (IllegalArgumentException invalidStoredValue) {
            return base;
        }
    }

    public static void save(Context context, PrinterProfile profile) {
        String prefix = "hardware_profile_" + profile.id + '_';
        preferences(context).edit()
                .putString(PROFILE_KEY, profile.id)
                .putInt(prefix + "width", profile.printableWidthDots)
                .putInt(prefix + "spp_chunk", profile.sppChunkBytes)
                .putInt(prefix + "ble_chunk", profile.bleChunkBytes)
                .putInt(prefix + "usb_chunk", profile.usbChunkBytes)
                .putInt(prefix + "delay", profile.writeDelayMs)
                .putInt(prefix + "retries", profile.maxWriteRetries)
                .putInt(prefix + "threshold", profile.threshold)
                .putInt(prefix + "print_density", profile.printDensity)
                .putInt(prefix + "print_speed", profile.printSpeed)
                .putString(prefix + "paper_type", profile.paperType.name())
                .putInt(prefix + "gap_tenths_mm", profile.gapLengthTenthsMm)
                .putBoolean(prefix + "auto_feed", profile.autoFeed)
                .putBoolean(prefix + "beep", profile.beep)
                .putInt(prefix + "auto_power_off", profile.autoPowerOffMinutes)
                .putString(prefix + "dothan_compression", profile.compression.name())
                .putString(prefix + "dothan_hello_token", profile.helloTokenHex)
                .putString(prefix + "dothan_login_token", profile.loginTokenHex)
                .remove(prefix + "dothan_default_auth")
                .putInt(prefix + "dothan_motor_mode", profile.motorMode)
                .putInt(prefix + "dothan_language", profile.language)
                .putBoolean(prefix + "dothan_apply_extras", profile.applyDothanExtras)
                .putString(prefix + "ble_services", TextUtils.join(",", profile.preferredBleServiceStrings()))
                .putString(prefix + "ble_writes", TextUtils.join(",", profile.preferredBleWriteStrings()))
                .apply();
    }

    public static void saveHardwareOnly(Context context, PrinterProfile profile) {
        String prefix = "hardware_profile_" + profile.id + '_';
        preferences(context).edit()
                .putString(PROFILE_KEY, profile.id)
                .putInt(prefix + "width", profile.printableWidthDots)
                .putInt(prefix + "spp_chunk", profile.sppChunkBytes)
                .putInt(prefix + "ble_chunk", profile.bleChunkBytes)
                .putInt(prefix + "usb_chunk", profile.usbChunkBytes)
                .putInt(prefix + "delay", profile.writeDelayMs)
                .putInt(prefix + "retries", profile.maxWriteRetries)
                .putInt(prefix + "threshold", profile.threshold)
                .putString(prefix + "dothan_compression", profile.compression.name())
                .putString(prefix + "dothan_hello_token", profile.helloTokenHex)
                .putString(prefix + "dothan_login_token", profile.loginTokenHex)
                .remove(prefix + "dothan_default_auth")
                .putInt(prefix + "dothan_motor_mode", profile.motorMode)
                .putInt(prefix + "dothan_language", profile.language)
                .putBoolean(prefix + "dothan_apply_extras", profile.applyDothanExtras)
                .putString(prefix + "ble_services", TextUtils.join(",", profile.preferredBleServiceStrings()))
                .putString(prefix + "ble_writes", TextUtils.join(",", profile.preferredBleWriteStrings()))
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static DothanCompressionMode parseCompression(
            String value, DothanCompressionMode fallback) {
        try {
            return DothanCompressionMode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalidStoredValue) {
            return fallback;
        }
    }

    private static String[] splitUuids(String value) {
        return Arrays.stream(value.split("[,\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toArray(String[]::new);
    }

    private static String[] mergeUuids(String[] required, String[] customized) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : required) values.add(value.toLowerCase(Locale.ROOT));
        for (String value : customized) values.add(value.toLowerCase(Locale.ROOT));
        return values.toArray(new String[0]);
    }
}

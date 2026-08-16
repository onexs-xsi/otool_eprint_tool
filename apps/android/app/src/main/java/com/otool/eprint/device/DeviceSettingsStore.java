package com.otool.eprint.device;

import android.content.Context;
import android.content.SharedPreferences;

/** Per physical endpoint settings. Prevents one printer's media settings leaking to another. */
public final class DeviceSettingsStore {
    private static final String PREFERENCES = "eprint_native";

    private DeviceSettingsStore() {}

    public static PrinterProfile apply(Context context, DeviceCandidate candidate,
                                       PrinterProfile base) {
        PrinterIdentity identity = DeviceCatalog.identify(candidate.name, candidate.transport);
        PrinterProfile defaults = PrinterDeviceDefaults.apply(base, identity);
        SharedPreferences values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String prefix = prefix(candidate);
        try {
            PaperType paper = PaperType.valueOf(values.getString(prefix + "paper",
                    defaults.paperType.name()));
            return base.withDeviceSettings(
                    defaults.manufacturer, defaults.deviceType,
                    values.getInt(prefix + "density", defaults.printDensity),
                    values.getInt(prefix + "speed", defaults.printSpeed), paper,
                    values.getInt(prefix + "gap", defaults.gapLengthTenthsMm),
                    values.getBoolean(prefix + "auto_feed", defaults.autoFeed),
                    values.getBoolean(prefix + "beep", defaults.beep),
                    values.getInt(prefix + "power_off", defaults.autoPowerOffMinutes));
        } catch (IllegalArgumentException invalidStoredValue) {
            return base;
        }
    }

    public static void save(Context context, DeviceCandidate candidate, PrinterProfile profile) {
        String prefix = prefix(candidate);
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putInt(prefix + "density", profile.printDensity)
                .putInt(prefix + "speed", profile.printSpeed)
                .putString(prefix + "paper", profile.paperType.name())
                .putInt(prefix + "gap", profile.gapLengthTenthsMm)
                .putBoolean(prefix + "auto_feed", profile.autoFeed)
                .putBoolean(prefix + "beep", profile.beep)
                .putInt(prefix + "power_off", profile.autoPowerOffMinutes)
                .apply();
    }

    private static String prefix(DeviceCandidate candidate) {
        String endpoint = (candidate.transport.name() + '_' + candidate.address)
                .replaceAll("[^A-Za-z0-9_]", "_");
        return "printer_device_settings_v1_" + endpoint + '_';
    }
}

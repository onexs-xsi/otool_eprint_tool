package com.otool.eprint.device;

import android.content.Context;

public final class DeviceHistoryStore {
    private static final String PREFERENCES = "eprint_native";
    private static final String ADDRESS_KEY = "last_printer_address_v1";
    private static final String TRANSPORT_KEY = "last_printer_transport_v1";
    private static final String PROFILE_KEY = "last_printer_profile_v1";
    private static final String NAME_KEY = "last_printer_name_v1";
    private static final String VENDOR_KEY = "last_printer_vendor_v1";
    private static final String PRODUCT_KEY = "last_printer_product_v1";
    private static final String CONNECTION_DESIRED_KEY = "printer_connection_desired_v1";

    private DeviceHistoryStore() {}

    public static void record(Context context, DeviceCandidate candidate, PrinterProfile profile) {
        recordSelection(context, candidate, profile);
    }

    public static void recordSelection(Context context, DeviceCandidate candidate,
                                       PrinterProfile profile) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(ADDRESS_KEY, candidate.address)
                .putString(TRANSPORT_KEY, candidate.transport.name())
                .putString(PROFILE_KEY, profile.id)
                .putString(NAME_KEY, candidate.name)
                .putInt(VENDOR_KEY, candidate.vendorId)
                .putInt(PRODUCT_KEY, candidate.productId)
                .putBoolean(CONNECTION_DESIRED_KEY, true)
                .apply();
    }

    public static void clearConnectionIntent(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(CONNECTION_DESIRED_KEY, false).apply();
    }

    public static boolean connectionDesired(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(CONNECTION_DESIRED_KEY, false);
    }

    public static String lastAddress(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(ADDRESS_KEY, "");
    }

    public static String lastName(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(NAME_KEY, "已选择打印机");
    }

    public static int lastVendorId(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getInt(VENDOR_KEY, 0);
    }

    public static int lastProductId(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getInt(PRODUCT_KEY, 0);
    }

    public static PrinterProfile lastProfile(Context context) {
        String id = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PROFILE_KEY, DeviceCatalog.defaultProfile().id);
        return ProfileStore.load(context, DeviceCatalog.find(id));
    }

    public static TransportType lastTransport(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(TRANSPORT_KEY, TransportType.CLASSIC_SPP.name());
        try {
            return TransportType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return TransportType.CLASSIC_SPP;
        }
    }
}

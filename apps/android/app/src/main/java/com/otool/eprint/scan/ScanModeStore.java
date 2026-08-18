package com.otool.eprint.scan;

import android.content.Context;

import com.otool.eprint.model.WorkMode;

/** 每个工作页面分别记忆最后使用的标签/二维码/条形码档位。 */
public final class ScanModeStore {
    private static final String PREFERENCES = "eprint_scan_mode_by_page_v1";

    private ScanModeStore() {}

    public static ScanInputMode load(Context context, WorkMode page) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(page.name(), defaultMode(page).name());
        try {
            ScanInputMode mode = ScanInputMode.valueOf(value);
            return page == WorkMode.NORMAL && mode == ScanInputMode.LABEL
                    ? ScanInputMode.QR : mode;
        } catch (IllegalArgumentException ignored) {
            return defaultMode(page);
        }
    }

    public static void save(Context context, WorkMode page, ScanInputMode mode) {
        ScanInputMode stored = page == WorkMode.NORMAL && mode == ScanInputMode.LABEL
                ? ScanInputMode.QR : mode;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(page.name(), stored.name()).apply();
    }

    private static ScanInputMode defaultMode(WorkMode page) {
        return page == WorkMode.COMPONENT_ORGANIZATION
                ? ScanInputMode.LABEL : ScanInputMode.QR;
    }
}

package com.otool.eprint.model;

import android.content.Context;

public final class WorkModeStore {
    private static final String PREFERENCES = "eprint_native";
    private static final String KEY = "work_mode_v1";

    private WorkModeStore() {}

    public static WorkMode load(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, WorkMode.NORMAL.name());
        try {
            return WorkMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return WorkMode.NORMAL;
        }
    }

    public static void save(Context context, WorkMode mode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY, mode.name()).apply();
    }
}

package com.otool.eprint.scan.qwen;

import android.content.Context;
import android.content.SharedPreferences;

/** Qwen text-only 后处理配置持久化；与 GLM 运行参数分开，便于独立启停。 */
public final class QwenSettingsStore {
    private static final String PREFERENCES = "eprint_qwen_settings";

    private QwenSettingsStore() {}

    public static QwenSettings load(Context context) {
        QwenSettings defaults = QwenSettings.defaults();
        SharedPreferences values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return new QwenSettings(
                values.getBoolean("enabled", defaults.enabled),
                values.getBoolean("only_when_missing", defaults.onlyWhenCoreFieldsMissing),
                values.getBoolean("allow_corrections", defaults.allowEvidenceBackedCorrections),
                values.getBoolean("require_catalog", defaults.requireCatalogMatch),
                values.getInt("thread_count", defaults.threadCount),
                values.getInt("max_new_tokens", defaults.maxNewTokens),
                values.getInt("timeout_seconds", defaults.timeoutSeconds),
                values.getString("extra_instructions", defaults.extraInstructions));
    }

    public static void save(Context context, QwenSettings settings) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", settings.enabled)
                .putBoolean("only_when_missing", settings.onlyWhenCoreFieldsMissing)
                .putBoolean("allow_corrections", settings.allowEvidenceBackedCorrections)
                .putBoolean("require_catalog", settings.requireCatalogMatch)
                .putInt("thread_count", settings.threadCount)
                .putInt("max_new_tokens", settings.maxNewTokens)
                .putInt("timeout_seconds", settings.timeoutSeconds)
                .putString("extra_instructions", settings.extraInstructions)
                .apply();
    }
}

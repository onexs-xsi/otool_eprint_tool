package com.otool.eprint.scan.vlm;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 持久化；覆盖安装和进程重启后配置均保留。 */
public final class VlmSettingsStore {
    private static final String PREFERENCES = "eprint_vlm_settings";

    private VlmSettingsStore() {}

    public static VlmSettings load(Context context) {
        VlmSettings defaults = VlmSettings.defaults();
        SharedPreferences values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String storedPrompt = values.getString("prompt", defaults.prompt);
        // “全文转写”遗留参数的高惩罚会破坏重复 JSON 标点；迁移到信息提取时只重置
        // 采样惩罚，线程/后端/精度/超时等用户配置继续保留。
        boolean migratingTranscribe = VlmSettings.isLegacyTranscribePrompt(storedPrompt);
        return new VlmSettings(
                values.getString("backend", defaults.backend),
                values.getInt("thread_count", defaults.threadCount),
                values.getString("precision", defaults.precision),
                values.getString("memory", defaults.memory),
                values.getInt("max_new_tokens", defaults.maxNewTokens),
                values.getInt("timeout_seconds", defaults.timeoutSeconds),
                migratingTranscribe ? defaults.repetitionPenalty
                        : values.getFloat("repetition_penalty", defaults.repetitionPenalty),
                migratingTranscribe ? defaults.presencePenalty
                        : values.getFloat("presence_penalty", defaults.presencePenalty),
                migratingTranscribe ? defaults.frequencyPenalty
                        : values.getFloat("frequency_penalty", defaults.frequencyPenalty),
                storedPrompt);
    }

    public static void save(Context context, VlmSettings settings) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("backend", settings.backend)
                .putInt("thread_count", settings.threadCount)
                .putString("precision", settings.precision)
                .putString("memory", settings.memory)
                .putInt("max_new_tokens", settings.maxNewTokens)
                .putInt("timeout_seconds", settings.timeoutSeconds)
                .putFloat("repetition_penalty", settings.repetitionPenalty)
                .putFloat("presence_penalty", settings.presencePenalty)
                .putFloat("frequency_penalty", settings.frequencyPenalty)
                .putString("prompt", settings.prompt)
                .apply();
    }
}

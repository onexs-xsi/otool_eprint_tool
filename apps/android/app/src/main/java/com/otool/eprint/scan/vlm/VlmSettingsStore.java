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
        // 旧信息提取 prompt（v1.5.17~v1.5.27）迁移到转写模式时，同步把采样惩罚重置为
        // 转写工作点：旧默认 1.1/0/0 在转写任务上会触发单 token 退化（"333…"）。
        // 线程数一并重置：SM-X800 实测 thread_num>4 显著更慢（8 线程 decode 慢 4.2×，
        // 见 docs/ocr_optimize_0819.md 性能节）。后端/精度/超时等用户配置继续保留。
        boolean migratingExtraction = VlmSettings.isLegacyExtractionPrompt(storedPrompt);
        return new VlmSettings(
                values.getString("backend", defaults.backend),
                migratingExtraction ? defaults.threadCount
                        : values.getInt("thread_count", defaults.threadCount),
                values.getString("precision", defaults.precision),
                values.getString("memory", defaults.memory),
                values.getInt("max_new_tokens", defaults.maxNewTokens),
                values.getInt("timeout_seconds", defaults.timeoutSeconds),
                migratingExtraction ? defaults.repetitionPenalty
                        : values.getFloat("repetition_penalty", defaults.repetitionPenalty),
                migratingExtraction ? defaults.presencePenalty
                        : values.getFloat("presence_penalty", defaults.presencePenalty),
                migratingExtraction ? defaults.frequencyPenalty
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

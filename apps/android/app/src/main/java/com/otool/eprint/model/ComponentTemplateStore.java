package com.otool.eprint.model;

import android.content.Context;
import android.content.SharedPreferences;

/** 离线保存器件整理模式当前选择的模板及其配置项。 */
public final class ComponentTemplateStore {
    private static final String PREFERENCES = "eprint_component_template";
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_BOLD_HEADLINE = "bold_headline";
    private static final String KEY_SHOW_TRACE = "show_trace";
    private static final String KEY_LARGE_FONT = "large_font";

    private ComponentTemplateStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static ComponentTemplate loadTemplate(Context context) {
        return ComponentTemplate.byName(prefs(context).getString(KEY_TEMPLATE,
                ComponentTemplate.STANDARD.name()));
    }

    public static boolean loadBoldHeadline(Context context) {
        return prefs(context).getBoolean(KEY_BOLD_HEADLINE, true);
    }

    public static boolean loadShowTrace(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_TRACE, true);
    }

    public static boolean loadLargeFont(Context context) {
        return prefs(context).getBoolean(KEY_LARGE_FONT, false);
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean showTrace, boolean largeFont) {
        prefs(context).edit()
                .putString(KEY_TEMPLATE, template.name())
                .putBoolean(KEY_BOLD_HEADLINE, boldHeadline)
                .putBoolean(KEY_SHOW_TRACE, showTrace)
                .putBoolean(KEY_LARGE_FONT, largeFont)
                .apply();
    }
}

package com.otool.eprint.model;

import android.content.Context;
import android.content.SharedPreferences;

/** 离线保存器件整理模式当前选择的模板及其配置项。 */
public final class ComponentTemplateStore {
    private static final String PREFERENCES = "eprint_component_template";
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_BOLD_HEADLINE = "bold_headline";
    private static final String KEY_SHOW_BRAND = "show_brand";
    private static final String KEY_LARGE_FONT = "large_font";
    private static final String KEY_FIELD_MODEL = "field_model";
    private static final String KEY_FIELD_CATEGORY = "field_category";
    private static final String KEY_FIELD_SPECIFICATION = "field_specification";
    private static final String KEY_FIELD_PACKAGE = "field_package";
    private static final String KEY_FIELD_PARAMETERS = "field_parameters";
    private static final String KEY_FIELD_SUPPLIER = "field_supplier";

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

    public static boolean loadShowBrand(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_BRAND, true);
    }

    public static boolean loadLargeFont(Context context) {
        return prefs(context).getBoolean(KEY_LARGE_FONT, false);
    }

    public static ComponentFieldSelection loadFields(Context context) {
        SharedPreferences preferences = prefs(context);
        return new ComponentFieldSelection(
                preferences.getBoolean(KEY_FIELD_MODEL, true),
                preferences.getBoolean(KEY_FIELD_CATEGORY, true),
                preferences.getBoolean(KEY_FIELD_SPECIFICATION, true),
                preferences.getBoolean(KEY_FIELD_PACKAGE, true),
                preferences.getBoolean(KEY_SHOW_BRAND, true),
                preferences.getBoolean(KEY_FIELD_PARAMETERS, true),
                preferences.getBoolean(KEY_FIELD_SUPPLIER, true));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean showBrand, boolean largeFont) {
        ComponentFieldSelection current = loadFields(context);
        save(context, template, boldHeadline, largeFont, new ComponentFieldSelection(
                current.model, current.category, current.specification, current.packageName,
                showBrand, current.parameters, current.supplierPartNumber));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean largeFont, ComponentFieldSelection fields) {
        prefs(context).edit()
                .putString(KEY_TEMPLATE, template.name())
                .putBoolean(KEY_BOLD_HEADLINE, boldHeadline)
                .putBoolean(KEY_LARGE_FONT, largeFont)
                .putBoolean(KEY_FIELD_MODEL, fields.model)
                .putBoolean(KEY_FIELD_CATEGORY, fields.category)
                .putBoolean(KEY_FIELD_SPECIFICATION, fields.specification)
                .putBoolean(KEY_FIELD_PACKAGE, fields.packageName)
                .putBoolean(KEY_SHOW_BRAND, fields.brand)
                .putBoolean(KEY_FIELD_PARAMETERS, fields.parameters)
                .putBoolean(KEY_FIELD_SUPPLIER, fields.supplierPartNumber)
                .apply();
    }
}

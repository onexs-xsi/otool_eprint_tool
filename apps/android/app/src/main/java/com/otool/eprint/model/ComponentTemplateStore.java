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
    private static final String KEY_SEPARATORS = "separators";
    private static final String KEY_REMOVE_BLANK_FIELDS = "remove_blank_fields";

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

    /** 模板是否绘制分隔线（竖线 + 横线）；默认开启。 */
    public static boolean loadSeparators(Context context) {
        return prefs(context).getBoolean(KEY_SEPARATORS, true);
    }

    /** 是否在排版前剔除值为空的字段行；默认关闭以兼容旧版固定前缀行为。 */
    public static boolean loadRemoveBlankFields(Context context) {
        return prefs(context).getBoolean(KEY_REMOVE_BLANK_FIELDS, false);
    }

    public static ComponentFieldSelection loadFields(Context context) {
        return loadFields(context, loadTemplate(context));
    }

    /**
     * 每套模板独立保存字段组合。旧版本只有全局字段键；当模板键尚不存在时，
     * 以旧值作为迁移初值，避免升级后重置用户原有选择。
     */
    public static ComponentFieldSelection loadFields(Context context, ComponentTemplate template) {
        SharedPreferences preferences = prefs(context);
        return new ComponentFieldSelection(
                loadTemplateField(preferences, template, KEY_FIELD_MODEL, true),
                loadTemplateField(preferences, template, KEY_FIELD_CATEGORY, true),
                loadTemplateField(preferences, template, KEY_FIELD_SPECIFICATION, true),
                loadTemplateField(preferences, template, KEY_FIELD_PACKAGE, true),
                loadTemplateField(preferences, template, KEY_SHOW_BRAND, true),
                loadTemplateField(preferences, template, KEY_FIELD_PARAMETERS, true),
                loadTemplateField(preferences, template, KEY_FIELD_SUPPLIER, true));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean showBrand, boolean largeFont) {
        ComponentFieldSelection current = loadFields(context, template);
        save(context, template, boldHeadline, largeFont, new ComponentFieldSelection(
                current.model, current.category, current.specification, current.packageName,
                showBrand, current.parameters, current.supplierPartNumber));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean largeFont, ComponentFieldSelection fields) {
        save(context, template, boldHeadline, largeFont, fields, loadSeparators(context));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean largeFont, ComponentFieldSelection fields, boolean separators) {
        save(context, template, boldHeadline, largeFont, fields, separators,
                loadRemoveBlankFields(context));
    }

    public static void save(Context context, ComponentTemplate template, boolean boldHeadline,
                            boolean largeFont, ComponentFieldSelection fields, boolean separators,
                            boolean removeBlankFields) {
        prefs(context).edit()
                .putString(KEY_TEMPLATE, template.name())
                .putBoolean(KEY_BOLD_HEADLINE, boldHeadline)
                .putBoolean(KEY_LARGE_FONT, largeFont)
                .putBoolean(templateFieldKey(template, KEY_FIELD_MODEL), fields.model)
                .putBoolean(templateFieldKey(template, KEY_FIELD_CATEGORY), fields.category)
                .putBoolean(templateFieldKey(template, KEY_FIELD_SPECIFICATION), fields.specification)
                .putBoolean(templateFieldKey(template, KEY_FIELD_PACKAGE), fields.packageName)
                .putBoolean(templateFieldKey(template, KEY_SHOW_BRAND), fields.brand)
                .putBoolean(templateFieldKey(template, KEY_FIELD_PARAMETERS), fields.parameters)
                .putBoolean(templateFieldKey(template, KEY_FIELD_SUPPLIER), fields.supplierPartNumber)
                .putBoolean(KEY_SEPARATORS, separators)
                .putBoolean(KEY_REMOVE_BLANK_FIELDS, removeBlankFields)
                .apply();
    }

    private static boolean loadTemplateField(SharedPreferences preferences,
                                             ComponentTemplate template, String legacyKey,
                                             boolean defaultValue) {
        String key = templateFieldKey(template, legacyKey);
        return preferences.contains(key)
                ? preferences.getBoolean(key, defaultValue)
                : preferences.getBoolean(legacyKey, defaultValue);
    }

    private static String templateFieldKey(ComponentTemplate template, String fieldKey) {
        return "template_" + template.name() + "_" + fieldKey;
    }
}

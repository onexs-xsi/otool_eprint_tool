package com.otool.eprint.model;

/** 器件整理模式的标签模板：决定由供应商贴纸解析结果生成哪种版面。 */
public enum ComponentTemplate {
    COMPACT("简洁", "紧凑布局 · 字段自由组合"),
    STANDARD("标准", "均衡布局 · 字段自由组合"),
    DETAILED("完整", "宽松布局 · 字段自由组合");

    public final String label;
    public final String description;

    ComponentTemplate(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public static ComponentTemplate byName(String name) {
        for (ComponentTemplate value : values()) {
            if (value.name().equals(name)) return value;
        }
        return STANDARD;
    }

    @Override
    public String toString() {
        return label;
    }
}

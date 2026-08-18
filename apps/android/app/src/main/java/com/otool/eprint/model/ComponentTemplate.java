package com.otool.eprint.model;

/** 器件整理模式的标签模板：决定由供应商贴纸解析结果生成哪种版面。 */
public enum ComponentTemplate {
    COMPACT("简洁", "仅型号，大字居中"),
    STANDARD("标准", "型号 + 料号/数量 + 追溯行"),
    DETAILED("完整", "全部字段，小字多行");

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

package com.otool.eprint.model;

/** 器件整理模式的标签模板：决定由供应商贴纸解析结果生成哪种版面。 */
public enum ComponentTemplate {
    COMPACT("简洁", "固定前缀：型号/品类/规格/封装，不含立创料号"),
    STANDARD("标准", "固定前缀：基础技术字段 + 立创料号"),
    DETAILED("完整", "固定前缀：全部技术字段 + 立创料号");

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

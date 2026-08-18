package com.otool.eprint.model;

/** 用户可按需启停的器件标签字段类别；模板仍决定该类别是否属于当前版式。 */
public final class ComponentFieldSelection {
    public final boolean model;
    public final boolean category;
    public final boolean specification;
    public final boolean packageName;
    public final boolean brand;
    public final boolean parameters;
    public final boolean supplierPartNumber;

    public ComponentFieldSelection(boolean model, boolean category, boolean specification,
                                   boolean packageName, boolean brand, boolean parameters,
                                   boolean supplierPartNumber) {
        this.model = model;
        this.category = category;
        this.specification = specification;
        this.packageName = packageName;
        this.brand = brand;
        this.parameters = parameters;
        this.supplierPartNumber = supplierPartNumber;
    }

    public static ComponentFieldSelection defaults() {
        return new ComponentFieldSelection(true, true, true, true, true, true, true);
    }

    public boolean anyEnabled() {
        return model || category || specification || packageName || brand
                || parameters || supplierPartNumber;
    }
}

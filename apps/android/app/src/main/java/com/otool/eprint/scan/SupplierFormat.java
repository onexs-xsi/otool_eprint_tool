package com.otool.eprint.scan;

public enum SupplierFormat {
    AUTO("自适应"),
    LCSC("立创商城");

    public final String label;

    SupplierFormat(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

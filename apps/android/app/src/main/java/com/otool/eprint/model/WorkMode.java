package com.otool.eprint.model;

public enum WorkMode {
    NORMAL("普通"),
    COMPONENT_ORGANIZATION("器件整理");

    public final String label;

    WorkMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

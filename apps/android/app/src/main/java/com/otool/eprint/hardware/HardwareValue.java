package com.otool.eprint.hardware;

public enum HardwareValue {
    UNKNOWN("未知"),
    OK("正常"),
    WARNING("警告"),
    ERROR("异常");

    public final String label;

    HardwareValue(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

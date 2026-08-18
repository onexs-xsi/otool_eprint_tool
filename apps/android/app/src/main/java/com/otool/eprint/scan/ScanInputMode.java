package com.otool.eprint.scan;

/**
 * 扫描输入模式：标签（拍摄整面识别）、二维码、条形码。
 * 顺序即分段控件上的档位顺序（标签在最左）。
 */
public enum ScanInputMode {
    LABEL("标签"),
    QR("二维码"),
    BARCODE("条形码");

    public final String label;

    ScanInputMode(String label) {
        this.label = label;
    }

    public int index() {
        return ordinal();
    }

    public static int count() {
        return values().length;
    }

    public static ScanInputMode fromIndex(int index) {
        ScanInputMode[] modes = values();
        if (index < 0) return modes[0];
        if (index >= modes.length) return modes[modes.length - 1];
        return modes[index];
    }

    /** 分段控件触摸分派：把 [0,1] 归一化横坐标映射到档位。 */
    public static ScanInputMode forFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(0.9999f, fraction));
        return fromIndex((int) (clamped * count()));
    }

    @Override
    public String toString() {
        return label;
    }
}

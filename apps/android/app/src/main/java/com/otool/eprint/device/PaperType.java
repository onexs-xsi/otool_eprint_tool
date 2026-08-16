package com.otool.eprint.device;

public enum PaperType {
    RECEIPT("小票纸"),
    GAP_LABEL("不干胶"),
    CARDSTOCK("卡纸 / 黑标"),
    TRANSPARENT("透明贴");

    public final String label;

    PaperType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

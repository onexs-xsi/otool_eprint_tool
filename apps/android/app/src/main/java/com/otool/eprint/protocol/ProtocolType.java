package com.otool.eprint.protocol;

public enum ProtocolType {
    DOTHAN_PRIVATE("P1 私有协议"),
    ESC_POS("ESC/POS 光栅"),
    TSPL("TSPL/TSPL2 BITMAP"),
    CPCL("CPCL CG"),
    ZPL("ZPL ^GFA");

    public final String label;

    ProtocolType(String label) {
        this.label = label;
    }
}

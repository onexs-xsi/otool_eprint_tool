package com.otool.eprint.protocol;

/** Selects the Dothan bitmap row representation used by the private protocol encoder. */
public enum DothanCompressionMode {
    UNCOMPRESSED("原始行（兼容优先）"),
    AUTO("自动（体积最小）"),
    BYTE_RLE("字节 RLE"),
    BIT_RLE_5("位 RLE 5bit"),
    BIT_RLE_4("位 RLE 4bit"),
    BIT_RLE_6("位 RLE 6bit");

    public final String label;

    DothanCompressionMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

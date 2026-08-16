package com.otool.eprint.protocol;

import java.util.Arrays;

public final class DothanFrame {
    public final int type;
    public final byte[] payload;

    public DothanFrame(int type, byte[] payload) {
        this.type = type & 0xFF;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }
}

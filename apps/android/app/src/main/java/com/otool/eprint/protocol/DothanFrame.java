package com.otool.eprint.protocol;

import java.util.Arrays;

public final class DothanFrame {
    public final int type;
    public final byte[] payload;
    public final boolean text;
    public final String textCommand;
    public final int rawByte23;

    public DothanFrame(int type, byte[] payload) {
        this.type = type & 0xFF;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        this.text = false;
        this.textCommand = "";
        this.rawByte23 = -1;
    }

    public DothanFrame(int type, byte[] payload, int rawByte23) {
        this.type = type & 0xFF;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        this.text = false;
        this.textCommand = "";
        this.rawByte23 = rawByte23;
    }

    public DothanFrame(String textCommand) {
        this.type = -1;
        this.payload = new byte[0];
        this.text = true;
        this.textCommand = textCommand == null ? "" : textCommand;
        this.rawByte23 = -1;
    }

    public boolean isText() {
        return text;
    }
}

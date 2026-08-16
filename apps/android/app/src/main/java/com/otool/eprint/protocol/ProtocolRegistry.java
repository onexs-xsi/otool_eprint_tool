package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

public final class ProtocolRegistry {
    private static final ProtocolEncoder ESC_POS = new EscPosProtocolEncoder();
    private static final ProtocolEncoder DOTHAN = new DothanProtocolEncoder();
    private static final ProtocolEncoder TSPL = new TsplProtocolEncoder();
    private static final ProtocolEncoder CPCL = new CpclProtocolEncoder();
    private static final ProtocolEncoder ZPL = new ZplProtocolEncoder();

    private ProtocolRegistry() {}

    public static ProtocolEncoder encoder(PrinterProfile profile) {
        switch (profile.protocol) {
            case DOTHAN_PRIVATE: return DOTHAN;
            case TSPL: return TSPL;
            case CPCL: return CPCL;
            case ZPL: return ZPL;
            case ESC_POS:
            default: return ESC_POS;
        }
    }
}

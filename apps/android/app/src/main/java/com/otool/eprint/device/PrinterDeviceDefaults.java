package com.otool.eprint.device;

/** Device-family defaults derived from confirmed device details, before user customization. */
public final class PrinterDeviceDefaults {
    private PrinterDeviceDefaults() {}

    public static PrinterProfile apply(PrinterProfile base, PrinterIdentity identity) {
        if (identity == null) return base;
        boolean dothanP1 = identity.privateProtocol && "P1 系列".equals(identity.family);
        return base.withDeviceSettings(identity.manufacturer, identity.deviceType,
                dothanP1 ? 15 : base.printDensity,
                dothanP1 ? 1 : base.printSpeed,
                dothanP1 ? PaperType.GAP_LABEL : base.paperType,
                dothanP1 ? 30 : base.gapLengthTenthsMm,
                true,
                dothanP1 || base.beep,
                dothanP1 ? 15 : base.autoPowerOffMinutes);
    }
}

package com.otool.eprint.device;

public final class PrinterIdentity {
    public final String family;
    public final String manufacturer;
    public final String deviceType;
    public final int dpi;
    public final int widthDots;
    public final float widthMm;
    public final boolean privateProtocol;

    public PrinterIdentity(String family, String manufacturer, String deviceType, int dpi,
                           int widthDots, float widthMm, boolean privateProtocol) {
        this.family = family;
        this.manufacturer = manufacturer;
        this.deviceType = deviceType;
        this.dpi = dpi;
        this.widthDots = widthDots;
        this.widthMm = widthMm;
        this.privateProtocol = privateProtocol;
    }

    public String specification() {
        return dpi + " DPI · " + widthDots + " 点 / "
                + String.format(java.util.Locale.US, "%.1f", widthMm) + " mm";
    }
}

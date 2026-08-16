package com.otool.eprint.protocol;

import com.otool.eprint.device.PrinterProfile;

public interface ProtocolEncoder {
    byte[] encode(MonochromeRaster raster, float widthMm, float heightMm, PrinterProfile profile);
}

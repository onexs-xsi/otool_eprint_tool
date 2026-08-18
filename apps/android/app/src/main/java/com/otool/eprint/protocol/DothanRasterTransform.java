package com.otool.eprint.protocol;

/** Applies Dothan bitmap job options to a monochrome raster. */
public final class DothanRasterTransform {
    private DothanRasterTransform() {}

    public static MonochromeRaster apply(MonochromeRaster source, DothanJobOptions options) {
        if (source == null) throw new IllegalArgumentException("Raster is null");
        DothanJobOptions value = options == null ? DothanJobOptions.defaults() : options;
        MonochromeRaster result = source;
        if (value.flipHorizontal) result = flipHorizontal(result);
        if (value.direction == 180) result = rotate180(result);
        if (value.antiColor) result = result.inverted();
        int outWidth = result.widthDots + value.leftDots + value.rightDots;
        int outHeight = result.heightDots + value.topDots + value.bottomDots;
        if (outWidth != result.widthDots || outHeight != result.heightDots) {
            result = place(result, outWidth, outHeight, value);
        }
        return result;
    }

    private static MonochromeRaster flipHorizontal(MonochromeRaster source) {
        byte[] output = new byte[source.copyData().length];
        for (int y = 0; y < source.heightDots; y++) {
            for (int x = 0; x < source.widthDots; x++) {
                if (source.getBit(x, y)) set(output, source.widthDots - 1 - x, y, source.widthDots);
            }
        }
        return new MonochromeRaster(source.widthDots, source.heightDots, output);
    }

    private static MonochromeRaster rotate180(MonochromeRaster source) {
        byte[] output = new byte[source.copyData().length];
        for (int y = 0; y < source.heightDots; y++) {
            for (int x = 0; x < source.widthDots; x++) {
                if (source.getBit(x, y)) {
                    set(output, source.widthDots - 1 - x,
                            source.heightDots - 1 - y, source.widthDots);
                }
            }
        }
        return new MonochromeRaster(source.widthDots, source.heightDots, output);
    }

    private static MonochromeRaster place(MonochromeRaster source, int outWidth, int outHeight,
                                          DothanJobOptions options) {
        int widthBytes = (outWidth + 7) / 8;
        byte[] output = new byte[widthBytes * outHeight];
        // The current job model has no independent target canvas width. Left/right margins
        // therefore determine the only well-defined horizontal placement.
        int x = options.leftDots;
        int y = options.topDots;
        for (int row = 0; row < source.heightDots; row++) {
            for (int col = 0; col < source.widthDots; col++) {
                if (source.getBit(col, row)) set(output, x + col, y + row, outWidth);
            }
        }
        return new MonochromeRaster(outWidth, outHeight, output);
    }

    private static void set(byte[] data, int x, int y, int widthDots) {
        int widthBytes = (widthDots + 7) / 8;
        int index = y * widthBytes + (x >>> 3);
        data[index] |= (byte) (0x80 >>> (x & 7));
    }
}

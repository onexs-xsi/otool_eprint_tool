package com.otool.eprint.protocol;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Arrays;

public final class MonochromeRaster {
    public final int widthDots;
    public final int heightDots;
    public final int widthBytes;
    private final byte[] data;

    public MonochromeRaster(int widthDots, int heightDots, byte[] data) {
        if (widthDots < 1 || heightDots < 1) throw new IllegalArgumentException("Raster dimensions must be positive");
        this.widthDots = widthDots;
        this.heightDots = heightDots;
        this.widthBytes = (widthDots + 7) / 8;
        int expected = widthBytes * heightDots;
        if (data.length != expected) throw new IllegalArgumentException("Expected " + expected + " raster bytes");
        this.data = Arrays.copyOf(data, data.length);
    }

    public static MonochromeRaster fromBitmap(Bitmap bitmap, int threshold) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int widthBytes = (width + 7) / 8;
        byte[] data = new byte[widthBytes * height];
        int[] pixels = new int[width];
        int normalizedThreshold = Math.max(0, Math.min(255, threshold));
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (isBlack(pixels[x], normalizedThreshold)) {
                    int index = y * widthBytes + x / 8;
                    data[index] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return new MonochromeRaster(width, height, data);
    }

    public byte[] copyData() {
        return Arrays.copyOf(data, data.length);
    }

    public boolean getBit(int x, int y) {
        if (x < 0 || y < 0 || x >= widthDots || y >= heightDots) return false;
        int index = y * widthBytes + (x >>> 3);
        return (data[index] & (0x80 >>> (x & 7))) != 0;
    }

    /** 生成严格黑白、无插值的 1-bit 预览图。 */
    public Bitmap toBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[widthDots];
        for (int y = 0; y < heightDots; y++) {
            for (int x = 0; x < widthDots; x++) {
                pixels[x] = getBit(x, y) ? Color.BLACK : Color.WHITE;
            }
            bitmap.setPixels(pixels, 0, widthDots, 0, y, widthDots, 1);
        }
        return bitmap;
    }

    /** 只反转有效打印点，不污染每行末尾的字节填充位。 */
    public MonochromeRaster inverted() {
        byte[] output = new byte[data.length];
        for (int y = 0; y < heightDots; y++) {
            for (int x = 0; x < widthDots; x++) {
                if (!getBit(x, y)) {
                    int index = y * widthBytes + (x >>> 3);
                    output[index] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return new MonochromeRaster(widthDots, heightDots, output);
    }

    public void writeRows(java.io.ByteArrayOutputStream output, int firstRow, int rowCount) {
        output.write(data, firstRow * widthBytes, rowCount * widthBytes);
    }

    private static boolean isBlack(int color, int threshold) {
        int luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
        return Color.alpha(color) > 80 && luminance < threshold;
    }
}

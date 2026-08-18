package com.otool.eprint.protocol;

import com.otool.eprint.device.PaperType;
import com.otool.eprint.device.PrinterProfile;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Dothan bitmap command stream. Keeps verified raw rows by default and supports RLE modes. */
public final class DothanProtocolEncoder implements ProtocolEncoder {
    private static final AtomicInteger PAGE_KEY = new AtomicInteger(1);

    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm,
                         PrinterProfile profile) {
        return encode(raster, widthMm, heightMm, profile, DothanJobOptions.defaults());
    }

    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm,
                         PrinterProfile profile, DothanJobOptions options) {
        DothanJobOptions job = options == null ? DothanJobOptions.defaults() : options;
        MonochromeRaster transformed = DothanRasterTransform.apply(raster, job);
        ByteArrayOutputStream all = new ByteArrayOutputStream(
                transformed.widthBytes * transformed.heightDots + 128);
        for (int copy = 0; copy < job.copies; copy++) {
            byte[] page = encodePage(transformed, widthMm, heightMm, profile);
            all.write(page, 0, page.length);
        }
        return all.toByteArray();
    }

    private byte[] encodePage(MonochromeRaster raster, float widthMm, float heightMm,
                              PrinterProfile profile) {
        if (raster.widthDots > profile.printableWidthDots) {
            throw new IllegalArgumentException("P1 raster exceeds 384-dot print head");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                raster.widthBytes * raster.heightDots + raster.heightDots * 6 + 128);
        int pageKey = PAGE_KEY.getAndUpdate(value -> value >= 0xFFFF ? 1 : value + 1);
        append(output, DothanCommandCatalog.pageHeader(pageKey, false));
        // P1's official UI presents density/speed as 1-based levels, while the
        // device replies and bitmap protocol carry zero-based values.
        append(output, DothanCommandCatalog.density(profile.printDensity));
        append(output, DothanCommandCatalog.speed(profile.printSpeed));
        int wireGapType = gapType(profile.paperType);
        append(output, DothanCommandCatalog.gapType(profile.paperType));
        if (wireGapType != 0) {
            append(output, DothanCommandCatalog.gapLength(profile.gapLengthTenthsMm));
        }
        append(output, DothanCommandCatalog.rowWidthBytes(raster.widthBytes));
        append(output, DothanCommandCatalog.pageHeight(raster.heightDots));

        byte[] data = raster.copyData();
        DothanCompressionMode compression = profile.compression;
        int blankRows = 0;
        int maxBlackDots = 0;
        int energyWindowRows = Math.max(1,
                (int) Math.round(profile.dpi * 3.0 / 25.4));
        int[] recentBlackDots = new int[energyWindowRows];
        int recentIndex = 0;
        int recentTotal = 0;
        int maxAverageBlackDots = 0;
        byte[] previousRow = null;
        for (int row = 0; row < raster.heightDots; row++) {
            int start = row * raster.widthBytes;
            int end = start + raster.widthBytes;
            int black = 0;
            for (int index = start; index < end; index++) {
                black += Integer.bitCount(data[index] & 0xFF);
            }
            maxBlackDots = Math.max(maxBlackDots, black);
            recentTotal -= recentBlackDots[recentIndex];
            recentBlackDots[recentIndex] = black;
            recentTotal += black;
            recentIndex = (recentIndex + 1) % recentBlackDots.length;
            maxAverageBlackDots = Math.max(maxAverageBlackDots,
                    recentTotal / recentBlackDots.length);
            byte[] currentRow = Arrays.copyOfRange(data, start, end);
            if (black == 0) {
                blankRows++;
                previousRow = currentRow;
                continue;
            }
            appendBlankRows(output, blankRows);
            blankRows = 0;
            DothanBitmapRowEncoder.RowCommand command =
                    DothanBitmapRowEncoder.encode(currentRow, previousRow, raster.widthDots,
                            compression);
            output.write(command.bytes, 0, command.bytes.length);
            previousRow = currentRow;
        }
        appendBlankRows(output, blankRows);
        int maxLine = Math.min(0x3FFF, maxBlackDots);
        int maxAverage = Math.min(0x3FFF, maxAverageBlackDots);
        append(output, DothanCommandCatalog.bandStats(maxLine, maxAverage));
        append(output, DothanCommandCatalog.pageEnd());
        return output.toByteArray();
    }

    private static void appendBlankRows(ByteArrayOutputStream output, int count) {
        while (count > 0) {
            int block = Math.min(count, 0x4000);
            append(output, DothanCommandCatalog.feedRows(block));
            count -= block;
        }
    }

    private static void append(ByteArrayOutputStream output, byte[] value) {
        output.write(value, 0, value.length);
    }

    static int wireLevel(int displayedLevel) {
        return Math.max(0, displayedLevel - 1);
    }

    static int gapType(PaperType paperType) {
        if (paperType == PaperType.RECEIPT) return 0;     // None
        if (paperType == PaperType.GAP_LABEL) return 2;   // Gap
        if (paperType == PaperType.CARDSTOCK) return 3;   // Black mark
        if (paperType == PaperType.TRANSPARENT) return 4; // Transparent
        return 2;
    }

    /** App settings use 0.1 mm; the P1 wire command uses 0.01 mm. */
    static byte[] distanceHundredthsMm(int tenthsMm) {
        int value = Math.max(0, Math.min(0x3FFFFF, tenthsMm * 10));
        if (value <= DothanFrameCodec.MAX_PAYLOAD) {
            return DothanFrameCodec.variableShort(value);
        }
        return new byte[]{
                (byte) (0xC0 | (value >>> 16)),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}

package com.otool.eprint.protocol;

import com.otool.eprint.device.PaperType;
import com.otool.eprint.device.PrinterProfile;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** P1 bitmap command stream. Uses uncompressed super-bitmap rows for first-printer safety. */
public final class DothanProtocolEncoder implements ProtocolEncoder {
    private static final AtomicInteger PAGE_KEY = new AtomicInteger(1);

    @Override
    public byte[] encode(MonochromeRaster raster, float widthMm, float heightMm,
                         PrinterProfile profile) {
        if (raster.widthDots > profile.printableWidthDots) {
            throw new IllegalArgumentException("P1 raster exceeds 384-dot print head");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                raster.widthBytes * raster.heightDots + raster.heightDots * 6 + 128);
        int pageKey = PAGE_KEY.getAndUpdate(value -> value >= 0xFFFF ? 1 : value + 1);
        append(output, DothanFrameCodec.encode(0x20,
                (byte) (pageKey >>> 8), (byte) pageKey, (byte) 0, (byte) 0,
                (byte) 0, (byte) 0, (byte) 0, (byte) 0));
        // P1's official UI presents density/speed as 1-based levels, while the
        // device replies and bitmap protocol carry zero-based values.
        append(output, DothanFrameCodec.encode(0x43, (byte) wireLevel(profile.printDensity)));
        append(output, DothanFrameCodec.encode(0x44, (byte) wireLevel(profile.printSpeed)));
        int gapType = gapType(profile.paperType);
        append(output, DothanFrameCodec.encode(0x42, (byte) gapType));
        if (gapType != 0) append(output, DothanFrameCodec.encode(0x45,
                distanceHundredthsMm(profile.gapLengthTenthsMm)));
        append(output, DothanFrameCodec.encode(0x27,
                DothanFrameCodec.variableShort(raster.widthBytes)));
        append(output, DothanFrameCodec.encode(0x26,
                DothanFrameCodec.variableShort(raster.heightDots)));

        byte[] data = raster.copyData();
        int blankRows = 0;
        int maxBlackDots = 0;
        int energyWindowRows = Math.max(1,
                (int) Math.round(profile.dpi * 3.0 / 25.4));
        int[] recentBlackDots = new int[energyWindowRows];
        int recentIndex = 0;
        int recentTotal = 0;
        int maxAverageBlackDots = 0;
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
            int last = end - 1;
            while (last >= start && data[last] == 0) last--;
            if (last < start) {
                blankRows++;
                continue;
            }
            appendBlankRows(output, blankRows);
            blankRows = 0;
            int first = start;
            while (first <= last && data[first] == 0) first++;
            byte[] line = Arrays.copyOfRange(data, first, last + 1);
            appendSuperBitmapLine(output, first - start, line);
        }
        appendBlankRows(output, blankRows);
        int maxLine = Math.min(0x3FFF, maxBlackDots);
        int maxAverage = Math.min(0x3FFF, maxAverageBlackDots);
        append(output, DothanFrameCodec.encode(0x25,
                (byte) (0xC0 | (maxLine >>> 8)), (byte) maxLine,
                (byte) (0xC0 | (maxAverage >>> 8)), (byte) maxAverage));
        append(output, DothanFrameCodec.encode(0x28));
        return output.toByteArray();
    }

    private static void appendBlankRows(ByteArrayOutputStream output, int count) {
        while (count > 0) {
            int block = Math.min(count, 0x4000);
            append(output, DothanFrameCodec.encode(0x22,
                    DothanFrameCodec.variableShort(block - 1)));
            count -= block;
        }
    }

    /** Super-bitmap line commands are stream records, not CRC-framed control packets. */
    private static void appendSuperBitmapLine(ByteArrayOutputStream output, int skipBytes,
                                              byte[] line) {
        output.write(DothanFrameCodec.HEADER);
        output.write(0x2B);
        append(output, DothanFrameCodec.variableShort(skipBytes));
        append(output, DothanFrameCodec.variableShort(line.length));
        append(output, line);
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

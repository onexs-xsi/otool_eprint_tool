package com.otool.eprint.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encodes a single Dothan bitmap row into the shortest supported command representation.
 *
 * <p>The first-printer safe path is {@code 0x2B} (raw row). The complete WePrint/DzPrinter
 * command set additionally supports byte-level RLE ({@code 0x29}), bit-level RLE variants
 * ({@code 0x3A..0x3D}, {@code 0x2C..0x2D}) and row repetition ({@code 0x2E}).</p>
 */
public final class DothanBitmapRowEncoder {
    private DothanBitmapRowEncoder() {}

    public static final class RowCommand {
        public final int type;
        public final byte[] bytes;

        RowCommand(int type, byte[] bytes) {
            this.type = type;
            this.bytes = bytes;
        }
    }

    public static RowCommand encode(byte[] row, byte[] previous, int widthDots,
                                    DothanCompressionMode mode) {
        if (row == null || row.length == 0) throw new IllegalArgumentException("Row is empty");
        int widthBytes = (widthDots + 7) / 8;
        if (row.length != widthBytes) {
            throw new IllegalArgumentException("Row length does not match raster width");
        }
        byte[] fullRow = Arrays.copyOf(row, row.length);
        byte[] previousRow = previous == null ? null : Arrays.copyOf(previous, previous.length);
        switch (mode) {
            case BYTE_RLE: return firstNonNull(byteRle(fullRow), raw(fullRow));
            case BIT_RLE_5: return firstNonNull(bitRle(fullRow, previousRow, widthDots, 5, false), raw(fullRow));
            case BIT_RLE_4: return firstNonNull(bitRle(fullRow, previousRow, widthDots, 4, false), raw(fullRow));
            case BIT_RLE_6: return firstNonNull(bitRle(fullRow, previousRow, widthDots, 6, false), raw(fullRow));
            case AUTO: return shortestAuto(fullRow, previousRow, widthDots);
            case UNCOMPRESSED:
            default: return raw(fullRow);
        }
    }

    private static RowCommand shortestAuto(byte[] row, byte[] previous, int widthDots) {
        RowCommand best = raw(row);
        if (best == null) return null;
        RowCommand candidate;
        candidate = byteRle(row);
        best = smaller(best, candidate);
        candidate = bitRle(row, previous, widthDots, 5, false);
        best = smaller(best, candidate);
        candidate = bitRle(row, previous, widthDots, 4, false);
        best = smaller(best, candidate);
        candidate = bitRle(row, previous, widthDots, 6, false);
        best = smaller(best, candidate);
        if (previous != null) {
            candidate = bitRle(row, previous, widthDots, 5, true);
            best = smaller(best, candidate);
            candidate = bitRle(row, previous, widthDots, 4, true);
            best = smaller(best, candidate);
            candidate = bitRle(row, previous, widthDots, 6, true);
            best = smaller(best, candidate);
            if (Arrays.equals(row, previous)) best = smaller(best, repeat());
        }
        return best;
    }

    private static RowCommand smaller(RowCommand current, RowCommand candidate) {
        if (candidate == null) return current;
        return candidate.bytes.length < current.bytes.length ? candidate : current;
    }

    private static RowCommand firstNonNull(RowCommand preferred, RowCommand fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static RowCommand raw(byte[] row) {
        int first = 0;
        while (first < row.length && row[first] == 0) first++;
        int last = row.length - 1;
        while (last >= first && row[last] == 0) last--;
        if (last < first) return null;
        byte[] line = Arrays.copyOfRange(row, first, last + 1);
        byte[] skip = DothanFrameCodec.variableShort(first);
        byte[] length = DothanFrameCodec.variableShort(line.length);
        byte[] command = new byte[2 + skip.length + length.length + line.length];
        command[0] = (byte) DothanFrameCodec.HEADER;
        command[1] = (byte) 0x2B;
        int offset = 2;
        System.arraycopy(skip, 0, command, offset, skip.length);
        offset += skip.length;
        System.arraycopy(length, 0, command, offset, length.length);
        offset += length.length;
        System.arraycopy(line, 0, command, offset, line.length);
        return new RowCommand(0x2B, command);
    }

    private static RowCommand repeat() {
        byte[] count = DothanFrameCodec.variableShort(0);
        byte[] command = new byte[2 + count.length];
        command[0] = (byte) DothanFrameCodec.HEADER;
        command[1] = (byte) 0x2E;
        System.arraycopy(count, 0, command, 2, count.length);
        return new RowCommand(0x2E, command);
    }

    private static RowCommand byteRle(byte[] row) {
        byte[] payload = encodeByteRuns(row);
        if (payload != null && payload.length > row.length) return null;
        return payload == null ? null : new RowCommand(0x29,
                DothanFrameCodec.encode(0x29, payload));
    }

    static byte[] encodeByteRuns(byte[] row) {
        if (row.length == 0) return null;
        int[] values = new int[row.length];
        for (int index = 0; index < row.length; index++) values[index] = row[index] & 0xFF;
        int index = 0;
        int outputLength = 0;
        while (index < values.length) {
            int run = 1;
            while (index + run < values.length && values[index + run] == values[index]) run++;
            int value = values[index];
            if (run == 1) {
                outputLength += value > 0xC0 ? 2 : 1;
            } else {
                int remaining = run;
                while (remaining >= 63) {
                    outputLength += 2;
                    remaining -= 63;
                }
                if (remaining == 1) outputLength += 1;
                else if (remaining > 1) outputLength += 2;
            }
            index += run;
        }
        byte[] output = new byte[outputLength];
        index = 0;
        int out = 0;
        while (index < values.length) {
            int run = 1;
            while (index + run < values.length && values[index + run] == values[index]) run++;
            int value = values[index];
            if (run == 1) {
                if (value > 0xC0) output[out++] = (byte) 0xC1;
                output[out++] = (byte) value;
            } else {
                int remaining = run;
                while (remaining >= 63) {
                    output[out++] = (byte) 0xFF;
                    output[out++] = (byte) value;
                    remaining -= 63;
                }
                if (remaining == 1) {
                    output[out++] = (byte) value;
                } else if (remaining > 1) {
                    output[out++] = (byte) (remaining | 0xC0);
                    output[out++] = (byte) value;
                }
            }
            index += run;
        }
        return output;
    }

    private static RowCommand bitRle(byte[] row, byte[] previous, int widthDots,
                                     int variant, boolean xor) {
        byte[] source = xor ? xorRows(row, previous) : row;
        if (source == null) return null;
        List<Run> runs = bitRuns(source, widthDots);
        byte[] packed = packRuns(runs, variant);
        if (packed == null || packed.length > row.length) return null;
        int type = xor ? xorType(variant) : currentType(variant);
        return new RowCommand(type, DothanFrameCodec.encode(type, packed));
    }

    private static byte[] xorRows(byte[] row, byte[] previous) {
        if (previous == null || previous.length != row.length) return null;
        byte[] output = new byte[row.length];
        for (int index = 0; index < row.length; index++) {
            output[index] = (byte) (row[index] ^ previous[index]);
        }
        return output;
    }

    private static int currentType(int variant) {
        if (variant == 4) return 0x3A;
        if (variant == 5) return 0x2C;
        return 0x3C;
    }

    private static int xorType(int variant) {
        if (variant == 4) return 0x3B;
        if (variant == 5) return 0x2D;
        return 0x3D;
    }

    private static List<Run> bitRuns(byte[] row, int widthDots) {
        List<Run> runs = new ArrayList<>();
        if (widthDots <= 0) return runs;
        int current = -1;
        int length = 0;
        for (int bit = 0; bit < widthDots; bit++) {
            int byteIndex = bit >>> 3;
            int mask = 0x80 >>> (bit & 7);
            int value = (row[byteIndex] & mask) != 0 ? 1 : 0;
            if (value == current) {
                length++;
            } else {
                if (current >= 0) runs.add(new Run(current, length));
                current = value;
                length = 1;
            }
        }
        if (current >= 0) runs.add(new Run(current, length));
        return runs;
    }

    private static byte[] packRuns(List<Run> runs, int variant) {
        int[] table = table(variant);
        int tokenBits = variant;
        int colorBit = variant - 1;
        List<Integer> tokens = new ArrayList<>();
        for (Run run : runs) {
            int remaining = run.length;
            int idx = table.length - 1;
            while (remaining > 0) {
                if (remaining >= table[idx]) {
                    tokens.add((run.color << colorBit) | idx);
                    remaining -= table[idx];
                } else if (remaining <= table[0]) {
                    idx = remaining - 1;
                } else {
                    idx--;
                }
            }
        }
        if (tokens.isEmpty()) return null;
        return packMsbFirst(tokens, tokenBits);
    }

    private static int[] table(int variant) {
        if (variant == 4) return new int[]{1, 2, 3, 4, 9, 14, 29, 88};
        if (variant == 5) return new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 24, 36, 48, 120};
        return new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 41, 62, 83, 104, 125, 146, 167, 188, 209, 230, 461, 923};
    }

    private static byte[] packMsbFirst(List<Integer> tokens, int tokenBits) {
        int bitCount = tokens.size() * tokenBits;
        byte[] output = new byte[(bitCount + 7) / 8];
        int streamBit = 0;
        for (int token : tokens) {
            for (int bit = tokenBits - 1; bit >= 0; bit--) {
                if (((token >>> bit) & 1) != 0) {
                    int byteIndex = streamBit >>> 3;
                    int bitInByte = 7 - (streamBit & 7);
                    output[byteIndex] |= (byte) (0x80 >>> (7 - bitInByte));
                }
                streamBit++;
            }
        }
        return output;
    }

    private static final class Run {
        final int color;
        final int length;

        Run(int color, int length) {
            this.color = color;
            this.length = length;
        }
    }
}

package com.otool.eprint.protocol;

/** Locates complete commands so flow-controlled chunks never split a P1 bitmap row. */
public final class DothanCommandStream {
    private DothanCommandStream() {}

    public static int nextCommandLength(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 3 > data.length
                || (data[offset] & 0xFF) != DothanFrameCodec.HEADER) return -1;
        int type = data[offset + 1] & 0xFF;
        if (type == 0x2B) {
            int firstSize = variableSize(data, offset + 2);
            if (firstSize < 0) return -1;
            int secondOffset = offset + 2 + firstSize;
            int secondSize = variableSize(data, secondOffset);
            if (secondSize < 0) return -1;
            int length = DothanFrameCodec.readVariableShort(data, secondOffset);
            int total = 2 + firstSize + secondSize + length;
            return offset + total <= data.length ? total : -1;
        }
        if (type == 0x2E) {
            int size = variableSize(data, offset + 2);
            return size < 0 ? -1 : 2 + size;
        }
        int available = data.length - offset;
        int length = DothanFrameCodec.encodedLength(data, offset, available);
        return length > 0 ? length : -1;
    }

    public static int chunkLength(byte[] data, int offset, int allowance) {
        int total = 0;
        while (offset + total < data.length) {
            int command = nextCommandLength(data, offset + total);
            if (command <= 0 || total + command > allowance) break;
            total += command;
        }
        return total;
    }

    private static int variableSize(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) return -1;
        int first = data[offset] & 0xFF;
        if (first < 192) return 1;
        return offset + 1 < data.length ? 2 : -1;
    }
}

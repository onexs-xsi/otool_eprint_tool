package com.otool.eprint.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental receive parser tolerant of fragmented and coalesced BLE notifications. */
public final class DothanFrameStreamParser {
    private byte[] buffer = new byte[1024];
    private int size;
    private int rejectedFrames;

    public synchronized List<DothanFrame> feed(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ArrayList<>();
        ensureCapacity(size + bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        List<DothanFrame> result = new ArrayList<>();
        int consumed = 0;
        while (consumed < size) {
            if (!DothanFrameCodec.isHeader(buffer[consumed] & 0xFF)) {
                consumed++;
                continue;
            }
            int available = size - consumed;
            int frameLength = DothanFrameCodec.encodedLength(buffer, consumed, available);
            if (frameLength == 0) break;
            if (frameLength < 0) {
                rejectedFrames++;
                consumed++;
                continue;
            }
            byte[] frame = Arrays.copyOfRange(buffer, consumed, consumed + frameLength);
            try {
                result.add(DothanFrameCodec.decode(frame));
                consumed += frameLength;
            } catch (IllegalArgumentException invalid) {
                rejectedFrames++;
                consumed++;
            }
        }
        if (consumed > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, size - consumed);
            size -= consumed;
        }
        if (size > DothanFrameCodec.MAX_PAYLOAD + 8) reset();
        return result;
    }

    public synchronized void reset() {
        size = 0;
    }

    public synchronized int bufferedBytes() {
        return size;
    }

    public synchronized int rejectedFrames() {
        return rejectedFrames;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) return;
        int target = buffer.length;
        while (target < required) target *= 2;
        buffer = Arrays.copyOf(buffer, target);
    }
}

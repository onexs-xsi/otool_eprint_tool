package com.otool.eprint.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Incremental receive parser tolerant of fragmented and coalesced BLE notifications. */
public final class DothanFrameStreamParser {
    static final long STALE_FRAGMENT_MILLIS = 2_000;
    private byte[] buffer = new byte[1024];
    private int size;
    private int rejectedFrames;
    private long lastFeedAt;
    private final StringBuilder textBuffer = new StringBuilder();

    public synchronized List<DothanFrame> feed(byte[] bytes) {
        return feed(bytes, System.currentTimeMillis());
    }

    synchronized List<DothanFrame> feed(byte[] bytes, long now) {
        if (bytes == null || bytes.length == 0) return new ArrayList<>();
        if ((size > 0 || textBuffer.length() > 0) && lastFeedAt > 0
                && now - lastFeedAt > STALE_FRAGMENT_MILLIS) {
            reset();
        }
        lastFeedAt = now;
        ensureCapacity(size + bytes.length);
        System.arraycopy(bytes, 0, buffer, size, bytes.length);
        size += bytes.length;
        List<DothanFrame> result = new ArrayList<>();
        int consumed = 0;
        while (consumed < size) {
            int value = buffer[consumed] & 0xFF;
            if (!DothanFrameCodec.isHeader(value)) {
                textBuffer.append((char) value);
                consumed++;
                if (value == 0 || value == '\r' || value == '\n') flushText(result);
                continue;
            }
            flushText(result);
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
        lastFeedAt = 0;
        textBuffer.setLength(0);
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

    private void flushText(List<DothanFrame> result) {
        String value = textBuffer.toString();
        textBuffer.setLength(0);
        if (value.isEmpty()) return;
        String command = value.replace("\u0000", "").replace("\r", "")
                .replace("\n", "").trim();
        if (!command.isEmpty()) result.add(new DothanFrame(command));
    }
}

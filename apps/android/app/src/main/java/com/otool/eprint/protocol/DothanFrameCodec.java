package com.otool.eprint.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Clean-room implementation of the DothanTech 0x1F private command frame. */
public final class DothanFrameCodec {
    public static final int HEADER = 0x1F;
    public static final int ALT_SEND_HEADER = 0xAA;
    public static final int ALT_RECEIVE_HEADER = 0xBB;
    public static final int MAX_PAYLOAD = 0x3FFF;

    private DothanFrameCodec() {}

    public static byte[] encode(int type, byte... payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (body.length > MAX_PAYLOAD) throw new IllegalArgumentException("P1 frame payload too large");
        int lengthBytes = body.length < 192 ? 1 : 2;
        byte[] result = new byte[1 + 1 + lengthBytes + body.length + 1];
        result[0] = (byte) HEADER;
        result[1] = (byte) type;
        int offset = 2;
        if (body.length < 192) {
            result[offset++] = (byte) body.length;
        } else {
            result[offset++] = (byte) (0xC0 | (body.length >>> 8));
            result[offset++] = (byte) body.length;
        }
        System.arraycopy(body, 0, result, offset, body.length);
        int checksum = 0;
        for (int index = 1; index < result.length - 1; index++) checksum += result[index] & 0xFF;
        result[result.length - 1] = (byte) ~checksum;
        return result;
    }

    public static DothanFrame decode(byte[] frame) {
        if (frame == null || frame.length < 4 || !isHeader(frame[0] & 0xFF)) {
            throw new IllegalArgumentException("Invalid P1 frame header");
        }
        int lengthFirst = frame[2] & 0xFF;
        int payloadLength;
        int payloadOffset;
        if (lengthFirst >= 192) {
            if (frame.length < 5) throw new IllegalArgumentException("Incomplete P1 frame length");
            payloadLength = ((lengthFirst & 0x3F) << 8) | (frame[3] & 0xFF);
            payloadOffset = 4;
        } else {
            payloadLength = lengthFirst;
            payloadOffset = 3;
        }
        int expected = payloadOffset + payloadLength + 1;
        if (frame.length != expected) throw new IllegalArgumentException("P1 frame length mismatch");
        int checksum = 0;
        for (int index = 1; index < frame.length - 1; index++) checksum += frame[index] & 0xFF;
        int crc = frame[frame.length - 1] & 0xFF;
        if (crc != 0x88 && (((byte) ~checksum) & 0xFF) != crc) {
            throw new IllegalArgumentException("P1 frame checksum mismatch");
        }
        return new DothanFrame(frame[1] & 0xFF,
                Arrays.copyOfRange(frame, payloadOffset, payloadOffset + payloadLength));
    }

    public static int encodedLength(byte[] buffer, int offset, int available) {
        if (buffer == null || available < 3 || offset < 0 || offset + available > buffer.length) return 0;
        if (!isHeader(buffer[offset] & 0xFF)) return -1;
        int first = buffer[offset + 2] & 0xFF;
        int lengthBytes = first >= 192 ? 2 : 1;
        if (available < 2 + lengthBytes) return 0;
        int payload = first >= 192
                ? ((first & 0x3F) << 8) | (buffer[offset + 3] & 0xFF) : first;
        if (payload > MAX_PAYLOAD) return -1;
        int total = 1 + 1 + lengthBytes + payload + 1;
        return available < total ? 0 : total;
    }

    public static byte[] variableShort(int value) {
        if (value < 0 || value > MAX_PAYLOAD) {
            throw new IllegalArgumentException("P1 variable short out of range: " + value);
        }
        if (value < 192) return new byte[]{(byte) value};
        return new byte[]{(byte) (0xC0 | (value >>> 8)), (byte) value};
    }

    public static int readVariableShort(byte[] payload, int offset) {
        if (payload == null || offset < 0 || offset >= payload.length) return -1;
        int first = payload[offset] & 0xFF;
        if (first < 192) return first;
        if (offset + 1 >= payload.length) return -1;
        return ((first & 0x3F) << 8) | (payload[offset + 1] & 0xFF);
    }

    public static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            if (value != null && value.length > 0) output.write(value, 0, value.length);
        }
        return output.toByteArray();
    }

    public static boolean isHeader(int value) {
        return value == HEADER || value == ALT_SEND_HEADER || value == ALT_RECEIVE_HEADER;
    }
}

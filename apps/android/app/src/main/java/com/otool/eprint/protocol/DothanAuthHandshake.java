package com.otool.eprint.protocol;

import java.util.Arrays;

/** Builds and verifies authorization using credentials explicitly supplied by the user. */
public final class DothanAuthHandshake {
    private DothanAuthHandshake() {}

    public static final class Credentials {
        private final byte[] hello;
        private final byte[] reply;

        private Credentials(byte[] hello, byte[] reply) {
            this.hello = hello;
            this.reply = reply;
        }

        public byte[] helloToken() { return Arrays.copyOf(hello, hello.length); }
        public byte[] expectedReply() { return Arrays.copyOf(reply, reply.length); }
    }

    /** Both blank skips authorization; incomplete or malformed explicit configuration fails. */
    public static Credentials fromUserConfiguration(String helloHex, String replyHex) {
        if (isBlank(helloHex) && isBlank(replyHex)) return null;
        byte[] hello = parseHex(helloHex);
        byte[] reply = parseHex(replyHex);
        if (hello == null || hello.length < 4 || reply == null || reply.length != 4) {
            throw new IllegalArgumentException(
                    "helloToken 至少 4 字节，loginToken 必须 4 字节；仅接受用户提供的令牌");
        }
        return new Credentials(hello, reply);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static byte[] parseHex(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("[\\s:,]", "");
        if (cleaned.isEmpty() || (cleaned.length() & 1) != 0) return null;
        byte[] result = new byte[cleaned.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(cleaned.charAt(index * 2), 16);
            int low = Character.digit(cleaned.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public static byte[] helloToken(byte[] token) {
        if (token == null || token.length < 4) {
            throw new IllegalArgumentException("helloToken must be at least 4 bytes");
        }
        return DothanFrameCodec.encode(0x9E, token);
    }

    public static boolean isLoginReply(DothanFrame frame) {
        return frame != null && frame.type == 0x9F;
    }

    public static boolean loginMatches(byte[] reply, byte[] expectedToken) {
        if (expectedToken == null || expectedToken.length == 0) return false;
        return reply != null && Arrays.equals(reply, expectedToken);
    }
}

package com.otool.eprint.protocol;

import java.util.Arrays;

/** Builds and verifies the optional Dothan print-authorization token handshake. */
public final class DothanAuthHandshake {
    // Embedded authorization table removed from distributable history.

    private DothanAuthHandshake() {}

    public static byte[] defaultHelloToken(int index) {
        throw new UnsupportedOperationException("Embedded authorization removed");
    }

    public static byte[] defaultLoginToken(int index) {
        throw new UnsupportedOperationException("Embedded authorization removed");
    }

    public static int defaultPairIndex() {
        return (int) (System.nanoTime() & 7L);
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

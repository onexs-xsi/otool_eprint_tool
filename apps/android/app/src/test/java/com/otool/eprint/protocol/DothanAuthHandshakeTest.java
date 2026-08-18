package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanAuthHandshakeTest {
    @Test
    public void buildsHelloAndVerifiesLoginReply() {
        byte[] hello = DothanAuthHandshake.helloToken(new byte[]{0, 1, 2, 3});
        DothanFrame frame = DothanFrameCodec.decode(hello);
        assertEquals(0x9E, frame.type);
        assertArrayEquals(new byte[]{0, 1, 2, 3}, frame.payload);

        assertTrue(DothanAuthHandshake.isLoginReply(
                new DothanFrame(0x9F, new byte[]{9, 9, 9, 9})));
        assertTrue(DothanAuthHandshake.loginMatches(
                new byte[]{9, 9, 9, 9}, new byte[]{9, 9, 9, 9}));
        assertFalse(DothanAuthHandshake.loginMatches(
                new byte[]{1, 2, 3, 4}, new byte[]{9, 9, 9, 9}));
    }

    @Test
    public void exposesEightOfflineDefaultPairs() {
        for (int index = 0; index < 8; index++) {
            assertEquals(4, DothanAuthHandshake.defaultHelloToken(index).length);
            assertEquals(4, DothanAuthHandshake.defaultLoginToken(index).length);
        }
        assertArrayEquals(new byte[]{0, 0, 0, 1},
                DothanAuthHandshake.defaultHelloToken(1));
        assertArrayEquals(new byte[]{0, 0, 0, 0},
                DothanAuthHandshake.defaultLoginToken(6));
    }

    @Test
    public void callersCannotMutateOfflineDefaults() {
        byte[] token = DothanAuthHandshake.defaultHelloToken(1);
        token[3] = 9;
        assertArrayEquals(new byte[]{0, 0, 0, 1},
                DothanAuthHandshake.defaultHelloToken(1));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }
}

package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

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
    public void absentUserCredentialsSkipAuthorization() {
        assertNull(DothanAuthHandshake.fromUserConfiguration(null, null));
        assertNull(DothanAuthHandshake.fromUserConfiguration("", ""));
        assertNull(DothanAuthHandshake.fromUserConfiguration("  ", "\t"));
    }

    @Test
    public void onlyExplicitUserCredentialsAreUsed() {
        DothanAuthHandshake.Credentials credentials =
                DothanAuthHandshake.fromUserConfiguration("11:22 33,44 55", "66 77 88 99");
        assertArrayEquals(new byte[]{17, 34, 51, 68, 85}, credentials.helloToken());
        assertArrayEquals(new byte[]{102, 119, (byte) 136, (byte) 153},
                credentials.expectedReply());
        credentials.helloToken()[0] = 0;
        credentials.expectedReply()[0] = 0;
        assertEquals(17, credentials.helloToken()[0]);
        assertEquals(102, credentials.expectedReply()[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingReplyNeverFallsBackToEmbeddedCredentials() {
        DothanAuthHandshake.fromUserConfiguration("11223344", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingHelloNeverFallsBackToEmbeddedCredentials() {
        DothanAuthHandshake.fromUserConfiguration("", "66778899");
    }

    @Test
    public void malformedCredentialsAreRejected() {
        String[][] invalid = {{"11", "66778899"}, {"1122334", "66778899"},
                {"112233GG", "66778899"}, {"11223344", "6677889900"}};
        for (String[] pair : invalid) {
            try {
                DothanAuthHandshake.fromUserConfiguration(pair[0], pair[1]);
                org.junit.Assert.fail("Malformed user credentials must be rejected");
            } catch (IllegalArgumentException expected) {
                // Invalid explicit configuration must stop; it must not choose another token.
            }
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }
}

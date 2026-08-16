package com.otool.eprint.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ReconnectPolicyTest {
    @Test
    public void delayBacksOffThenSaturatesWithoutAttemptLimit() {
        assertEquals(1_000, ReconnectPolicy.delayMillis(0));
        assertEquals(2_000, ReconnectPolicy.delayMillis(1));
        assertEquals(16_000, ReconnectPolicy.delayMillis(4));
        assertEquals(16_000, ReconnectPolicy.delayMillis(5));
        assertEquals(16_000, ReconnectPolicy.delayMillis(100_000));
    }

    @Test
    public void negativeAttemptUsesFirstDelayDefensively() {
        assertEquals(1_000, ReconnectPolicy.delayMillis(-1));
    }
}

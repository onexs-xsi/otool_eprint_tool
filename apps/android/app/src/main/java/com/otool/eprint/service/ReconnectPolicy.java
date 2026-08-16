package com.otool.eprint.service;

/** Infinite retry policy. Delays saturate, but retries never stop before manual disconnect. */
public final class ReconnectPolicy {
    private static final long[] DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000};

    private ReconnectPolicy() {}

    public static long delayMillis(int completedAttempts) {
        int index = Math.max(0, Math.min(completedAttempts, DELAYS_MS.length - 1));
        return DELAYS_MS[index];
    }
}

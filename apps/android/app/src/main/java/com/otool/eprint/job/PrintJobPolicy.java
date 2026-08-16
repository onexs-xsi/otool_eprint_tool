package com.otool.eprint.job;

public final class PrintJobPolicy {
    public static final int MAX_AUTOMATIC_RETRIES = 2;
    public static final long IDEMPOTENCY_WINDOW_MS = 10_000;

    private PrintJobPolicy() {}

    public static boolean isDuplicate(long previousSubmissionAt, long now) {
        return previousSubmissionAt >= 0
                && now >= previousSubmissionAt
                && now - previousSubmissionAt < IDEMPOTENCY_WINDOW_MS;
    }

    public static boolean shouldAutomaticallyRetry(int progressPercent, int attempts) {
        return progressPercent == 0 && attempts <= MAX_AUTOMATIC_RETRIES;
    }
}

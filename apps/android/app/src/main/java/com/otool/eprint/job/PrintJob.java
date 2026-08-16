package com.otool.eprint.job;

import com.otool.eprint.device.PrinterProfile;

import java.util.Arrays;
import java.util.UUID;

public final class PrintJob {
    public final String id;
    public final String idempotencyKey;
    public final byte[] data;
    public final PrinterProfile profile;
    public final long createdAt;
    public volatile PrintJobState state = PrintJobState.QUEUED;
    public volatile int attempts;
    public volatile int progress;
    public volatile String message = "已进入队列";

    public PrintJob(String idempotencyKey, byte[] data, PrinterProfile profile) {
        this.id = UUID.randomUUID().toString();
        this.idempotencyKey = idempotencyKey;
        this.data = Arrays.copyOf(data, data.length);
        this.profile = profile;
        this.createdAt = System.currentTimeMillis();
    }
}

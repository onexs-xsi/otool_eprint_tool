package com.otool.eprint.job;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.PrinterProfile;

import org.junit.Test;

public final class PrintJobQueueTest {
    private final PrinterProfile profile = DeviceCatalog.defaultProfile();

    @Test
    public void duplicateWithinWindowReturnsOriginalWithoutQueueingTwice() {
        PrintJobQueue queue = new PrintJobQueue();
        PrintJobQueue.EnqueueResult first = queue.enqueue(new byte[]{1}, profile, "same", 1_000);
        PrintJobQueue.EnqueueResult duplicate = queue.enqueue(new byte[]{2}, profile, "same", 10_999);
        assertTrue(first.created);
        assertFalse(duplicate.created);
        assertSame(first.job, duplicate.job);
        assertEquals(1, queue.size());

        PrintJobQueue.EnqueueResult later = queue.enqueue(new byte[]{3}, profile, "same", 11_000);
        assertTrue(later.created);
        assertEquals(2, queue.size());
    }

    @Test
    public void requeueFirstPreservesRetryBeforeLaterJobs() {
        PrintJobQueue queue = new PrintJobQueue();
        PrintJob first = queue.enqueue(new byte[]{1}, profile, "1", 0).job;
        PrintJob second = queue.enqueue(new byte[]{2}, profile, "2", 0).job;
        assertSame(first, queue.pollFirst());
        queue.requeueFirst(first);
        assertSame(first, queue.pollFirst());
        assertSame(second, queue.pollFirst());
    }

    @Test
    public void cancelLastAndManualRetryRestoreStateExactlyOnce() {
        PrintJobQueue queue = new PrintJobQueue();
        PrintJob first = queue.enqueue(new byte[]{1}, profile, "1", 0).job;
        PrintJob second = queue.enqueue(new byte[]{2}, profile, "2", 0).job;
        assertSame(second, queue.cancelLast());
        assertEquals(PrintJobState.CANCELLED, second.state);
        assertEquals(1, queue.size());
        assertSame(second, queue.retryLastFailed());
        assertEquals(PrintJobState.QUEUED, second.state);
        assertEquals(2, queue.size());
        assertNull(queue.retryLastFailed());
        assertSame(first, queue.pollFirst());
        assertSame(second, queue.pollFirst());
    }

    @Test
    public void partialProgressNeverAutomaticallyRetries() {
        assertTrue(PrintJobPolicy.shouldAutomaticallyRetry(0, 1));
        assertTrue(PrintJobPolicy.shouldAutomaticallyRetry(0, 2));
        assertFalse(PrintJobPolicy.shouldAutomaticallyRetry(0, 3));
        assertFalse(PrintJobPolicy.shouldAutomaticallyRetry(1, 1));
    }
}

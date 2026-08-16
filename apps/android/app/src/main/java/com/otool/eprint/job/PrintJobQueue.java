package com.otool.eprint.job;

import com.otool.eprint.device.PrinterProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure Java queue core so ordering, idempotency, cancellation and retry can be JVM-tested. */
public final class PrintJobQueue {
    public static final class EnqueueResult {
        public final PrintJob job;
        public final boolean created;

        EnqueueResult(PrintJob job, boolean created) {
            this.job = job;
            this.created = created;
        }
    }

    private static final int MAX_HISTORY = 50;
    private final Deque<PrintJob> waiting = new ArrayDeque<>();
    private final LinkedHashMap<String, PrintJob> history = new LinkedHashMap<>();
    private final Map<String, Submission> submissions = new LinkedHashMap<>();

    public synchronized EnqueueResult enqueue(byte[] data, PrinterProfile profile,
                                              String idempotencyKey, long now) {
        pruneSubmissions(now);
        Submission previous = submissions.get(idempotencyKey);
        if (previous != null && PrintJobPolicy.isDuplicate(previous.at, now)) {
            return new EnqueueResult(previous.job, false);
        }
        PrintJob job = new PrintJob(idempotencyKey, data, profile);
        waiting.addLast(job);
        history.put(job.id, job);
        submissions.put(idempotencyKey, new Submission(now, job));
        trimHistory();
        return new EnqueueResult(job, true);
    }

    public synchronized PrintJob pollFirst() {
        return waiting.pollFirst();
    }

    public synchronized PrintJob peekFirst() {
        return waiting.peekFirst();
    }

    public synchronized void requeueFirst(PrintJob job) {
        if (!waiting.contains(job)) waiting.addFirst(job);
    }

    public synchronized boolean isEmpty() {
        return waiting.isEmpty();
    }

    public synchronized int size() {
        return waiting.size();
    }

    public synchronized PrintJob cancel(String jobId) {
        for (PrintJob job : new ArrayList<>(waiting)) {
            if (!job.id.equals(jobId)) continue;
            waiting.remove(job);
            cancel(job, "已从队列取消");
            return job;
        }
        return null;
    }

    public synchronized PrintJob cancelLast() {
        PrintJob job = waiting.pollLast();
        if (job != null) cancel(job, "已从等待队列取消");
        return job;
    }

    public synchronized PrintJob retryLastFailed() {
        List<PrintJob> values = new ArrayList<>(history.values());
        for (int index = values.size() - 1; index >= 0; index--) {
            PrintJob job = values.get(index);
            if (job.state != PrintJobState.FAILED && job.state != PrintJobState.CANCELLED) continue;
            job.state = PrintJobState.QUEUED;
            job.progress = 0;
            job.attempts = 0;
            job.message = "已手动重新入队";
            if (!waiting.contains(job)) waiting.addLast(job);
            return job;
        }
        return null;
    }

    public synchronized List<PrintJob> history() {
        return new ArrayList<>(history.values());
    }

    private void pruneSubmissions(long now) {
        submissions.entrySet().removeIf(entry ->
                !PrintJobPolicy.isDuplicate(entry.getValue().at, now));
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            Iterator<Map.Entry<String, PrintJob>> iterator = history.entrySet().iterator();
            Map.Entry<String, PrintJob> first = iterator.next();
            iterator.remove();
            submissions.entrySet().removeIf(entry -> entry.getValue().job == first.getValue());
        }
    }

    private static void cancel(PrintJob job, String message) {
        job.state = PrintJobState.CANCELLED;
        job.message = message;
    }

    private static final class Submission {
        final long at;
        final PrintJob job;

        Submission(long at, PrintJob job) {
            this.at = at;
            this.job = job;
        }
    }
}

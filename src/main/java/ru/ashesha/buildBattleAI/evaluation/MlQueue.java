package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded FIFO queue feeding the ML stage, with a "wait for one then
 * drain whatever's there" batched-take primitive. Designed for a single
 * consumer (the ML coalescer thread) that wants to keep batches as full
 * as possible without blocking forever when load is low.
 */
final class MlQueue {

    private final LinkedBlockingQueue<EvalFrame> queue;

    MlQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking offer. Returns {@code false} on full queue.
     */
    boolean offer(@NonNull EvalFrame frame) {
        return queue.offer(frame);
    }

    /**
     * Blocks up to {@code waitMs} milliseconds for the first frame, then
     * opportunistically drains whatever else is already queued.
     * <p>
     * Returns at least 1 and at most {@code maxSize} frames when a frame
     * arrives within the wait window; returns an empty list (without
     * throwing) when the wait expires with no frame available.
     *
     * @param maxSize maximum total batch size, must be {@code >= 1}
     * @param waitMs  maximum wait for the first frame, milliseconds
     */
    @NonNull List<EvalFrame> drainBatch(int maxSize, long waitMs) throws InterruptedException {
        if (maxSize <= 0)
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        EvalFrame first = queue.poll(waitMs, TimeUnit.MILLISECONDS);
        if (first == null)
            return Collections.emptyList();
        List<EvalFrame> batch = new ArrayList<>(maxSize);
        batch.add(first);
        queue.drainTo(batch, maxSize - 1);
        return batch;
    }

    /** Approximate queue depth — for metrics only. */
    int size() {
        return queue.size();
    }

    /** Drops all queued frames. Called only during service shutdown. */
    void clear() {
        queue.clear();
    }
}

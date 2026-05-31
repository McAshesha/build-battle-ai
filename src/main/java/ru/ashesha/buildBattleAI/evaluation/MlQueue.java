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
     * Blocks up to {@code waitMs} for the first frame, then opportunistically
     * drains whatever else is already queued, up to {@code maxSize - 1} more.
     * Returns an empty list (without exception) if no frame arrives within
     * the wait window.
     */
    @NonNull List<EvalFrame> drainBatch(int maxSize, long waitMs) throws InterruptedException {
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

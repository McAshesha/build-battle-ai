package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bounded FIFO queue feeding the render stage, with per-player
 * deduplication. When a new job arrives for a player who already has a
 * queued job, the queued job is marked stale ({@link EvalJob#markStale()})
 * and the new job takes its place in the dedup index. Workers skip stale
 * jobs on dequeue, so the queue's nominal size may overshoot the actual
 * useful work slightly — that's by design and bounded by the dedup map's
 * cardinality.
 * <p>
 * <b>Threading contract:</b> exactly one producer (the
 * {@code EvaluationCoordinator}, running on the Bukkit main thread) and
 * one or more consumers (render workers). Concurrent {@code offer()}
 * calls from multiple producers are NOT supported — the two-step
 * {@code pending.put} → {@code queue.offer} sequence is non-atomic and a
 * race would either duplicate a player's job or silently lose one.
 * {@code take()} is safe to call from multiple consumers concurrently.
 */
final class RenderQueue {

    private final LinkedBlockingQueue<EvalJob> queue;
    private final ConcurrentHashMap<UUID, EvalJob> pending = new ConcurrentHashMap<>();

    RenderQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking offer. Returns {@code false} if the underlying queue is
     * full — the caller should treat that as backpressure.
     * <p>
     * <b>Precondition:</b> must be called from a single producer thread (see class Javadoc).
     */
    boolean offer(@NonNull EvalJob job) {
        // Atomically replace any prior pending job for this player; the
        // previous entry (if any) is now superseded and must be marked stale
        // so the consumer skips it on dequeue.
        EvalJob prev = pending.put(job.playerId(), job);
        if (prev != null)
            prev.markStale();
        if (!queue.offer(job)) {
            // Bounded queue is full — roll back the dedup index so we don't
            // leave a phantom mapping pointing at a never-enqueued job.
            pending.remove(job.playerId(), job);
            return false;
        }
        return true;
    }

    /**
     * Blocking take that transparently skips stale jobs. Stale entries are
     * discarded silently — they were either already superseded by a fresh
     * offer or invalidated externally.
     */
    @NonNull EvalJob take() throws InterruptedException {
        while (true) {
            EvalJob j = queue.take();
            if (j.isStale())
                continue;
            // Clear the dedup index only if it still points at *this* job;
            // a newer offer may have already replaced the mapping.
            pending.remove(j.playerId(), j);
            return j;
        }
    }

    /** Approximate queue depth — for metrics only. */
    int size() {
        return queue.size();
    }

    /** Drops all queued jobs. Called only during service shutdown. */
    void clear() {
        queue.clear();
        pending.clear();
    }
}

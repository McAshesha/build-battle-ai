package ru.ashesha.buildBattleAI.evaluation;

import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable counter set for the evaluation pipeline. Designed for high
 * write contention from multiple worker threads:
 * <ul>
 *   <li>{@link LongAdder} for hot counters (renders, drops, errors).</li>
 *   <li>{@link AtomicLong} for latency sums (paired with their count).</li>
 *   <li>{@link AtomicLongArray} for the batch-size histogram.</li>
 * </ul>
 * Snapshots are eventually-consistent — fields are not read atomically as a
 * group, but each individual counter is correct in isolation.
 */
public final class EvaluationMetrics {

    private final int maxBatchSize;

    private final LongAdder rendersCompleted   = new LongAdder();
    private final LongAdder mlBatchesCompleted = new LongAdder();
    private final LongAdder scoresAwarded      = new LongAdder();
    private final LongAdder droppedRenderJobs  = new LongAdder();
    private final LongAdder droppedMlJobs      = new LongAdder();
    private final LongAdder renderErrors       = new LongAdder();
    private final LongAdder mlErrors           = new LongAdder();

    private final AtomicLong renderLatencySumNanos = new AtomicLong();
    private final AtomicLong renderLatencyCount    = new AtomicLong();
    private final AtomicLong mlLatencySumNanos     = new AtomicLong();
    private final AtomicLong mlLatencyCount        = new AtomicLong();

    private final AtomicLongArray batchSizeHistogram;

    public EvaluationMetrics(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
        // Histogram buckets are indexed 0..maxBatchSize inclusive; bucket[0] is
        // intentionally kept so degenerate empty-batch records remain visible.
        this.batchSizeHistogram = new AtomicLongArray(maxBatchSize + 1);
    }

    public void incRendersCompleted()  { rendersCompleted.increment(); }
    public void incMlBatchesCompleted(){ mlBatchesCompleted.increment(); }
    public void incScoresAwarded()     { scoresAwarded.increment(); }
    public void incDroppedRenderJobs() { droppedRenderJobs.increment(); }
    public void incDroppedMlJobs()     { droppedMlJobs.increment(); }
    public void incRenderErrors()      { renderErrors.increment(); }
    public void incMlErrors()          { mlErrors.increment(); }

    public void recordRenderLatencyNanos(long nanos) {
        renderLatencySumNanos.addAndGet(nanos);
        renderLatencyCount.incrementAndGet();
    }

    public void recordMlLatencyNanos(long nanos) {
        mlLatencySumNanos.addAndGet(nanos);
        mlLatencyCount.incrementAndGet();
    }

    /**
     * Records a single ML batch's size. Out-of-range values are clamped
     * into the last bucket so an unexpected size never silently disappears.
     */
    public void recordBatchSize(int size) {
        int clamped = size;
        if (clamped < 0)
            clamped = 0;
        if (clamped > maxBatchSize)
            clamped = maxBatchSize;
        batchSizeHistogram.incrementAndGet(clamped);
    }

    /**
     * Captures the current counter state into an immutable {@link EvaluationStats}.
     * Queue depths and session counts come from the caller because they live
     * outside the metrics object (in queues / session registries) and reading
     * them here would require coupling we want to avoid.
     *
     * @param renderQueueDepth    current depth of the render-job queue
     * @param mlQueueDepth        current depth of the ML-job queue
     * @param registeredSessions  number of arena sessions registered with the pipeline
     * @param activePlayers       number of players being scored across all sessions
     * @return a new immutable snapshot; callers may share it across threads
     */
    public EvaluationStats snapshot(int renderQueueDepth, int mlQueueDepth,
                                    int registeredSessions, int activePlayers) {
        long[] hist = new long[batchSizeHistogram.length()];
        for (int i = 0; i < hist.length; i++)
            hist[i] = batchSizeHistogram.get(i);

        return new EvaluationStats(
                rendersCompleted.sum(),
                mlBatchesCompleted.sum(),
                scoresAwarded.sum(),
                droppedRenderJobs.sum(),
                droppedMlJobs.sum(),
                renderErrors.sum(),
                mlErrors.sum(),
                avgMicros(renderLatencySumNanos.get(), renderLatencyCount.get()),
                avgMicros(mlLatencySumNanos.get(), mlLatencyCount.get()),
                renderQueueDepth,
                mlQueueDepth,
                registeredSessions,
                activePlayers,
                hist);
    }

    /**
     * Returns the average of {@code sumNanos / count} converted to microseconds,
     * or zero when no samples have been recorded yet.
     */
    private static long avgMicros(long sumNanos, long count) {
        if (count == 0)
            return 0L;
        return sumNanos / count / 1_000L;
    }
}

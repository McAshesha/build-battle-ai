package ru.ashesha.buildBattleAI.evaluation.api;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Immutable point-in-time snapshot of evaluation-pipeline metrics. Safe
 * to pass across threads — all fields are primitives or an array that the
 * service has already defensively copied.
 */
@Value
@Accessors(fluent = true)
public class EvaluationStats {

    long rendersCompleted;
    long mlBatchesCompleted;
    long matchesDispatched;
    long droppedRenderJobs;
    long droppedMlJobs;
    long renderErrors;
    long mlErrors;
    long renderLatencyAvgMicros;
    long mlLatencyAvgMicros;
    int renderQueueDepth;
    int mlQueueDepth;
    int registeredSessions;
    int activePlayers;
    long[] batchSizeHistogram;

    /**
     * Returns a defensive copy of the batch-size histogram. The internal
     * array is never exposed to callers — this preserves the immutability
     * contract advertised in the class Javadoc.
     */
    public long[] batchSizeHistogram() {
        return batchSizeHistogram.clone();
    }
}

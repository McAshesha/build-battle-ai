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
    long scoresAwarded;
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
}

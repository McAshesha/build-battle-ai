package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationMetricsTest {

    @Test
    void countersAggregate() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.incRendersCompleted();
        m.incRendersCompleted();
        m.incMlBatchesCompleted();
        m.incMatchesDispatched();
        m.incDroppedRenderJobs();

        EvaluationStats s = m.snapshot(3, 1, 2, 16);
        assertEquals(2, s.rendersCompleted());
        assertEquals(1, s.mlBatchesCompleted());
        assertEquals(1, s.matchesDispatched());
        assertEquals(1, s.droppedRenderJobs());
        assertEquals(3, s.renderQueueDepth());
        assertEquals(1, s.mlQueueDepth());
        assertEquals(2, s.registeredSessions());
        assertEquals(16, s.activePlayers());
    }

    @Test
    void latencyAverageIsComputed() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.recordRenderLatencyNanos(10_000_000L); // 10 ms
        m.recordRenderLatencyNanos(20_000_000L); // 20 ms

        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        assertEquals(15_000L, s.renderLatencyAvgMicros()); // average 15 ms = 15000 µs
    }

    @Test
    void batchHistogramTracksSizes() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.recordBatchSize(1);
        m.recordBatchSize(4);
        m.recordBatchSize(4);
        m.recordBatchSize(8);

        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        long[] h = s.batchSizeHistogram();
        assertEquals(9, h.length); // 0..8 inclusive
        assertEquals(1, h[1]);
        assertEquals(2, h[4]);
        assertEquals(1, h[8]);
    }

    @Test
    void oversizedBatch_clampsToHistogramTail() {
        EvaluationMetrics m = new EvaluationMetrics(4);
        m.recordBatchSize(99); // out of range
        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        long[] h = s.batchSizeHistogram();
        assertEquals(1, h[4]); // clamped to max
    }

    @Test
    void mlLatencyAverageIsComputed() {
        EvaluationMetrics m = new EvaluationMetrics(8);
        m.recordMlLatencyNanos(50_000_000L); // 50 ms
        m.recordMlLatencyNanos(100_000_000L); // 100 ms

        EvaluationStats s = m.snapshot(0, 0, 0, 0);
        assertEquals(75_000L, s.mlLatencyAvgMicros()); // average 75 ms = 75000 µs
    }
}

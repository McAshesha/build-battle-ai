package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link MlCoalescerWorker} drains a batch from the
 * {@link MlQueue}, runs ML inference once across the whole batch, dispatches
 * one main-thread callback per matched frame, and records the corresponding
 * metrics.
 */
class MlCoalescerWorkerTest {

    @Test
    void batchOfTwo_matchOneDispatchesCallbackOnceWithCorrectArgs() throws Exception {
        BBAIMLService ml = mock(BBAIMLService.class);

        // First frame: top-K contains its expected theme "castle" → MATCH.
        PredictionResult matchResult = new PredictionResult(
                new float[0], "castle", 0.9f, new float[0],
                Arrays.asList(new TopKEntry("castle", 0.9f), new TopKEntry("tree", 0.4f)));
        // Second frame: expected "house" not in top-K → MISS.
        PredictionResult missResult = new PredictionResult(
                new float[0], "tree", 0.7f, new float[0],
                Arrays.asList(new TopKEntry("tree", 0.7f), new TopKEntry("castle", 0.2f)));
        when(ml.predictBatchRgb(any(byte[][].class), anyInt(), anyInt(), anyInt()))
                .thenReturn(new PredictionResult[]{matchResult, missResult});

        AtomicInteger calls = new AtomicInteger();
        UUID matchPid = UUID.randomUUID();
        BiConsumer<UUID, Integer> arenaCallback = (pid, theme) -> {
            // Verify the dispatcher receives the exact (pid, themeIndex) pair we enqueued.
            if (pid.equals(matchPid) && theme == 5)
                calls.incrementAndGet();
        };
        Function<String, BiConsumer<UUID, Integer>> registry =
                arena -> "arena1".equals(arena) ? arenaCallback : null;

        SyncDispatcher dispatcher = new SyncDispatcher();

        MlQueue mq = new MlQueue(4);
        mq.offer(frameFor("arena1", matchPid, 5, "castle"));
        mq.offer(frameFor("arena1", UUID.randomUUID(), 0, "house"));

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, registry, dispatcher, metrics, mock(PluginLogger.class),
                /* maxBatch */ 8, /* waitMs */ 50L, /* topK */ 2);

        Thread t = new Thread(worker, "test-ml");
        t.start();

        // Deterministic wait: poll the batch-completion counter rather than sleeping a fixed slice.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted() == 0
                && System.nanoTime() < deadline)
            Thread.sleep(1);

        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(1, calls.get(), "exactly one matched callback expected");
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).matchesDispatched());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted());
    }

    @Test
    void batch_unregisteredArena_doesNotDispatchButStillCountsBatch() throws Exception {
        BBAIMLService ml = mock(BBAIMLService.class);
        // Single frame whose theme matches — but the arena registry returns null.
        PredictionResult matchResult = new PredictionResult(
                new float[0], "castle", 0.9f, new float[0],
                Collections.singletonList(new TopKEntry("castle", 0.9f)));
        when(ml.predictBatchRgb(any(byte[][].class), anyInt(), anyInt(), anyInt()))
                .thenReturn(new PredictionResult[]{matchResult});

        AtomicInteger calls = new AtomicInteger();
        Function<String, BiConsumer<UUID, Integer>> registry = arena -> null;
        SyncDispatcher dispatcher = new SyncDispatcher() {
            @Override
            public void dispatch(Runnable r) {
                calls.incrementAndGet();
                super.dispatch(r);
            }
        };

        MlQueue mq = new MlQueue(4);
        mq.offer(frameFor("ghost-arena", UUID.randomUUID(), 0, "castle"));

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, registry, dispatcher, metrics, mock(PluginLogger.class),
                8, 50L, 2);

        Thread t = new Thread(worker, "test-ml-unreg");
        t.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted() == 0
                && System.nanoTime() < deadline)
            Thread.sleep(1);
        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(0, calls.get(), "no dispatch when arena unregistered");
        assertEquals(0, metrics.snapshot(0, 0, 0, 0).matchesDispatched());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted());
    }

    @Test
    void mlException_isSwallowedAndCounted() throws Exception {
        BBAIMLService ml = mock(BBAIMLService.class);
        when(ml.predictBatchRgb(any(byte[][].class), anyInt(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        MlQueue mq = new MlQueue(4);
        mq.offer(frameFor("arena1", UUID.randomUUID(), 0, "castle"));

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, arena -> (pid, theme) -> { },
                new SyncDispatcher(), metrics, mock(PluginLogger.class),
                8, 50L, 2);

        Thread t = new Thread(worker, "test-ml-err");
        t.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (metrics.snapshot(0, 0, 0, 0).mlErrors() == 0
                && System.nanoTime() < deadline)
            Thread.sleep(1);
        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(0, metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted(),
                "failed batch must not increment success counter");
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).mlErrors());
        assertEquals(0, metrics.snapshot(0, 0, 0, 0).matchesDispatched());
    }

    @Test
    void emptyBatch_doesNotIncrementAnyCounter() throws Exception {
        BBAIMLService ml = mock(BBAIMLService.class);
        MlQueue mq = new MlQueue(4); // nothing offered

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, arena -> null, new SyncDispatcher(), metrics,
                mock(PluginLogger.class), 8, 30L, 2);

        Thread t = new Thread(worker, "test-ml-empty");
        t.start();
        Thread.sleep(100); // give the worker at least one full wait window
        worker.stop();
        t.interrupt();
        t.join(1000);

        EvaluationStats s = metrics.snapshot(0, 0, 0, 0);
        assertEquals(0, s.mlBatchesCompleted());
        assertEquals(0, s.matchesDispatched());
        assertEquals(0, s.mlErrors());
        verify(ml, never()).predictBatchRgb(any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Builds an {@link EvalFrame} with a freshly-allocated RGB buffer, the
     * given arena/player identity, and the supplied {@code expectedTheme}
     * (the theme name the worker will look for in the top-K ranking).
     */
    private static EvalFrame frameFor(String arena, UUID pid, int themeIndex, String expectedTheme) {
        EvalJob j = EvalJob.builder()
                .arenaName(arena).playerId(pid).playerName("p")
                .plotIndex(0).themeIndex(themeIndex).expectedTheme(expectedTheme)
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        return new EvalFrame(j, new byte[224 * 224 * 3], 0L);
    }

    /** Test dispatcher that runs the callback synchronously on the current thread. */
    static class SyncDispatcher implements MlCoalescerWorker.MainThreadDispatcher {
        @Override
        public void dispatch(Runnable r) {
            r.run();
        }
    }
}

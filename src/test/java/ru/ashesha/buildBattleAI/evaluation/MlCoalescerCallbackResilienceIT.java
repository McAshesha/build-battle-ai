package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test covering risk EVAL-006 from the test-coverage spec.
 * <p>
 * Invariant: a throwing score callback dispatched to the "main thread"
 * (in tests: a synchronous dispatcher that records and swallows the
 * throwable) must not kill the {@link MlCoalescerWorker} run loop.
 * Subsequent batches must continue to be processed and the
 * {@code mlBatchesCompleted} counter keeps advancing.
 * <p>
 * Production parallel: in production the dispatcher is
 * {@code Bukkit.getScheduler().runTask(...)} which schedules the callback
 * onto the main thread and silently swallows any exception thrown there
 * (Bukkit logs but does not propagate back to the worker thread). This
 * test's dispatcher mirrors that catch-and-record semantics.
 */
@Tag("integration")
class MlCoalescerCallbackResilienceIT {

    /**
     * Feeds three frames across two batches. The score callback always throws.
     * The worker must survive all batches: {@code mlBatchesCompleted >= 2}
     * and the dispatcher must have swallowed at least one throwable.
     */
    @Test
    @DisplayName("EVAL-006: throwing score callback does NOT kill MlCoalescerWorker")
    void throwingCallbackSurvives() throws InterruptedException {
        // Build ML stub: every prediction's top-K contains "theme" so the
        // dispatch path fires (matched=true) for every frame. This ensures the
        // callback is actually invoked and can throw.
        BBAIMLService ml = mock(BBAIMLService.class);
        List<TopKEntry> topK = Arrays.asList(
                new TopKEntry("theme", 0.9f),
                new TopKEntry("other", 0.4f));
        PredictionResult matchResult = new PredictionResult(
                new float[0], "theme", 0.9f, new float[0], topK);
        // Return one result per frame regardless of actual batch size.
        // The worker calls predictBatchRgb with a variable-length byte[][]; we
        // return an array of the same length by using thenAnswer, but since the
        // test always offers frames in groups of one or two we supply a generous
        // array and the worker always reads results[i] for 0..batch.size()-1, so
        // returning a fixed two-element array is safe when batches are ≤ 2.
        when(ml.predictBatchRgb(any(byte[][].class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    byte[][] rgbs = invocation.getArgument(0);
                    PredictionResult[] arr = new PredictionResult[rgbs.length];
                    for (int i = 0; i < arr.length; i++)
                        arr[i] = matchResult;
                    return arr;
                });

        // A callback that always throws — simulating a buggy game-side handler.
        EvaluationCallback throwingCallback = (uuid, themeIdx, topKList, matched) -> {
            throw new RuntimeException("intentional — verifying worker survival");
        };
        Function<String, EvaluationCallback> registry = arena -> throwingCallback;

        // Dispatcher that catches and records throwables — mirrors Bukkit's
        // runTask exception-swallowing behaviour.
        AtomicInteger swallowedExceptions = new AtomicInteger();
        MlCoalescerWorker.MainThreadDispatcher dispatcher = r -> {
            try {
                r.run();
            } catch (Throwable t) {
                swallowedExceptions.incrementAndGet();
            }
        };

        MlQueue mq = new MlQueue(8);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        PluginLogger logger = mock(PluginLogger.class);

        MlCoalescerWorker worker = new MlCoalescerWorker(
                mq, ml, registry, dispatcher, metrics, logger,
                /* maxBatchSize */ 2,
                /* waitMs */ 50L,
                /* topK */ 2);

        Thread t = new Thread(worker, "test-ml-coalescer-throwing");
        t.setDaemon(true);
        t.start();

        // Offer frames one by one so that each pair drains as its own batch
        // (maxBatchSize=2 and waitMs=50 ms), producing at least two distinct
        // batch invocations of predictBatchRgb and two dispatch calls.
        mq.offer(syntheticFrame(UUID.randomUUID(), "theme"));
        mq.offer(syntheticFrame(UUID.randomUUID(), "theme"));
        // Brief pause so the worker picks up the first two before the third
        // arrives — not mandatory for correctness but keeps batch count clean.
        Thread.sleep(20);
        mq.offer(syntheticFrame(UUID.randomUUID(), "theme"));

        // Poll for at least 2 completed batches (covers the 3 frames above
        // being spread across at least 2 separate batch invocations).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted() < 2
                && System.nanoTime() < deadline)
            Thread.sleep(1);

        worker.stop();
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(2));

        long completed = metrics.snapshot(0, 0, 0, 0).mlBatchesCompleted();
        assertTrue(completed >= 2,
                "worker must complete >= 2 batches despite throwing callbacks; got " + completed);
        assertTrue(swallowedExceptions.get() >= 1,
                "dispatcher must have swallowed at least one throwable from the callback; got "
                        + swallowedExceptions.get()
                        + " — check that predictBatchRgb stub returns top-K containing \"theme\"");
    }

    /**
     * Builds a minimal {@link EvalFrame} for testing. Does not mock
     * {@link MutablePlotScene} read-lock because the render step is bypassed —
     * the frame's RGB buffer is pre-filled synthetically.
     */
    private static EvalFrame syntheticFrame(UUID pid, String expectedTheme) {
        EvalJob job = EvalJob.builder()
                .arenaName("arena-1")
                .playerId(pid)
                .playerName("p-" + pid)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme(expectedTheme)
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
        return new EvalFrame(job, new byte[224 * 224 * 3], System.nanoTime());
    }
}

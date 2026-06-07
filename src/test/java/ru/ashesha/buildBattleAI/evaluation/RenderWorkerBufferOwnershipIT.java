package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for EVAL-002: {@link RenderWorker} buffer-ownership contract.
 *
 * <p><b>Risk EVAL-002:</b> if {@link RenderWorker} ever switches to the non-allocating
 * {@code RenderService.render(scene, ..., outBuf)} overload and reuses a single
 * {@code byte[]} across frames, in-flight ML batches would silently share the same
 * backing array. A later render call would overwrite the pixel data while the
 * {@link MlCoalescerWorker} is still reading it, producing corrupt predictions
 * with no exception raised.
 *
 * <p><b>Strategy:</b> enqueue two jobs for <em>different</em> player IDs (same player
 * would dedup-stale the first job in {@link RenderQueue}). Each call to
 * {@link RenderService#render} returns a freshly allocated buffer tagged with a
 * monotonically increasing call counter in {@code buf[0]}. After both frames are
 * drained we assert that:
 * <ul>
 *   <li>The two {@code rgb} arrays are not the same object reference.</li>
 *   <li>Their tag bytes differ, ruling out the scenario where a single array
 *       was reused and the second render simply overwrote byte 0 in place.</li>
 *   <li>The allocating overload was called exactly twice.</li>
 *   <li>The buffer-reuse overload was never called.</li>
 * </ul>
 */
@Tag("integration")
class RenderWorkerBufferOwnershipIT {

    /**
     * Verifies that every {@link EvalFrame} produced by {@link RenderWorker}
     * carries a distinct, freshly-allocated RGB byte buffer.
     */
    @Test
    @DisplayName("EVAL-002: every frame produced by RenderWorker carries a fresh rgb buffer")
    void everyFrameGetsFreshBuffer() throws InterruptedException {
        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        PluginLogger logger = mock(PluginLogger.class);

        // Tag each returned buffer with an incrementing counter so we can
        // distinguish a genuine fresh allocation from a reused array.
        AtomicInteger callCount = new AtomicInteger();
        RenderService renderService = mock(RenderService.class);
        when(renderService.render(any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenAnswer(inv -> {
                    byte[] buf = new byte[224 * 224 * 3];
                    buf[0] = (byte) (callCount.incrementAndGet() & 0xFF);
                    return buf;
                });

        // Use distinct mirror instances with a real read-lock so the worker
        // can lock/unlock without NPE.
        MutablePlotScene mirror1 = mock(MutablePlotScene.class);
        when(mirror1.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());

        MutablePlotScene mirror2 = mock(MutablePlotScene.class);
        when(mirror2.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());

        // Two different player UUIDs — same UUID would cause RenderQueue to
        // stale-mark the first job, so only one frame would ever reach MlQueue.
        rq.offer(jobFor(UUID.randomUUID(), mirror1));
        rq.offer(jobFor(UUID.randomUUID(), mirror2));

        RenderWorker worker = new RenderWorker(0, rq, mq, renderService, metrics, logger);
        Thread t = new Thread(worker, "test-render-worker-bufown");
        t.setDaemon(true);
        t.start();

        // Drain each frame individually; 5 s timeout is generous but finite.
        List<EvalFrame> first = mq.drainBatch(1, 5_000);
        List<EvalFrame> second = mq.drainBatch(1, 5_000);

        worker.stop();
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(2));

        assertEquals(1, first.size(), "first batch must contain exactly one frame");
        assertEquals(1, second.size(), "second batch must contain exactly one frame");

        byte[] buf1 = first.get(0).rgb();
        byte[] buf2 = second.get(0).rgb();

        // Core EVAL-002 assertion: buffers must be distinct objects.
        assertNotSame(buf1, buf2,
                "RenderWorker must allocate a fresh byte[] per frame — "
                        + "reusing a single buffer would corrupt in-flight ML batches");

        // Secondary assertion: tag bytes must differ.  If a single array was
        // reused, the second render would overwrite buf[0] of the first buffer,
        // making both tag bytes equal to the second call's counter value.
        assertNotEquals(buf1[0], buf2[0],
                "tag bytes must differ — equal values imply the second "
                        + "render call overwrote the first buffer in place");

        // Verify the allocating overload was called exactly twice.
        verify(renderService, times(2)).render(
                any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());

        // Verify the buffer-reuse overload was never called.
        verify(renderService, never()).render(
                any(MutablePlotScene.class),
                anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(),
                any(byte[].class));
    }

    /** Builds a minimal {@link EvalJob} for a given player and mirror. */
    private static EvalJob jobFor(UUID id, MutablePlotScene mirror) {
        return EvalJob.builder()
                .arenaName("a")
                .playerId(id)
                .playerName("p-" + id)
                .plotIndex(0)
                .themeIndex(0)
                .expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0)
                .cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(System.nanoTime())
                .build();
    }
}

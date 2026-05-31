package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the threading and error-handling contract of {@link RenderWorker}:
 * happy-path frames are emitted to the ML queue, and exceptions thrown from
 * {@link RenderService#render(ru.ashesha.buildBattleAI.render.data.SceneData, double, double, double, float, float)}
 * are swallowed and counted so the worker survives a single bad job.
 */
class RenderWorkerTest {

    @Test
    void happyPath_renderAndEmitFrame() throws Exception {
        RenderService render = mock(RenderService.class);
        byte[] rgb = new byte[224 * 224 * 3];
        when(render.render(any(MutablePlotScene.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(rgb);

        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        Lock readLock = new ReentrantReadWriteLock().readLock();
        when(mirror.readLock()).thenReturn(readLock);

        EvalJob job = EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        rq.offer(job);

        RenderWorker worker = new RenderWorker(0, rq, mq, render, metrics, mock(PluginLogger.class));
        Thread t = new Thread(worker, "test-render-worker");
        t.start();
        Thread.sleep(100);
        worker.stop();
        t.interrupt();
        t.join(1000);

        assertEquals(0, rq.size());
        assertEquals(1, mq.size());
        verify(render, times(1)).render(eq(mirror), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
    }

    @Test
    void renderException_isSwallowed_andCounted() throws Exception {
        RenderService render = mock(RenderService.class);
        when(render.render(any(MutablePlotScene.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenThrow(new RuntimeException("boom"));

        RenderQueue rq = new RenderQueue(4);
        MlQueue mq = new MlQueue(4);
        EvaluationMetrics metrics = new EvaluationMetrics(8);

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());

        rq.offer(EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mirror)
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build());

        RenderWorker worker = new RenderWorker(0, rq, mq, render, metrics, mock(PluginLogger.class));
        Thread t = new Thread(worker, "test-render-worker");
        t.start();
        Thread.sleep(100);
        worker.stop();
        t.interrupt();
        t.join(1000);

        // Nothing emitted on exception, and the error counter increments to 1.
        assertEquals(0, mq.size());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).renderErrors());
    }
}

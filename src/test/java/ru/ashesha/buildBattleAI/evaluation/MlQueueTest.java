package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MlQueueTest {

    @Test
    void drainBatch_returnsEmpty_whenIdleForFullWait() throws Exception {
        MlQueue q = new MlQueue(4);
        long t0 = System.nanoTime();
        List<EvalFrame> batch = q.drainBatch(4, 50L);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(batch.isEmpty());
        assertTrue(ms >= 40L, "actual: " + ms); // allow scheduling slop
    }

    @Test
    void drainBatch_returnsImmediately_whenItemAvailable() throws Exception {
        MlQueue q = new MlQueue(4);
        q.offer(frame());
        long t0 = System.nanoTime();
        List<EvalFrame> batch = q.drainBatch(4, 5_000L);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(1, batch.size());
        assertTrue(ms < 200L, "actual: " + ms);
    }

    @Test
    void drainBatch_capsAtMax() throws Exception {
        MlQueue q = new MlQueue(8);
        for (int i = 0; i < 6; i++)
            q.offer(frame());
        List<EvalFrame> batch = q.drainBatch(4, 5_000L);
        assertEquals(4, batch.size());
    }

    @Test
    void offer_failsWhenFull() {
        MlQueue q = new MlQueue(1);
        assertTrue(q.offer(frame()));
        assertFalse(q.offer(frame()));
    }

    @Test
    void size_reflectsDepth() {
        MlQueue q = new MlQueue(4);
        q.offer(frame());
        q.offer(frame());
        assertEquals(2, q.size());
    }

    @Test
    void clear_emptiesQueue() {
        MlQueue q = new MlQueue(4);
        q.offer(frame());
        q.offer(frame());
        assertEquals(2, q.size());
        q.clear();
        assertEquals(0, q.size());
    }

    @Test
    void drainBatch_propagatesInterrupt() throws Exception {
        MlQueue q = new MlQueue(4);
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> q.drainBatch(4, 1_000L));
        // clear any residual interrupt flag for the rest of the test suite
        Thread.interrupted();
    }

    private static EvalFrame frame() {
        EvalJob j = EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
        return new EvalFrame(j, new byte[224 * 224 * 3], 0L);
    }
}

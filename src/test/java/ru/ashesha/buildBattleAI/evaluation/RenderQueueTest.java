package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RenderQueueTest {

    @Test
    void offerThenTake_returnsSameJob() throws Exception {
        RenderQueue q = new RenderQueue(8);
        EvalJob j = jobFor(UUID.randomUUID());
        assertTrue(q.offer(j));
        assertSame(j, q.take());
    }

    @Test
    void capacityIsRespected() {
        RenderQueue q = new RenderQueue(2);
        assertTrue(q.offer(jobFor(UUID.randomUUID())));
        assertTrue(q.offer(jobFor(UUID.randomUUID())));
        assertFalse(q.offer(jobFor(UUID.randomUUID())));
    }

    @Test
    void dedup_secondOfferForSamePlayer_marksFirstStale() {
        RenderQueue q = new RenderQueue(8);
        UUID pid = UUID.randomUUID();
        EvalJob first = jobFor(pid);
        EvalJob second = jobFor(pid);
        assertTrue(q.offer(first));
        assertTrue(q.offer(second));
        assertTrue(first.isStale());
        assertFalse(second.isStale());
    }

    @Test
    void take_skipsStaleJobs() throws Exception {
        RenderQueue q = new RenderQueue(8);
        UUID pid = UUID.randomUUID();
        EvalJob first = jobFor(pid);
        EvalJob second = jobFor(pid);
        q.offer(first);
        q.offer(second);
        EvalJob taken = q.take();
        assertSame(second, taken);
    }

    @Test
    void size_reflectsQueueDepth() {
        RenderQueue q = new RenderQueue(8);
        q.offer(jobFor(UUID.randomUUID()));
        q.offer(jobFor(UUID.randomUUID()));
        assertEquals(2, q.size());
    }

    private static EvalJob jobFor(UUID playerId) {
        return EvalJob.builder()
                .arenaName("a").playerId(playerId).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
    }
}

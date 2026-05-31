package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvalJobTest {

    @Test
    void newJob_isNotStale() {
        EvalJob job = sampleJob();
        assertFalse(job.isStale());
    }

    @Test
    void markStale_flipsFlag() {
        EvalJob job = sampleJob();
        job.markStale();
        assertTrue(job.isStale());
    }

    @Test
    void carriesAllFields() {
        UUID pid = UUID.randomUUID();
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        EvalJob job = EvalJob.builder()
                .arenaName("arena1")
                .playerId(pid)
                .playerName("Bob")
                .plotIndex(0)
                .themeIndex(3)
                .expectedTheme("castle")
                .mirror(mirror)
                .cameraX(1.5).cameraY(64.0).cameraZ(2.5)
                .cameraYaw(90f).cameraPitch(0f)
                .enqueuedAtNanos(123_456L)
                .build();

        assertEquals("arena1", job.arenaName());
        assertEquals(pid, job.playerId());
        assertEquals("Bob", job.playerName());
        assertEquals(0, job.plotIndex());
        assertEquals(3, job.themeIndex());
        assertEquals("castle", job.expectedTheme());
        assertSame(mirror, job.mirror());
        assertEquals(1.5, job.cameraX());
        assertEquals(123_456L, job.enqueuedAtNanos());
    }

    private static EvalJob sampleJob() {
        return EvalJob.builder()
                .arenaName("a").playerId(UUID.randomUUID()).playerName("p")
                .plotIndex(0).themeIndex(0).expectedTheme("t")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L)
                .build();
    }
}

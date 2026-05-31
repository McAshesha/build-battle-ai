package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationCoordinatorTest {

    @Test
    void dirtyPlayer_beyondMinCadence_isEnqueued() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, true);
        Map<String, SessionHandle> registry = singleton("a1", h);

        RenderQueue rq = new RenderQueue(8);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator coord = new EvaluationCoordinator(registry, rq, metrics, 5000L);

        coord.tick(nanos(0));
        assertEquals(1, rq.size());
    }

    @Test
    void dirtyPlayer_withinMinCadence_isSkipped() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, true);
        // Record at a non-zero baseline: the SessionHandle returns 0L as the
        // sentinel for "never recorded", so a real prior attempt must be
        // non-zero to be distinguishable from the never-recorded state.
        h.recordEvalAttempt(pid, nanos(1));
        Map<String, SessionHandle> registry = singleton("a1", h);

        RenderQueue rq = new RenderQueue(8);
        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator coord = new EvaluationCoordinator(registry, rq, metrics, 5000L);

        coord.tick(nanos(3000));
        assertEquals(0, rq.size());
    }

    @Test
    void notDirty_isSkipped() {
        SessionHandle h = handleWith(UUID.randomUUID(), false);
        RenderQueue rq = new RenderQueue(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq, new EvaluationMetrics(8), 5000L);
        c.tick(nanos(0));
        assertEquals(0, rq.size());
    }

    @Test
    void notInPlayingState_isSkipped() {
        SessionHandle h = handleWith(UUID.randomUUID(), true);
        when(h.session().state()).thenReturn(ArenaState.COUNTDOWN);
        RenderQueue rq = new RenderQueue(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq, new EvaluationMetrics(8), 5000L);
        c.tick(nanos(0));
        assertEquals(0, rq.size());
    }

    @Test
    void renderQueueFull_incrementsDropCounter_andDoesNotRecordLastEvalAt() {
        UUID pid = UUID.randomUUID();
        SessionHandle h = handleWith(pid, true);
        RenderQueue rq2 = new RenderQueue(1);
        rq2.offer(EvalJob.builder()
                .arenaName("z").playerId(UUID.randomUUID()).playerName("z")
                .plotIndex(0).themeIndex(0).expectedTheme("z")
                .mirror(mock(MutablePlotScene.class))
                .cameraX(0).cameraY(0).cameraZ(0).cameraYaw(0).cameraPitch(0)
                .enqueuedAtNanos(0L).build());

        EvaluationMetrics metrics = new EvaluationMetrics(8);
        EvaluationCoordinator c = new EvaluationCoordinator(
                singleton("a1", h), rq2, metrics, 5000L);
        c.tick(nanos(0));

        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs());
        assertEquals(0L, h.lastEvalAtNanos(pid));
    }

    private static long nanos(long ms) {
        return ms * 1_000_000L;
    }

    private static Map<String, SessionHandle> singleton(String name, SessionHandle h) {
        ConcurrentHashMap<String, SessionHandle> m = new ConcurrentHashMap<>();
        m.put(name, h);
        return m;
    }

    private static SessionHandle handleWith(UUID pid, boolean dirty) {
        GameSession session = mock(GameSession.class);
        when(session.state()).thenReturn(ArenaState.PLAYING);

        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn("a1");
        Arena.PlotData plot = mock(Arena.PlotData.class);
        Arena.Position cam = mock(Arena.Position.class);
        when(cam.x()).thenReturn(0.0); when(cam.y()).thenReturn(0.0); when(cam.z()).thenReturn(0.0);
        when(cam.yaw()).thenReturn(0f); when(cam.pitch()).thenReturn(0f);
        when(plot.cameras()).thenReturn(Collections.singletonList(cam));
        when(arena.plots()).thenReturn(Collections.singletonList(plot));
        when(session.arena()).thenReturn(arena);

        GamePlayer gp = mock(GamePlayer.class);
        when(gp.playerId()).thenReturn(pid);
        when(gp.playerName()).thenReturn("Bob");
        when(gp.plotIndex()).thenReturn(0);
        when(gp.themeIndex()).thenReturn(0);
        when(gp.zoneDirty()).thenReturn(dirty);
        when(session.getTheme(0)).thenReturn("castle");

        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(session.mirror(0)).thenReturn(mirror);

        Map<UUID, GamePlayer> players = new LinkedHashMap<>();
        players.put(pid, gp);
        when(session.players()).thenReturn(players);

        return new SessionHandle(session, (p, t) -> {});
    }
}

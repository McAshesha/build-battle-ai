package ru.ashesha.buildBattleAI.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test — covers risks EVAL-003, EVAL-005, EVAL-011 from the
 * test-coverage spec.
 * <ul>
 *   <li><b>EVAL-003:</b> unregistering a session while a job is in flight
 *       must not crash the pipeline — the next tick simply skips the
 *       arena, the in-flight job (if any reaches the ML stage) is
 *       silently dropped at callback lookup, and the batch is still
 *       counted as completed.</li>
 *   <li><b>EVAL-005:</b> when the render queue is full, the coordinator
 *       must increment the drop counter but NOT update
 *       {@code lastEvalAtNanos} — otherwise the next tick would skip
 *       this player and the drop would persist into the future,
 *       producing a busy-loop / starvation interaction.</li>
 *   <li><b>EVAL-011:</b> a dropped job must be re-considered on the
 *       very next tick (assuming the player is still dirty and the
 *       queue has freed up).</li>
 * </ul>
 *
 * <p>The fixture pattern (mock Arena + PlotData + GameSession + GamePlayer
 * registered via a {@code Map<String, SessionHandle>}) mirrors
 * {@link EvaluationCoordinatorTest}. We extract the helper here so
 * each test reads at a glance.
 */
@Tag("integration")
class EvaluationCoordinatorLifecycleIT {

    private Map<String, SessionHandle> registry;
    private RenderQueue renderQueue;
    private EvaluationMetrics metrics;
    private EvaluationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new ConcurrentHashMap<>();
        // Capacity 1 is deliberate — makes it trivial to engineer queue-full
        // scenarios by pre-filling with one throwaway job.
        renderQueue = new RenderQueue(1);
        metrics = new EvaluationMetrics(8);
        coordinator = new EvaluationCoordinator(registry, renderQueue, metrics, /*minCadenceMs=*/ 10L);
    }

    /**
     * EVAL-005: when the queue is full the coordinator increments the drop
     * counter but {@code lastEvalAtNanos} must remain at its sentinel (0L).
     * <p>
     * If the coordinator updated {@code lastEvalAtNanos} on a failed offer,
     * the cadence gate on the next tick would suppress the same player for
     * the full cadence window — effectively a starvation / busy-loop after
     * every backpressure event.
     */
    @Test
    @DisplayName("EVAL-005: queue-full drop does NOT update lastEvalAtNanos")
    void queueFullDoesNotUpdateLastEvalAt() {
        UUID pid = UUID.randomUUID();
        SessionHandle handle = registerSessionWithDirtyPlayer("arena-1", pid);

        // Pre-fill the single capacity slot so the coordinator's offer must fail.
        renderQueue.offer(throwawayJob(UUID.randomUUID()));

        long beforeTick = System.nanoTime();
        coordinator.tick(beforeTick);

        // The coordinator must have counted the drop.
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs(),
                "queue-full must increment droppedRenderJobs counter");

        // lastEvalAtNanos must remain at 0L (sentinel = "never enqueued")
        // so the next tick re-considers the player without a cadence delay.
        assertEquals(0L, handle.lastEvalAtNanos(pid),
                "lastEvalAtNanos must stay at 0 (sentinel) when the offer "
                        + "was dropped — otherwise the next tick busy-loops");
    }

    /**
     * EVAL-011: the very next tick after a drop MUST re-consider the
     * previously-dropped player, provided the player is still dirty and
     * the queue has a free slot.
     * <p>
     * This is the complement of EVAL-005: because the sentinel is preserved
     * on drop, the cadence check passes on the next tick, which then
     * successfully enqueues the job.
     */
    @Test
    @DisplayName("EVAL-011: dropped job is re-considered in the next tick")
    void droppedJobReenqueuedNextTick() {
        UUID pid = UUID.randomUUID();
        registerSessionWithDirtyPlayer("arena-1", pid);

        // Engineer the first drop: fill the queue, then tick.
        renderQueue.offer(throwawayJob(UUID.randomUUID()));
        coordinator.tick(System.nanoTime());
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).droppedRenderJobs(),
                "precondition: queue-full drop must happen on the first tick");

        // Free the slot — drain the throwaway filler that we pre-offered.
        try {
            renderQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("take interrupted unexpectedly");
        }
        assertEquals(0, renderQueue.size(), "precondition: queue empty after drain");

        // Second tick: player is still dirty, lastEvalAtNanos is still 0L,
        // queue has capacity — the job must land successfully.
        coordinator.tick(System.nanoTime());

        assertEquals(1, renderQueue.size(),
                "second tick must re-enqueue the previously-dropped player's job");
    }

    /**
     * EVAL-003: removing a session from the registry mid-operation must
     * not crash the coordinator. Subsequent ticks simply skip the missing
     * arena — no job is enqueued, no exception is thrown.
     * <p>
     * The "in-flight ML batch survives despite the arena being gone" aspect
     * is covered separately in {@code MlCoalescerCallbackResilienceIT}
     * (Task 4). Here we only verify the coordinator-side invariants.
     */
    @Test
    @DisplayName("EVAL-003: unregister mid-flight is silent — coordinator tolerates missing arena")
    void unregisterDuringInflight() {
        UUID pid = UUID.randomUUID();
        registerSessionWithDirtyPlayer("arena-1", pid);

        // First tick: arena registered, queue empty — job must enqueue.
        coordinator.tick(System.nanoTime());
        assertEquals(1, renderQueue.size(),
                "precondition: first tick must enqueue (queue starts empty, "
                        + "minCadenceMs=10 allows immediate first evaluation)");

        // Simulate unregister (e.g. game ends, EvaluationService.unregisterSession
        // removes the arena from the shared registry).
        registry.remove("arena-1");

        // Second tick: arena gone — coordinator must be silent.
        int sizeBeforeSecondTick = renderQueue.size();
        assertDoesNotThrow(() -> coordinator.tick(System.nanoTime() + 1_000_000_000L),
                "tick after unregister must not throw any exception");
        assertEquals(sizeBeforeSecondTick, renderQueue.size(),
                "no new jobs may be enqueued after the session is unregistered");
    }

    // ── private helpers ───────────────────────────────────────────────────

    /**
     * Builds a {@link SessionHandle} whose mocked {@link GameSession} is in
     * state {@link ArenaState#PLAYING}, with a single dirty player identified
     * by {@code pid}. Registers it into {@code this.registry} under
     * {@code arenaName}.
     *
     * <p>The mock chain mirrors {@code EvaluationCoordinatorTest.handleWith}
     * exactly — only the return type differs (SessionHandle vs void).
     *
     * @param arenaName the key used in the session registry
     * @param pid       the player UUID; the player's {@code zoneDirty()} flag
     *                  is {@code true} so the coordinator selects the player
     *                  on its first tick
     * @return the registered handle (useful for asserting on
     *         {@link SessionHandle#lastEvalAtNanos(UUID)} in EVAL-005/011)
     */
    private SessionHandle registerSessionWithDirtyPlayer(String arenaName, UUID pid) {
        // Set up the session mock in PLAYING state.
        GameSession session = mock(GameSession.class);
        when(session.state()).thenReturn(ArenaState.PLAYING);

        // Wire arena + plot + single camera so the coordinator can build a job.
        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn(arenaName);
        Arena.PlotData plot = mock(Arena.PlotData.class);
        Arena.Position cam = mock(Arena.Position.class);
        when(cam.x()).thenReturn(0.0);
        when(cam.y()).thenReturn(0.0);
        when(cam.z()).thenReturn(0.0);
        when(cam.yaw()).thenReturn(0f);
        when(cam.pitch()).thenReturn(0f);
        when(plot.cameras()).thenReturn(Collections.singletonList(cam));
        when(arena.plots()).thenReturn(Collections.singletonList(plot));
        when(session.arena()).thenReturn(arena);

        // Single player stub: dirty=true so the coordinator considers them.
        GamePlayer gp = mock(GamePlayer.class);
        when(gp.playerId()).thenReturn(pid);
        when(gp.playerName()).thenReturn("TestPlayer");
        when(gp.plotIndex()).thenReturn(0);
        when(gp.themeIndex()).thenReturn(0);
        when(gp.zoneDirty()).thenReturn(true);
        when(session.getTheme(0)).thenReturn("castle");

        // Provide a mirror so the coordinator doesn't null-exit early.
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());
        when(session.mirror(0)).thenReturn(mirror);

        // One-entry players map.
        Map<UUID, GamePlayer> players = new LinkedHashMap<>();
        players.put(pid, gp);
        when(session.players()).thenReturn(players);

        // No-op callback — lifecycle tests don't care about score dispatch.
        SessionHandle handle = new SessionHandle(session, (playerId, themeIdx, topK, matched) -> {});
        registry.put(arenaName, handle);
        return handle;
    }

    /**
     * Builds a minimal {@link EvalJob} used as a queue filler. The player ID
     * is arbitrary (must not collide with the session-under-test player so the
     * dedup logic doesn't interact with the test's real player).
     *
     * @param id a throwaway player UUID
     * @return a filler job whose only purpose is to occupy a queue slot
     */
    private EvalJob throwawayJob(UUID id) {
        MutablePlotScene mirror = mock(MutablePlotScene.class);
        when(mirror.readLock()).thenReturn(new ReentrantReadWriteLock().readLock());
        return EvalJob.builder()
                .arenaName("filler")
                .playerId(id)
                .playerName("filler-player")
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

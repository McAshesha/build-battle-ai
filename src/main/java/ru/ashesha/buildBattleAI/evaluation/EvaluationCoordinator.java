package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.game.ArenaState;
import ru.ashesha.buildBattleAI.game.GamePlayer;
import ru.ashesha.buildBattleAI.game.GameSession;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.Map;

/**
 * Pure (Bukkit-free) picker that drives the evaluation pipeline. Designed
 * to be invoked from a Bukkit-scheduled main-thread task — the actual
 * scheduling lives in {@link EvaluationService}.
 */
final class EvaluationCoordinator {

    private final Map<String, SessionHandle> registry;
    private final RenderQueue renderQueue;
    private final EvaluationMetrics metrics;
    private final long minCadenceNanos;

    EvaluationCoordinator(@NonNull Map<String, SessionHandle> registry,
                          @NonNull RenderQueue renderQueue,
                          @NonNull EvaluationMetrics metrics,
                          long minCadenceMs) {
        this.registry = registry;
        this.renderQueue = renderQueue;
        this.metrics = metrics;
        this.minCadenceNanos = minCadenceMs * 1_000_000L;
    }

    /**
     * Executes one coordinator tick. Caller is responsible for invoking
     * this on the Bukkit main thread.
     */
    void tick(long nowNanos) {
        for (SessionHandle handle : registry.values()) {
            GameSession session = handle.session();
            if (session.state() != ArenaState.PLAYING)
                continue;

            int cameraIdx = handle.currentCameraIndex();
            handle.advanceCamera();

            for (GamePlayer gp : session.players().values())
                considerPlayer(handle, session, gp, cameraIdx, nowNanos);
        }
    }

    private void considerPlayer(SessionHandle handle, GameSession session,
                                GamePlayer gp, int cameraIdx, long nowNanos) {
        if (!gp.zoneDirty())
            return;
        // Sentinel zero == "never enqueued" → always allow first evaluation
        // regardless of nowNanos, otherwise (nowNanos - 0) at the very first
        // tick (where nowNanos may itself be small or zero in tests) would
        // unconditionally fail the cadence check.
        long lastEvalAt = handle.lastEvalAtNanos(gp.playerId());
        if (lastEvalAt != 0L && nowNanos - lastEvalAt < minCadenceNanos)
            return;

        Arena arena = session.arena();
        List<Arena.PlotData> plots = arena.plots();
        if (gp.plotIndex() >= plots.size())
            return;
        Arena.PlotData plot = plots.get(gp.plotIndex());
        List<Arena.Position> cameras = plot.cameras();
        if (cameras.isEmpty())
            return;

        MutablePlotScene mirror = session.mirror(gp.plotIndex());
        if (mirror == null)
            return;

        Arena.Position cam = cameras.get(cameraIdx % cameras.size());

        // Note: zone-dirty flag is not cleared here — the dirty flag is owned by
        // GamePlayer (package-private mutator). Rate-limiting is enforced by the
        // cadence guard (lastEvalAtNanos) above; the dirty flag is cleared
        // elsewhere (e.g. on score award or session reset).
        EvalJob job = EvalJob.builder()
                .arenaName(arena.name())
                .playerId(gp.playerId())
                .playerName(gp.playerName())
                .plotIndex(gp.plotIndex())
                .themeIndex(gp.themeIndex())
                .expectedTheme(session.getTheme(gp.themeIndex()))
                .mirror(mirror)
                .cameraX(cam.x()).cameraY(cam.y()).cameraZ(cam.z())
                .cameraYaw(cam.yaw()).cameraPitch(cam.pitch())
                .enqueuedAtNanos(nowNanos)
                .build();

        if (renderQueue.offer(job))
            handle.recordEvalAttempt(gp.playerId(), nowNanos);
        else
            metrics.incDroppedRenderJobs();
    }
}

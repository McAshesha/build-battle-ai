package ru.ashesha.buildBattleAI.evaluation.api;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.game.GameSession;

/**
 * Public API of the evaluation pipeline. Implementations centrally schedule
 * render + ML inference across all active arenas with bounded queues,
 * ML batching, and per-player cadence smoothing.
 * <p>
 * Lifecycle is owned by {@code PluginService} (internal); callers only see
 * the runtime API below.
 * <p>
 * <b>Threading:</b> {@code registerSession} and {@code unregisterSession}
 * must be called from the Bukkit main thread. {@code stats()} is safe to
 * call from any thread.
 */
public interface BBAIEvaluationService {

    /**
     * Registers an active game session with the evaluation pipeline. From
     * this moment on, the service will periodically scan the session's
     * dirty players and run the render → ML pipeline for them.
     * <p>
     * The {@code callback} fires for <i>every</i> completed evaluation, not
     * only on theme matches — see {@link EvaluationCallback} for the full
     * contract (top-K guesses, match flag, main-thread dispatch).
     *
     * @param session  the active session (already in PLAYING state)
     * @param callback invoked on the Bukkit main thread after every ML
     *                 evaluation of one of this session's players
     */
    void registerSession(@NonNull GameSession session,
                         @NonNull EvaluationCallback callback);

    /**
     * Unregisters a session. Any in-flight jobs for this arena are dropped
     * silently when they reach the dispatch stage.
     *
     * @param arenaName arena name (the session's arena identifier)
     */
    void unregisterSession(@NonNull String arenaName);

    /**
     * Returns an immutable snapshot of pipeline metrics. Safe to call from
     * any thread.
     */
    @NonNull EvaluationStats stats();
}

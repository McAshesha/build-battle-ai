package ru.ashesha.buildBattleAI.evaluation.api;

import lombok.NonNull;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Public API of the evaluation pipeline. Implementations centrally schedule
 * render + ML inference across all active arenas with bounded queues,
 * ML batching, and per-player cadence smoothing.
 * <p>
 * Lifecycle is owned by {@code PluginService} (internal); callers only see
 * the runtime API below.
 */
public interface BBAIEvaluationService {

    /**
     * Registers an active game session with the evaluation pipeline. From
     * this moment on, the service will periodically scan the session's
     * dirty players and run the render → ML pipeline for them.
     *
     * @param session       the active session (already in PLAYING state)
     * @param scoreCallback invoked on the Bukkit main thread for every
     *                      successful match — arguments are (playerId, themeIndex)
     */
    void registerSession(@NonNull GameSession session,
                         @NonNull BiConsumer<UUID, Integer> scoreCallback);

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

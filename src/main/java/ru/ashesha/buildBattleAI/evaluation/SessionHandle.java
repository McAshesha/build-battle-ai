package ru.ashesha.buildBattleAI.evaluation;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.evaluation.api.EvaluationCallback;
import ru.ashesha.buildBattleAI.game.GameSession;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session bookkeeping for the evaluation pipeline. Owns the camera
 * rotation index (moved out of {@code GameSession} — it's an evaluation
 * concern, not a game-session concern) and the per-player last-evaluated
 * timestamps that drive cadence enforcement.
 * <p>
 * The camera index is only touched by the coordinator (main-thread,
 * single-writer) — no synchronization needed. The lastEvalAt map is a
 * {@link ConcurrentHashMap} so future-proof against off-main-thread use.
 */
@RequiredArgsConstructor
@Getter
@Accessors(fluent = true)
final class SessionHandle {

    private final @NonNull GameSession session;
    /**
     * Per-session feedback callback. Fires for every completed evaluation
     * of a session player, regardless of theme match — see
     * {@link EvaluationCallback} for the full contract.
     */
    private final @NonNull EvaluationCallback callback;

    @Getter(AccessLevel.NONE)
    private final ConcurrentHashMap<UUID, Long> lastEvalAtNanos = new ConcurrentHashMap<>();

    private int currentCameraIndex = 0;

    /** Advances camera through the fixed 3-slot rotation. */
    public void advanceCamera() {
        currentCameraIndex = (currentCameraIndex + 1) % 3;
    }

    /** Returns the last enqueue time for this player, or 0 if never enqueued. */
    public long lastEvalAtNanos(@NonNull UUID playerId) {
        Long v = lastEvalAtNanos.get(playerId);
        return v == null ? 0L : v;
    }

    /** Records that a job was just enqueued for this player. */
    public void recordEvalAttempt(@NonNull UUID playerId, long nanos) {
        lastEvalAtNanos.put(playerId, nanos);
    }

    /** Drops the lastEvalAt entry for a player who left the session. */
    public void forgetPlayer(@NonNull UUID playerId) {
        lastEvalAtNanos.remove(playerId);
    }
}

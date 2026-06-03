package ru.ashesha.buildBattleAI.evaluation.api;

import ru.ashesha.buildBattleAI.ml.api.TopKEntry;

import java.util.List;
import java.util.UUID;

/**
 * Callback invoked by the {@code EvaluationService} after every completed
 * ML evaluation of a player's build, regardless of whether the AI matched
 * the expected theme.
 * <p>
 * Always dispatched on the Bukkit main thread, so implementations may
 * freely touch Bukkit state (player teleports, block placements, packet
 * sending) without further marshalling.
 * <p>
 * <b>Replaces the legacy {@code BiConsumer<UUID, Integer>} score callback.</b>
 * The richer signature lets the game-side feedback layer surface AI
 * "thoughts" (top-K guesses) to players continuously, not just on match.
 * <p>
 * <b>Threading:</b> invoked on the Bukkit main thread.
 * <br>
 * <b>Lifecycle:</b> the {@code topK} list is immutable and safe to retain;
 * the {@code playerId} and {@code themeIndex} reflect the state at the
 * moment the job was enqueued — implementations should re-verify against
 * the current session (the player may have advanced to a new theme,
 * left, or had their session torn down between enqueue and dispatch).
 */
@FunctionalInterface
public interface EvaluationCallback {

    /**
     * @param playerId   the player whose build was evaluated
     * @param themeIndex the theme index the player was on at enqueue time;
     *                   compare against the current session to detect stale
     *                   results when the player has since advanced
     * @param topK       ranked top-K nearest classes (descending similarity);
     *                   never {@code null}, never empty (the ML service
     *                   always returns at least one entry)
     * @param matched    {@code true} if {@code topK} contains an entry whose
     *                   class name matches the player's expected theme
     *                   (case-insensitive)
     */
    void onEvaluated(UUID playerId, int themeIndex, List<TopKEntry> topK, boolean matched);
}

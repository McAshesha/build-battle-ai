package ru.ashesha.buildBattleAI.game;

import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.UUID;

/**
 * Per-player state within an active game session.
 * <p>
 * Tracks the player's assigned plot, current theme, build timer,
 * score, and the captured pre-game state for restoration on leave/end.
 * <p>
 * Theme index advances independently per player — each player works
 * through the shuffled theme list at their own pace.
 */
@Getter
@Accessors(fluent = true)
public class GamePlayer {

    /** The player's UUID. */
    @NonNull
    private final UUID playerId;

    /** The player's display name (cached at join time). */
    @NonNull
    private final String playerName;

    /** 0-based index into the arena's plot list. */
    private final int plotIndex;

    /** Saved player state for restoration on leave/end. */
    @NonNull
    private final PlayerSnapshot snapshot;

    /** Points scored in this game session. */
    private int score;

    /** Current index into the session's shuffled theme list. */
    private int themeIndex;

    /** Seconds remaining for the current build attempt. */
    private int buildTimeRemaining;

    /**
     * Whether the player has placed or broken any block in their zone
     * since the last zone clear. Used to skip render ticks when the
     * zone is empty.
     */
    private boolean zoneDirty;

    /**
     * Creates a game player.
     *
     * @param playerId           the player UUID
     * @param playerName         the player display name
     * @param plotIndex          0-based plot index
     * @param snapshot           captured pre-game state
     * @param buildTimeRemaining initial build time in seconds
     */
    GamePlayer(@NonNull UUID playerId, @NonNull String playerName,
               int plotIndex, @NonNull PlayerSnapshot snapshot,
               int buildTimeRemaining) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.plotIndex = plotIndex;
        this.snapshot = snapshot;
        this.buildTimeRemaining = buildTimeRemaining;
    }

    /** Increments the score by one. */
    void incrementScore() {
        score++;
    }

    /**
     * Advances to the next theme in the session's theme list.
     *
     * @param themeCount total number of available themes (for wrapping)
     */
    void advanceTheme(int themeCount) {
        themeIndex = (themeIndex + 1) % themeCount;
    }

    /** Decrements the build timer by one second. */
    void decrementBuildTime() {
        if (buildTimeRemaining > 0)
            buildTimeRemaining--;
    }

    /**
     * Resets the build timer to the given value.
     *
     * @param seconds the new build time in seconds
     */
    void resetBuildTime(int seconds) {
        this.buildTimeRemaining = seconds;
    }

    /** Marks the zone as dirty (block placed/broken). */
    void markZoneDirty() {
        zoneDirty = true;
    }

    /** Marks the zone as clean (after zone clear). */
    public void clearZoneDirty() {
        zoneDirty = false;
    }
}

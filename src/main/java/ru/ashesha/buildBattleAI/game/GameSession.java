package ru.ashesha.buildBattleAI.game;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.*;

/**
 * Manages the runtime state of a single game session within an arena.
 * <p>
 * Each session tracks connected players, the state machine, shuffled themes,
 * rotating camera index, remaining game time, and Bukkit scheduler task IDs
 * for cleanup on shutdown.
 * <p>
 * Created when the first player joins an arena, destroyed when the game ends
 * and all players have been restored.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public class GameSession {

    /** The arena definition this session belongs to. */
    @NonNull
    private final Arena arena;

    /** Current lifecycle state. */
    @Setter
    private ArenaState state = ArenaState.WAITING;

    /** Players currently in this session, keyed by UUID. */
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();

    /** Used plot indices, tracked for assignment. */
    private final Set<Integer> usedPlots = new HashSet<>();

    /** Shuffled theme list for this game (populated at game start). */
    private List<String> themes = Collections.emptyList();

    /**
     * Camera index that rotates across render ticks.
     * Shared across the session — all players rotate cameras together.
     * Cycles 0 → 1 → 2 → 0 → ...
     */
    @Setter
    private int currentCameraIndex;

    /** Seconds remaining in the entire game session. */
    @Setter
    private int gameTimeRemaining;

    // ── Bukkit task IDs for cleanup ───────────────────────────────────

    /** Countdown timer task ID, or -1 if not active. */
    @Setter
    private int countdownTaskId = -1;

    /** Game tick timer task ID, or -1 if not active. */
    @Setter
    private int gameTickTaskId = -1;

    /** Render/ML pipeline timer task ID, or -1 if not active. */
    @Setter
    private int renderTaskId = -1;

    /** Ending delay task ID, or -1 if not active. */
    @Setter
    private int endingTaskId = -1;

    /**
     * Per-plot block-state mirrors used by the async renderer.
     * <p>
     * Keys are plot indices; entries are populated when the session enters
     * {@link ArenaState#PLAYING} and removed wholesale at game end.
     */
    private final Map<Integer, MutablePlotScene> mirrors = new HashMap<>();

    /**
     * Adds a player to this session.
     *
     * @param player the game player to add
     */
    void addPlayer(@NonNull GamePlayer player) {
        players.put(player.playerId(), player);
        usedPlots.add(player.plotIndex());
    }

    /**
     * Removes a player from this session.
     *
     * @param playerId the player UUID
     * @return the removed player, or {@code null} if not found
     */
    GamePlayer removePlayer(@NonNull UUID playerId) {
        GamePlayer removed = players.remove(playerId);
        if (removed != null)
            usedPlots.remove(removed.plotIndex());
        return removed;
    }

    /**
     * Returns the game player with the given UUID, or {@code null}.
     *
     * @param playerId the player UUID
     * @return the game player, or {@code null}
     */
    GamePlayer getPlayer(@NonNull UUID playerId) {
        return players.get(playerId);
    }

    /**
     * Finds the first unused plot index (0-based).
     *
     * @return the first available plot index, or -1 if all plots are taken
     */
    int findAvailablePlot() {
        for (int i = 0; i < arena.maxPlayers(); i++)
            if (!usedPlots.contains(i))
                return i;
        return -1;
    }

    /**
     * Sets the shuffled theme list for this game.
     *
     * @param themes the theme list
     */
    void setThemes(@NonNull List<String> themes) {
        this.themes = themes;
    }

    /**
     * Returns the theme at the given index, wrapping if needed.
     *
     * @param index the theme index
     * @return the theme string
     */
    String getTheme(int index) {
        if (themes.isEmpty())
            return "unknown";
        return themes.get(index % themes.size());
    }

    /**
     * Advances the camera index, wrapping at 3 (since each plot has 3 cameras).
     */
    void advanceCamera() {
        currentCameraIndex = (currentCameraIndex + 1) % 3;
    }

    /**
     * Cancels all active Bukkit scheduler tasks associated with this session.
     * Safe to call multiple times.
     */
    void cancelAllTasks() {
        cancelTask(countdownTaskId);
        cancelTask(gameTickTaskId);
        cancelTask(renderTaskId);
        cancelTask(endingTaskId);
        countdownTaskId = -1;
        gameTickTaskId = -1;
        renderTaskId = -1;
        endingTaskId = -1;
    }

    /**
     * Returns the mirror for the given plot index, or {@code null} if not installed.
     *
     * @param plotIndex the 0-based plot index
     * @return the installed mirror or {@code null}
     */
    MutablePlotScene mirror(int plotIndex) {
        return mirrors.get(plotIndex);
    }

    /**
     * Installs the mirror for the given plot index. Replaces any existing mapping.
     *
     * @param plotIndex the 0-based plot index
     * @param scene     the mirror to install
     */
    void installMirror(int plotIndex, @NonNull MutablePlotScene scene) {
        mirrors.put(plotIndex, scene);
    }

    /**
     * Drops all installed mirrors. Called on game end.
     */
    void clearMirrors() {
        mirrors.clear();
    }

    /**
     * Cancels a single Bukkit scheduler task if active.
     *
     * @param taskId the task ID, or -1 if not active
     */
    private static void cancelTask(int taskId) {
        if (taskId != -1)
            Bukkit.getScheduler().cancelTask(taskId);
    }
}

package ru.ashesha.buildBattleAI.game.api;

import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.game.ArenaState;

import java.util.UUID;

/**
 * Service contract for managing game sessions.
 * <p>
 * A game session encompasses the full lifecycle of a Build Battle game: lobby phase,
 * countdown, building phase with ML-based scoring, and results display.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the internal
 * {@code PluginService} contract.
 */
public interface BBAIGameManager {

    /**
     * Attempts to join a player into the specified arena.
     *
     * @param player    the player to join
     * @param arenaName the target arena name
     * @return {@code true} if the join was successful
     */
    boolean joinArena(Player player, String arenaName);

    /**
     * Removes a player from their current arena session, restoring
     * their pre-game state.
     *
     * @param player the player to remove
     * @return {@code true} if the player was in a game and was removed
     */
    boolean leaveArena(Player player);

    /**
     * Checks whether a player is currently in any game session.
     *
     * @param playerId the player UUID
     * @return {@code true} if the player is in a game
     */
    boolean isInGame(UUID playerId);

    /**
     * Returns the arena name the player is currently in, or {@code null}.
     *
     * @param playerId the player UUID
     * @return the arena name, or {@code null}
     */
    String getPlayerArena(UUID playerId);

    /**
     * Returns the current state of the arena, or {@link ArenaState#WAITING}
     * if no session exists.
     *
     * @param arenaName the arena name
     * @return the arena state
     */
    ArenaState getArenaState(String arenaName);

    /**
     * Returns the number of players currently in the arena's session.
     *
     * @param arenaName the arena name
     * @return the player count, or 0 if no session exists
     */
    int getPlayerCount(String arenaName);
}

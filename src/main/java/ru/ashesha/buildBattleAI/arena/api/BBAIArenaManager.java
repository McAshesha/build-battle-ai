package ru.ashesha.buildBattleAI.arena.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Service contract for managing build arenas.
 * <p>
 * An arena represents a dedicated void world containing one or more
 * player build zones (plots), each with a defined camera angle for
 * the AI renderer. Implementations handle:
 * <ul>
 *     <li>Loading/unloading arenas from YAML configuration on startup/shutdown</li>
 *     <li>Interactive arena creation via a non-linear panel-based wizard</li>
 *     <li>Arena deletion with world cleanup</li>
 * </ul>
 * The setup wizard presents a single panel showing ALL configurable
 * settings. The admin can fill them in any order and re-select values
 * freely. Each change re-renders the full panel.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the internal
 * {@code PluginService} contract.
 *
 * @see Arena
 */
public interface BBAIArenaManager {

    // ── query ──────────────────────────────────────────────────────────

    /**
     * Returns the arena with the given name, or {@code null} if no such
     * arena exists.
     *
     * @param name the arena name
     * @return the arena, or {@code null}
     */
    Arena getArena(String name);

    /**
     * Returns the names of all loaded arenas.
     *
     * @return unmodifiable set of arena names
     */
    Set<String> getArenaNames();

    /**
     * Returns all loaded arenas.
     *
     * @return unmodifiable collection of arenas
     */
    Collection<Arena> getArenas();

    /**
     * Checks whether an arena with the given name is loaded and enabled.
     *
     * @param name the arena name
     * @return {@code true} if the arena exists and is enabled
     */
    boolean isArenaLoaded(String name);

    // ── setup wizard ───────────────────────────────────────────────────

    /**
     * Starts the interactive arena creation wizard for the given player.
     * Creates a void world, teleports the player in, and shows the
     * initial setup panel with all settings unset.
     *
     * @param player    the admin running the wizard
     * @param arenaName the desired arena name
     */
    void startSetup(Player player, String arenaName);

    /**
     * Checks whether the given player has an active setup session.
     *
     * @param playerId player UUID
     * @return {@code true} if a session exists
     */
    boolean hasSetupSession(UUID playerId);

    /**
     * Sets the maximum player count for the arena being created.
     * Re-renders the panel with plot sections for the chosen count.
     *
     * @param player the admin
     * @param count  selected player count (2–8)
     */
    void handleSetPlayers(Player player, int count);

    /**
     * Records the player's current position as the arena lobby
     * (where players wait before the game starts).
     *
     * @param player the admin
     */
    void handleSetLobby(Player player);

    /**
     * Records the player's current position as the spectator/results
     * area. This is optional — if unset, the lobby is used.
     *
     * @param player the admin
     */
    void handleSetSpectator(Player player);

    /**
     * Records the player's current position as the spawn point
     * for the given plot (where the player teleports to build).
     *
     * @param player    the admin
     * @param plotIndex 1-based plot index
     */
    void handleSetSpawn(Player player, int plotIndex);

    /**
     * Records the player's current block position as the first corner
     * of the given plot's build zone.
     *
     * @param player    the admin
     * @param plotIndex 1-based plot index
     */
    void handleSetCorner1(Player player, int plotIndex);

    /**
     * Records the player's current block position as the opposite corner
     * of the given plot's build zone.
     *
     * @param player    the admin
     * @param plotIndex 1-based plot index
     */
    void handleSetCorner2(Player player, int plotIndex);

    /**
     * Records the player's current position and look direction as a
     * camera angle for the given plot's renderer capture.
     * Each plot has 3 cameras to provide multiple viewing angles
     * for the AI classifier.
     *
     * @param player      the admin
     * @param plotIndex   1-based plot index
     * @param cameraIndex 1-based camera index (1–3)
     */
    void handleSetCamera(Player player, int plotIndex, int cameraIndex);

    /**
     * Finalizes arena creation if all required settings are filled.
     * Saves the arena config to YAML, registers it as active, and
     * returns the player to their original location.
     *
     * @param player the admin
     */
    void handleConfirm(Player player);

    /**
     * Cancels the active setup session: deletes the temporary world and
     * returns the player to their original location.
     *
     * @param player the admin
     */
    void handleCancel(Player player);

    /**
     * Cancels the setup session for the given player without sending
     * messages (used when the player disconnects).
     *
     * @param playerId player UUID
     */
    void cancelSetupSession(UUID playerId);

    // ── management ─────────────────────────────────────────────────────

    /**
     * Permanently deletes an arena: removes the YAML config, unloads
     * and deletes the arena world.
     *
     * @param name the arena name to delete
     */
    void deleteArena(String name);
}

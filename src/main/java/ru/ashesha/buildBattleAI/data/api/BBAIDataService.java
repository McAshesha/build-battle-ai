package ru.ashesha.buildBattleAI.data.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Service contract for persistent player and arena data storage.
 * <p>
 * Provides domain-specific methods for reading and writing player statistics
 * and arena statistics. The underlying storage backend (Apache Ignite cluster
 * or local JSON files) is transparent to callers — the active backend is
 * determined by the {@code data.provider} key in {@code config.yml}.
 * <p>
 * All methods are thread-safe. Read methods return the current snapshot;
 * write methods are eventually durable (periodically auto-saved for local
 * storage, immediately committed for Ignite). An explicit {@link #flush()}
 * forces all pending writes to persistent storage.
 * <p>
 * When the service is disabled (via {@code data.enabled: false} in config),
 * read methods return {@code null} or empty collections, and write methods
 * are silent no-ops. Callers should check {@link #isEnabled()} if they need
 * to distinguish "no data found" from "service disabled".
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the
 * internal {@link ru.ashesha.buildBattleAI.core.PluginService} contract.
 */
public interface BBAIDataService {

    // ── player data ───────────────────────────────────────────────────────

    /**
     * Returns the stored data for a player, or {@code null} if not found.
     *
     * @param uuid the player's unique ID
     * @return the player data, or {@code null}
     */
    PlayerData getPlayer(UUID uuid);

    /**
     * Returns the stored data for a player, creating a new default entry
     * if one does not exist. If the player already exists but their name
     * has changed, the stored name is updated automatically.
     *
     * @param uuid the player's unique ID
     * @param name the player's current display name
     * @return the player data (never {@code null} when the service is enabled)
     */
    PlayerData getOrCreatePlayer(UUID uuid, String name);

    /**
     * Saves player data, creating or replacing the existing entry.
     *
     * @param data the player data to save
     */
    void savePlayer(PlayerData data);

    /**
     * Returns all stored player data entries.
     *
     * @return unmodifiable collection of all player data (empty if disabled)
     */
    Collection<PlayerData> getAllPlayers();

    // ── arena stats ───────────────────────────────────────────────────────

    /**
     * Returns the stored statistics for an arena, or {@code null} if not found.
     *
     * @param name the arena name
     * @return the arena stats, or {@code null}
     */
    ArenaStats getArenaStats(String name);

    /**
     * Saves arena statistics, creating or replacing the existing entry.
     *
     * @param stats the arena statistics to save
     */
    void saveArenaStats(ArenaStats stats);

    /**
     * Returns all stored arena statistics entries.
     *
     * @return unmodifiable collection of all arena stats (empty if disabled)
     */
    Collection<ArenaStats> getAllArenaStats();

    // ── lifecycle helpers ─────────────────────────────────────────────────

    /**
     * Forces all pending data to be written to persistent storage.
     * No-op if the backend writes immediately (e.g. Ignite) or if the
     * service is disabled.
     */
    void flush();

    /**
     * Returns whether the data service is currently enabled and operational.
     *
     * @return {@code true} if the service was successfully enabled
     */
    boolean isEnabled();
}

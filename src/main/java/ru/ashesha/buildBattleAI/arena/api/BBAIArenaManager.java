package ru.ashesha.buildBattleAI.arena.api;

/**
 * Service contract for managing build arenas.
 * <p>
 * An arena represents a physical region in the world where players construct their builds.
 * Implementations handle arena creation, allocation to players/teams, and cleanup.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the internal
 * {@code PluginService} contract.
 */
public interface BBAIArenaManager {
}

package ru.ashesha.buildBattleAI.arena.api;

/**
 * Service contract for managing build arenas.
 * <p>
 * An arena represents a physical region in the world where players construct their builds.
 * Implementations handle arena creation, allocation to players/teams, and cleanup.
 */
public interface BBAIArenaManager {

    /**
     * Shuts down the arena manager, releasing all arena resources
     * and saving any persistent state.
     */
    void shutdown();
}

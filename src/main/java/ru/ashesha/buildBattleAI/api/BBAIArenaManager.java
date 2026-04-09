package ru.ashesha.buildBattleAI.api;

/**
 * Service contract for managing build arenas.
 * <p>
 * An arena represents a physical region in the world where players construct their builds.
 * Implementations handle arena creation, allocation to players/teams, and cleanup.
 */
public interface BBAIArenaManager {

    /**
     * Initializes the arena manager, loading arena definitions from configuration
     * and preparing them for game use.
     */
    void initialize();

    /**
     * Shuts down the arena manager, releasing all arena resources
     * and saving any persistent state.
     */
    void shutdown();
}

package ru.ashesha.buildBattleAI.api;

/**
 * Service contract for managing game sessions.
 * <p>
 * A game session encompasses the full lifecycle of a Build Battle round: lobby phase,
 * topic assignment, building phase, AI judging, and results display.
 */
public interface BBAIGameManager {

    /**
     * Initializes the game manager, loading game configuration
     * and preparing for session creation.
     */
    void initialize();

    /**
     * Shuts down the game manager, ending all active sessions
     * and cleaning up associated resources.
     */
    void shutdown();
}

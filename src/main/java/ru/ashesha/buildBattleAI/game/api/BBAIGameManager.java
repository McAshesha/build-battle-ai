package ru.ashesha.buildBattleAI.game.api;

/**
 * Service contract for managing game sessions.
 * <p>
 * A game session encompasses the full lifecycle of a Build Battle round: lobby phase,
 * topic assignment, building phase, AI judging, and results display.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the internal
 * {@code PluginService} contract.
 */
public interface BBAIGameManager {
}

package ru.ashesha.buildBattleAI.game;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;

/**
 * Default implementation of {@link BBAIGameManager}.
 * <p>
 * Currently a stub — game session lifecycle (lobby, building phase, AI judging,
 * results display), topic assignment, scoring, and player tracking will be
 * implemented here once the game flow is designed.
 * <p>
 * Implements both {@link BBAIGameManager} (the public API contract) and
 * {@link PluginService} (the internal lifecycle contract) independently, so
 * external consumers cannot reach lifecycle methods through the API type.
 */
@RequiredArgsConstructor
public class GameManager implements BBAIGameManager, PluginService {

    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Starts the game manager. Currently a stub — will eventually bring up
     * the lobby state machine and tick loop.
     */
    @Override
    public void enable() {
        plugin.getLogger().info("GameManager enabled.");
    }

    /**
     * Shuts down the game manager, ending any active sessions and releasing
     * associated resources without disturbing other plugins or the server.
     */
    @Override
    public void shutdown() {
        plugin.getLogger().info("GameManager shut down.");
    }
}

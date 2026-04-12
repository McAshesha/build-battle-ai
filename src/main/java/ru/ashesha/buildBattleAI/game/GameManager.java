package ru.ashesha.buildBattleAI.game;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.game.api.BBAIGameManager;

/**
 * Default implementation of {@link BBAIGameManager}.
 * <p>
 * Currently a stub — game session lifecycle (lobby, building phase, AI judging,
 * results display), topic assignment, scoring, and player tracking will be
 * implemented here once the game flow is designed.
 */
@RequiredArgsConstructor
public class GameManager implements BBAIGameManager {

    @NonNull private final BuildBattleAI plugin;

    @Override
    public void shutdown() {
        plugin.getLogger().info("GameManager shut down.");
    }
}

package ru.ashesha.buildBattleAI.arena;

import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.api.BBAIArenaManager;

/**
 * Default implementation of {@link BBAIArenaManager}.
 * <p>
 * Currently a stub — arena loading, plot allocation, and region protection
 * will be implemented here once the arena configuration format is defined.
 */
@RequiredArgsConstructor
public class ArenaManager implements BBAIArenaManager {

    private final BuildBattleAI plugin;

    @Override
    public void initialize() {
        plugin.getLogger().info("ArenaManager initialized.");
    }

    @Override
    public void shutdown() {
        plugin.getLogger().info("ArenaManager shut down.");
    }
}

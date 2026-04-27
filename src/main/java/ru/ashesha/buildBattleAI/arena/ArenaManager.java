package ru.ashesha.buildBattleAI.arena;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.core.PluginService;

/**
 * Default implementation of {@link BBAIArenaManager}.
 * <p>
 * Currently a stub — arena loading, plot allocation, and region protection
 * will be implemented here once the arena configuration format is defined.
 * <p>
 * Implements both {@link BBAIArenaManager} (the public API contract) and
 * {@link PluginService} (the internal lifecycle contract) independently, so
 * external consumers cannot reach lifecycle methods through the API type.
 */
@RequiredArgsConstructor
public class ArenaManager implements BBAIArenaManager, PluginService {

    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Starts the arena manager. Currently a stub — will eventually load arena
     * definitions from disk and rebuild the in-memory arena registry.
     */
    @Override
    public void enable() {
        plugin.getPluginLogger().info("ArenaManager enabled.");
    }

    /**
     * Shuts down the arena manager and releases any held resources.
     * Must not touch state owned by other plugins.
     */
    @Override
    public void shutdown() {
        plugin.getPluginLogger().debug("ArenaManager shut down.");
    }
}

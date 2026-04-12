package ru.ashesha.buildBattleAI.core;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.ArenaManager;
import ru.ashesha.buildBattleAI.commands.BBAICommand;
import ru.ashesha.buildBattleAI.commands.ShotCommand;
import ru.ashesha.buildBattleAI.game.GameManager;
import ru.ashesha.buildBattleAI.listeners.PlayerJoinListener;
import ru.ashesha.buildBattleAI.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.message.MessageService;
import ru.ashesha.buildBattleAI.render.CpuRenderer;

/**
 * Centralized startup and shutdown coordinator for the plugin.
 * <p>
 * Owns all manager instances and orchestrates their lifecycle in the correct order:
 * <ol>
 *     <li>Arena manager — loads arena definitions</li>
 *     <li>Game manager — prepares game session handling</li>
 *     <li>Message service — resolves version-dependent packet factories</li>
 *     <li>Commands and listeners — registered last, after all services are ready</li>
 * </ol>
 * Shutdown occurs in reverse dependency order (game manager before arena manager).
 */
@RequiredArgsConstructor
public class PluginBootstrap {

    @NonNull private final BuildBattleAI plugin;

    @Getter private ArenaManager arenaManager;
    @Getter private GameManager gameManager;
    @Getter private BBAIMessageService messageService;

    /**
     * Initializes all plugin subsystems, registers commands and event listeners.
     * Called once from {@link BuildBattleAI#onEnable()}.
     */
    public void enable() {
        // Initialize managers in dependency order
        arenaManager = new ArenaManager(plugin);
        arenaManager.initialize();

        gameManager = new GameManager(plugin);
        gameManager.initialize();

        messageService = new MessageService(plugin);

        // Register commands (must be declared in plugin.yml)
        new BBAICommand(plugin).register();
        new ShotCommand(plugin).register();
        plugin.getLogger().info("Commands registered.");

        // Register event listeners
        new PlayerJoinListener(plugin).register();
        plugin.getLogger().info("Listeners registered.");
    }

    /**
     * Shuts down all plugin subsystems in reverse dependency order.
     * Called from {@link BuildBattleAI#onDisable()}.
     */
    public void disable() {
        gameManager.shutdown();
        arenaManager.shutdown();
        CpuRenderer.shutdown();
    }
}

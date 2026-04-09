package ru.ashesha.buildBattleAI;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ashesha.buildBattleAI.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.core.PluginBootstrap;

/**
 * Main plugin class for BuildBattleAI — a Minecraft Build Battle variant
 * where builds are judged by an AI classifier instead of player voting.
 * <p>
 * Lifecycle:
 * <ol>
 *     <li>{@link #onLoad()} — initializes PacketEvents API before any other plugin interaction</li>
 *     <li>{@link #onEnable()} — bootstraps managers, commands, and listeners</li>
 *     <li>{@link #onDisable()} — shuts down managers and terminates PacketEvents</li>
 * </ol>
 */
@Getter
public final class BuildBattleAI extends JavaPlugin {

    /** Central bootstrap that owns all manager, command, and listener instances. */
    private PluginBootstrap bootstrap;

    /**
     * Called during server startup before {@link #onEnable()}.
     * Builds and loads the PacketEvents API instance for this plugin.
     */
    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    /**
     * Called when the plugin is enabled. Initializes the PacketEvents event loop,
     * creates the plugin bootstrap, and registers all commands and listeners.
     */
    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();

        bootstrap = new PluginBootstrap(this);
        bootstrap.enable();

        getLogger().info("BuildBattleAI v" + getDescription().getVersion() + " has been enabled!");
    }

    /**
     * Called when the plugin is disabled (server shutdown or plugin reload).
     * Gracefully shuts down game and arena managers, then terminates PacketEvents.
     */
    @Override
    public void onDisable() {
        if (bootstrap != null) {
            bootstrap.disable();
        }
        PacketEvents.getAPI().terminate();
        getLogger().info("BuildBattleAI has been disabled.");
    }

    /**
     * Returns the message service for sending packet-based messages to players,
     * or {@code null} if the plugin has not finished enabling yet.
     *
     * @return the active message service, or {@code null}
     */
    public BBAIMessageService getMessageService() {
        return bootstrap == null ? null : bootstrap.getMessageService();
    }
}

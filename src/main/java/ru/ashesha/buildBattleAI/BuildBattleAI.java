package ru.ashesha.buildBattleAI;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ashesha.buildBattleAI.core.PluginContext;

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

    /**
     * Central context that owns all manager, command, and listener instances.
     */
    private PluginContext context;

    /**
     * Called during server startup before {@link #onEnable()}.
     * Builds and loads the PacketEvents API instance for this plugin.
     */
    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
        context = new PluginContext(this);
    }

    /**
     * Called when the plugin is enabled. Initializes the PacketEvents event loop,
     * creates the plugin context, and registers all commands and listeners.
     */
    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();
        context.enable();
        getLogger().info("BuildBattleAI v" + getDescription().getVersion() + " has been enabled!");
    }

    /**
     * Called when the plugin is disabled (server shutdown or plugin reload).
     * Gracefully shuts down every plugin service through the uniform
     * {@link PluginContext#shutdown()} pipeline, then terminates PacketEvents.
     */
    @Override
    public void onDisable() {
        context.shutdown();
        PacketEvents.getAPI().terminate();
        getLogger().info("BuildBattleAI has been disabled.");
    }

}

package ru.ashesha.buildBattleAI;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.util.JarIntegrityVerifier;

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
     * Configurable plugin logger that respects the {@code log-level} setting
     * in {@code config.yml}. Created in {@link #onLoad()} so it is available
     * to all services from the very start of the lifecycle.
     */
    private PluginLogger pluginLogger;

    /**
     * Set to {@code false} in {@link #onLoad()} when the JAR's digital
     * signature is broken — the plugin refuses to enable in that case.
     */
    private boolean integrityVerified = true;

    /**
     * Called during server startup before {@link #onEnable()}.
     * <p>
     * First verifies the JAR's digital signature (if present) — a broken
     * signature aborts early and the plugin will disable itself in
     * {@link #onEnable()}. If the JAR is unsigned (development build) or
     * intact, PacketEvents is loaded and the {@link PluginContext} is created.
     */
    @SuppressWarnings("UnstableApiUsage")
    @Override
    public void onLoad() {
        // Create the plugin logger early so all services can use it
        pluginLogger = new PluginLogger(getLogger());


        // Integrity gate — must run before any other initialisation.
        if (!JarIntegrityVerifier.verify(getClass(), getLogger())) {
            integrityVerified = false;
            pluginLogger.error("JAR integrity verification failed — aborting plugin load.");
            return;
        }

        PacketEventsSettings settings = new PacketEventsSettings()
                .debug(false)
                .fullStackTrace(false)
                .checkForUpdates(false);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this, settings));
        PacketEvents.getAPI().load();
        context = new PluginContext(this);
    }

    /**
     * Called when the plugin is enabled. If the JAR integrity check failed
     * during {@link #onLoad()}, the plugin logs a prominent warning and
     * disables itself immediately. Otherwise initialises PacketEvents,
     * enables the plugin context, and registers commands and listeners.
     */
    @Override
    public void onEnable() {
        if (!integrityVerified) {
            getLogger().severe("=============================================");
            getLogger().severe(" JAR INTEGRITY CHECK FAILED!");
            getLogger().severe(" The plugin JAR has been tampered with.");
            getLogger().severe(" Please download an official build.");
            getLogger().severe("=============================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PacketEvents.getAPI().init();
        context.enable();
        getLogger().info("BuildBattleAI v" + getDescription().getVersion() + " has been enabled!");
    }

    /**
     * Called when the plugin is disabled (server shutdown or plugin reload).
     * Gracefully shuts down every plugin service through the uniform
     * {@link PluginContext#shutdown()} pipeline, then terminates PacketEvents.
     * <p>
     * If the plugin was never fully initialised (integrity check failure),
     * the method returns immediately — there is nothing to tear down.
     */
    @Override
    public void onDisable() {
        if (!integrityVerified || context == null)
            return;
        context.shutdown();
        PacketEvents.getAPI().terminate();
        getLogger().info("BuildBattleAI has been disabled.");
    }

}

package ru.ashesha.buildBattleAI.listeners.base;

import lombok.RequiredArgsConstructor;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import ru.ashesha.buildBattleAI.BuildBattleAI;

/**
 * Base class for all Bukkit event listeners in the plugin.
 * Provides convenient {@link #register()} and {@link #unregister()} methods
 * that handle Bukkit's {@link org.bukkit.plugin.PluginManager} registration.
 * <p>
 * Subclasses annotate their handler methods with {@link org.bukkit.event.EventHandler}.
 */
@RequiredArgsConstructor
public abstract class PluginListener implements Listener {

    /** Reference to the plugin instance for accessing managers and server API. */
    protected final BuildBattleAI plugin;

    /**
     * Registers all {@code @EventHandler} methods in this listener with Bukkit.
     */
    public final void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Unregisters all event handlers in this listener, stopping event delivery.
     */
    public final void unregister() {
        HandlerList.unregisterAll(this);
    }
}

package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import lombok.NonNull;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages registration and unregistration of plugin event listeners with both
 * Bukkit's {@link org.bukkit.plugin.PluginManager} and PacketEvents' event system.
 * <p>
 * Listeners are represented by the nested {@link PluginListener} abstract class.
 * Subclass it to define event handling, then pass instances to
 * {@link #register(PluginListener)} and {@link #unregister(PluginListener)}.
 */
public class ListenerService {

    /** The plugin instance, used for Bukkit event registration. */
    private final BuildBattleAI plugin;

    /** All listeners registered through this service, for bulk unregistration on shutdown. */
    private final List<PluginListener> registeredListeners = new ArrayList<>();

    /**
     * Creates the listener service.
     *
     * @param plugin the plugin instance
     */
    public ListenerService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a listener with both Bukkit and PacketEvents event systems.
     *
     * @param listener the listener to register
     */
    public void register(@NonNull PluginListener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listener.registeredPacketListener = PacketEvents.getAPI().getEventManager()
                .registerListener(listener, listener.priority);
        registeredListeners.add(listener);
    }

    /**
     * Unregisters a listener from both Bukkit and PacketEvents event systems.
     * Safe to call even if the listener was not previously registered.
     *
     * @param listener the listener to unregister
     */
    public void unregister(@NonNull PluginListener listener) {
        HandlerList.unregisterAll(listener);
        if (listener.registeredPacketListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener.registeredPacketListener);
            listener.registeredPacketListener = null;
        }
        registeredListeners.remove(listener);
    }

    /**
     * Unregisters all listeners that were registered through this service.
     * Called during plugin shutdown.
     */
    public void shutdown() {
        for (PluginListener listener : registeredListeners) {
            HandlerList.unregisterAll(listener);
            if (listener.registeredPacketListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener.registeredPacketListener);
                listener.registeredPacketListener = null;
            }
        }
        registeredListeners.clear();
    }

    /**
     * Unified base class for Bukkit event listeners and PacketEvents packet listeners.
     * <p>
     * Every subclass is registered with <b>both</b> event systems simultaneously via
     * {@link ListenerService#register(PluginListener)}: Bukkit's
     * {@link org.bukkit.plugin.PluginManager} for {@code @EventHandler} methods,
     * and PacketEvents for {@code onPacketReceive} / {@code onPacketSend} overrides.
     * This allows a single listener to handle both Bukkit events and raw packets.
     */
    public static abstract class PluginListener implements Listener, PacketListener {

        /**
         * Reference to the plugin instance for accessing managers and server API.
         */
        @NonNull
        protected final BuildBattleAI plugin;

        /**
         * The priority at which this listener intercepts packets.
         */
        final PacketListenerPriority priority;

        /**
         * Handle to the registered PacketEvents listener, used for unregistration.
         * Managed by {@link ListenerService}.
         */
        PacketListenerCommon registeredPacketListener;

        /**
         * Creates a listener with the default {@link PacketListenerPriority#NORMAL} priority.
         *
         * @param plugin the plugin instance
         */
        protected PluginListener(@NonNull BuildBattleAI plugin) {
            this(plugin, PacketListenerPriority.NORMAL);
        }

        /**
         * Creates a listener with the specified packet priority.
         *
         * @param plugin   the plugin instance
         * @param priority the PacketEvents listener priority
         */
        protected PluginListener(@NonNull BuildBattleAI plugin, @NonNull PacketListenerPriority priority) {
            this.plugin = plugin;
            this.priority = priority;
        }
    }
}

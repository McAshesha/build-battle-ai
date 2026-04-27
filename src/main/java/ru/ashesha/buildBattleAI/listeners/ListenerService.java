package ru.ashesha.buildBattleAI.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginService;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages registration and unregistration of plugin event listeners with both
 * Bukkit's {@link org.bukkit.plugin.PluginManager} and PacketEvents' event system.
 * <p>
 * Listeners are represented by the nested {@link PluginListener} abstract class.
 * Subclass it to define event handling, then pass instances to
 * {@link #register(PluginListener)} and {@link #unregister(PluginListener)}.
 * <p>
 * Implements {@link PluginService} so the listener service participates in the
 * uniform plugin lifecycle. {@link #enable()} is a no-op because the actual
 * listener registrations are business actions owned by {@link PluginContext};
 * {@link #shutdown()} bulk-unregisters every listener this service registered
 * so no handlers leak into Bukkit or PacketEvents after the plugin is disabled.
 */
@RequiredArgsConstructor
public class ListenerService implements PluginService {

    /**
     * The plugin instance, used for Bukkit event registration.
     */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * All listeners registered through this service, for bulk unregistration on shutdown.
     */
    private final List<PluginListener> registeredListeners = new ArrayList<>();

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
        plugin.getPluginLogger().debug("Registered listener %s.", listener.getClass().getSimpleName());
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
     * No per-cycle setup is required — actual listener registrations are
     * driven by {@link PluginContext#enable()} (a business-layer concern),
     * not by the service itself. Kept for {@link PluginService} conformance.
     */
    @Override
    public void enable() {
        // Intentionally empty — listeners are registered by PluginContext.
    }

    /**
     * Unregisters all listeners that were registered through this service.
     * Called during plugin shutdown. Touches only handlers that this service
     * registered, so Bukkit and PacketEvents state owned by other plugins
     * remains intact.
     */
    @Override
    public void shutdown() {
        if (!registeredListeners.isEmpty())
            plugin.getPluginLogger().debug("ListenerService unregistering %d listener(s).", registeredListeners.size());
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

package ru.ashesha.buildBattleAI.listeners.base;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import ru.ashesha.buildBattleAI.BuildBattleAI;

/**
 * Unified base class for Bukkit event listeners and PacketEvents packet listeners.
 * <p>
 * Every subclass is registered with <b>both</b> event systems simultaneously:
 * Bukkit's {@link org.bukkit.plugin.PluginManager} for {@code @EventHandler} methods,
 * and PacketEvents for {@code onPacketReceive} / {@code onPacketSend} overrides.
 * This allows a single listener to handle both Bukkit events and raw packets.
 */
@RequiredArgsConstructor
public abstract class PluginListener implements Listener, PacketListener {

    /** Reference to the plugin instance for accessing managers and server API. */
    @NonNull protected final BuildBattleAI plugin;

    /** The priority at which this listener intercepts packets. */
    private final PacketListenerPriority priority;

    /** Handle to the registered PacketEvents listener, used for unregistration. */
    private PacketListenerCommon registeredListener;

    /**
     * Creates a listener with the default {@link PacketListenerPriority#NORMAL} priority.
     *
     * @param plugin the plugin instance
     */
    protected PluginListener(@NonNull BuildBattleAI plugin) {
        this(plugin, PacketListenerPriority.NORMAL);
    }

    /**
     * Registers this listener with both Bukkit and PacketEvents event systems.
     */
    public final void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registeredListener = PacketEvents.getAPI().getEventManager().registerListener(this, priority);
    }

    /**
     * Unregisters this listener from both Bukkit and PacketEvents event systems.
     * Safe to call even if not currently registered.
     */
    public final void unregister() {
        HandlerList.unregisterAll(this);
        if (registeredListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            registeredListener = null;
        }
    }
}

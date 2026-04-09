package ru.ashesha.buildBattleAI.listeners.base;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import lombok.Getter;
import ru.ashesha.buildBattleAI.BuildBattleAI;

/**
 * Base class for PacketEvents packet listeners.
 * Provides managed {@link #register()} and {@link #unregister()} lifecycle methods
 * analogous to {@link PluginListener} but for raw packet interception.
 * <p>
 * Subclasses override PacketEvents handler methods
 * (e.g., {@code onPacketReceive}, {@code onPacketSend}).
 */
@Getter
public abstract class PluginPacketListener implements PacketListener {

    /** Reference to the plugin instance for accessing managers and server API. */
    protected final BuildBattleAI plugin;

    /** The priority at which this listener intercepts packets. */
    private final PacketListenerPriority priority;

    /** Handle to the registered listener, used for unregistration. */
    private PacketListenerCommon registeredListener;

    /**
     * Creates a packet listener with {@link PacketListenerPriority#NORMAL} priority.
     *
     * @param plugin the plugin instance
     */
    protected PluginPacketListener(BuildBattleAI plugin) {
        this.plugin = plugin;
        this.priority = PacketListenerPriority.NORMAL;
    }

    /**
     * Creates a packet listener with a custom priority.
     *
     * @param plugin   the plugin instance
     * @param priority the packet interception priority
     */
    protected PluginPacketListener(BuildBattleAI plugin, PacketListenerPriority priority) {
        this.plugin = plugin;
        this.priority = priority;
    }

    /**
     * Registers this listener with the PacketEvents event manager.
     */
    public final void register() {
        registeredListener = PacketEvents.getAPI().getEventManager().registerListener(this, priority);
    }

    /**
     * Unregisters this listener from the PacketEvents event manager.
     * Safe to call even if not currently registered.
     */
    public final void unregister() {
        if (registeredListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registeredListener);
            registeredListener = null;
        }
    }
}

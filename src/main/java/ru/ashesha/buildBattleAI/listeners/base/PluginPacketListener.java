package ru.ashesha.buildBattleAI.listeners.base;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;

/**
 * Base class for PacketEvents packet listeners.
 * Provides managed {@link #register()} and {@link #unregister()} lifecycle methods
 * analogous to {@link PluginListener} but for raw packet interception.
 * <p>
 * Subclasses override PacketEvents handler methods
 * (e.g., {@code onPacketReceive}, {@code onPacketSend}).
 */
@RequiredArgsConstructor
public abstract class PluginPacketListener implements PacketListener {

    /** Reference to the plugin instance for accessing managers and server API. */
    @NonNull protected final BuildBattleAI plugin;

    /** The priority at which this listener intercepts packets. */
    @NonNull private final PacketListenerPriority priority;

    /** Handle to the registered listener, used for unregistration. */
    private PacketListenerCommon registeredListener;

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

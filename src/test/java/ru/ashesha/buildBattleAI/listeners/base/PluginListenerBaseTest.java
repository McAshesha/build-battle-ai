package ru.ashesha.buildBattleAI.listeners.base;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import org.bukkit.Server;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link PluginListener} — the unified base class that registers
 * with both Bukkit and PacketEvents event systems.
 * <p>
 * Uses a concrete stub subclass since {@code PluginListener} is abstract.
 * PacketEvents static API is mocked via Mockito's {@code mockStatic}.
 */
class PluginListenerBaseTest {

    private BuildBattleAI plugin;
    private PluginManager pluginManager;
    private EventManager eventManager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        eventManager = mock(EventManager.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
    }

    @Test
    void registerWithBukkitAndPacketEvents() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            com.github.retrooper.packetevents.PacketEventsAPI<?> api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin);
            listener.register();

            verify(pluginManager).registerEvents(listener, plugin);
            verify(eventManager).registerListener(eq(listener), eq(PacketListenerPriority.NORMAL));
        }
    }

    @Test
    void registerWithCustomPriority() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            com.github.retrooper.packetevents.PacketEventsAPI<?> api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.HIGH)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin, PacketListenerPriority.HIGH);
            listener.register();

            verify(eventManager).registerListener(eq(listener), eq(PacketListenerPriority.HIGH));
        }
    }

    @Test
    void unregisterRemovesFromPacketEvents() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            com.github.retrooper.packetevents.PacketEventsAPI<?> api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin);
            listener.register();
            listener.unregister();

            verify(eventManager).unregisterListener(registered);
        }
    }

    @Test
    void unregisterWithoutPriorRegisterIsSafe() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class);
             MockedStatic<HandlerList> handlerList = mockStatic(HandlerList.class)) {
            com.github.retrooper.packetevents.PacketEventsAPI<?> api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            TestListener listener = new TestListener(plugin);
            // Should not throw
            assertDoesNotThrow(listener::unregister);
            // PacketEvents unregister should not be called since never registered
            verify(eventManager, never()).unregisterListener(any());
        }
    }

    @Test
    void doubleUnregisterIsSafe() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            com.github.retrooper.packetevents.PacketEventsAPI<?> api = mock(com.github.retrooper.packetevents.PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin);
            listener.register();
            listener.unregister();
            listener.unregister();

            // Second unregister should not call PacketEvents again
            verify(eventManager, times(1)).unregisterListener(registered);
        }
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new TestListener(null));
    }

    /**
     * Minimal concrete subclass for testing the abstract {@code PluginListener}.
     */
    static class TestListener extends PluginListener {

        TestListener(BuildBattleAI plugin) {
            super(plugin);
        }

        TestListener(BuildBattleAI plugin, PacketListenerPriority priority) {
            super(plugin, priority);
        }
    }
}

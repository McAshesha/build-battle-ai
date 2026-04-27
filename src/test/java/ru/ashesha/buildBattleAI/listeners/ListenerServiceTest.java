package ru.ashesha.buildBattleAI.listeners;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
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
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.listeners.ListenerService.PluginListener;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ListenerService} and its nested {@link PluginListener} base class.
 * <p>
 * Uses a concrete stub subclass since {@code PluginListener} is abstract.
 * PacketEvents static API is mocked via Mockito's {@code mockStatic}.
 */
class ListenerServiceTest {

    private BuildBattleAI plugin;
    private PluginManager pluginManager;
    private EventManager eventManager;
    private ListenerService service;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        eventManager = mock(EventManager.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        service = new ListenerService(plugin);
    }

    @Test
    void registerWithBukkitAndPacketEvents() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin);
            service.register(listener);

            verify(pluginManager).registerEvents(listener, plugin);
            verify(eventManager).registerListener(eq(listener), eq(PacketListenerPriority.NORMAL));
        }
    }

    @Test
    void registerWithCustomPriority() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.HIGH)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin, PacketListenerPriority.HIGH);
            service.register(listener);

            verify(eventManager).registerListener(eq(listener), eq(PacketListenerPriority.HIGH));
        }
    }

    @Test
    void unregisterRemovesFromPacketEvents() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon registered = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(registered);

            TestListener listener = new TestListener(plugin);
            service.register(listener);
            service.unregister(listener);

            verify(eventManager).unregisterListener(registered);
        }
    }

    @Test
    void unregisterWithoutPriorRegisterIsSafe() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class);
             MockedStatic<HandlerList> handlerList = mockStatic(HandlerList.class)) {
            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            TestListener listener = new TestListener(plugin);
            assertDoesNotThrow(() -> service.unregister(listener));
            verify(eventManager, never()).unregisterListener(any());
        }
    }

    @Test
    void shutdownUnregistersAllListeners() {
        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
            packetEvents.when(PacketEvents::getAPI).thenReturn(api);
            when(api.getEventManager()).thenReturn(eventManager);

            PacketListenerCommon reg1 = mock(PacketListenerCommon.class);
            PacketListenerCommon reg2 = mock(PacketListenerCommon.class);
            when(eventManager.registerListener(any(TestListener.class), eq(PacketListenerPriority.NORMAL)))
                    .thenReturn(reg1, reg2);

            TestListener listener1 = new TestListener(plugin);
            TestListener listener2 = new TestListener(plugin);
            service.register(listener1);
            service.register(listener2);
            service.shutdown();

            verify(eventManager).unregisterListener(reg1);
            verify(eventManager).unregisterListener(reg2);
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

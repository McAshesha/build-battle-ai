package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BarMicroService} — action bar message sending via version-resolved packets.
 * <p>
 * Verifies that 1.11+ uses the dedicated {@link WrapperPlayServerActionBar} and
 * older versions fall back to the chat packet with GAME_INFO position.
 */
class BarMicroServiceTest {

    private BuildBattleAI plugin;
    private PluginContext context;
    private Player player;
    private MockedStatic<PacketEvents> packetEventsMock;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        player = mock(Player.class);
        when(plugin.getContext()).thenReturn(context);

        packetEventsMock = mockStatic(PacketEvents.class);
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        packetEventsMock.when(PacketEvents::getAPI).thenReturn(api);
        when(api.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21);

        PacketEventsSettings settings = new PacketEventsSettings();
        settings.customResourceProvider(
                name -> BarMicroServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    /**
     * Creates a {@link BarMicroService} bound to the given server version by
     * stubbing {@link PluginContext#getServerVersion()} before construction.
     */
    private BarMicroService serviceFor(ServerVersion version) {
        when(context.getServerVersion()).thenReturn(version);
        return new BarMicroService(plugin);
    }

    // ===== Version factory selection =====

    @Test
    void modernVersionUsesActionBarPacket() {
        BarMicroService service = serviceFor(ServerVersion.V_1_11);
        service.sendActionBar(player, "Test");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerActionBar,
                "1.11+ should use WrapperPlayServerActionBar");
    }

    @Test
    void legacyVersionUsesChatPacket() {
        BarMicroService service = serviceFor(ServerVersion.V_1_8);
        service.sendActionBar(player, "Test");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerChatMessage,
                "1.8 should use WrapperPlayServerChatMessage for action bar");
    }

    // ===== Single player =====

    @Test
    void sendActionBarToSinglePlayer() {
        BarMicroService service = serviceFor(ServerVersion.V_1_19);
        service.sendActionBar(player, "&cImportant!");

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    // ===== Multiple players =====

    @Test
    void sendActionBarToMultiplePlayers() {
        BarMicroService service = serviceFor(ServerVersion.V_1_19);
        Player player2 = mock(Player.class);

        service.sendActionBar(Arrays.asList(player, player2), "Broadcast bar");

        verify(context).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void sendActionBarToEmptyCollectionSendsNothing() {
        BarMicroService service = serviceFor(ServerVersion.V_1_19);

        service.sendActionBar(Collections.<Player>emptyList(), "Nobody");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Color code support =====

    @Test
    void actionBarWithColorCodesDoesNotThrow() {
        BarMicroService service = serviceFor(ServerVersion.V_1_19);
        assertDoesNotThrow(() -> service.sendActionBar(player, "&a&lBold Green"));
    }
}

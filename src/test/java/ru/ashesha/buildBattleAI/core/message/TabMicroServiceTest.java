package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
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
 * Tests for {@link TabMicroService} — player list header/footer sending.
 * <p>
 * TabMicroService is version-independent (uses a single packet type for all versions),
 * so tests focus on correct delegation and packet construction.
 */
class TabMicroServiceTest {

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
                name -> TabMicroServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new TabMicroService(null));
    }

    // ===== Single player =====

    @Test
    void sendTabToSinglePlayer() {
        TabMicroService service = new TabMicroService(plugin);
        service.sendTab(player, "&6Header", "&7Footer");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(1)).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerPlayerListHeaderAndFooter,
                "Should use WrapperPlayServerPlayerListHeaderAndFooter");
    }

    @Test
    void sendTabWithNullHeaderDoesNotThrow() {
        TabMicroService service = new TabMicroService(plugin);
        assertDoesNotThrow(() -> service.sendTab(player, null, "Footer"));
        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void sendTabWithNullFooterDoesNotThrow() {
        TabMicroService service = new TabMicroService(plugin);
        assertDoesNotThrow(() -> service.sendTab(player, "Header", null));
        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void sendTabWithBothNullDoesNotThrow() {
        TabMicroService service = new TabMicroService(plugin);
        assertDoesNotThrow(() -> service.sendTab(player, null, null));
        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    // ===== Multiple players =====

    @Test
    void sendTabToMultiplePlayers() {
        TabMicroService service = new TabMicroService(plugin);
        Player player2 = mock(Player.class);

        service.sendTab(Arrays.asList(player, player2), "Header", "Footer");

        verify(context).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void sendTabToEmptyCollectionSendsNothing() {
        TabMicroService service = new TabMicroService(plugin);

        service.sendTab(Collections.<Player>emptyList(), "Header", "Footer");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Color code support =====

    @Test
    void sendTabWithColorCodesDoesNotThrow() {
        TabMicroService service = new TabMicroService(plugin);
        assertDoesNotThrow(() -> service.sendTab(player, "&a&lHeader", "&c&oFooter"));
    }
}

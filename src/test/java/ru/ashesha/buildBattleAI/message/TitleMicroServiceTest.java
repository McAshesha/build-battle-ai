package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TitleMicroService} — title/subtitle overlay sending via version-resolved packets.
 * <p>
 * Verifies that 1.17+ uses separate packets for times, title, and subtitle,
 * while older versions use the combined title packet with action enum.
 * <p>
 * Note: {@code null} title/subtitle passed to {@code sendTitle()} is converted
 * to a non-null empty {@link net.kyori.adventure.text.Component} by
 * {@link ru.ashesha.buildBattleAI.util.MessageUtils#toComponent(String)}, so
 * the factory's null checks always pass — all 3 packets are always sent.
 */
class TitleMicroServiceTest {

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
                name -> TitleMicroServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    /**
     * Creates a {@link TitleMicroService} bound to the given server version by
     * stubbing {@link PluginContext#getServerVersion()} before construction.
     */
    private TitleMicroService serviceFor(ServerVersion version) {
        when(context.getServerVersion()).thenReturn(version);
        return new TitleMicroService(plugin);
    }

    // ===== Modern version (1.17+) =====

    @Test
    void modernVersionSendsSeparatePackets() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);
        service.sendTitle(player, "Title", "Subtitle", 10, 70, 20);

        // 1.17+: times + title + subtitle = 3 packets
        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(3)).sendPacket(eq(player), captor.capture());

        List<PacketWrapper> packets = captor.getAllValues();
        assertTrue(packets.get(0) instanceof WrapperPlayServerSetTitleTimes,
                "First packet should be title times");
        assertTrue(packets.get(1) instanceof WrapperPlayServerSetTitleText,
                "Second packet should be title text");
        assertTrue(packets.get(2) instanceof WrapperPlayServerSetTitleSubtitle,
                "Third packet should be subtitle text");
    }

    @Test
    void modernVersionWithNullTitleStillSendsThreePackets() {
        // null title → empty Component (non-null) via MessageUtils.toComponent(null),
        // so the factory's "if (title != null)" check passes
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);
        service.sendTitle(player, null, "Subtitle only", 10, 70, 20);

        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void modernVersionWithNullSubtitleStillSendsThreePackets() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);
        service.sendTitle(player, "Title only", null, 10, 70, 20);

        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void modernVersionWithBothNullStillSendsThreePackets() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);
        service.sendTitle(player, null, null, 10, 70, 20);

        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    // ===== Legacy version (<1.17) =====

    @Test
    void legacyVersionUsesCombinedTitlePacket() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_8);
        service.sendTitle(player, "Title", "Subtitle", 10, 70, 20);

        // Legacy: times + title + subtitle = 3 combined packets
        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context, times(3)).sendPacket(eq(player), captor.capture());

        for (PacketWrapper packet : captor.getAllValues()) {
            assertTrue(packet instanceof WrapperPlayServerTitle,
                    "Legacy version should use WrapperPlayServerTitle");
        }
    }

    @Test
    void legacyVersionAlsoSendsThreePacketsForNullTitle() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_8);
        service.sendTitle(player, null, "Subtitle", 10, 70, 20);

        // null → empty Component, so all 3 packets are sent
        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    // ===== Multiple players =====

    @Test
    void sendTitleToMultiplePlayers() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);
        Player player2 = mock(Player.class);

        service.sendTitle(Arrays.asList(player, player2), "Title", "Sub", 5, 40, 10);

        // Each player gets 3 packets (times + title + subtitle)
        verify(context, times(3)).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context, times(3)).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void sendTitleToEmptyCollectionSendsNothing() {
        TitleMicroService service = serviceFor(ServerVersion.V_1_17);

        service.sendTitle(Collections.<Player>emptyList(), "Title", "Sub", 5, 40, 10);

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }
}

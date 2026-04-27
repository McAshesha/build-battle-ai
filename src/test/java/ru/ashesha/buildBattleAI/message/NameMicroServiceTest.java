package ru.ashesha.buildBattleAI.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NameMicroService} — player list display name updates via version-resolved packets.
 * <p>
 * Verifies version-dependent factory selection (1.19.3+ vs legacy),
 * Bukkit-to-PacketEvents game mode conversion, and delivery to single/multiple viewers.
 */
class NameMicroServiceTest {

    private BuildBattleAI plugin;
    private PluginContext context;
    private Player target;
    private Player viewer;
    private UserProfile profile;
    private MockedStatic<PacketEvents> packetEventsMock;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        context = mock(PluginContext.class);
        target = mock(Player.class);
        viewer = mock(Player.class);
        profile = new UserProfile(UUID.randomUUID(), "TestTarget");

        when(plugin.getContext()).thenReturn(context);
        when(context.getUserProfile(target)).thenReturn(profile);
        when(target.getGameMode()).thenReturn(GameMode.SURVIVAL);

        packetEventsMock = mockStatic(PacketEvents.class);
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        packetEventsMock.when(PacketEvents::getAPI).thenReturn(api);
        when(api.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21);

        PacketEventsSettings settings = new PacketEventsSettings();
        settings.customResourceProvider(
                name -> NameMicroServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    /**
     * Creates a {@link NameMicroService} bound to the given server version by
     * stubbing {@link PluginContext#getServerVersion()} before construction.
     */
    private NameMicroService serviceFor(ServerVersion version) {
        when(context.getServerVersion()).thenReturn(version);
        return new NameMicroService(plugin);
    }

    // ===== Version factory selection =====

    @Test
    void modernVersionUsesPlayerInfoUpdate() {
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);
        service.sendPlayerListName(target, "&aGreenName", viewer);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(viewer), captor.capture());
        assertInstanceOf(WrapperPlayServerPlayerInfoUpdate.class, captor.getValue(), "1.19.3+ should use WrapperPlayServerPlayerInfoUpdate");
    }

    @Test
    void legacyVersionUsesPlayerInfo() {
        NameMicroService service = serviceFor(ServerVersion.V_1_8);
        service.sendPlayerListName(target, "&cRedName", viewer);

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(viewer), captor.capture());
        assertInstanceOf(WrapperPlayServerPlayerInfo.class, captor.getValue(), "1.8 should use WrapperPlayServerPlayerInfo");
    }

    // ===== Single viewer =====

    @Test
    void sendPlayerListNameToSingleViewer() {
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);
        service.sendPlayerListName(target, "DisplayName", viewer);

        verify(context, times(1)).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    @Test
    void sendPlayerListNameWithNullNameDoesNotThrow() {
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);
        assertDoesNotThrow(() -> service.sendPlayerListName(target, null, viewer));
        verify(context, times(1)).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    // ===== Multiple viewers =====

    @Test
    void sendPlayerListNameToMultipleViewers() {
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);
        Player viewer2 = mock(Player.class);

        service.sendPlayerListName(target, "Name", Arrays.asList(viewer, viewer2));

        verify(context).sendPacket(eq(viewer), any(PacketWrapper.class));
        verify(context).sendPacket(eq(viewer2), any(PacketWrapper.class));
    }

    @Test
    void sendPlayerListNameToEmptyCollectionSendsNothing() {
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);

        service.sendPlayerListName(target, "Name", Collections.emptyList());

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Game mode conversion =====

    @Test
    void creativeGameModeConvertsCorrectly() {
        when(target.getGameMode()).thenReturn(GameMode.CREATIVE);
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);

        assertDoesNotThrow(() -> service.sendPlayerListName(target, "Name", viewer));
        verify(context).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    @Test
    void adventureGameModeConvertsCorrectly() {
        when(target.getGameMode()).thenReturn(GameMode.ADVENTURE);
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);

        assertDoesNotThrow(() -> service.sendPlayerListName(target, "Name", viewer));
        verify(context).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    @Test
    void spectatorGameModeConvertsCorrectly() {
        when(target.getGameMode()).thenReturn(GameMode.SPECTATOR);
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);

        assertDoesNotThrow(() -> service.sendPlayerListName(target, "Name", viewer));
        verify(context).sendPacket(eq(viewer), any(PacketWrapper.class));
    }

    @Test
    void survivalGameModeConvertsCorrectly() {
        when(target.getGameMode()).thenReturn(GameMode.SURVIVAL);
        NameMicroService service = serviceFor(ServerVersion.V_1_19_3);

        assertDoesNotThrow(() -> service.sendPlayerListName(target, "Name", viewer));
        verify(context).sendPacket(eq(viewer), any(PacketWrapper.class));
    }
}

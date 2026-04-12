package ru.ashesha.buildBattleAI.core.message;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
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
 * Tests for {@link ChatService} — chat message sending via version-resolved packets.
 * <p>
 * Verifies version-dependent factory selection (1.19+ vs 1.16–1.18 vs &lt;1.16),
 * single and batch message delivery, and rich {@link ChatService.ChatMessage}
 * segment handling.
 * <p>
 * PacketEvents static API is mocked because packet wrapper constructors and
 * chat type registries internally call {@code PacketEvents.getAPI()}.
 */
class ChatServiceTest {

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

        // Mock PacketEvents static API — packet wrapper constructors and ChatTypes
        // static init both need getAPI() and getSettings().getResourceProvider()
        packetEventsMock = mockStatic(PacketEvents.class);
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        packetEventsMock.when(PacketEvents::getAPI).thenReturn(api);
        when(api.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21);

        // ChatTypes.<clinit> reads registry data via the resource provider
        PacketEventsSettings settings = new PacketEventsSettings();
        settings.customResourceProvider(
                name -> ChatServiceTest.class.getClassLoader().getResourceAsStream(name));
        when(api.getSettings()).thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        packetEventsMock.close();
    }

    // ===== Version factory selection =====

    @Test
    void modernVersionUsesSystemChatMessage() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        service.sendChat(player, "Hello");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerSystemChatMessage,
                "1.19+ should use WrapperPlayServerSystemChatMessage");
    }

    @Test
    void v1_16UsesLegacyChatMessage() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_16);
        service.sendChat(player, "Hello");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerChatMessage,
                "1.16 should use WrapperPlayServerChatMessage");
    }

    @Test
    void v1_8UsesLegacyChatMessage() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_8);
        service.sendChat(player, "Hello");

        ArgumentCaptor<PacketWrapper> captor = ArgumentCaptor.forClass(PacketWrapper.class);
        verify(context).sendPacket(eq(player), captor.capture());
        assertTrue(captor.getValue() instanceof WrapperPlayServerChatMessage,
                "1.8 should use WrapperPlayServerChatMessage");
    }

    // ===== Single player =====

    @Test
    void sendChatPlainTextSendsOnePacket() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        service.sendChat(player, "&aGreen message");

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    // ===== Multiple players =====

    @Test
    void sendChatToMultiplePlayersSendsToEach() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        Player player2 = mock(Player.class);

        service.sendChat(Arrays.asList(player, player2), "Broadcast");

        verify(context).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void sendChatToEmptyCollectionSendsNothing() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);

        service.sendChat(Collections.<Player>emptyList(), "Nobody");

        verify(context, never()).sendPacket(any(Player.class), any(PacketWrapper.class));
    }

    // ===== Rich ChatMessage =====

    @Test
    void sendRichChatMessageSendsPacket() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        ChatService.ChatMessage message = new ChatService.ChatMessage()
                .append("Click me", ChatService.ClickAction.RUN_COMMAND, "/help")
                .append(" or hover", "tooltip text");

        service.sendChat(player, message);

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void sendRichChatMessageToMultiplePlayers() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        Player player2 = mock(Player.class);
        ChatService.ChatMessage message = new ChatService.ChatMessage()
                .append("Hello")
                .append(" World", ChatService.ClickAction.OPEN_URL, "https://example.com", "Go!");

        service.sendChat(Arrays.asList(player, player2), message);

        verify(context).sendPacket(eq(player), any(PacketWrapper.class));
        verify(context).sendPacket(eq(player2), any(PacketWrapper.class));
    }

    @Test
    void sendEmptyChatMessageStillSendsPacket() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        ChatService.ChatMessage message = new ChatService.ChatMessage();

        service.sendChat(player, message);

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void sendChatMessageWithAllClickActions() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        ChatService.ChatMessage message = new ChatService.ChatMessage()
                .append("URL", ChatService.ClickAction.OPEN_URL, "https://example.com")
                .append("CMD", ChatService.ClickAction.RUN_COMMAND, "/help")
                .append("SUG", ChatService.ClickAction.SUGGEST_COMMAND, "/suggest");

        service.sendChat(player, message);

        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }

    @Test
    void sendChatMessageWithNullClickActionDoesNotThrow() {
        ChatService service = new ChatService(plugin, ServerVersion.V_1_19);
        ChatService.ChatMessage message = new ChatService.ChatMessage()
                .append("plain", null, null, null);

        assertDoesNotThrow(() -> service.sendChat(player, message));
        verify(context, times(1)).sendPacket(eq(player), any(PacketWrapper.class));
    }
}

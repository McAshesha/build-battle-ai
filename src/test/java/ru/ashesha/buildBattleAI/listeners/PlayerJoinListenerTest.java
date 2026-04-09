package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerJoinListenerTest {

    private Logger logger;
    private PlayerJoinListener listener;

    @BeforeEach
    void setUp() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        listener = new PlayerJoinListener(plugin);
    }

    @Test
    void onJoinLogsPlayerNameAndUuid() {
        Player player = mock(Player.class);
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        when(player.getName()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(uuid);

        PlayerJoinEvent event = new PlayerJoinEvent(player, "Steve joined");
        listener.onPlayerJoin(event);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger).info(captor.capture());
        String logMessage = captor.getValue();
        assertTrue(logMessage.contains("Steve"));
        assertTrue(logMessage.contains("12345678-1234-1234-1234-123456789abc"));
    }
}

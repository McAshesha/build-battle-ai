package ru.ashesha.buildBattleAI.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.BBAIArenaManager;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link ArenaSetupListener}.
 * <p>
 * The listener has a single responsibility — cancel any active arena setup
 * session when the creating admin disconnects. These tests verify both the
 * positive case (session exists → cancel) and the negative case (no session
 * → no-op), matching the established pattern in {@link GameListenerTest}.
 */
class ArenaSetupListenerTest {

    private BuildBattleAI plugin;
    private BBAIArenaManager arenaManager;
    private ArenaSetupListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        arenaManager = mock(BBAIArenaManager.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getArenaManager()).thenReturn(arenaManager);

        listener = new ArenaSetupListener(plugin);
    }

    @Test
    void cancelsSessionWhenSetupActive() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(true);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(arenaManager).cancelSetupSession(uuid);
    }

    @Test
    void doesNothingWhenNoSession() {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(false);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(arenaManager, never()).cancelSetupSession(any(UUID.class));
    }

    @Test
    void onlyChecksSessionForTheLeavingPlayer() {
        // Two different players quit in sequence. Verify each lookup uses
        // its own UUID — the listener must not confuse cleanup targets.
        Player alice = mock(Player.class);
        UUID aliceId = UUID.randomUUID();
        when(alice.getUniqueId()).thenReturn(aliceId);

        Player bob = mock(Player.class);
        UUID bobId = UUID.randomUUID();
        when(bob.getUniqueId()).thenReturn(bobId);

        when(arenaManager.hasSetupSession(aliceId)).thenReturn(true);
        when(arenaManager.hasSetupSession(bobId)).thenReturn(false);

        PlayerQuitEvent aliceQuit = mock(PlayerQuitEvent.class);
        when(aliceQuit.getPlayer()).thenReturn(alice);
        PlayerQuitEvent bobQuit = mock(PlayerQuitEvent.class);
        when(bobQuit.getPlayer()).thenReturn(bob);

        listener.onPlayerQuit(aliceQuit);
        listener.onPlayerQuit(bobQuit);

        verify(arenaManager).cancelSetupSession(aliceId);
        verify(arenaManager, never()).cancelSetupSession(bobId);
    }

    @Test
    void quitFromPlayerWhoCompletedSetupIsNoOp() {
        // After handleConfirm the session is removed — a subsequent quit by
        // the same player must not double-trigger any cleanup work.
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(arenaManager.hasSetupSession(uuid)).thenReturn(false);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(arenaManager).hasSetupSession(uuid);
        verifyNoMoreInteractions(arenaManager);
    }
}

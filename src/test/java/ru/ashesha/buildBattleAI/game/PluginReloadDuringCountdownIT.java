package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.evaluation.api.BBAIEvaluationService;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test — covers GAME-09.
 * <p>
 * Invariant: plugin reload (manager.shutdown()) must cancel every active
 * countdown / game-tick / ending timer across all registered sessions.
 * No orphan timers remain to fire post-reload against half-disposed state.
 */
@Tag("integration")
class PluginReloadDuringCountdownIT {

    /**
     * Verifies that {@code shutdown()} iterates all sessions and cancels
     * their timers by calling {@code forceEndSession} on each, which internally
     * unregisters the session from EvaluationService before cancelling the
     * Bukkit scheduler tasks.
     */
    @Test
    @DisplayName("GAME-09: shutdown cancels timers across all registered sessions")
    void reloadCancelsAllTimers() throws Exception {
        // ── 1. Build mocks matching the GameManager.enable() contract ───────
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        PluginLogger pluginLogger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        BBAIConfigService configService = mock(BBAIConfigService.class);
        Lang lang = mock(Lang.class);
        BBAIMessageService messageService = mock(BBAIMessageService.class);
        BBAIWorldService worldService = mock(BBAIWorldService.class);
        BBAIEvaluationService evaluationService = mock(BBAIEvaluationService.class);

        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getMessageService()).thenReturn(messageService);
        when(context.getEvaluationService()).thenReturn(evaluationService);
        // ensureWorldLoaded falls back to worldService when Bukkit.getWorld is null.
        // Returning null from both loadWorld and createEmptyWorld causes arenaWorld=null,
        // which safely skips clearZone — avoiding Block API calls in a unit context.
        when(context.getWorldService()).thenReturn(worldService);
        when(worldService.loadWorld(anyString())).thenReturn(null);
        when(worldService.createEmptyWorld(anyString())).thenReturn(null);
        when(configService.getLangFor(any(UUID.class))).thenReturn(lang);
        // Lang.get always returns a non-null string so any sendChat calls don't throw.
        when(lang.get(anyString())).thenReturn("");
        when(lang.get(anyString(), (Object[]) any())).thenReturn("");

        GameManager manager = new GameManager(plugin);
        manager.enable();

        // ── 2. Create session 1 in COUNTDOWN state with countdown timer ──────
        Arena arena1 = mock(Arena.class);
        when(arena1.name()).thenReturn("arena-1");
        when(arena1.maxPlayers()).thenReturn(2);
        when(arena1.worldName()).thenReturn("bbai_arena-1");

        GameSession session1 = new GameSession(arena1);
        session1.state(ArenaState.COUNTDOWN);
        session1.countdownTaskId(101);
        session1.gameTickTaskId(-1);
        session1.endingTaskId(-1);

        // ── 3. Create session 2 in PLAYING state with game-tick timer ───────
        Arena arena2 = mock(Arena.class);
        when(arena2.name()).thenReturn("arena-2");
        when(arena2.maxPlayers()).thenReturn(2);
        when(arena2.worldName()).thenReturn("bbai_arena-2");

        GameSession session2 = new GameSession(arena2);
        session2.state(ArenaState.PLAYING);
        session2.countdownTaskId(-1);
        session2.gameTickTaskId(202);
        session2.endingTaskId(-1);

        // ── 4. Reflectively inject both sessions into GameManager.sessions ───
        Field sessionsField = GameManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GameSession> sessions =
                (Map<String, GameSession>) sessionsField.get(manager);
        sessions.put("arena-1", session1);
        sessions.put("arena-2", session2);

        // ── 5. Drive shutdown() under a static Bukkit mock ────────────────
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // getScheduler() is called by GameSession.cancelTask (private helper).
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            // getWorld returns null so ensureWorldLoaded returns null, skipping clearZone.
            bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
            // getPlayer returns null so the snapshot-restore loop is a no-op.
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);

            manager.shutdown();

            // ── 6. Assert all timers were cancelled ─────────────────────────
            verify(scheduler).cancelTask(101);  // session 1 countdown timer
            verify(scheduler).cancelTask(202);  // session 2 game-tick timer
            // Never cancel -1 (idle task slots)
            verify(scheduler, never()).cancelTask(-1);
            // Both sessions unregistered from evaluation service
            verify(evaluationService).unregisterSession("arena-1");
            verify(evaluationService).unregisterSession("arena-2");
        }

        // ── 7. Assert task IDs were reset to -1 (idle) ─────────────────────
        assertEquals(-1, session1.countdownTaskId(),
                "session1 countdownTaskId must be -1 after shutdown");
        assertEquals(-1, session2.gameTickTaskId(),
                "session2 gameTickTaskId must be -1 after shutdown");
    }
}

package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Integration test — covers GAME-07.
 * <p>
 * Invariant: {@code forceEndSession} must call
 * {@link BBAIEvaluationService#unregisterSession(String)} <em>before</em>
 * invoking {@code session.cancelAllTasks()}, which cancels the Bukkit timers.
 * <p>
 * Reversing the order creates a brief window where the timers are already
 * dead but the evaluation pipeline still holds a live {@code SessionHandle}
 * and may dispatch a score callback against a half-torn-down
 * {@link GameSession}.
 */
@Tag("integration")
class ForceEndSessionOrderingIT {

    /**
     * Verifies that {@code forceEndSession} unregisters the session from
     * {@link BBAIEvaluationService} before it cancels the Bukkit scheduler
     * task via {@code session.cancelAllTasks()}.
     */
    @Test
    @DisplayName("GAME-07: forceEndSession unregisters EvaluationService before cancelling timers")
    void unregisterBeforeCancel() throws Exception {
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

        // ── 2. Build a real GameSession with one active timer task ───────────
        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn("arena-1");
        when(arena.maxPlayers()).thenReturn(2);
        // worldName is used by ensureWorldLoaded → Bukkit.getWorld(worldName).
        when(arena.worldName()).thenReturn("bbai_arena-1");

        GameSession session = new GameSession(arena);
        session.state(ArenaState.PLAYING);
        // Only the game-tick task is active; countdown and ending are idle.
        // cancelAllTasks() will call Bukkit.getScheduler().cancelTask(42).
        session.countdownTaskId(-1);
        session.gameTickTaskId(42);
        session.endingTaskId(-1);

        // ── 3. Reflectively inject session into GameManager.sessions ─────────
        Field sessionsField = GameManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GameSession> sessions =
                (Map<String, GameSession>) sessionsField.get(manager);
        sessions.put("arena-1", session);

        // ── 4. Obtain forceEndSession via reflection (package-private method) ─
        Method forceEnd = GameManager.class.getDeclaredMethod(
                "forceEndSession", GameSession.class);
        forceEnd.setAccessible(true);

        // ── 5. Drive forceEndSession under a static Bukkit mock ─────────────
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // getScheduler() is called by GameSession.cancelTask (private helper).
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            // getWorld returns null so ensureWorldLoaded returns null, skipping clearZone.
            bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
            // getPlayer returns null so the snapshot-restore loop is a no-op.
            bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);

            forceEnd.invoke(manager, session);

            // ── 6. Assert ordering: unregister THEN cancelTask ───────────────
            InOrder order = inOrder(evaluationService, scheduler);
            order.verify(evaluationService).unregisterSession("arena-1");
            order.verify(scheduler).cancelTask(42);
        }
    }
}

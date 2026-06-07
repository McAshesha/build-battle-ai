package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Integration test — covers GAME-05.
 * <p>
 * Invariant: a score callback whose {@code expectedThemeIndex} no longer
 * matches the player's current {@code themeIndex} (because the player has
 * since advanced to a new theme) must be silently ignored — no score bump,
 * no theme advancement, no zone clear. Otherwise a stale ML inference would
 * double-score a player on a theme they have already moved past.
 */
@Tag("integration")
class ScoreCallbackStalenessIT {

    /**
     * Verifies that {@code handleScore} with a stale {@code expectedThemeIndex}
     * is a no-op, and that a subsequent call with the current index scores
     * correctly.
     */
    @Test
    @DisplayName("GAME-05: handleScore with stale themeIndex is silently ignored")
    void staleCallbackIgnored() throws Exception {
        // ── 1. Build mocks matching the GameManager.enable() contract ───────
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        PluginLogger pluginLogger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        BBAIConfigService configService = mock(BBAIConfigService.class);
        Lang lang = mock(Lang.class);
        BBAIMessageService messageService = mock(BBAIMessageService.class);
        BBAIWorldService worldService = mock(BBAIWorldService.class);

        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getMessageService()).thenReturn(messageService);
        // ensureWorldLoaded falls back to worldService when Bukkit.getWorld is null.
        // Returning null from both loadWorld and createEmptyWorld causes arenaWorld=null,
        // which safely skips clearZone — avoiding Block API calls in a unit context.
        when(context.getWorldService()).thenReturn(worldService);
        when(worldService.loadWorld(anyString())).thenReturn(null);
        when(worldService.createEmptyWorld(anyString())).thenReturn(null);
        when(configService.getLangFor(any(UUID.class))).thenReturn(lang);
        // Lang.get always returns a non-null string so sendChat doesn't throw.
        when(lang.get(anyString())).thenReturn("");
        when(lang.get(anyString(), (Object[]) any())).thenReturn("");

        GameManager manager = new GameManager(plugin);
        manager.enable();

        // ── 2. Build real GameSession with one real GamePlayer ───────────────
        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn("arena-1");
        when(arena.maxPlayers()).thenReturn(2);
        when(arena.worldName()).thenReturn("bbai_arena-1");
        when(arena.buildTime()).thenReturn(120);

        GameSession session = new GameSession(arena);
        // Provide a non-empty theme list so advanceTheme (themeCount % n) never
        // divides by zero and getTheme returns a real string.
        session.setThemes(Arrays.asList("cat", "sword", "ball", "house", "tree",
                "glasses", "ship", "tower", "car", "plane", "boat"));

        UUID pid = UUID.randomUUID();
        PlayerSnapshot snapshot = mock(PlayerSnapshot.class);
        // plotIndex=0, buildTimeRemaining=120 — matches arena.buildTime() so
        // resetBuildTime in the success path is a no-op value change only.
        GamePlayer gp = new GamePlayer(pid, "Alice", 0, snapshot, 120);

        // Drive themeIndex to 5 (player has already advanced past 0..4).
        for (int i = 0; i < 5; i++)
            gp.advanceTheme(session.themes().size());
        assertEquals(5, gp.themeIndex(), "precondition: gp.themeIndex() == 5");
        assertEquals(0, gp.score(), "precondition: gp.score() == 0");

        // addPlayer is package-private; accessible within the same package.
        session.addPlayer(gp);
        session.state(ArenaState.PLAYING);

        // ── 3. Reflectively inject session into GameManager.sessions ─────────
        Field sessionsField = GameManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GameSession> sessions =
                (Map<String, GameSession>) sessionsField.get(manager);
        sessions.put("arena-1", session);

        // ── 4. Reflectively obtain handleScore ───────────────────────────────
        Method handleScore = GameManager.class.getDeclaredMethod(
                "handleScore", String.class, UUID.class, int.class);
        handleScore.setAccessible(true);

        // Mock the online player returned by Bukkit.getPlayer so the success path
        // does not return early at the "player == null" guard.
        Player onlinePlayer = mock(Player.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            // Bukkit.getPlayer(UUID) → online player (needed for success path).
            bukkit.when(() -> Bukkit.getPlayer(pid)).thenReturn(onlinePlayer);
            // Bukkit.getWorld → null so clearZone is safely skipped (world is
            // not available in this unit context).
            bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);

            // ── 5. STALE call: expectedThemeIndex=4, current=5 ──────────────
            handleScore.invoke(manager, "arena-1", pid, 4);

            assertEquals(0, gp.score(),
                    "stale callback (expectedThemeIndex=4, current=5) must NOT bump score");
            assertEquals(5, gp.themeIndex(),
                    "stale callback must NOT advance theme");

            // ── 6. FRESH call: expectedThemeIndex=5, current=5 ──────────────
            handleScore.invoke(manager, "arena-1", pid, 5);

            assertEquals(1, gp.score(),
                    "matching themeIndex must bump score to 1");
            // After the success path themeIndex should have advanced by 1.
            assertEquals(6, gp.themeIndex(),
                    "success path must advance themeIndex from 5 to 6");
        }
    }
}

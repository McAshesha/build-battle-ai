package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.message.api.BBAIMessageService;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for GAME-11.
 *
 * <h2>Risk: GAME-11</h2>
 * <p>In {@code GameManager.startGameTickTimer} the build-time-expiry branch
 * runs three side-effects in sequence:
 * <ol>
 *   <li>{@code clearZone(...)} wipes the in-world build zone;</li>
 *   <li>{@code mirror.clearAll()} wipes the render mirror;</li>
 *   <li>{@code gp.advanceTheme(...)} + {@code gp.resetBuildTime(...)} advance
 *       the player state to the new round.</li>
 * </ol>
 * Without a guard on step 2, a throw from {@code clearAll()} would skip step
 * 3 — leaving the player on the same theme with the world zone already
 * cleared. That non-atomic half-commit is the GAME-11 bug.
 *
 * <h3>Fix verified by this test</h3>
 * <p>{@code clearAll()} is wrapped in {@code try { } catch (Throwable t)} so
 * step 3 always runs. The catch path logs via {@code PluginLogger.error}.
 *
 * <h3>What the tests cover</h3>
 * <ol>
 *   <li>{@link #happyPathBothOperationsComplete} — pins the no-throw case as
 *       a regression detector: theme advances AND mirror cleared.</li>
 *   <li>{@link #clearAllThrowingSkipsAdvanceTheme} — drives a mocked
 *       {@code MutablePlotScene} whose {@code clearAll()} throws; asserts
 *       theme still advances and build time still resets.</li>
 * </ol>
 *
 * <h3>Why integration tier</h3>
 * The tests exercise {@code GameManager} together with {@code GameSession},
 * the per-tick Bukkit scheduler hook (captured via Mockito), and a real or
 * mocked {@code MutablePlotScene} — multiple collaborators wired together,
 * which is what integration tier protects.
 */
@Tag("integration")
class BuildTimeExpiryAtomicityIT {

    // ── shared fixture helpers ─────────────────────────────────────────────

    /**
     * Wires the standard mock chain expected by {@link GameManager#enable()}.
     * Returns a fully-enabled manager ready for session injection.
     */
    private GameManager buildManager(BukkitScheduler scheduler,
                                     MockedStatic<Bukkit> bukkit) throws Exception {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        PluginLogger logger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        BBAIConfigService configService = mock(BBAIConfigService.class);
        Lang lang = mock(Lang.class);
        BBAIMessageService messageService = mock(BBAIMessageService.class);
        BBAIWorldService worldService = mock(BBAIWorldService.class);

        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getMessageService()).thenReturn(messageService);
        // ensureWorldLoaded falls back to worldService when Bukkit.getWorld returns null.
        // Returning null from both calls causes arenaWorld=null, which safely skips
        // clearZone (no Block API calls needed in this unit context).
        when(context.getWorldService()).thenReturn(worldService);
        when(worldService.loadWorld(anyString())).thenReturn(null);
        when(worldService.createEmptyWorld(anyString())).thenReturn(null);
        when(configService.getLangFor(any(UUID.class))).thenReturn(lang);
        when(lang.get(anyString())).thenReturn("");
        when(lang.get(anyString(), (Object[]) any())).thenReturn("");

        // FeedbackController.onTick → Bukkit.getPlayer per player; return null
        // so the feedback layer skips scoreboard/tab refresh (sf not found for
        // an unregistered session).
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

        GameManager manager = new GameManager(plugin);
        manager.enable();
        return manager;
    }

    /**
     * Builds a minimal arena mock that covers the fields accessed during the
     * game-tick expiry branch.
     */
    private Arena buildArena(List<Arena.PlotData> plots) {
        Arena arena = mock(Arena.class);
        when(arena.name()).thenReturn("arena-expiry");
        when(arena.maxPlayers()).thenReturn(4);
        when(arena.worldName()).thenReturn("bbai_arena-expiry");
        when(arena.buildTime()).thenReturn(60);
        when(arena.gameTime()).thenReturn(300);
        when(arena.plots()).thenReturn(plots);
        return arena;
    }

    /**
     * Injects a session directly into the manager's private sessions map via
     * reflection. This bypasses join/countdown flow to test the tick path in
     * isolation.
     */
    private void injectSession(GameManager manager, GameSession session) throws Exception {
        Field f = GameManager.class.getDeclaredField("sessions");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GameSession> sessions = (Map<String, GameSession>) f.get(manager);
        sessions.put(session.arena().name(), session);
    }

    /**
     * Captures the {@link Runnable} passed to
     * {@code BukkitScheduler.runTaskTimer} by reflectively calling the
     * private {@code startGameTickTimer} method. The captured Runnable
     * represents one invocation of the per-second game tick.
     */
    private Runnable captureGameTickRunnable(GameManager manager,
                                             GameSession session,
                                             BukkitScheduler scheduler) throws Exception {
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(99);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(
                any(org.bukkit.plugin.Plugin.class),
                runnableCaptor.capture(),
                anyLong(),
                anyLong())).thenReturn(task);

        Method startTick = GameManager.class.getDeclaredMethod(
                "startGameTickTimer", GameSession.class);
        startTick.setAccessible(true);
        startTick.invoke(manager, session);

        // The Runnable for the game-tick is the one registered by startGameTickTimer.
        // If multiple runnables were captured (shouldn't happen here), take the last.
        List<Runnable> captured = runnableCaptor.getAllValues();
        return captured.get(captured.size() - 1);
    }

    // ── test 1: happy path — both operations complete atomically ──────────

    /**
     * Verifies the happy-path contract for GAME-11: when build time expires
     * and {@code clearAll()} succeeds, both the mirror wipe and the theme
     * advancement happen in the same tick.
     *
     * <p>This test pins the current production ordering and acts as a
     * regression detector: if either operation is accidentally removed or
     * reordered, this test fails.
     */
    @Test
    @DisplayName("GAME-11 (happy path): build-time expiry advances themeIndex AND clears the mirror")
    void happyPathBothOperationsComplete() throws Exception {
        List<String> themes = Arrays.asList(
                "cat", "sword", "ball", "house", "tree", "glasses",
                "ship", "tower", "car", "plane");
        Arena.PlotData plot = mock(Arena.PlotData.class);
        // Provide corner coordinates so MutablePlotScene.forPlot can compute size.
        when(plot.corner1X()).thenReturn(0);  when(plot.corner2X()).thenReturn(9);
        when(plot.corner1Y()).thenReturn(60); when(plot.corner2Y()).thenReturn(70);
        when(plot.corner1Z()).thenReturn(0);  when(plot.corner2Z()).thenReturn(9);
        when(plot.spawn()).thenReturn(null); // unused in tick path

        @SuppressWarnings("unchecked")
        List<Arena.PlotData> plots = mock(List.class);
        when(plots.get(0)).thenReturn(plot);

        Arena arena = buildArena(plots);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            GameManager manager = buildManager(scheduler, bukkit);

            GameSession session = new GameSession(arena);
            session.setThemes(themes);
            // Put enough global time so the game does NOT end during this tick.
            session.gameTimeRemaining(300);
            session.state(ArenaState.PLAYING);

            UUID pid = UUID.randomUUID();
            PlayerSnapshot snapshot = mock(PlayerSnapshot.class);
            // buildTimeRemaining = 0 → decrementBuildTime keeps it at 0 →
            // gp.buildTimeRemaining() <= 0 fires on the very first tick.
            GamePlayer gp = new GamePlayer(pid, "Bob", 0, snapshot, 0);
            session.addPlayer(gp);

            // Install a real MutablePlotScene as the mirror so we can observe
            // clearAll() — verify all cells become AIR after the expiry tick.
            MutablePlotScene mirror = MutablePlotScene.forPlot(plot, /*legacy=*/false);
            session.installMirror(0, mirror);

            injectSession(manager, session);

            Runnable tick = captureGameTickRunnable(manager, session, scheduler);

            int themeIndexBefore = gp.themeIndex();

            // Execute one game tick — build-time expiry should fire.
            tick.run();

            // ── assert: theme advanced ──────────────────────────────────────
            assertEquals(
                    (themeIndexBefore + 1) % themes.size(),
                    gp.themeIndex(),
                    "themeIndex must advance by 1 after build-time expiry");

            // ── assert: mirror is all-AIR ───────────────────────────────────
            // MutablePlotScene.getBlockType returns XMaterial.AIR for every
            // cell that was cleared. Sample a few cells across the volume to
            // confirm clearAll() ran.
            com.cryptomorin.xseries.XMaterial air =
                    com.cryptomorin.xseries.XMaterial.AIR;
            assertTrue(mirror.getBlockType(0, 60, 0) == air,
                    "mirror cell (0,60,0) must be AIR after expiry");
            assertTrue(mirror.getBlockType(5, 65, 5) == air,
                    "mirror cell (5,65,5) must be AIR after expiry");
            assertTrue(mirror.getBlockType(9, 70, 9) == air,
                    "mirror cell (9,70,9) must be AIR after expiry");

            // ── assert: build time reset ────────────────────────────────────
            assertEquals(arena.buildTime(), gp.buildTimeRemaining(),
                    "buildTimeRemaining must be reset to arena.buildTime() after expiry");
        }
    }

    // ── test 2: clearAll throw still advances theme (GAME-11 fix verified) ─

    /**
     * Invariant: when {@code mirror.clearAll()} throws during build-time
     * expiry, the player's {@code themeIndex} must still advance and
     * {@code buildTimeRemaining} must still reset — the in-world zone has
     * already been cleared above, so leaving the per-player counters frozen
     * would create a "cleared zone, same theme" inconsistent state (GAME-11).
     *
     * <p>The test installs a Mockito-mocked {@code MutablePlotScene} whose
     * {@code clearAll()} throws and drives one tick of the game-tick runnable.
     * Asserts both invariants hold.
     *
     * <p>Mockito 5's default inline mock-maker handles the {@code final}
     * {@code MutablePlotScene} class — no extension is required.
     */
    @Test
    @DisplayName("GAME-11: mirror.clearAll() throwing still advances themeIndex (atomic expiry)")
    void clearAllThrowingSkipsAdvanceTheme() throws Exception {
        List<String> themes = Arrays.asList(
                "cat", "sword", "ball", "house", "tree", "glasses",
                "ship", "tower", "car", "plane");
        Arena.PlotData plot = mock(Arena.PlotData.class);
        when(plot.corner1X()).thenReturn(0);  when(plot.corner2X()).thenReturn(9);
        when(plot.corner1Y()).thenReturn(60); when(plot.corner2Y()).thenReturn(70);
        when(plot.corner1Z()).thenReturn(0);  when(plot.corner2Z()).thenReturn(9);
        when(plot.spawn()).thenReturn(null);

        @SuppressWarnings("unchecked")
        List<Arena.PlotData> plots = mock(List.class);
        when(plots.get(0)).thenReturn(plot);

        Arena arena = buildArena(plots);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            GameManager manager = buildManager(scheduler, bukkit);

            GameSession session = new GameSession(arena);
            session.setThemes(themes);
            session.gameTimeRemaining(300);
            session.state(ArenaState.PLAYING);

            UUID pid = UUID.randomUUID();
            PlayerSnapshot snapshot = mock(PlayerSnapshot.class);
            GamePlayer gp = new GamePlayer(pid, "Bob", 0, snapshot, 0);
            session.addPlayer(gp);

            // Install a mirror whose clearAll() throws. Mockito 5's default
            // inline mock-maker can mock the final MutablePlotScene class.
            MutablePlotScene throwingMirror = mock(MutablePlotScene.class);
            doThrow(new RuntimeException("simulated clearAll failure"))
                    .when(throwingMirror).clearAll();
            session.installMirror(0, throwingMirror);

            injectSession(manager, session);

            Runnable tick = captureGameTickRunnable(manager, session, scheduler);

            int themeIndexBefore = gp.themeIndex();

            // Execute one game tick — clearAll throws, but advanceTheme MUST still run.
            tick.run();

            // ── invariant: theme advanced despite clearAll failure ──────────
            assertEquals(
                    (themeIndexBefore + 1) % themes.size(),
                    gp.themeIndex(),
                    "themeIndex must advance even if mirror.clearAll() throws");

            // ── invariant: clearAll was attempted ───────────────────────────
            verify(throwingMirror).clearAll();

            // ── invariant: build time was reset for the new round ───────────
            assertEquals(arena.buildTime(), gp.buildTimeRemaining(),
                    "buildTimeRemaining must be reset after clearAll failure too");
        }
    }
}

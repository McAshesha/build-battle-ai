package ru.ashesha.buildBattleAI.game;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GameSession}.
 * <p>
 * Covers construction, player add/remove/lookup, plot assignment,
 * theme management with wrapping, camera cycling, state transitions,
 * and task cancellation. Arena is mocked with maxPlayers=2 unless
 * otherwise specified.
 */
class GameSessionTest {

    private Arena arena;
    private GameSession session;

    @BeforeEach
    void setUp() {
        arena = mock(Arena.class);
        when(arena.maxPlayers()).thenReturn(2);
        session = new GameSession(arena);
    }

    // ── constructor ──────────────────────────────────────────────────

    @Test
    void constructorInitializesState() {
        assertEquals(ArenaState.WAITING, session.state());
        assertTrue(session.players().isEmpty());
        assertEquals(0, session.currentCameraIndex());
        assertEquals(0, session.gameTimeRemaining());
        assertEquals(-1, session.countdownTaskId());
        assertEquals(-1, session.gameTickTaskId());
        assertEquals(-1, session.renderTaskId());
        assertEquals(-1, session.endingTaskId());
    }

    @Test
    void constructorRejectsNullArena() {
        assertThrows(NullPointerException.class, () -> new GameSession(null));
    }

    // ── player management ────────────────────────────────────────────

    @Test
    void addPlayerRegistersPlayer() {
        UUID id = UUID.randomUUID();
        GamePlayer gp = mockGamePlayer(id, 0);

        session.addPlayer(gp);

        assertSame(gp, session.players().get(id));
        assertEquals(1, session.players().size());
    }

    @Test
    void removePlayerReturnsPlayer() {
        UUID id = UUID.randomUUID();
        GamePlayer gp = mockGamePlayer(id, 0);
        session.addPlayer(gp);

        GamePlayer removed = session.removePlayer(id);
        assertSame(gp, removed);
        assertNull(session.getPlayer(id));
        assertTrue(session.players().isEmpty());
    }

    @Test
    void removePlayerReturnsNullForUnknown() {
        assertNull(session.removePlayer(UUID.randomUUID()));
    }

    @Test
    void getPlayerReturnsCorrectPlayer() {
        UUID id = UUID.randomUUID();
        GamePlayer gp = mockGamePlayer(id, 0);
        session.addPlayer(gp);

        assertSame(gp, session.getPlayer(id));
    }

    @Test
    void getPlayerReturnsNullForUnknown() {
        assertNull(session.getPlayer(UUID.randomUUID()));
    }

    // ── plot assignment ──────────────────────────────────────────────

    @Test
    void findAvailablePlotReturnsFirstUnused() {
        // Plot 0 is taken — should return 1
        GamePlayer gp = mockGamePlayer(UUID.randomUUID(), 0);
        session.addPlayer(gp);

        assertEquals(1, session.findAvailablePlot());
    }

    @Test
    void findAvailablePlotReturnsNegativeWhenFull() {
        // Fill both plots (maxPlayers=2)
        session.addPlayer(mockGamePlayer(UUID.randomUUID(), 0));
        session.addPlayer(mockGamePlayer(UUID.randomUUID(), 1));

        assertEquals(-1, session.findAvailablePlot());
    }

    @Test
    void removePlayerFreesPlot() {
        UUID id = UUID.randomUUID();
        GamePlayer gp = mockGamePlayer(id, 0);
        session.addPlayer(gp);
        session.addPlayer(mockGamePlayer(UUID.randomUUID(), 1));

        // Both plots full
        assertEquals(-1, session.findAvailablePlot());

        // Remove player at plot 0 — plot 0 should be available again
        session.removePlayer(id);
        assertEquals(0, session.findAvailablePlot());
    }

    // ── themes ───────────────────────────────────────────────────────

    @Test
    void setThemesAndGetThemeWraps() {
        session.setThemes(Arrays.asList("a", "b", "c"));

        assertEquals("a", session.getTheme(0));
        assertEquals("b", session.getTheme(1));
        assertEquals("c", session.getTheme(2));
        // Wraps around
        assertEquals("a", session.getTheme(3));
        assertEquals("b", session.getTheme(4));
    }

    @Test
    void getThemeReturnsUnknownWhenEmpty() {
        // Themes default to empty list
        assertEquals("unknown", session.getTheme(0));
    }

    // ── camera cycling ───────────────────────────────────────────────

    @Test
    void advanceCameraCycles() {
        assertEquals(0, session.currentCameraIndex());

        session.advanceCamera();
        assertEquals(1, session.currentCameraIndex());

        session.advanceCamera();
        assertEquals(2, session.currentCameraIndex());

        // Wraps back to 0
        session.advanceCamera();
        assertEquals(0, session.currentCameraIndex());

        // Continues cycling
        session.advanceCamera();
        assertEquals(1, session.currentCameraIndex());
    }

    // ── state transitions ────────────────────────────────────────────

    @Test
    void stateTransitions() {
        assertEquals(ArenaState.WAITING, session.state());

        session.state(ArenaState.COUNTDOWN);
        assertEquals(ArenaState.COUNTDOWN, session.state());

        session.state(ArenaState.PLAYING);
        assertEquals(ArenaState.PLAYING, session.state());

        session.state(ArenaState.ENDING);
        assertEquals(ArenaState.ENDING, session.state());

        session.state(ArenaState.WAITING);
        assertEquals(ArenaState.WAITING, session.state());
    }

    // ── game time ────────────────────────────────────────────────────

    @Test
    void gameTimeRemainingGetterSetter() {
        assertEquals(0, session.gameTimeRemaining());
        session.gameTimeRemaining(300);
        assertEquals(300, session.gameTimeRemaining());
    }

    // ── task cancellation ────────────────────────────────────────────

    @Test
    void cancelAllTasksCancelsBukkitTasks() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        session.countdownTaskId(10);
        session.gameTickTaskId(20);
        session.renderTaskId(30);
        session.endingTaskId(40);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            session.cancelAllTasks();

            verify(scheduler).cancelTask(10);
            verify(scheduler).cancelTask(20);
            verify(scheduler).cancelTask(30);
            verify(scheduler).cancelTask(40);
        }

        // Task IDs should be reset to -1
        assertEquals(-1, session.countdownTaskId());
        assertEquals(-1, session.gameTickTaskId());
        assertEquals(-1, session.renderTaskId());
        assertEquals(-1, session.endingTaskId());
    }

    @Test
    void cancelAllTasksSkipsInactiveIds() {
        // All IDs default to -1 — cancelAllTasks should not call cancelTask
        BukkitScheduler scheduler = mock(BukkitScheduler.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            session.cancelAllTasks();

            verifyNoInteractions(scheduler);
        }
    }

    // ── task ID setters/getters ──────────────────────────────────────

    @Test
    void taskIdSettersAndGetters() {
        session.countdownTaskId(5);
        assertEquals(5, session.countdownTaskId());

        session.gameTickTaskId(15);
        assertEquals(15, session.gameTickTaskId());

        session.renderTaskId(25);
        assertEquals(25, session.renderTaskId());

        session.endingTaskId(35);
        assertEquals(35, session.endingTaskId());
    }

    // ── mirror management ────────────────────────────────────────────

    @Test
    void mirrorLifecycleHappyPath() {
        // Build a minimal arena with two identical plots.
        Arena.Position spawn = new Arena.Position(0.5, 64.5, 0.5, 0f, 0f);
        Arena.Position cam = new Arena.Position(0.5, 70.5, 0.5, 0f, 0f);
        // 1×1 picture region — both corners the same block, any cardinal face is valid.
        Arena.PictureRegion picture = new Arena.PictureRegion(
                0, 64, 0, 0, 64, 0, BlockFace.NORTH);
        Arena.PlotData plot = new Arena.PlotData(spawn,
                0, 60, 0, 7, 67, 7,
                Arrays.asList(cam, cam, cam),
                picture);
        Arena realArena = new Arena("test", "bbai_test", 2, true,
                spawn, null, 2, 60, 120, 5,
                Arrays.asList(plot, plot));

        GameSession realSession = new GameSession(realArena);

        // No mirror installed yet.
        assertNull(realSession.mirror(0));

        // Install one and read it back.
        MutablePlotScene m0 = MutablePlotScene.forPlot(plot, false);
        realSession.installMirror(0, m0);
        assertSame(m0, realSession.mirror(0));
        assertNull(realSession.mirror(1));

        // Clear and verify both gone.
        realSession.clearMirrors();
        assertNull(realSession.mirror(0));
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Creates a mock {@link GamePlayer} with the given UUID and plot index.
     */
    private static GamePlayer mockGamePlayer(UUID id, int plotIndex) {
        GamePlayer gp = mock(GamePlayer.class);
        when(gp.playerId()).thenReturn(id);
        when(gp.plotIndex()).thenReturn(plotIndex);
        return gp;
    }
}

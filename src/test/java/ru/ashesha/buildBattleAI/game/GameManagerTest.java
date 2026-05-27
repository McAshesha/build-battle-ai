package ru.ashesha.buildBattleAI.game;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GameManager}.
 * <p>
 * Covers construction, lifecycle, join validation, and state queries.
 * Full game flow (countdown, rendering, ML, scoring) requires a live
 * server and is tested manually.
 */
class GameManagerTest {

    private BuildBattleAI plugin;
    private PluginLogger pluginLogger;
    private PluginContext context;
    private GameManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        pluginLogger = mock(PluginLogger.class);
        context = mock(PluginContext.class);
        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
        manager = new GameManager(plugin);
    }

    // ── construction ──────────────────────────────────────────────────

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new GameManager(null));
    }

    // ── lifecycle ─────────────────────────────────────────────────────

    @Test
    void enableLogsMessage() {
        manager.enable();
        verify(pluginLogger).info("GameManager enabled.");
    }

    @Test
    void shutdownLogsMessage() {
        manager.enable();
        manager.shutdown();
        verify(pluginLogger).debug("GameManager shut down.");
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.enable();
        manager.shutdown();
        manager.shutdown();
        verify(pluginLogger, times(2)).debug("GameManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        manager.enable();
        manager.reload();
        InOrder order = inOrder(pluginLogger);
        order.verify(pluginLogger).debug("GameManager shut down.");
        order.verify(pluginLogger).info("GameManager enabled.");
    }

    // ── query methods ─────────────────────────────────────────────────

    @Test
    void isInGameReturnsFalseByDefault() {
        manager.enable();
        assertFalse(manager.isInGame(UUID.randomUUID()));
    }

    @Test
    void getPlayerArenaReturnsNullByDefault() {
        manager.enable();
        assertNull(manager.getPlayerArena(UUID.randomUUID()));
    }

    @Test
    void getArenaStateReturnsWaitingForUnknownArena() {
        manager.enable();
        assertEquals(ArenaState.WAITING, manager.getArenaState("nonexistent"));
    }

    @Test
    void getPlayerCountReturnsZeroForUnknownArena() {
        manager.enable();
        assertEquals(0, manager.getPlayerCount("nonexistent"));
    }

    // ── isInZone ──────────────────────────────────────────────────────

    @Test
    void isInZoneReturnsTrueForBlockInsideZone() {
        Arena.PlotData plot = buildPlot(0, 0, 0, 10, 10, 10);
        assertTrue(GameManager.isInZone(5, 5, 5, plot));
    }

    @Test
    void isInZoneReturnsTrueForBoundaryBlock() {
        Arena.PlotData plot = buildPlot(0, 0, 0, 10, 10, 10);
        assertTrue(GameManager.isInZone(0, 0, 0, plot));
        assertTrue(GameManager.isInZone(10, 10, 10, plot));
    }

    @Test
    void isInZoneReturnsFalseForBlockOutsideZone() {
        Arena.PlotData plot = buildPlot(0, 0, 0, 10, 10, 10);
        assertFalse(GameManager.isInZone(11, 5, 5, plot));
        assertFalse(GameManager.isInZone(-1, 5, 5, plot));
    }

    @Test
    void isInZoneHandlesReversedCorners() {
        // Corner2 < Corner1 — should still work
        Arena.PlotData plot = buildPlot(10, 10, 10, 0, 0, 0);
        assertTrue(GameManager.isInZone(5, 5, 5, plot));
        assertFalse(GameManager.isInZone(11, 5, 5, plot));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static Arena.PlotData buildPlot(int c1x, int c1y, int c1z,
                                            int c2x, int c2y, int c2z) {
        Arena.Position spawn = new Arena.Position(5, 65, 5, 0, 0);
        java.util.List<Arena.Position> cameras = java.util.Arrays.asList(
                new Arena.Position(0, 70, -5, 0, 30),
                new Arena.Position(5, 70, -5, 30, 30),
                new Arena.Position(10, 70, -5, 60, 30));
        // 1×1 picture region — geometry irrelevant for zone tests but
        // required by the PlotData contract.
        Arena.PictureRegion picture = new Arena.PictureRegion(
                0, 80, 30, 0, 80, 30, org.bukkit.block.BlockFace.NORTH);
        return new Arena.PlotData(spawn, c1x, c1y, c1z, c2x, c2y, c2z, cameras, picture);
    }
}

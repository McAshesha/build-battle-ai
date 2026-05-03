package ru.ashesha.buildBattleAI.arena;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ArenaManager}.
 * <p>
 * Covers construction, lifecycle, arena loading with validation,
 * query methods, and arena deletion. The interactive setup wizard
 * requires a live server (Player, World, Location) and is tested manually.
 */
class ArenaManagerTest {

    private BuildBattleAI plugin;
    private PluginLogger pluginLogger;
    private PluginContext context;
    private BBAIConfigService configService;
    private BBAIWorldService worldService;
    private ArenaManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        pluginLogger = mock(PluginLogger.class);
        context = mock(PluginContext.class);
        configService = mock(BBAIConfigService.class);
        worldService = mock(BBAIWorldService.class);

        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getWorldService()).thenReturn(worldService);
        when(configService.getArenaNames()).thenReturn(Collections.<String>emptySet());

        manager = new ArenaManager(plugin);
    }

    // ── construction ───────────────────────────────────────────────────

    @Test
    void constructorRejectsNullPlugin() {
        assertThrows(NullPointerException.class, () -> new ArenaManager(null));
    }

    // ── lifecycle ──────────────────────────────────────────────────────

    @Test
    void enableLoadsArenasAndLogs() {
        manager.enable();
        verify(configService).getArenaNames();
        verify(pluginLogger).info("ArenaManager enabled (%d arena(s) loaded).", 0);
    }

    @Test
    void enableLoadsWorldForEnabledArena() {
        YamlConfiguration config = buildValidArenaConfig("bbai_test", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("test"));
        when(configService.getArenaConfig("test")).thenReturn(config);

        manager.enable();

        verify(worldService).loadWorld("bbai_test");
        verify(pluginLogger).info("ArenaManager enabled (%d arena(s) loaded).", 1);
    }

    @Test
    void enableCreatesWorldWhenLoadFails() {
        YamlConfiguration config = buildValidArenaConfig("bbai_fb", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("fb"));
        when(configService.getArenaConfig("fb")).thenReturn(config);
        when(worldService.loadWorld("bbai_fb")).thenReturn(null);

        manager.enable();
        verify(worldService).createEmptyWorld("bbai_fb");
    }

    @Test
    void enableSkipsWorldLoadForDisabledArena() {
        YamlConfiguration config = buildValidArenaConfig("bbai_off", 2, false);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("off"));
        when(configService.getArenaConfig("off")).thenReturn(config);

        manager.enable();

        verify(worldService, never()).loadWorld(anyString());
        verify(worldService, never()).createEmptyWorld(anyString());
    }

    @Test
    void shutdownClearsArenas() {
        YamlConfiguration config = buildValidArenaConfig("bbai_t", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("t"));
        when(configService.getArenaConfig("t")).thenReturn(config);

        manager.enable();
        assertFalse(manager.getArenaNames().isEmpty());

        manager.shutdown();
        assertTrue(manager.getArenaNames().isEmpty());
    }

    @Test
    void shutdownCanBeCalledMultipleTimes() {
        manager.shutdown();
        manager.shutdown();
        verify(pluginLogger, times(2)).debug("ArenaManager shut down.");
    }

    @Test
    void reloadCallsShutdownThenEnable() {
        manager.reload();
        InOrder order = inOrder(pluginLogger);
        order.verify(pluginLogger).debug("ArenaManager shut down.");
        order.verify(pluginLogger).info("ArenaManager enabled (%d arena(s) loaded).", 0);
    }

    // ── query methods ──────────────────────────────────────────────────

    @Test
    void getArenaReturnsNullForUnknown() {
        manager.enable();
        assertNull(manager.getArena("nonexistent"));
    }

    @Test
    void getArenaReturnsLoadedArena() {
        YamlConfiguration config = buildValidArenaConfig("bbai_my", 4, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("my"));
        when(configService.getArenaConfig("my")).thenReturn(config);

        manager.enable();

        Arena arena = manager.getArena("my");
        assertNotNull(arena);
        assertEquals("my", arena.name());
        assertEquals("bbai_my", arena.worldName());
        assertEquals(4, arena.maxPlayers());
        assertTrue(arena.enabled());
    }

    @Test
    void getArenaNamesReturnsUnmodifiable() {
        manager.enable();
        assertThrows(UnsupportedOperationException.class,
                () -> manager.getArenaNames().add("x"));
    }

    @Test
    void isArenaLoadedReturnsTrueForEnabled() {
        YamlConfiguration config = buildValidArenaConfig("bbai_a", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("a"));
        when(configService.getArenaConfig("a")).thenReturn(config);

        manager.enable();
        assertTrue(manager.isArenaLoaded("a"));
    }

    @Test
    void isArenaLoadedReturnsFalseForDisabled() {
        YamlConfiguration config = buildValidArenaConfig("bbai_d", 2, false);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("d"));
        when(configService.getArenaConfig("d")).thenReturn(config);

        manager.enable();
        assertFalse(manager.isArenaLoaded("d"));
    }

    // ── deserialization correctness ────────────────────────────────────

    @Test
    void deserializedArenaHasLobby() {
        YamlConfiguration config = buildValidArenaConfig("bbai_lob", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("lob"));
        when(configService.getArenaConfig("lob")).thenReturn(config);

        manager.enable();

        Arena arena = manager.getArena("lob");
        assertNotNull(arena.lobby());
        assertEquals(0.5, arena.lobby().x(), 0.001);
        assertEquals(65.0, arena.lobby().y(), 0.001);
        assertEquals(0.5, arena.lobby().z(), 0.001);
    }

    @Test
    void deserializedArenaHasPlotSpawn() {
        YamlConfiguration config = buildValidArenaConfig("bbai_sp", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("sp"));
        when(configService.getArenaConfig("sp")).thenReturn(config);

        manager.enable();

        Arena arena = manager.getArena("sp");
        assertEquals(2, arena.plots().size());
        Arena.PlotData firstPlot = arena.plots().get(0);
        assertNotNull(firstPlot.spawn());
        assertEquals(3, firstPlot.cameras().size());
    }

    @Test
    void deserializedArenaUsesDefaultsForOptionals() {
        YamlConfiguration config = buildValidArenaConfig("bbai_def", 2, true);
        // Don't set optional fields — they should use defaults
        when(configService.getArenaNames()).thenReturn(Collections.singleton("def"));
        when(configService.getArenaConfig("def")).thenReturn(config);

        manager.enable();

        Arena arena = manager.getArena("def");
        assertNotNull(arena);
        assertNull(arena.spectator());
        assertNotNull(arena.effectiveSpectator()); // falls back to lobby
        assertEquals(2, arena.minPlayers());
        assertEquals(150, arena.buildTime());
        assertEquals(300, arena.gameTime());
        assertEquals(5, arena.countdownTime());
    }

    @Test
    void deserializedArenaReadsSpectatorWhenPresent() {
        YamlConfiguration config = buildValidArenaConfig("bbai_spec", 2, true);
        config.set("spectator.x", 10.0);
        config.set("spectator.y", 80.0);
        config.set("spectator.z", 10.0);
        config.set("spectator.yaw", 90.0);
        config.set("spectator.pitch", 0.0);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("spec"));
        when(configService.getArenaConfig("spec")).thenReturn(config);

        manager.enable();

        Arena arena = manager.getArena("spec");
        assertNotNull(arena.spectator());
        assertEquals(10.0, arena.spectator().x(), 0.001);
        assertEquals(80.0, arena.spectator().y(), 0.001);
    }

    // ── validation — missing required fields ───────────────────────────

    @Test
    void validationRejectsMissingLobby() {
        YamlConfiguration config = buildValidArenaConfig("bbai_nolob", 2, true);
        config.set("lobby", null); // remove lobby
        when(configService.getArenaNames()).thenReturn(Collections.singleton("nolob"));
        when(configService.getArenaConfig("nolob")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("nolob"));
        verify(pluginLogger).error("Arena '%s': %s", "nolob", "missing 'lobby'");
        verify(pluginLogger).error("Arena '%s' will not be activated due to configuration errors.", "nolob");
    }

    @Test
    void validationRejectsMissingPlotSpawn() {
        YamlConfiguration config = buildValidArenaConfig("bbai_nosp", 2, true);
        config.set("plots.1.spawn", null); // remove plot 1 spawn
        when(configService.getArenaNames()).thenReturn(Collections.singleton("nosp"));
        when(configService.getArenaConfig("nosp")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("nosp"));
        verify(pluginLogger).error("Arena '%s': %s", "nosp", "missing 'plots.1.spawn'");
    }

    @Test
    void validationRejectsMissingCorner() {
        YamlConfiguration config = buildValidArenaConfig("bbai_noc", 2, true);
        config.set("plots.2.corner1", null); // remove plot 2 corner1
        when(configService.getArenaNames()).thenReturn(Collections.singleton("noc"));
        when(configService.getArenaConfig("noc")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("noc"));
        verify(pluginLogger).error("Arena '%s': %s", "noc", "missing 'plots.2.corner1'");
    }

    @Test
    void validationRejectsMissingCamera() {
        YamlConfiguration config = buildValidArenaConfig("bbai_nocam", 2, true);
        config.set("plots.1.camera1", null); // remove plot 1 camera 1
        when(configService.getArenaNames()).thenReturn(Collections.singleton("nocam"));
        when(configService.getArenaConfig("nocam")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("nocam"));
        verify(pluginLogger).error("Arena '%s': %s", "nocam", "missing 'plots.1.camera1'");
    }

    @Test
    void validationRejectsInvalidPlayerCount() {
        YamlConfiguration config = buildValidArenaConfig("bbai_bad", 2, true);
        config.set("max-players", 1); // below minimum
        when(configService.getArenaNames()).thenReturn(Collections.singleton("bad"));
        when(configService.getArenaConfig("bad")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("bad"));
        verify(pluginLogger).error("Arena '%s': %s", "bad",
                "'max-players' must be between 2 and 8 (got 1)");
    }

    @Test
    void validationCollectsMultipleErrors() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", "bbai_multi");
        config.set("max-players", 2);
        config.set("enabled", true);
        // Missing: lobby, all plot fields
        when(configService.getArenaNames()).thenReturn(Collections.singleton("multi"));
        when(configService.getArenaConfig("multi")).thenReturn(config);

        manager.enable();

        assertNull(manager.getArena("multi"));
        // Should log missing lobby + 6 fields per plot * 2 plots = 13 errors + summary
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'lobby'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.spawn'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.corner1'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.corner2'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.camera1'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.camera2'");
        verify(pluginLogger).error("Arena '%s': %s", "multi", "missing 'plots.1.camera3'");
        verify(pluginLogger).error(
                "Arena '%s' will not be activated due to configuration errors.", "multi");
    }

    // ── deletion ───────────────────────────────────────────────────────

    @Test
    void deleteArenaRemovesFromRegistryAndConfig() {
        YamlConfiguration config = buildValidArenaConfig("bbai_del", 2, true);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("del"));
        when(configService.getArenaConfig("del")).thenReturn(config);

        manager.enable();
        assertNotNull(manager.getArena("del"));

        manager.deleteArena("del");

        assertNull(manager.getArena("del"));
        verify(worldService).deleteWorld("bbai_del");
        verify(configService).deleteArenaConfig("del");
    }

    @Test
    void deleteNonexistentArenaStillCleansConfig() {
        manager.enable();
        manager.deleteArena("ghost");
        verify(configService).deleteArenaConfig("ghost");
    }

    // ── setup session ──────────────────────────────────────────────────

    @Test
    void hasSetupSessionReturnsFalseByDefault() {
        assertFalse(manager.hasSetupSession(UUID.randomUUID()));
    }

    @Test
    void cancelSetupSessionNoOpForUnknownPlayer() {
        manager.cancelSetupSession(UUID.randomUUID());
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a complete valid arena YAML config with all required fields.
     * Each plot has deterministic coordinates based on its 1-based index.
     */
    private static YamlConfiguration buildValidArenaConfig(String worldName, int maxPlayers,
                                                           boolean enabled) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("world", worldName);
        config.set("max-players", maxPlayers);
        config.set("enabled", enabled);

        // Lobby (required)
        config.set("lobby.x", 0.5);
        config.set("lobby.y", 65.0);
        config.set("lobby.z", 0.5);
        config.set("lobby.yaw", 0.0);
        config.set("lobby.pitch", 0.0);

        // Plots with all required fields
        for (int i = 1; i <= maxPlayers; i++) {
            String p = "plots." + i;
            // Spawn
            config.set(p + ".spawn.x", (double) (i * 20 + 5));
            config.set(p + ".spawn.y", 65.0);
            config.set(p + ".spawn.z", 5.0);
            config.set(p + ".spawn.yaw", 0.0);
            config.set(p + ".spawn.pitch", 0.0);
            // Corner 1
            config.set(p + ".corner1.x", i * 20);
            config.set(p + ".corner1.y", 64);
            config.set(p + ".corner1.z", 0);
            // Corner 2
            config.set(p + ".corner2.x", i * 20 + 15);
            config.set(p + ".corner2.y", 80);
            config.set(p + ".corner2.z", 15);
            // Cameras (3 per plot)
            for (int c = 1; c <= 3; c++) {
                config.set(p + ".camera" + c + ".x", (double) (i * 20 + 8 + c * 2));
                config.set(p + ".camera" + c + ".y", 72.0);
                config.set(p + ".camera" + c + ".z", -10.0 - c * 3);
                config.set(p + ".camera" + c + ".yaw", (double) (c * 30));
                config.set(p + ".camera" + c + ".pitch", 30.0);
            }
        }
        return config;
    }
}

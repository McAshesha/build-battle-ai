package ru.ashesha.buildBattleAI.data;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.data.api.ArenaStats;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.File;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DataService}.
 * <p>
 * Mocks the {@link BuildBattleAI} plugin and its {@link PluginContext} to
 * exercise the full service lifecycle (enable, CRUD, flush, shutdown) with
 * a local JSON file backend. Uses a temporary directory as the plugin data
 * folder to avoid side effects.
 */
class DataServiceTest {

    @TempDir
    File dataFolder;

    private BuildBattleAI plugin;
    private PluginContext context;
    private BBAIConfigService configService;
    private DataService service;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        configService = mock(BBAIConfigService.class);

        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DataServiceTest"));
        when(plugin.getContext()).thenReturn(context);
        when(context.getConfigService()).thenReturn(configService);

        // Build a config with data section enabled
        config = new YamlConfiguration();
        config.set("data.enabled", true);
        config.set("data.provider", "local");
        config.set("data.local.directory", "data");
        config.set("data.local.auto-save-interval", 0); // disable auto-save in tests
        when(configService.config()).thenReturn(config);

        // Mock the scheduler for auto-save (return a mock task)
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));

        service = new DataService(plugin);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -- lifecycle -----------------------------------------------------------

    @Test
    void enableSetsEnabledFlag() {
        assertTrue(service.isEnabled());
    }

    @Test
    void enableCreatesDataDirectory() {
        File dataDir = new File(dataFolder, "data");
        assertTrue(dataDir.exists() && dataDir.isDirectory());
    }

    @Test
    void shutdownClearsEnabledFlag() {
        service.shutdown();
        assertFalse(service.isEnabled());
    }

    @Test
    void shutdownIsIdempotent() {
        service.shutdown();
        service.shutdown(); // second call should not throw
    }

    @Test
    void disabledServiceDoesNotCreateDirectory() {
        service.shutdown();

        config.set("data.enabled", false);
        service.enable();

        assertFalse(service.isEnabled());
        File dataDir = new File(dataFolder, "data-disabled");
        config.set("data.local.directory", "data-disabled");
        assertFalse(dataDir.exists());
    }

    @Test
    void reloadCycleRestoresService() {
        UUID uuid = UUID.randomUUID();
        service.getOrCreatePlayer(uuid, "Alice");
        service.flush();

        // Simulate reload
        service.shutdown();
        service.enable();

        assertTrue(service.isEnabled());
        PlayerData reloaded = service.getPlayer(uuid);
        assertNotNull(reloaded, "Player data should survive reload cycle");
        assertEquals("Alice", reloaded.name());
    }

    // -- player data CRUD ---------------------------------------------------

    @Test
    void getPlayerReturnsNullForUnknown() {
        assertNull(service.getPlayer(UUID.randomUUID()));
    }

    @Test
    void getOrCreatePlayerCreatesNewEntry() {
        UUID uuid = UUID.randomUUID();
        PlayerData data = service.getOrCreatePlayer(uuid, "Steve");

        assertNotNull(data);
        assertEquals(uuid, data.uuid());
        assertEquals("Steve", data.name());
        assertEquals(0, data.wins());
    }

    @Test
    void getOrCreatePlayerReturnsExistingEntry() {
        UUID uuid = UUID.randomUUID();
        PlayerData first = service.getOrCreatePlayer(uuid, "Steve");
        first.wins(5);
        service.savePlayer(first);

        PlayerData second = service.getOrCreatePlayer(uuid, "Steve");
        assertEquals(5, second.wins(), "Should return the same object with updated stats");
    }

    @Test
    void getOrCreatePlayerUpdatesNameOnChange() {
        UUID uuid = UUID.randomUUID();
        service.getOrCreatePlayer(uuid, "OldName");

        PlayerData updated = service.getOrCreatePlayer(uuid, "NewName");
        assertEquals("NewName", updated.name());
    }

    @Test
    void savePlayerPersistsData() {
        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "Alice");
        data.wins(10);
        data.losses(3);
        service.savePlayer(data);

        PlayerData retrieved = service.getPlayer(uuid);
        assertNotNull(retrieved);
        assertEquals(10, retrieved.wins());
        assertEquals(3, retrieved.losses());
    }

    @Test
    void getAllPlayersReturnsAllEntries() {
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        service.getOrCreatePlayer(uuid1, "Alice");
        service.getOrCreatePlayer(uuid2, "Bob");

        Collection<PlayerData> all = service.getAllPlayers();
        assertEquals(2, all.size());
    }

    @Test
    void getAllPlayersReturnsEmptyWhenNone() {
        Collection<PlayerData> all = service.getAllPlayers();
        assertTrue(all.isEmpty());
    }

    // -- arena stats CRUD ---------------------------------------------------

    @Test
    void getArenaStatsReturnsNullForUnknown() {
        assertNull(service.getArenaStats("nonexistent"));
    }

    @Test
    void saveAndRetrieveArenaStats() {
        ArenaStats stats = new ArenaStats("lobby");
        stats.totalGames(42);
        stats.highestScore(99);
        service.saveArenaStats(stats);

        ArenaStats retrieved = service.getArenaStats("lobby");
        assertNotNull(retrieved);
        assertEquals("lobby", retrieved.name());
        assertEquals(42, retrieved.totalGames());
        assertEquals(99, retrieved.highestScore());
    }

    @Test
    void getAllArenaStatsReturnsAllEntries() {
        service.saveArenaStats(new ArenaStats("a1"));
        service.saveArenaStats(new ArenaStats("a2"));

        Collection<ArenaStats> all = service.getAllArenaStats();
        assertEquals(2, all.size());
    }

    @Test
    void getAllArenaStatsReturnsEmptyWhenNone() {
        assertTrue(service.getAllArenaStats().isEmpty());
    }

    // -- flush and persistence -----------------------------------------------

    @Test
    void flushCreatesJsonFiles() {
        service.getOrCreatePlayer(UUID.randomUUID(), "Test");
        service.saveArenaStats(new ArenaStats("arena"));
        service.flush();

        File playersFile = new File(dataFolder, "data/players.json");
        File arenaFile = new File(dataFolder, "data/arena-stats.json");
        assertTrue(playersFile.exists(), "players.json should exist after flush");
        assertTrue(arenaFile.exists(), "arena-stats.json should exist after flush");
    }

    @Test
    void dataSurvivesShutdownAndRestart() {
        UUID uuid = UUID.randomUUID();
        PlayerData player = service.getOrCreatePlayer(uuid, "Steve");
        player.wins(7);
        service.savePlayer(player);

        ArenaStats stats = new ArenaStats("lobby");
        stats.totalGames(10);
        service.saveArenaStats(stats);

        // Shutdown (includes flush)
        service.shutdown();

        // Re-enable (reloads from disk)
        service.enable();

        PlayerData reloadedPlayer = service.getPlayer(uuid);
        assertNotNull(reloadedPlayer);
        assertEquals("Steve", reloadedPlayer.name());
        assertEquals(7, reloadedPlayer.wins());

        ArenaStats reloadedStats = service.getArenaStats("lobby");
        assertNotNull(reloadedStats);
        assertEquals(10, reloadedStats.totalGames());
    }

    // -- disabled service behavior -------------------------------------------

    @Test
    void disabledGetPlayerReturnsNull() {
        service.shutdown();
        config.set("data.enabled", false);
        service.enable();

        assertNull(service.getPlayer(UUID.randomUUID()));
    }

    @Test
    void disabledGetOrCreatePlayerReturnsTransientObject() {
        service.shutdown();
        config.set("data.enabled", false);
        service.enable();

        UUID uuid = UUID.randomUUID();
        PlayerData data = service.getOrCreatePlayer(uuid, "Test");
        assertNotNull(data, "Should return a transient PlayerData even when disabled");
        assertEquals("Test", data.name());
    }

    @Test
    void disabledSavePlayerIsNoOp() {
        service.shutdown();
        config.set("data.enabled", false);
        service.enable();

        // Should not throw
        service.savePlayer(new PlayerData(UUID.randomUUID(), "Test"));
    }

    @Test
    void disabledGetAllPlayersReturnsEmpty() {
        service.shutdown();
        config.set("data.enabled", false);
        service.enable();

        assertTrue(service.getAllPlayers().isEmpty());
    }

    @Test
    void disabledFlushIsNoOp() {
        service.shutdown();
        config.set("data.enabled", false);
        service.enable();

        service.flush(); // should not throw
    }

    // -- auto-save scheduling ------------------------------------------------

    @Test
    void autoSaveIsScheduledWhenIntervalPositive() {
        service.shutdown();
        config.set("data.local.auto-save-interval", 60);

        Server server = plugin.getServer();
        BukkitScheduler scheduler = server.getScheduler();
        reset(scheduler);
        when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));

        service.enable();

        verify(scheduler).runTaskTimerAsynchronously(
                eq(plugin), any(Runnable.class), eq(1200L), eq(1200L));
    }

    @Test
    void autoSaveNotScheduledWhenIntervalZero() {
        // auto-save-interval is already 0 in setUp
        Server server = plugin.getServer();
        BukkitScheduler scheduler = server.getScheduler();

        verify(scheduler, never()).runTaskTimerAsynchronously(
                eq(plugin), any(Runnable.class), anyLong(), anyLong());
    }
}

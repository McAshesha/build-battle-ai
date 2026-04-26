package ru.ashesha.buildBattleAI.data;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.ashesha.buildBattleAI.data.api.ArenaStats;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Ignite data providers.
 * <p>
 * Starts a real embedded Ignite server node and exercises full CRUD
 * lifecycles with {@link PlayerData} and {@link ArenaStats} through
 * both {@link IgniteEmbeddedProvider} (server mode) and
 * {@link IgniteThinProvider} (thin-client connecting to the same node).
 * <p>
 * The Ignite node is started once per class via {@link BeforeAll} and
 * shut down in {@link AfterAll} to avoid startup overhead per test.
 * A unique instance name prevents collisions with other running nodes.
 */
class IgniteDataProviderTest {

    /** Discovery addresses for the local single-node cluster. */
    private static final List<String> DISCOVERY = Arrays.asList("127.0.0.1:47500..47509");

    /** Thin client address for the local server node. */
    private static final List<String> THIN_ADDR = Arrays.asList("127.0.0.1:10800");

    @TempDir
    static File workDir;

    private static IgniteEmbeddedProvider serverProvider;

    @BeforeAll
    static void startIgnite() throws Exception {
        System.setProperty("IGNITE_QUIET", "true");

        serverProvider = new IgniteEmbeddedProvider(
                false,
                "BBAI-Test-" + UUID.randomUUID().toString().substring(0, 8),
                false,
                workDir,
                true,
                0,
                64, 128,
                DISCOVERY
        );
        serverProvider.start();
    }

    @AfterAll
    static void stopIgnite() {
        if (serverProvider != null)
            serverProvider.stop();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Server node — IgniteEmbeddedProvider + IgniteCacheRepository
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void serverPutAndGet() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-players", UUID.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "Steve");
        data.wins(7);
        repo.put(uuid, data);

        PlayerData loaded = repo.get(uuid);
        assertNotNull(loaded);
        assertEquals("Steve", loaded.name());
        assertEquals(7, loaded.wins());
        assertEquals(uuid, loaded.uuid());
    }

    @Test
    void serverGetReturnsNullForMissing() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-players-miss", UUID.class, PlayerData.class);
        assertNull(repo.get(UUID.randomUUID()));
    }

    @Test
    void serverContainsKey() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-contains", UUID.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        assertFalse(repo.containsKey(uuid));
        repo.put(uuid, new PlayerData(uuid, "Test"));
        assertTrue(repo.containsKey(uuid));
    }

    @Test
    void serverRemove() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-remove", UUID.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        repo.put(uuid, new PlayerData(uuid, "Test"));
        repo.remove(uuid);
        assertNull(repo.get(uuid));
    }

    @Test
    void serverGetAll() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-getall", UUID.class, PlayerData.class);

        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        repo.put(u1, new PlayerData(u1, "Alice"));
        repo.put(u2, new PlayerData(u2, "Bob"));

        Collection<PlayerData> all = repo.getAll();
        assertEquals(2, all.size());
    }

    @Test
    void serverPlayerDataFieldsSurvive() {
        DataRepository<UUID, PlayerData> repo =
                serverProvider.getRepository("srv-fields", UUID.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        PlayerData original = new PlayerData(uuid, "Steve");
        original.wins(10);
        original.losses(3);
        original.gamesPlayed(15);
        original.totalScore(4200L);
        original.bestScore(95);
        original.firstJoin(1000000L);
        original.lastPlayed(2000000L);
        repo.put(uuid, original);

        PlayerData loaded = repo.get(uuid);
        assertNotNull(loaded);
        assertEquals(uuid, loaded.uuid());
        assertEquals("Steve", loaded.name());
        assertEquals(10, loaded.wins());
        assertEquals(3, loaded.losses());
        assertEquals(15, loaded.gamesPlayed());
        assertEquals(4200L, loaded.totalScore());
        assertEquals(95, loaded.bestScore());
        assertEquals(1000000L, loaded.firstJoin());
        assertEquals(2000000L, loaded.lastPlayed());
    }

    @Test
    void serverArenaStatsSurvive() {
        DataRepository<String, ArenaStats> repo =
                serverProvider.getRepository("srv-arenas", String.class, ArenaStats.class);

        ArenaStats stats = new ArenaStats("lobby");
        stats.totalGames(42);
        stats.totalPlayers(100);
        stats.highestScore(99);
        stats.highestScorePlayer("some-uuid");
        stats.lastGameTime(3000000L);
        repo.put("lobby", stats);

        ArenaStats loaded = repo.get("lobby");
        assertNotNull(loaded);
        assertEquals("lobby", loaded.name());
        assertEquals(42, loaded.totalGames());
        assertEquals(100, loaded.totalPlayers());
        assertEquals(99, loaded.highestScore());
        assertEquals("some-uuid", loaded.highestScorePlayer());
        assertEquals(3000000L, loaded.lastGameTime());
    }

    @Test
    void serverFlushIsNoOp() {
        // Should not throw
        serverProvider.flush();
    }

    @Test
    void serverGetRepositoryReturnsSameInstance() {
        DataRepository<String, String> repo1 =
                serverProvider.getRepository("srv-same", String.class, String.class);
        DataRepository<String, String> repo2 =
                serverProvider.getRepository("srv-same", String.class, String.class);
        assertSame(repo1, repo2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Thin client — IgniteThinProvider + IgniteClientCacheRepository
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void thinClientCrud() throws Exception {
        IgniteThinProvider thinProvider = new IgniteThinProvider(THIN_ADDR);
        try {
            thinProvider.start();

            DataRepository<UUID, PlayerData> repo =
                    thinProvider.getRepository("thin-players", UUID.class, PlayerData.class);

            UUID uuid = UUID.randomUUID();
            PlayerData data = new PlayerData(uuid, "ThinSteve");
            data.wins(3);
            repo.put(uuid, data);

            PlayerData loaded = repo.get(uuid);
            assertNotNull(loaded);
            assertEquals("ThinSteve", loaded.name());
            assertEquals(3, loaded.wins());

            // containsKey
            assertTrue(repo.containsKey(uuid));
            assertFalse(repo.containsKey(UUID.randomUUID()));

            // getAll
            UUID uuid2 = UUID.randomUUID();
            repo.put(uuid2, new PlayerData(uuid2, "ThinAlex"));
            Collection<PlayerData> all = repo.getAll();
            assertEquals(2, all.size());

            // remove
            repo.remove(uuid);
            assertNull(repo.get(uuid));
        } finally {
            thinProvider.stop();
        }
    }

    @Test
    void thinClientFlushIsNoOp() throws Exception {
        IgniteThinProvider thinProvider = new IgniteThinProvider(THIN_ADDR);
        try {
            thinProvider.start();
            thinProvider.flush(); // should not throw
        } finally {
            thinProvider.stop();
        }
    }

    @Test
    void thinClientStopIsIdempotent() throws Exception {
        IgniteThinProvider thinProvider = new IgniteThinProvider(THIN_ADDR);
        thinProvider.start();
        thinProvider.stop();
        thinProvider.stop(); // second call should not throw
    }

    @Test
    void thinClientSeesServerData() throws Exception {
        // Write data via server provider
        DataRepository<String, ArenaStats> serverRepo =
                serverProvider.getRepository("shared-arenas", String.class, ArenaStats.class);
        ArenaStats stats = new ArenaStats("pvp");
        stats.totalGames(10);
        serverRepo.put("pvp", stats);

        // Read it via thin client
        IgniteThinProvider thinProvider = new IgniteThinProvider(THIN_ADDR);
        try {
            thinProvider.start();
            DataRepository<String, ArenaStats> thinRepo =
                    thinProvider.getRepository("shared-arenas", String.class, ArenaStats.class);

            ArenaStats loaded = thinRepo.get("pvp");
            assertNotNull(loaded, "Thin client should see data written by server node");
            assertEquals("pvp", loaded.name());
            assertEquals(10, loaded.totalGames());
        } finally {
            thinProvider.stop();
        }
    }
}

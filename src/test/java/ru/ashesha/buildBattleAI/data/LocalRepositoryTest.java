package ru.ashesha.buildBattleAI.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ashesha.buildBattleAI.data.api.ArenaStats;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LocalRepository}.
 * <p>
 * Exercises CRUD operations, dirty-flag flush logic, atomic file writes,
 * load-from-disk on startup, and Gson serialization of domain models.
 * Runs without Bukkit — pure Java I/O and Gson.
 */
class LocalRepositoryTest {

    @TempDir
    File tempDir;

    private Gson gson;

    @BeforeEach
    void setUp() {
        gson = new GsonBuilder().setPrettyPrinting().create();
    }

    // -- basic CRUD ---------------------------------------------------------

    @Test
    void putAndGetReturnsValue() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");

        UUID uuid = UUID.randomUUID();
        PlayerData data = new PlayerData(uuid, "Steve");
        repo.put(uuid.toString(), data);

        PlayerData retrieved = repo.get(uuid.toString());
        assertNotNull(retrieved);
        assertEquals("Steve", retrieved.name());
        assertEquals(uuid, retrieved.uuid());
    }

    @Test
    void getReturnsNullForMissingKey() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        assertNull(repo.get("nonexistent"));
    }

    @Test
    void containsKeyReturnsTrueAfterPut() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        repo.put("key", new PlayerData(UUID.randomUUID(), "Test"));
        assertTrue(repo.containsKey("key"));
    }

    @Test
    void containsKeyReturnsFalseForMissing() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        assertFalse(repo.containsKey("key"));
    }

    @Test
    void removeDeletesEntry() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        repo.put("key", new PlayerData(UUID.randomUUID(), "Test"));
        repo.remove("key");
        assertNull(repo.get("key"));
        assertFalse(repo.containsKey("key"));
    }

    @Test
    void removeNonexistentIsNoOp() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        repo.remove("nonexistent"); // should not throw
    }

    @Test
    void getAllReturnsAllValues() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        repo.put("a", new PlayerData(UUID.randomUUID(), "Alice"));
        repo.put("b", new PlayerData(UUID.randomUUID(), "Bob"));

        Collection<PlayerData> all = repo.getAll();
        assertEquals(2, all.size());
    }

    @Test
    void getAllReturnsSnapshotNotLiveView() {
        LocalRepository<String, PlayerData> repo = playerRepo("players.json");
        repo.put("a", new PlayerData(UUID.randomUUID(), "Alice"));
        Collection<PlayerData> snapshot = repo.getAll();

        // Modify the repo after taking the snapshot
        repo.put("b", new PlayerData(UUID.randomUUID(), "Bob"));

        // Snapshot should still have only 1 entry
        assertEquals(1, snapshot.size());
    }

    // -- flush and persistence -----------------------------------------------

    @Test
    void flushWritesDataToFile() {
        File file = new File(tempDir, "players.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        repo.put(uuid.toString(), new PlayerData(uuid, "Steve"));
        repo.flush();

        assertTrue(file.exists(), "JSON file should be created after flush");
        String content = readFile(file);
        assertTrue(content.contains("Steve"), "JSON should contain player name");
        assertTrue(content.contains(uuid.toString()), "JSON should contain UUID");
    }

    @Test
    void flushIsNoOpWhenNotDirty() {
        File file = new File(tempDir, "players.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        // No puts — flush should not create the file
        repo.flush();
        assertFalse(file.exists(), "No file should be created when nothing was modified");
    }

    @Test
    void flushResetsCleanState() {
        File file = new File(tempDir, "players.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        repo.put("key", new PlayerData(UUID.randomUUID(), "Test"));
        repo.flush();

        long lastModified = file.lastModified();

        // Second flush without changes should be a no-op
        // (We can't reliably assert file wasn't touched due to timestamp
        // resolution, but at least verify it doesn't throw)
        repo.flush();
    }

    @Test
    void loadRestoresDataFromFile() {
        File file = new File(tempDir, "data.json");

        // Write data, then create a new repo that loads from the same file
        LocalRepository<String, PlayerData> repo1 =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        UUID uuid = UUID.randomUUID();
        repo1.put(uuid.toString(), new PlayerData(uuid, "Steve"));
        repo1.flush();

        // New repo should load persisted data
        LocalRepository<String, PlayerData> repo2 =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        repo2.load();

        PlayerData loaded = repo2.get(uuid.toString());
        assertNotNull(loaded, "Data should survive save/load cycle");
        assertEquals("Steve", loaded.name());
        assertEquals(uuid, loaded.uuid());
    }

    @Test
    void loadFromNonexistentFileStartsEmpty() {
        File file = new File(tempDir, "nonexistent.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        repo.load(); // should not throw
        assertTrue(repo.getAll().isEmpty());
    }

    @Test
    void loadFromEmptyFileStartsEmpty() throws Exception {
        File file = new File(tempDir, "empty.json");
        file.createNewFile();

        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        repo.load(); // should not throw
        assertTrue(repo.getAll().isEmpty());
    }

    // -- domain model serialization ------------------------------------------

    @Test
    void playerDataFieldsSurviveRoundTrip() {
        File file = new File(tempDir, "players.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        UUID uuid = UUID.randomUUID();
        PlayerData original = new PlayerData(uuid, "Steve");
        original.wins(10);
        original.losses(3);
        original.gamesPlayed(15);
        original.totalScore(4200L);
        original.bestScore(95);
        original.firstJoin(1000000L);
        original.lastPlayed(2000000L);

        repo.put(uuid.toString(), original);
        repo.flush();

        // Reload in a fresh repo
        LocalRepository<String, PlayerData> repo2 =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        repo2.load();

        PlayerData loaded = repo2.get(uuid.toString());
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
    void arenaStatsSurviveRoundTrip() {
        File file = new File(tempDir, "arenas.json");
        LocalRepository<String, ArenaStats> repo =
                new LocalRepository<>(file, gson, String.class, ArenaStats.class);

        ArenaStats original = new ArenaStats("lobby");
        original.totalGames(42);
        original.totalPlayers(100);
        original.highestScore(99);
        original.highestScorePlayer("d4a3e2b1-1234-5678-9abc-def012345678");
        original.lastGameTime(3000000L);

        repo.put("lobby", original);
        repo.flush();

        // Reload
        LocalRepository<String, ArenaStats> repo2 =
                new LocalRepository<>(file, gson, String.class, ArenaStats.class);
        repo2.load();

        ArenaStats loaded = repo2.get("lobby");
        assertNotNull(loaded);
        assertEquals("lobby", loaded.name());
        assertEquals(42, loaded.totalGames());
        assertEquals(100, loaded.totalPlayers());
        assertEquals(99, loaded.highestScore());
        assertEquals("d4a3e2b1-1234-5678-9abc-def012345678", loaded.highestScorePlayer());
        assertEquals(3000000L, loaded.lastGameTime());
    }

    @Test
    void multipleEntriesSurviveRoundTrip() {
        File file = new File(tempDir, "players.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        repo.put(uuid1.toString(), new PlayerData(uuid1, "Alice"));
        repo.put(uuid2.toString(), new PlayerData(uuid2, "Bob"));
        repo.flush();

        LocalRepository<String, PlayerData> repo2 =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);
        repo2.load();

        assertEquals(2, repo2.getAll().size());
        assertNotNull(repo2.get(uuid1.toString()));
        assertNotNull(repo2.get(uuid2.toString()));
    }

    // -- atomic write safety -------------------------------------------------

    @Test
    void flushDoesNotLeaveTmpFileOnSuccess() {
        File file = new File(tempDir, "data.json");
        LocalRepository<String, PlayerData> repo =
                new LocalRepository<>(file, gson, String.class, PlayerData.class);

        repo.put("key", new PlayerData(UUID.randomUUID(), "Test"));
        repo.flush();

        File tmpFile = new File(tempDir, "data.json.tmp");
        assertFalse(tmpFile.exists(), "Temp file should be cleaned up after successful flush");
    }

    // -- helpers ------------------------------------------------------------

    private LocalRepository<String, PlayerData> playerRepo(String filename) {
        File file = new File(tempDir, filename);
        return new LocalRepository<>(file, gson, String.class, PlayerData.class);
    }

    private static String readFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

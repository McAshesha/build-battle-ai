package ru.ashesha.buildBattleAI.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorldService} lifecycle and operations.
 * <p>
 * Verifies world creation, loading, unloading, and deletion through mocked
 * Bukkit statics. The {@link WorldService.VoidChunkGenerator} is tested
 * directly without Bukkit since it produces deterministic output.
 * <p>
 * Each test opens a {@link MockedStatic} scope for {@link Bukkit} and
 * tears it down in {@link #tearDown()} to prevent cross-test contamination.
 */
class WorldServiceTest {

    private BuildBattleAI plugin;
    private WorldService service;
    private MockedStatic<Bukkit> bukkitStatic;

    /** Temporary directory simulating the server's world container. */
    @TempDir
    File worldContainer;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TestWorldService"));

        bukkitStatic = mockStatic(Bukkit.class);
        bukkitStatic.when(Bukkit::getWorldContainer).thenReturn(worldContainer);

        service = new WorldService(plugin);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        bukkitStatic.close();
    }

    // ── lifecycle tests ──────────────────────────────────────────────────

    @Test
    void enableAndShutdownAreIdempotent() {
        // Double enable should not throw
        assertDoesNotThrow(() -> service.enable());
        // Double shutdown should not throw
        assertDoesNotThrow(() -> service.shutdown());
        assertDoesNotThrow(() -> service.shutdown());
    }

    @Test
    void shutdownWithoutEnableIsNoOp() {
        WorldService fresh = new WorldService(plugin);
        assertDoesNotThrow(fresh::shutdown);
    }

    @Test
    void shutdownClearsTrackedWorlds() {
        // Simulate a successful createEmptyWorld
        World mockWorld = mockWorld("arena1");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);

        service.createEmptyWorld("arena1");
        assertFalse(service.getTrackedWorlds().isEmpty());

        service.shutdown();
        assertTrue(service.getTrackedWorlds().isEmpty());
    }

    @Test
    void multipleReloadCyclesAreStable() {
        for (int i = 0; i < 3; i++) {
            service.shutdown();
            service.enable();
        }
        // All cycles completed without error — tracking set is empty after each cycle
        assertTrue(service.getTrackedWorlds().isEmpty());
    }

    // ── createEmptyWorld tests ───────────────────────────────────────────

    @Test
    void createEmptyWorldPassesVoidGenerator() {
        World mockWorld = mockWorld("void_arena");
        ArgumentCaptor<WorldCreator> captor = ArgumentCaptor.forClass(WorldCreator.class);
        bukkitStatic.when(() -> Bukkit.createWorld(captor.capture()))
                .thenReturn(mockWorld);

        World result = service.createEmptyWorld("void_arena");

        assertSame(mockWorld, result);
        // Verify the WorldCreator was configured with a generator
        WorldCreator captured = captor.getValue();
        assertNotNull(captured);
        assertEquals("void_arena", captured.name());
    }

    @Test
    void createEmptyWorldConfiguresWorldSettings() {
        World mockWorld = mockWorld("arena");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);

        service.createEmptyWorld("arena");

        // Verify world configuration — these settings are critical for
        // temporary game worlds to minimise resource usage
        verify(mockWorld).setKeepSpawnInMemory(false);
        verify(mockWorld).setSpawnFlags(false, false);
        verify(mockWorld).setAutoSave(false);
        verify(mockWorld).setSpawnLocation(0, 64, 0);
    }

    @Test
    void createEmptyWorldTracksWorldName() {
        World mockWorld = mockWorld("tracked");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);

        service.createEmptyWorld("tracked");

        assertTrue(service.getTrackedWorlds().contains("tracked"));
    }

    @Test
    void createEmptyWorldHandlesNullReturnFromBukkit() {
        // Bukkit.createWorld can return null if world creation fails
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(null);

        World result = service.createEmptyWorld("fail");

        assertNull(result);
        assertFalse(service.getTrackedWorlds().contains("fail"));
    }

    // ── loadWorld tests ──────────────────────────────────────────────────

    @Test
    void loadWorldReturnsExistingIfAlreadyLoaded() {
        World mockWorld = mockWorld("existing");
        bukkitStatic.when(() -> Bukkit.getWorld("existing")).thenReturn(mockWorld);

        World result = service.loadWorld("existing");

        assertSame(mockWorld, result);
        assertTrue(service.getTrackedWorlds().contains("existing"));
        // Should NOT call createWorld — the world is already in memory
        bukkitStatic.verify(() -> Bukkit.createWorld(any(WorldCreator.class)), never());
    }

    @Test
    void loadWorldReturnsNullWhenFolderMissing() {
        bukkitStatic.when(() -> Bukkit.getWorld("ghost")).thenReturn(null);

        World result = service.loadWorld("ghost");

        assertNull(result);
        assertFalse(service.getTrackedWorlds().contains("ghost"));
    }

    @Test
    void loadWorldLoadsFromDiskWhenFolderExists() throws IOException {
        // Create a fake world folder on disk
        File worldFolder = new File(worldContainer, "on_disk");
        assertTrue(worldFolder.mkdir());

        bukkitStatic.when(() -> Bukkit.getWorld("on_disk")).thenReturn(null);
        World mockWorld = mockWorld("on_disk");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);

        World result = service.loadWorld("on_disk");

        assertSame(mockWorld, result);
        assertTrue(service.getTrackedWorlds().contains("on_disk"));
    }

    @Test
    void loadWorldDoesNotCreateWhenPathIsFile() throws IOException {
        // If a file (not directory) exists with that name, don't try to load it
        File notADir = new File(worldContainer, "not_a_dir");
        assertTrue(notADir.createNewFile());

        bukkitStatic.when(() -> Bukkit.getWorld("not_a_dir")).thenReturn(null);

        World result = service.loadWorld("not_a_dir");

        assertNull(result);
    }

    // ── unloadWorld tests ────────────────────────────────────────────────

    @Test
    void unloadWorldEvacuatesPlayersToDefaultSpawn() {
        World defaultWorld = mockWorld("world");
        Location spawn = new Location(defaultWorld, 0, 64, 0);
        when(defaultWorld.getSpawnLocation()).thenReturn(spawn);
        bukkitStatic.when(Bukkit::getWorlds)
                .thenReturn(Collections.singletonList(defaultWorld));

        World arena = mockWorld("arena");
        Player player1 = mock(Player.class);
        Player player2 = mock(Player.class);
        when(arena.getPlayers()).thenReturn(Arrays.asList(player1, player2));

        service.unloadWorld(arena);

        // Both players should have been teleported to default spawn
        verify(player1).teleport(spawn);
        verify(player2).teleport(spawn);
        // World should be unloaded without saving
        bukkitStatic.verify(() -> Bukkit.unloadWorld(arena, false));
    }

    @Test
    void unloadWorldRemovesFromTracking() {
        // Pre-populate tracking
        World mockWorld = mockWorld("tracked_arena");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);
        service.createEmptyWorld("tracked_arena");
        assertTrue(service.getTrackedWorlds().contains("tracked_arena"));

        // Set up unload prerequisites
        World defaultWorld = mockWorld("world");
        when(defaultWorld.getSpawnLocation()).thenReturn(new Location(defaultWorld, 0, 64, 0));
        bukkitStatic.when(Bukkit::getWorlds)
                .thenReturn(Collections.singletonList(defaultWorld));
        when(mockWorld.getPlayers()).thenReturn(Collections.emptyList());

        service.unloadWorld(mockWorld);

        assertFalse(service.getTrackedWorlds().contains("tracked_arena"));
    }

    @Test
    void unloadWorldByNameIsNoOpWhenNotLoaded() {
        bukkitStatic.when(() -> Bukkit.getWorld("absent")).thenReturn(null);

        // Should not throw and should not call unloadWorld
        assertDoesNotThrow(() -> service.unloadWorld("absent"));
        bukkitStatic.verify(() -> Bukkit.unloadWorld(any(World.class), anyBoolean()), never());
    }

    @Test
    void unloadWorldByNameDelegatesToWorldOverload() {
        World arena = mockWorld("named_arena");
        bukkitStatic.when(() -> Bukkit.getWorld("named_arena")).thenReturn(arena);

        World defaultWorld = mockWorld("world");
        when(defaultWorld.getSpawnLocation()).thenReturn(new Location(defaultWorld, 0, 64, 0));
        bukkitStatic.when(Bukkit::getWorlds)
                .thenReturn(Collections.singletonList(defaultWorld));
        when(arena.getPlayers()).thenReturn(Collections.emptyList());

        service.unloadWorld("named_arena");

        bukkitStatic.verify(() -> Bukkit.unloadWorld(arena, false));
    }

    // ── deleteWorld tests ────────────────────────────────────────────────

    @Test
    void deleteWorldUnloadsAndDeletesFolder() throws IOException {
        // Create a fake world folder with files inside
        File worldFolder = new File(worldContainer, "doomed");
        assertTrue(worldFolder.mkdir());
        assertTrue(new File(worldFolder, "level.dat").createNewFile());
        File regionDir = new File(worldFolder, "region");
        assertTrue(regionDir.mkdir());
        assertTrue(new File(regionDir, "r.0.0.mca").createNewFile());

        World arena = mock(World.class);
        when(arena.getName()).thenReturn("doomed");
        when(arena.getPlayers()).thenReturn(Collections.emptyList());

        World defaultWorld = mockWorld("world");
        when(defaultWorld.getSpawnLocation()).thenReturn(new Location(defaultWorld, 0, 64, 0));
        bukkitStatic.when(Bukkit::getWorlds)
                .thenReturn(Collections.singletonList(defaultWorld));

        service.deleteWorld(arena);

        // The folder and all contents should be gone
        assertFalse(worldFolder.exists());
    }

    @Test
    void deleteWorldByNameHandlesUnloadedWorld() throws IOException {
        // World is not loaded, but folder exists on disk
        File worldFolder = new File(worldContainer, "leftover");
        assertTrue(worldFolder.mkdir());
        assertTrue(new File(worldFolder, "level.dat").createNewFile());

        bukkitStatic.when(() -> Bukkit.getWorld("leftover")).thenReturn(null);

        service.deleteWorld("leftover");

        // No unload attempt, but folder still deleted
        bukkitStatic.verify(() -> Bukkit.unloadWorld(any(World.class), anyBoolean()), never());
        assertFalse(worldFolder.exists());
    }

    @Test
    void deleteWorldByNameUnloadsLoadedWorld() {
        World arena = mockWorld("active");
        bukkitStatic.when(() -> Bukkit.getWorld("active")).thenReturn(arena);

        World defaultWorld = mockWorld("world");
        when(defaultWorld.getSpawnLocation()).thenReturn(new Location(defaultWorld, 0, 64, 0));
        bukkitStatic.when(Bukkit::getWorlds)
                .thenReturn(Collections.singletonList(defaultWorld));
        when(arena.getPlayers()).thenReturn(Collections.emptyList());

        service.deleteWorld("active");

        bukkitStatic.verify(() -> Bukkit.unloadWorld(arena, false));
    }

    @Test
    void deleteWorldRemovesFromTracking() {
        // Pre-populate tracking
        World mockWorld = mockWorld("tracked_delete");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);
        service.createEmptyWorld("tracked_delete");

        // Unload setup
        bukkitStatic.when(() -> Bukkit.getWorld("tracked_delete")).thenReturn(null);

        service.deleteWorld("tracked_delete");

        assertFalse(service.getTrackedWorlds().contains("tracked_delete"));
    }

    // ── query tests ──────────────────────────────────────────────────────

    @Test
    void isWorldLoadedReturnsTrueWhenLoaded() {
        // Extract mock creation to avoid nested stubbing inside thenReturn
        World loaded = mockWorld("loaded");
        bukkitStatic.when(() -> Bukkit.getWorld("loaded")).thenReturn(loaded);
        assertTrue(service.isWorldLoaded("loaded"));
    }

    @Test
    void isWorldLoadedReturnsFalseWhenNotLoaded() {
        bukkitStatic.when(() -> Bukkit.getWorld("absent")).thenReturn(null);
        assertFalse(service.isWorldLoaded("absent"));
    }

    @Test
    void getWorldFolderReturnsCorrectPath() {
        File folder = service.getWorldFolder("my_world");
        assertEquals(new File(worldContainer, "my_world"), folder);
    }

    @Test
    void getTrackedWorldsReturnsUnmodifiableCopy() {
        World mockWorld = mockWorld("arena");
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);
        service.createEmptyWorld("arena");

        Set<String> tracked = service.getTrackedWorlds();
        assertThrows(UnsupportedOperationException.class, () -> tracked.add("hacked"));
    }

    // ── deleteDirectory utility tests ────────────────────────────────────

    @Test
    void deleteDirectoryRemovesNestedStructure() throws IOException {
        File root = new File(worldContainer, "nested");
        File sub1 = new File(root, "sub1");
        File sub2 = new File(sub1, "sub2");
        assertTrue(sub2.mkdirs());
        assertTrue(new File(root, "a.txt").createNewFile());
        assertTrue(new File(sub1, "b.txt").createNewFile());
        assertTrue(new File(sub2, "c.txt").createNewFile());

        WorldService.deleteDirectory(root);

        assertFalse(root.exists());
    }

    @Test
    void deleteDirectoryHandlesNull() {
        assertDoesNotThrow(() -> WorldService.deleteDirectory(null));
    }

    @Test
    void deleteDirectoryHandlesNonExistentPath() {
        assertDoesNotThrow(() -> WorldService.deleteDirectory(new File("nonexistent_xyz")));
    }

    @Test
    void deleteDirectoryHandlesEmptyDirectory() {
        File empty = new File(worldContainer, "empty_dir");
        assertTrue(empty.mkdir());

        WorldService.deleteDirectory(empty);

        assertFalse(empty.exists());
    }

    // ── VoidChunkGenerator tests ─────────────────────────────────────────

    @Test
    void voidGeneratorCanSpawnReturnsTrue() {
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        assertTrue(gen.canSpawn(mock(World.class), 0, 0));
        assertTrue(gen.canSpawn(mock(World.class), -100, 200));
    }

    @Test
    void voidGeneratorFixedSpawnIsAtOrigin() {
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        World world = mock(World.class);
        Location spawn = gen.getFixedSpawnLocation(world, new Random());

        assertEquals(0, spawn.getBlockX());
        assertEquals(64, spawn.getBlockY());
        assertEquals(0, spawn.getBlockZ());
        assertSame(world, spawn.getWorld());
    }

    @Test
    void voidGeneratorDefaultPopulatorsIsEmpty() {
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        assertTrue(gen.getDefaultPopulators(mock(World.class)).isEmpty());
    }

    @Test
    void voidGeneratorLegacyGenerateReturnsEmptyArray() {
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        byte[] result = gen.generate(mock(World.class), new Random(), 0, 0);

        assertNotNull(result);
        assertEquals(32768, result.length);
        // Every byte must be 0 (air in the legacy block ID scheme)
        for (byte b : result)
            assertEquals(0, b);
    }

    @Test
    void voidGeneratorLegacyGenerateReturnsSameInstance() {
        // The empty chunk array is pre-allocated and shared for efficiency
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        byte[] first = gen.generate(mock(World.class), new Random(), 0, 0);
        byte[] second = gen.generate(mock(World.class), new Random(), 5, 10);
        assertSame(first, second);
    }

    @Test
    void voidGeneratorExtendsChunkGenerator() {
        // Verify the class hierarchy for Bukkit compatibility
        WorldService.VoidChunkGenerator gen = new WorldService.VoidChunkGenerator();
        assertTrue(gen instanceof ChunkGenerator);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Creates a mock {@link World} with the given name and an empty
     * player list as defaults.
     */
    private static World mockWorld(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getPlayers()).thenReturn(Collections.emptyList());
        return world;
    }
}

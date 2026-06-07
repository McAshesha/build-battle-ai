package ru.ashesha.buildBattleAI.arena;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.world.WorldService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests covering arena deletion atomicity (ARENA-06) and
 * idempotent world creation (WORLD-01).
 *
 * <h2>Risk ARENA-06 — Deletion atomicity</h2>
 * <p>Invariant: {@code deleteArena(name)} must remove the arena from the
 * in-memory registry, call {@link BBAIWorldService#deleteWorld(String)} on
 * its world, and call {@link BBAIConfigService#deleteArenaConfig(String)},
 * in that order (world first, then config). If any step is skipped, a server
 * restart leaves orphaned world folders or stale config entries that block
 * re-creation.
 *
 * <h2>Risk WORLD-01 — Idempotent create</h2>
 * <p>Invariant: calling {@link WorldService#createEmptyWorld(String)} twice
 * with the same name must add the name to {@code trackedWorlds} exactly once
 * (a Set, never a list). Both calls delegate to {@link Bukkit#createWorld};
 * the server returns the same world instance on the second call, but the
 * tracking set must not accumulate duplicates.
 *
 * <p>Test tier: {@code integration} — exercises real {@link ArenaManager} and
 * {@link WorldService} implementations with mocked Bukkit statics.
 */
@Tag("integration")
class ArenaDeletionAtomicityIT {

    // ── shared fixtures ────────────────────────────────────────────────

    private BuildBattleAI plugin;
    private PluginContext context;
    private BBAIConfigService configService;

    /** Temporary directory simulating the server's world container. */
    @TempDir
    File worldContainer;

    /** MockedStatic for Bukkit — opened per-test, closed in tearDown. */
    private MockedStatic<Bukkit> bukkitStatic;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        context = mock(PluginContext.class);
        configService = mock(BBAIConfigService.class);

        PluginLogger logger = new PluginLogger(Logger.getLogger("ArenaDeletionAtomicityIT"));
        when(plugin.getPluginLogger()).thenReturn(logger);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ArenaDeletionAtomicityIT"));
        when(plugin.getContext()).thenReturn(context);
        when(context.getConfigService()).thenReturn(configService);

        // paper-api 1.21+ calls Bukkit.getUnsafe().getMainLevelName() from
        // inside the WorldCreator constructor — stub to avoid NPE.
        UnsafeValues mockUnsafe = mock(UnsafeValues.class);
        when(mockUnsafe.getMainLevelName()).thenReturn("world");

        bukkitStatic = mockStatic(Bukkit.class);
        bukkitStatic.when(Bukkit::getWorldContainer).thenReturn(worldContainer);
        bukkitStatic.when(Bukkit::getUnsafe).thenReturn(mockUnsafe);
    }

    @AfterEach
    void tearDown() {
        bukkitStatic.close();
    }

    // ── ARENA-06: deletion atomicity ───────────────────────────────────

    /**
     * ARENA-06: {@code deleteArena} must remove the arena from the internal
     * {@code arenas} map, call {@code worldService.deleteWorld(worldName)},
     * and call {@code configService.deleteArenaConfig(name)}.
     *
     * <p>The world-delete is issued before the config-delete so that a crash
     * between the two leaves a recoverable state (orphaned world folder with
     * no config is harmless on next start; orphaned config with no folder
     * risks re-loading a broken arena).
     *
     * <p>Player evacuation is delegated entirely to {@link BBAIWorldService#deleteWorld};
     * {@link ArenaManager#deleteArena} itself has no explicit player-kick step —
     * this is intentional. The contract is verified here at the call-delegation
     * level: if {@code deleteWorld} is called, the service implementation
     * handles evacuation.
     */
    @Test
    void deletionFullyCleansUp() throws Exception {
        // Wire a real ArenaManager with mocked world / config services so we
        // can verify call order without touching a live server.
        BBAIWorldService worldService = mock(BBAIWorldService.class);
        when(context.getWorldService()).thenReturn(worldService);
        when(configService.getArenaNames()).thenReturn(Collections.singleton("arena1"));
        when(configService.getArenaConfig("arena1"))
                .thenReturn(ArenaManagerTest.buildValidArenaConfig("bbai_arena1", 2, true));

        ArenaManager manager = new ArenaManager(plugin);
        manager.enable();

        // Precondition: arena is present in the registry.
        assertNotNull(manager.getArena("arena1"),
                "Precondition: arena must be loaded before deletion");

        // Exercise: delete the arena.
        manager.deleteArena("arena1");

        // Assert 1: arena is gone from the in-memory registry.
        assertNull(manager.getArena("arena1"),
                "ARENA-06: arena must be removed from registry after deleteArena");

        // Assert 2: verify call order — world deleted before config wiped.
        InOrder order = inOrder(worldService, configService);
        order.verify(worldService).deleteWorld("bbai_arena1");
        order.verify(configService).deleteArenaConfig("arena1");

        // Assert 3: the internal arenas map no longer contains the key
        // (belt-and-suspenders — getArena already checks this, but explicit
        // map inspection rules out any caching layer).
        Map<?, ?> arenas = reflectArenasMap(manager);
        assertFalse(arenas.containsKey("arena1"),
                "ARENA-06: internal arenas map must not contain arena1 after deletion");
    }

    // ── WORLD-01: idempotent createEmptyWorld ──────────────────────────

    /**
     * WORLD-01: calling {@link WorldService#createEmptyWorld(String)} twice
     * with the same name must leave exactly one entry in {@code trackedWorlds}.
     *
     * <p>The backing store is a {@code ConcurrentHashMap.newKeySet()} (a
     * {@link java.util.Set}), so duplicate {@code add} calls are silently
     * deduplicated. This test asserts that invariant holds even though
     * {@link Bukkit#createWorld} is called twice (the server de-dupes at its
     * level and returns the same world instance on the second call).
     */
    @Test
    void createIsIdempotent() throws Exception {
        // Prepare a WorldService backed by a mocked Bukkit.createWorld.
        WorldService worldService = new WorldService(plugin);
        worldService.enable();

        World mockWorld = mock(World.class);
        when(mockWorld.getName()).thenReturn("bbai_test");
        when(mockWorld.getPlayers()).thenReturn(Collections.<org.bukkit.entity.Player>emptyList());

        // Bukkit returns the same world instance on both calls, mimicking
        // the real server's deduplication behaviour.
        bukkitStatic.when(() -> Bukkit.createWorld(any(WorldCreator.class)))
                .thenReturn(mockWorld);

        // First call — world does not yet exist.
        World first = worldService.createEmptyWorld("bbai_test");

        // Second call — simulates a duplicate invocation (e.g. a reload bug).
        World second = worldService.createEmptyWorld("bbai_test");

        // Both calls return a non-null world.
        assertNotNull(first, "WORLD-01: first createEmptyWorld must return non-null");
        assertNotNull(second, "WORLD-01: second createEmptyWorld must return non-null");

        // The tracked set must contain exactly one entry.
        Set<String> tracked = worldService.getTrackedWorlds();
        assertEquals(1, tracked.size(),
                "WORLD-01: trackedWorlds must contain exactly one entry after two identical creates");
        assertTrue(tracked.contains("bbai_test"),
                "WORLD-01: trackedWorlds must contain 'bbai_test'");

        // Confirm Bukkit.createWorld was invoked twice — one per call (no
        // idempotency guard in WorldService itself; deduplication happens
        // at the Set level for tracking, and at the server for the world).
        bukkitStatic.verify(() -> Bukkit.createWorld(any(WorldCreator.class)), times(2));

        worldService.shutdown();
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Reflectively extracts the private {@code arenas} map from an
     * {@link ArenaManager} instance for white-box verification.
     *
     * @param manager the manager to inspect
     * @return the live {@code arenas} map
     * @throws Exception if reflection fails
     */
    private static Map<?, ?> reflectArenasMap(ArenaManager manager) throws Exception {
        Field field = ArenaManager.class.getDeclaredField("arenas");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(manager);
    }
}

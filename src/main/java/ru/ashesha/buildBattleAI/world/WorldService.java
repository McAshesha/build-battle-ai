package ru.ashesha.buildBattleAI.world;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link BBAIWorldService}.
 * <p>
 * Manages dynamic world creation, loading, unloading, and deletion for
 * the plugin. Uses a stateless {@link VoidChunkGenerator} to produce
 * completely empty (air-only) worlds suitable for build-battle arenas.
 * <p>
 * The generator supports both the modern {@code ChunkData}-based API
 * (Spigot 1.8+) and the legacy {@code byte[]}-based API (pre-1.8
 * fallback) to ensure compatibility across all supported server versions
 * (1.8 through 1.21.x).
 * <p>
 * All Bukkit world operations are synchronous and must be invoked from
 * the main server thread. The service itself is stateless apart from
 * the tracking set — no thread pools, connections, or scheduled tasks
 * are opened.
 * <p>
 * Implements both {@link BBAIWorldService} (the public API contract) and
 * {@link PluginService} (the internal lifecycle contract) independently,
 * so external consumers cannot reach lifecycle methods through the API type.
 */
@RequiredArgsConstructor
public class WorldService implements BBAIWorldService, PluginService {

    /**
     * The plugin instance, kept for future config or logging access.
     */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Stateless chunk generator that produces completely empty (void)
     * chunks. Shared across all world creations — no per-world state.
     */
    private static final VoidChunkGenerator VOID_GENERATOR = new VoidChunkGenerator();

    /**
     * Names of worlds created or loaded through this service during the
     * current lifecycle. Thread-safe via {@link ConcurrentHashMap} backing.
     * Cleared on {@link #shutdown()}.
     */
    private final Set<String> trackedWorlds = ConcurrentHashMap.newKeySet();

    // ── lifecycle ────────────────────────────────────────────────────────

    /**
     * Starts the world service.
     * <p>
     * No runtime resources are allocated — the void generator is a static
     * singleton and the tracking set is thread-safe. This method exists
     * solely to satisfy the {@link PluginService} contract.
     */
    @Override
    public void enable() {
        plugin.getPluginLogger().info("WorldService enabled.");
    }

    /**
     * Shuts down the world service by clearing the tracking set.
     * <p>
     * Does <b>not</b> unload or delete any worlds — cleanup of temporary
     * game worlds is the responsibility of the caller (arena manager,
     * game manager, etc.).
     */
    @Override
    public void shutdown() {
        if (!trackedWorlds.isEmpty())
            plugin.getPluginLogger().debug("WorldService clearing %d tracked world(s).", trackedWorlds.size());
        trackedWorlds.clear();
    }

    // ── world creation ───────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * The world is created using a {@link VoidChunkGenerator} that emits
     * completely empty chunks — no blocks, no structures, no populators.
     * The resulting world has mob spawning disabled, auto-save off, and
     * spawn chunks not kept in memory to minimise resource usage.
     */
    @Override
    public World createEmptyWorld(@NonNull String name) {
        PluginLogger log = plugin.getPluginLogger();
        log.debug("Creating empty void world '%s'.", name);

        World world = Bukkit.createWorld(
                new WorldCreator(name).generator(VOID_GENERATOR)
        );

        if (world != null) {
            // Disable features that are unnecessary for temporary game worlds
            world.setKeepSpawnInMemory(false);
            world.setSpawnFlags(false, false);
            world.setAutoSave(false);
            world.setSpawnLocation(0, 64, 0);
            trackedWorlds.add(name);
            log.info("Created empty world '%s'.", name);
        } else
            log.warn("Bukkit refused to create world '%s'.", name);

        return world;
    }

    // ── world loading ────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * Uses {@link Bukkit#createWorld(WorldCreator)} with the default
     * generator, which loads an existing world folder from disk without
     * overwriting its data. If the world is already in memory, the
     * existing instance is returned directly.
     */
    @Override
    public World loadWorld(@NonNull String name) {
        PluginLogger log = plugin.getPluginLogger();

        // Fast path: world is already loaded — return immediately
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            log.debug("World '%s' is already loaded, returning existing instance.", name);
            trackedWorlds.add(name);
            return existing;
        }

        // Guard: do not accidentally create a new world when the folder
        // is missing — Bukkit.createWorld would generate terrain in that case
        File worldFolder = getWorldFolder(name);
        if (!worldFolder.exists() || !worldFolder.isDirectory()) {
            log.debug("World folder for '%s' does not exist, skipping load.", name);
            return null;
        }

        log.debug("Loading world '%s' from disk.", name);
        World world = Bukkit.createWorld(new WorldCreator(name));
        if (world != null) {
            trackedWorlds.add(name);
            log.info("Loaded world '%s' from disk.", name);
        } else
            log.warn("Bukkit failed to load world '%s'.", name);

        return world;
    }

    // ── world unloading ──────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * Players are teleported to the default world's (index 0) spawn
     * location before the unload is attempted. The unload is performed
     * without saving ({@code save = false}) since game worlds are
     * considered disposable.
     */
    @Override
    public void unloadWorld(@NonNull World world) {
        PluginLogger log = plugin.getPluginLogger();
        String worldName = world.getName();

        // Evacuate all players to the default world's spawn
        World defaultWorld = Bukkit.getWorlds().get(0);
        Location spawn = defaultWorld.getSpawnLocation();
        int playerCount = world.getPlayers().size();
        if (playerCount > 0)
            log.debug("Evacuating %d player(s) from world '%s'.", playerCount, worldName);
        for (Player player : world.getPlayers())
            player.teleport(spawn);

        Bukkit.unloadWorld(world, false);
        trackedWorlds.remove(worldName);
        log.info("Unloaded world '%s'.", worldName);
    }

    /** {@inheritDoc} */
    @Override
    public void unloadWorld(@NonNull String name) {
        World world = Bukkit.getWorld(name);
        if (world != null)
            unloadWorld(world);
    }

    // ── world deletion ───────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to {@link #unloadWorld(World)} first, then recursively
     * deletes the world folder via {@link #deleteDirectory(File)}.
     */
    @Override
    public void deleteWorld(@NonNull World world) {
        String name = world.getName();
        unloadWorld(world);
        deleteDirectory(getWorldFolder(name));
    }

    /**
     * {@inheritDoc}
     * <p>
     * If the world is currently loaded, it is unloaded first. Either way,
     * the world folder is deleted from disk afterwards.
     */
    @Override
    public void deleteWorld(@NonNull String name) {
        World world = Bukkit.getWorld(name);
        if (world != null)
            unloadWorld(world);

        File folder = getWorldFolder(name);
        deleteDirectory(folder);
        trackedWorlds.remove(name);
        plugin.getPluginLogger().info("Deleted world '%s' (folder: %s).", name, folder.getAbsolutePath());
    }

    // ── queries ──────────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public boolean isWorldLoaded(@NonNull String name) {
        return Bukkit.getWorld(name) != null;
    }

    /** {@inheritDoc} */
    @Override
    public File getWorldFolder(@NonNull String name) {
        return new File(Bukkit.getWorldContainer(), name);
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getTrackedWorlds() {
        return Collections.unmodifiableSet(new HashSet<>(trackedWorlds));
    }

    // ── internal helpers ─────────────────────────────────────────────────

    /**
     * Recursively deletes a directory and all of its contents.
     * <p>
     * Silently ignores errors on individual files — best-effort deletion
     * is acceptable for temporary game world folders. Uses a plain
     * recursive approach instead of {@code java.nio.file.Files.walk()}
     * to stay compatible with restrictive security managers on some hosts.
     *
     * @param dir the directory to delete (may be {@code null} or absent)
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists())
            return;

        File[] children = dir.listFiles();
        if (children != null)
            for (File child : children) {
                if (child.isDirectory())
                    deleteDirectory(child);
                else
                    child.delete();
            }

        dir.delete();
    }

    // ── void chunk generator ─────────────────────────────────────────────

    /**
     * A {@link ChunkGenerator} that produces completely empty (air-only) chunks.
     * <p>
     * Multi-version compatible: overrides both the modern {@code ChunkData}-based
     * {@link #generateChunkData} method (Spigot 1.8+) and the legacy
     * {@code byte[]}-based {@link #generate} method (pre-1.8 fallback). On any
     * supported server version, at least one of the two will be invoked by Bukkit
     * to produce chunk terrain data.
     * <p>
     * The generator is completely stateless — a single instance is shared
     * across all void world creations.
     */
    static class VoidChunkGenerator extends ChunkGenerator {

        /**
         * Pre-allocated empty byte array for the legacy {@link #generate}
         * method. 32768 bytes = 16 * 128 * 16 (one chunk section worth of
         * block IDs in the old format). All zeros = all air.
         */
        private static final byte[] EMPTY_CHUNK = new byte[32768];

        /**
         * Allows players to spawn at any position in a void world.
         *
         * @param world the world
         * @param x     chunk X
         * @param z     chunk Z
         * @return always {@code true}
         */
        @Override
        public boolean canSpawn(@NonNull World world, int x, int z) {
            return true;
        }

        /**
         * Returns a fixed spawn location so Bukkit does not attempt to
         * find a "safe" spawn on non-existent terrain.
         *
         * @param world  the world
         * @param random unused
         * @return spawn at {@code (0, 64, 0)}
         */
        @Override
        public Location getFixedSpawnLocation(@NonNull World world, @NonNull Random random) {
            return new Location(world, 0, 64, 0);
        }

        /**
         * Modern chunk generation (Spigot 1.8+). Returns an empty
         * {@link ChunkData} — all blocks default to air.
         *
         * @param world  the world
         * @param random unused
         * @param x      chunk X
         * @param z      chunk Z
         * @param biome  biome grid (unused — default biomes are fine)
         * @return empty chunk data
         */
        @SuppressWarnings("deprecation")
        @Override
        public ChunkData generateChunkData(@NonNull World world, @NonNull Random random,
                                           int x, int z, @NonNull BiomeGrid biome) {
            // createChunkData returns air-filled data by default
            return createChunkData(world);
        }

        /**
         * Suppresses all block populators (trees, ores, structures, etc.).
         *
         * @param world the world
         * @return empty list — no populators
         */
        @Override
        @NonNull
        public List<BlockPopulator> getDefaultPopulators(@NonNull World world) {
            return Collections.emptyList();
        }

        /**
         * Legacy chunk generation (pre-1.8 servers). Returns a pre-allocated
         * zero-filled byte array — all block IDs are 0 (air).
         * <p>
         * This method does not carry {@code @Override} because the
         * {@code byte[]} signature was removed from modern Spigot APIs.
         * On servers that still call it, the method is found by polymorphic
         * dispatch; on newer servers it is simply ignored.
         *
         * @param world  the world
         * @param random unused
         * @param x      chunk X
         * @param z      chunk Z
         * @return zero-filled byte array representing an air-only chunk
         */
        @SuppressWarnings("deprecation")
        public byte[] generate(World world, Random random, int x, int z) {
            return EMPTY_CHUNK;
        }
    }
}

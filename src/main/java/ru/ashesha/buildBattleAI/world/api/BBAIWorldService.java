package ru.ashesha.buildBattleAI.world.api;

import org.bukkit.World;

import java.io.File;
import java.util.Set;

/**
 * Service contract for dynamic world management.
 * <p>
 * Provides operations for creating empty (void) worlds, loading existing
 * worlds from disk, unloading worlds from memory, and deleting world
 * directories from the file system. All Bukkit world operations are
 * synchronous and <b>must be called from the main server thread</b>.
 * <p>
 * The service maintains a tracking set of world names it has created or
 * loaded during the current lifecycle. This tracking is informational —
 * callers (e.g. the arena manager) use it to discover which worlds belong
 * to the plugin. Worlds are <b>not</b> automatically unloaded or deleted
 * when the service shuts down; cleanup responsibility lies with the caller.
 * <p>
 * Multi-version: all operations are compatible with Spigot 1.8 through
 * 1.21.x. The underlying void chunk generator supports both the legacy
 * {@code byte[]} generation path and the modern {@code ChunkData} path.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the
 * internal {@link ru.ashesha.buildBattleAI.core.PluginService} contract.
 */
public interface BBAIWorldService {

    // ── world creation ───────────────────────────────────────────────────

    /**
     * Creates a new empty (void) world with the given name.
     * <p>
     * The resulting world contains no terrain, no mobs, and has auto-save
     * disabled. Spawn is set to {@code (0, 64, 0)}. The world is added
     * to the tracking set automatically.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param name the world name (becomes the world folder name)
     * @return the created world, or {@code null} if Bukkit refused
     */
    World createEmptyWorld(String name);

    // ── world loading ────────────────────────────────────────────────────

    /**
     * Loads an existing world from disk by name.
     * <p>
     * If the world is already loaded in Bukkit, returns the existing
     * instance. If the world folder does not exist on disk, returns
     * {@code null} without creating anything. The world is added to the
     * tracking set on success.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param name the world folder name to load
     * @return the loaded world, or {@code null} if no such folder exists
     */
    World loadWorld(String name);

    // ── world unloading ──────────────────────────────────────────────────

    /**
     * Unloads a world from Bukkit without saving.
     * <p>
     * All players currently in the world are teleported to the default
     * world's spawn location before the unload. The world is removed
     * from the tracking set.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param world the world to unload
     */
    void unloadWorld(World world);

    /**
     * Unloads a world by name. If no world with the given name is
     * currently loaded, this method is a no-op.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param name the name of the world to unload
     */
    void unloadWorld(String name);

    // ── world deletion ───────────────────────────────────────────────────

    /**
     * Unloads the world and recursively deletes its folder from disk.
     * <p>
     * Players are evacuated and the world is unloaded first (see
     * {@link #unloadWorld(World)}), then the world directory and all of
     * its contents are permanently removed.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param world the world to delete
     */
    void deleteWorld(World world);

    /**
     * Deletes a world by name. If the world is currently loaded, it is
     * unloaded first. If only the folder exists (world not loaded), the
     * folder is deleted directly.
     * <p>
     * Must be called from the <b>main server thread</b>.
     *
     * @param name the name of the world to delete
     */
    void deleteWorld(String name);

    // ── queries ──────────────────────────────────────────────────────────

    /**
     * Checks whether a world with the given name is currently loaded
     * in Bukkit.
     *
     * @param name the world name to check
     * @return {@code true} if the world is loaded
     */
    boolean isWorldLoaded(String name);

    /**
     * Returns the expected folder for a world with the given name.
     * The folder may or may not exist on disk.
     *
     * @param name the world name
     * @return the world directory (relative to the server's world container)
     */
    File getWorldFolder(String name);

    /**
     * Returns an unmodifiable snapshot of world names that have been
     * created or loaded through this service during the current
     * lifecycle. The set is cleared on service shutdown.
     *
     * @return unmodifiable set of tracked world names
     */
    Set<String> getTrackedWorlds();
}

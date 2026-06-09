package ru.ashesha.buildBattleAI.data.local;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.data.DataRepository;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory repository backed by a single JSON file.
 * <p>
 * Uses a {@link ConcurrentHashMap} for lock-free concurrent reads (which
 * dominate the access pattern in a game server). Writes update the map
 * immediately (visible to all threads) but disk persistence is deferred
 * until an explicit {@link #flush()} call — the auto-save scheduler in
 * {@link ru.ashesha.buildBattleAI.data.DataService DataService} triggers these periodically.
 * <p>
 * The JSON file format is a map from string keys to value objects:
 * <pre>{@code
 * {
 *   "d4a3e2b1-...": { "uuid": "d4a3e2b1-...", "name": "Steve", "wins": 5, ... },
 *   "a1b2c3d4-...": { ... }
 * }
 * }</pre>
 * <p>
 * File writes are atomic: data is written to a {@code .tmp} file first,
 * then renamed over the original. This prevents corruption if the server
 * crashes mid-write.
 *
 * @param <K> key type (typically {@code String})
 * @param <V> value type (e.g. {@code PlayerData})
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class LocalRepository<K, V> implements DataRepository<K, V> {

    /** The JSON file this repository persists to. */
    @NonNull
    private final File file;

    /** Shared Gson instance (thread-safe, reusable). */
    @NonNull
    private final Gson gson;

    /** Runtime type of the key class, needed for Gson deserialization. */
    @NonNull
    private final Class<K> keyType;

    /** Runtime type of the value class, needed for Gson deserialization. */
    @NonNull
    private final Class<V> valueType;

    /** Logger used to report I/O failures to the server console. */
    @NonNull
    private final PluginLogger logger;

    /**
     * In-memory cache. ConcurrentHashMap provides lock-free reads and
     * atomic puts — no explicit synchronization needed for CRUD operations.
     */
    private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();

    /**
     * Tracks whether the in-memory state has diverged from the on-disk
     * state. Volatile ensures visibility across the main thread (writes)
     * and the auto-save thread (reads).
     */
    private volatile boolean dirty;

    /**
     * Loads existing data from the JSON file into the in-memory cache.
     * If the file does not exist or is empty, the cache starts empty.
     * Called once during provider startup.
     */
    void load() {
        if (!file.exists())
            return;
        try {
            InputStreamReader reader = new InputStreamReader(
                    Files.newInputStream(file.toPath()), StandardCharsets.UTF_8);
            Type mapType = TypeToken.getParameterized(Map.class, keyType, valueType).getType();
            Map<K, V> loaded = gson.fromJson(reader, mapType);
            reader.close();
            if (loaded != null)
                cache.putAll(loaded);
        } catch (Throwable e) {
            // Log but don't fail — start with empty cache on corrupt file
            logger.warn("Failed to load %s: %s", file.getName(), e.getMessage());
        }
    }

    @Override
    public V get(K key) {
        return cache.get(key);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
        dirty = true;
    }

    @Override
    public void remove(K key) {
        if (cache.remove(key) != null)
            dirty = true;
    }

    @Override
    public boolean containsKey(K key) {
        return cache.containsKey(key);
    }

    @Override
    public Collection<V> getAll() {
        return new ArrayList<>(cache.values());
    }

    /**
     * Writes the current cache snapshot to disk if any modifications
     * have occurred since the last flush. The write is atomic: data goes
     * to a temporary file first, then the temp file is renamed over the
     * original to prevent corruption on crash.
     * <p>
     * This method is synchronized to prevent concurrent flushes (e.g.
     * auto-save timer firing while the plugin is shutting down), but
     * reads and writes to the in-memory cache are never blocked.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public synchronized void flush() {
        if (!dirty)
            return;
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(temp.toPath()), StandardCharsets.UTF_8);
            // Snapshot the cache as a regular HashMap for clean JSON output
            gson.toJson(new LinkedHashMap<>(cache), writer);
            writer.flush();
            writer.close();

            // Atomic rename — prevents partial files on crash
            // Fallback for platforms where rename fails if target exists (Windows)
            if (!temp.renameTo(file) && file.delete())
                temp.renameTo(file);
            dirty = false;
        } catch (IOException e) {
            logger.error("Failed to save %s: %s", file.getName(), e.getMessage());
            // Clean up temp file on failure
            if (temp.exists())
                temp.delete();
        }
    }
}

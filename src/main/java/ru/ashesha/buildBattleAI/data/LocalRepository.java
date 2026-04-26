package ru.ashesha.buildBattleAI.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.NonNull;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory repository backed by a single JSON file.
 * <p>
 * Uses a {@link ConcurrentHashMap} for lock-free concurrent reads (which
 * dominate the access pattern in a game server). Writes update the map
 * immediately (visible to all threads) but disk persistence is deferred
 * until an explicit {@link #flush()} call — the auto-save scheduler in
 * {@link DataService} triggers these periodically.
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
class LocalRepository<K, V> implements DataRepository<K, V> {

    /** The JSON file this repository persists to. */
    private final File file;

    /** Shared Gson instance (thread-safe, reusable). */
    private final Gson gson;

    /** Runtime type of the key class, needed for Gson deserialization. */
    private final Class<K> keyType;

    /** Runtime type of the value class, needed for Gson deserialization. */
    private final Class<V> valueType;

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
     * Creates a local repository backed by the given JSON file.
     *
     * @param file      the JSON file to persist to (may not exist yet)
     * @param gson      the shared Gson instance
     * @param keyType   runtime key class
     * @param valueType runtime value class
     */
    LocalRepository(@NonNull File file, @NonNull Gson gson,
                    @NonNull Class<K> keyType, @NonNull Class<V> valueType) {
        this.file = file;
        this.gson = gson;
        this.keyType = keyType;
        this.valueType = valueType;
    }

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
                    new FileInputStream(file), StandardCharsets.UTF_8);
            Type mapType = TypeToken.getParameterized(Map.class, keyType, valueType).getType();
            Map<K, V> loaded = gson.fromJson(reader, mapType);
            reader.close();
            if (loaded != null)
                cache.putAll(loaded);
        } catch (Throwable e) {
            // Log but don't fail — start with empty cache on corrupt file
            System.err.println("[BuildBattleAI] Failed to load " + file.getName() + ": " + e.getMessage());
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
    @Override
    public synchronized void flush() {
        if (!dirty)
            return;
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(temp), StandardCharsets.UTF_8);
            // Snapshot the cache as a regular HashMap for clean JSON output
            gson.toJson(new LinkedHashMap<>(cache), writer);
            writer.flush();
            writer.close();

            // Atomic rename — prevents partial files on crash
            if (!temp.renameTo(file)) {
                // Fallback for platforms where rename fails if target exists (Windows)
                if (file.delete())
                    temp.renameTo(file);
            }
            dirty = false;
        } catch (IOException e) {
            System.err.println("[BuildBattleAI] Failed to save " + file.getName() + ": " + e.getMessage());
            // Clean up temp file on failure
            if (temp.exists())
                temp.delete();
        }
    }
}

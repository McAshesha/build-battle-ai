package ru.ashesha.buildBattleAI.data;

import java.util.Collection;

/**
 * Generic key-value repository used internally by {@link DataService}.
 * <p>
 * Not exposed in the public API — callers use the domain-specific methods
 * on {@link ru.ashesha.buildBattleAI.data.api.BBAIDataService}. The generic
 * interface avoids duplicating backend logic per data type: each
 * {@link DataProvider} implementation creates repositories of this type,
 * and {@link DataService} wraps them with domain-specific accessors.
 * <p>
 * All implementations must be thread-safe for concurrent reads.
 * Write ordering guarantees depend on the backend.
 *
 * @param <K> key type (typically {@code String})
 * @param <V> value type (e.g. {@code PlayerData}, {@code ArenaStats})
 */
public interface DataRepository<K, V> {

    /**
     * Retrieves a value by key.
     *
     * @param key the lookup key
     * @return the stored value, or {@code null} if not found
     */
    V get(K key);

    /**
     * Stores a key-value pair, replacing any existing entry.
     *
     * @param key   the key
     * @param value the value to store
     */
    void put(K key, V value);

    /**
     * Removes the entry for a key.
     *
     * @param key the key to remove
     */
    void remove(K key);

    /**
     * Checks whether a key exists in this repository.
     *
     * @param key the key to check
     * @return {@code true} if the key has an associated value
     */
    boolean containsKey(K key);

    /**
     * Returns a snapshot of all values in this repository.
     * The returned collection is not backed by the repository — changes
     * to the repository are not reflected in the returned collection.
     *
     * @return all stored values
     */
    Collection<V> getAll();

    /**
     * Writes pending changes to persistent storage. No-op for backends
     * that persist immediately (e.g. Ignite).
     */
    void flush();
}

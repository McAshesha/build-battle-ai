package ru.ashesha.buildBattleAI.data.ignite;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import ru.ashesha.buildBattleAI.data.DataRepository;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link DataRepository} backed by an {@link IgniteCache} for server and
 * thick-client node modes.
 * <p>
 * All operations delegate directly to the underlying cache, which handles
 * replication, persistence, and thread safety. {@link #flush()} is a no-op
 * because Ignite manages durability internally (WAL + checkpointing when
 * native persistence is enabled).
 * <p>
 * {@link #getAll()} uses a {@link ScanQuery} to iterate over all entries
 * in the cache without loading everything into memory at once.
 *
 * @param <K> key type
 * @param <V> value type
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class IgniteCacheRepository<K, V> implements DataRepository<K, V> {

    /** The underlying Ignite cache. */
    private final IgniteCache<K, V> cache;

    @Override
    public V get(K key) {
        return cache.get(key);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public void remove(K key) {
        cache.remove(key);
    }

    @Override
    public boolean containsKey(K key) {
        return cache.containsKey(key);
    }

    /**
     * Returns all values by scanning the entire cache. Uses a
     * {@link ScanQuery} which streams entries from the cluster
     * without requiring all data to fit in a single response.
     *
     * @return snapshot list of all values
     */
    @Override
    public Collection<V> getAll() {
        List<V> result = new ArrayList<>();
        try (QueryCursor<Cache.Entry<K, V>> cursor = cache.query(new ScanQuery<K, V>())) {
            for (Cache.Entry<K, V> entry : cursor)
                result.add(entry.getValue());
        }
        return result;
    }

    /** No-op — Ignite handles durability internally via WAL and checkpointing. */
    @Override
    public void flush() {
        // Ignite persists writes immediately; nothing to flush.
    }
}

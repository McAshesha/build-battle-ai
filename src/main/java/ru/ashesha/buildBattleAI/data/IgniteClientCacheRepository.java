package ru.ashesha.buildBattleAI.data;

import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.client.ClientCache;

import javax.cache.Cache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link DataRepository} backed by a {@link ClientCache} for thin-client
 * node mode.
 * <p>
 * Thin clients communicate with the Ignite cluster over a lightweight TCP
 * connection. All operations are forwarded to the remote cluster node that
 * owns the partition for the requested key. {@link #flush()} is a no-op
 * because durability is managed by the server nodes.
 * <p>
 * The {@link ClientCache} API mirrors {@link org.apache.ignite.IgniteCache}
 * but is a separate class hierarchy — hence this dedicated repository
 * implementation alongside {@link IgniteCacheRepository}.
 *
 * @param <K> key type
 * @param <V> value type
 */
class IgniteClientCacheRepository<K, V> implements DataRepository<K, V> {

    /** The underlying thin-client cache. */
    private final ClientCache<K, V> cache;

    /**
     * Creates a repository wrapping the given thin-client cache.
     *
     * @param cache the thin-client cache to delegate to
     */
    IgniteClientCacheRepository(ClientCache<K, V> cache) {
        this.cache = cache;
    }

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
     * Returns all values by scanning the remote cache via a
     * {@link ScanQuery}. The query is executed on the cluster; results
     * are streamed back to the thin client.
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

    /** No-op — durability is managed by the remote Ignite server nodes. */
    @Override
    public void flush() {
        // Thin client delegates durability to the cluster.
    }
}

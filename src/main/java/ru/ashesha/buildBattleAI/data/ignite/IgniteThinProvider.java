package ru.ashesha.buildBattleAI.data.ignite;

import lombok.RequiredArgsConstructor;
import org.apache.ignite.Ignition;
import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.ClientCacheConfiguration;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.ClientConfiguration;
import ru.ashesha.buildBattleAI.data.DataProvider;
import ru.ashesha.buildBattleAI.data.DataRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DataProvider} for Ignite <b>thin-client</b> mode.
 * <p>
 * Uses {@link Ignition#startClient(ClientConfiguration)} which returns an
 * {@link IgniteClient} — a lightweight TCP client that forwards all operations
 * to the remote Ignite cluster. Thin clients do not participate in cluster
 * discovery, do not store data locally, and have minimal memory/thread overhead.
 * <p>
 * Requires at least one running Ignite server node in the cluster. If no
 * server is reachable, {@link #start()} fails with a connection exception
 * and the {@link ru.ashesha.buildBattleAI.data.DataService DataService} falls back gracefully.
 * <p>
 * The thin client protocol (port 10800 by default) is separate from the
 * thick client / server discovery protocol (port 47500). Both are configured
 * independently in {@code config.yml}.
 *
 * @see IgniteEmbeddedProvider
 */
@RequiredArgsConstructor
public class IgniteThinProvider implements DataProvider {

    /** Remote server addresses for the thin-client connection. */
    private final List<String> addresses;

    /** The running thin-client instance, set in {@link #start()}, cleared in {@link #stop()}. */
    private IgniteClient client;

    /** Lazily created repositories, keyed by cache name. */
    private final Map<String, IgniteClientCacheRepository<?, ?>> repositories = new HashMap<>();

    /**
     * Opens a thin-client TCP connection to the Ignite cluster. Tries each
     * address in order until one succeeds. Blocks until connected.
     *
     * @throws Exception if no server is reachable
     */
    @Override
    public void start() throws Exception {
        ClientConfiguration cfg = new ClientConfiguration()
                .setAddresses(addresses.toArray(new String[0]));
        client = Ignition.startClient(cfg);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> DataRepository<K, V> getRepository(String name, Class<K> keyType, Class<V> valueType) {
        IgniteClientCacheRepository<K, V> repo =
                (IgniteClientCacheRepository<K, V>) repositories.get(name);
        if (repo == null) {
            ClientCacheConfiguration cacheCfg = new ClientCacheConfiguration()
                    .setName(name)
                    .setAtomicityMode(CacheAtomicityMode.ATOMIC)
                    .setCacheMode(CacheMode.REPLICATED);
            ClientCache<K, V> cache = client.getOrCreateCache(cacheCfg);
            repo = new IgniteClientCacheRepository<>(cache);
            repositories.put(name, repo);
        }
        return repo;
    }

    /** No-op — durability is managed by the remote server nodes. */
    @Override
    public void flush() {
        // Thin client has no local state to flush.
    }

    /**
     * Closes the thin-client TCP connection and releases all resources.
     * Idempotent — safe to call multiple times.
     */
    @Override
    public void stop() {
        repositories.clear();
        if (client != null) {
            try {
                client.close();
            } catch (Throwable ignored) {
                // Connection may already be closed; safe to ignore
            }
            client = null;
        }
    }
}

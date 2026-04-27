package ru.ashesha.buildBattleAI.data.ignite;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import ru.ashesha.buildBattleAI.data.DataProvider;
import ru.ashesha.buildBattleAI.data.DataRepository;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DataProvider} for Ignite <b>server node</b> and <b>thick-client</b> modes.
 * <p>
 * Uses {@link Ignition#start(IgniteConfiguration)} which returns an {@link Ignite}
 * instance. The instance participates fully in the cluster: in server mode it stores
 * data locally and handles client requests; in thick-client mode it routes operations
 * to server nodes but still runs a full discovery protocol.
 * <p>
 * Ignite manages its own thread pools internally. The {@link #start()} call blocks
 * until the node has joined the cluster (typically 1–3 seconds for a local cluster).
 * After startup, all {@link IgniteCache} operations are thread-safe and can be called
 * from any thread.
 * <p>
 * When native persistence is enabled, the provider automatically activates the cluster
 * after joining (required for the first node) to allow data operations.
 *
 * @see IgniteThinProvider
 */
public class IgniteEmbeddedProvider implements DataProvider {

    /** Whether this node runs as a thick client (true) or full server (false). */
    private final boolean clientMode;

    /** Ignite grid instance name — must match across all cluster nodes. */
    private final String instanceName;

    /** Whether native persistence (WAL + snapshots) is enabled. */
    private final boolean persistenceEnabled;

    /** Work directory for Ignite data files, WAL, and snapshots. */
    private final File workDirectory;

    /** Whether to suppress Ignite console output. */
    private final boolean quietMode;

    /** Metrics log frequency in milliseconds (0 = disabled). */
    private final long metricsLogFrequency;

    /** Initial size of the default data region in bytes. */
    private final long initialMemoryBytes;

    /** Maximum size of the default data region in bytes. */
    private final long maxMemoryBytes;

    /** Static IP addresses for node discovery. */
    private final List<String> discoveryAddresses;

    /** The running Ignite instance, set in {@link #start()}, cleared in {@link #stop()}. */
    private Ignite ignite;

    /** Lazily created repositories, keyed by cache name. */
    private final Map<String, IgniteCacheRepository<?, ?>> repositories = new HashMap<>();

    /**
     * Creates an embedded Ignite provider with the given configuration.
     *
     * @param clientMode         {@code true} for thick-client, {@code false} for server
     * @param instanceName       grid instance name
     * @param persistenceEnabled whether to enable WAL-based native persistence
     * @param workDirectory      directory for Ignite data (WAL, snapshots, indexes)
     * @param quietMode          suppress Ignite console output
     * @param metricsLogFrequency metrics log interval in ms (0 = disabled)
     * @param initialMemoryMb    initial data region size in megabytes
     * @param maxMemoryMb        maximum data region size in megabytes
     * @param discoveryAddresses static addresses for TCP discovery
     */
    public IgniteEmbeddedProvider(boolean clientMode, String instanceName,
                           boolean persistenceEnabled, File workDirectory,
                           boolean quietMode, long metricsLogFrequency,
                           int initialMemoryMb, int maxMemoryMb,
                           List<String> discoveryAddresses) {
        this.clientMode = clientMode;
        this.instanceName = instanceName;
        this.persistenceEnabled = persistenceEnabled;
        this.workDirectory = workDirectory;
        this.quietMode = quietMode;
        this.metricsLogFrequency = metricsLogFrequency;
        this.initialMemoryBytes = initialMemoryMb * 1024L * 1024L;
        this.maxMemoryBytes = maxMemoryMb * 1024L * 1024L;
        this.discoveryAddresses = discoveryAddresses;
    }

    /**
     * Builds the Ignite configuration and starts the node. Blocks until the
     * node has joined the cluster. If native persistence is enabled, activates
     * the cluster so data operations can proceed.
     *
     * @throws Exception if the node fails to start or join the cluster
     */
    @Override
    public void start() throws Exception {
        IgniteConfiguration cfg = new IgniteConfiguration();
        cfg.setClientMode(clientMode);
        cfg.setIgniteInstanceName(instanceName);
        cfg.setPeerClassLoadingEnabled(false);
        cfg.setMetricsLogFrequency(metricsLogFrequency);

        // Work directory for persistence data, WAL, and indexes
        if (!workDirectory.exists())
            workDirectory.mkdirs();
        cfg.setWorkDirectory(workDirectory.getAbsolutePath());

        // Suppress console output if requested
        if (quietMode)
            System.setProperty("IGNITE_QUIET", "true");

        // Data storage — memory region and optional native persistence
        DataRegionConfiguration regionCfg = new DataRegionConfiguration()
                .setInitialSize(initialMemoryBytes)
                .setMaxSize(maxMemoryBytes)
                .setPersistenceEnabled(persistenceEnabled);

        DataStorageConfiguration storageCfg = new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(regionCfg);

        // Place WAL and storage inside our work directory for clean isolation
        if (persistenceEnabled) {
            storageCfg.setWalPath(new File(workDirectory, "wal").getAbsolutePath());
            storageCfg.setWalArchivePath(new File(workDirectory, "wal-archive").getAbsolutePath());
            storageCfg.setStoragePath(new File(workDirectory, "storage").getAbsolutePath());
        }

        cfg.setDataStorageConfiguration(storageCfg);

        // TCP discovery with static IP finder
        TcpDiscoverySpi discoverySpi = new TcpDiscoverySpi();
        TcpDiscoveryVmIpFinder ipFinder = new TcpDiscoveryVmIpFinder();
        ipFinder.setAddresses(discoveryAddresses);
        discoverySpi.setIpFinder(ipFinder);
        cfg.setDiscoverySpi(discoverySpi);

        // Start the Ignite node — blocks until cluster join completes
        ignite = Ignition.start(cfg);

        // Activate the cluster when persistence is enabled (required for
        // the first server node; no-op if already active)
        if (persistenceEnabled && !clientMode)
            ignite.cluster().state(ClusterState.ACTIVE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> DataRepository<K, V> getRepository(String name, Class<K> keyType, Class<V> valueType) {
        IgniteCacheRepository<K, V> repo = (IgniteCacheRepository<K, V>) repositories.get(name);
        if (repo == null) {
            CacheConfiguration<K, V> cacheCfg = new CacheConfiguration<>(name);
            cacheCfg.setAtomicityMode(CacheAtomicityMode.ATOMIC);
            cacheCfg.setCacheMode(CacheMode.REPLICATED);
            IgniteCache<K, V> cache = ignite.getOrCreateCache(cacheCfg);
            repo = new IgniteCacheRepository<>(cache);
            repositories.put(name, repo);
        }
        return repo;
    }

    /** No-op — Ignite manages durability internally via WAL and checkpointing. */
    @Override
    public void flush() {
        // Nothing to flush; Ignite handles persistence.
    }

    /**
     * Stops the Ignite node and releases all resources. The node is
     * disconnected from the cluster; its data remains in the persistence
     * directory (if enabled) for the next startup.
     */
    @Override
    public void stop() {
        repositories.clear();
        if (ignite != null) {
            try {
                ignite.close();
            } catch (Throwable ignored) {
                // Ignite may log warnings during shutdown; safe to ignore
            }
            ignite = null;
        }
    }
}

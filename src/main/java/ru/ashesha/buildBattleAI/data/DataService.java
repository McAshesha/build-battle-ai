package ru.ashesha.buildBattleAI.data;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.data.api.ArenaStats;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.data.api.PlayerData;
import ru.ashesha.buildBattleAI.data.ignite.IgniteEmbeddedProvider;
import ru.ashesha.buildBattleAI.data.ignite.IgniteThinProvider;
import ru.ashesha.buildBattleAI.data.local.LocalDataProvider;

import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Default implementation of {@link BBAIDataService}.
 * <p>
 * Reads the storage backend configuration from {@code config.yml} during
 * {@link #enable()} and delegates to either a local JSON file provider or
 * an Apache Ignite provider. If data storage is disabled in the config
 * ({@code data.enabled: false}), all read methods return {@code null} or
 * empty collections, and all write methods are silent no-ops.
 * <p>
 * The chosen {@link DataProvider} is created in {@link #enable()} and
 * destroyed in {@link #shutdown()}, ensuring a clean lifecycle across
 * reload cycles. For local storage, an auto-save task is scheduled via
 * {@link org.bukkit.scheduler.BukkitScheduler} to periodically flush
 * dirty caches to disk.
 * <p>
 * Implements both {@link BBAIDataService} (public API) and
 * {@link PluginService} (internal lifecycle) independently.
 *
 * @see BBAIDataService
 * @see DataProvider
 */
@RequiredArgsConstructor
public class DataService implements BBAIDataService, PluginService {

    /** Cache/collection name for player data. */
    private static final String PLAYERS_REPO = "players";

    /** Cache/collection name for arena statistics. */
    private static final String ARENA_STATS_REPO = "arena-stats";

    /** The plugin instance, used for config access, logging, and scheduling. */
    @NonNull
    private final BuildBattleAI plugin;

    // ── runtime state (populated in enable, cleared in shutdown) ────────────

    /**
     * The active storage backend, or {@code null} if disabled.
     *
     * <p>Declared {@code volatile} so {@link #shutdown()} writing {@code null}
     * is immediately visible to the async autosave lambda — see DATA-02.
     */
    private volatile DataProvider provider;

    /** Player data repository, keyed by player UUID. */
    private DataRepository<UUID, PlayerData> playerRepo;

    /** Arena stats repository, backed by the active provider. */
    private DataRepository<String, ArenaStats> arenaStatsRepo;

    /** Periodic auto-save task for local storage, or {@code null}. */
    private BukkitTask autoSaveTask;

    /** Whether the service successfully started. */
    private boolean enabled;

    // ── PluginService lifecycle ────────────────────────────────────────────

    /**
     * Initializes the data storage backend based on configuration:
     * <ol>
     *     <li>Reads {@code data.enabled} and {@code data.provider} from config.</li>
     *     <li>Creates the appropriate {@link DataProvider} (local or Ignite).</li>
     *     <li>Starts the provider and obtains repositories.</li>
     *     <li>For local storage, schedules an auto-save task if configured.</li>
     * </ol>
     */
    @Override
    public void enable() {
        YamlConfiguration config = plugin.getContext().getConfigService().config();

        PluginLogger log = plugin.getPluginLogger();

        // Check if data storage is enabled at all
        if (!config.getBoolean("data.enabled", true)) {
            log.info("DataService is disabled in config.");
            return;
        }

        String providerType = config.getString("data.provider", "local");
        log.debug("DataService initializing with provider '%s'.", providerType);

        // Create the appropriate provider
        if ("ignite".equals(providerType))
            provider = createIgniteProvider(config);
        else
            provider = createLocalProvider(config);

        // Start the provider (open connections, create directories, etc.)
        try {
            if (provider == null) {
                log.error("Failed to create data provider. DataService disabled.");
                return;
            }
            provider.start();
            log.debug("Data provider started successfully.");
        } catch (Throwable e) {
            log.error("Failed to start data provider: " + e.getMessage());
            provider = null;
            return;
        }

        // Obtain typed repositories
        playerRepo = provider.getRepository(PLAYERS_REPO, UUID.class, PlayerData.class);
        arenaStatsRepo = provider.getRepository(ARENA_STATS_REPO, String.class, ArenaStats.class);
        enabled = true;

        // Schedule auto-save for local provider
        if ("local".equals(providerType))
            scheduleAutoSave(config);

        log.info("DataService enabled (provider: %s).", providerType);
    }

    /**
     * Shuts down the data service: cancels the auto-save task, flushes
     * pending data, and releases all provider resources. Idempotent and
     * safe to call even if {@link #enable()} was never invoked.
     */
    @Override
    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
            plugin.getPluginLogger().debug("DataService auto-save task cancelled.");
        }

        if (provider != null) {
            provider.stop();
            provider = null;
            plugin.getPluginLogger().debug("DataService provider stopped.");
        }

        playerRepo = null;
        arenaStatsRepo = null;
        enabled = false;
    }

    // ── BBAIDataService: player data ──────────────────────────────────────

    @Override
    public PlayerData getPlayer(UUID uuid) {
        if (!enabled)
            return null;
        return playerRepo.get(uuid);
    }

    @Override
    public PlayerData getOrCreatePlayer(UUID uuid, String name) {
        if (!enabled)
            return new PlayerData(uuid, name);

        PlayerData data = playerRepo.get(uuid);
        if (data == null) {
            data = new PlayerData(uuid, name);
            playerRepo.put(uuid, data);
        } else if (!name.equals(data.name())) {
            // Update the stored name if the player renamed themselves
            data.name(name);
            playerRepo.put(uuid, data);
        }
        return data;
    }

    @Override
    public void savePlayer(PlayerData data) {
        if (!enabled)
            return;
        playerRepo.put(data.uuid(), data);
    }

    @Override
    public Collection<PlayerData> getAllPlayers() {
        if (!enabled)
            return Collections.emptyList();
        return Collections.unmodifiableCollection(playerRepo.getAll());
    }

    // ── BBAIDataService: arena stats ──────────────────────────────────────

    @Override
    public ArenaStats getArenaStats(String name) {
        if (!enabled)
            return null;
        return arenaStatsRepo.get(name);
    }

    @Override
    public void saveArenaStats(ArenaStats stats) {
        if (!enabled)
            return;
        arenaStatsRepo.put(stats.name(), stats);
    }

    @Override
    public Collection<ArenaStats> getAllArenaStats() {
        if (!enabled)
            return Collections.emptyList();
        return Collections.unmodifiableCollection(arenaStatsRepo.getAll());
    }

    // ── BBAIDataService: lifecycle helpers ─────────────────────────────────

    @Override
    public void flush() {
        if (provider != null)
            provider.flush();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // ── private factory methods ───────────────────────────────────────────

    /**
     * Creates a local JSON file data provider.
     *
     * @param config the plugin configuration
     * @return the local provider
     */
    private DataProvider createLocalProvider(YamlConfiguration config) {
        String dir = config.getString("data.local.directory", "data");
        File dataDir = new File(plugin.getDataFolder(), dir);
        return new LocalDataProvider(dataDir, plugin.getPluginLogger());
    }

    /**
     * Creates an Apache Ignite data provider based on the configured mode.
     * <p>
     * This is a separate method so that Ignite classes are only resolved
     * by the JVM when this method is actually invoked. If the config
     * specifies {@code provider: local}, this method is never called and
     * zero Ignite classes are loaded — even if the Ignite JAR is on the
     * classpath.
     * <p>
     * If Ignite classes are not found on the classpath, falls back to
     * local file storage with a warning.
     *
     * @param config the plugin configuration
     * @return the Ignite provider, or a local fallback on class-not-found
     */
    private DataProvider createIgniteProvider(YamlConfiguration config) {
        try {
            String mode = config.getString("data.ignite.mode", "server");

            if ("thin-client".equals(mode))
                return createIgniteThinProvider(config);
            else
                return createIgniteEmbeddedProvider(config, "thick-client".equals(mode));
        } catch (NoClassDefFoundError e) {
            plugin.getPluginLogger().error("Apache Ignite classes not found on classpath! "
                    + "Falling back to local file storage. Add ignite-core to the server classpath "
                    + "or switch to provider: local in config.yml.");
            return createLocalProvider(config);
        }
    }

    /**
     * Creates an embedded Ignite provider (server or thick-client mode).
     * Reads all Ignite-specific config keys from the {@code data.ignite.*} section.
     *
     * @param config     the plugin configuration
     * @param clientMode {@code true} for thick-client, {@code false} for server
     * @return the embedded provider
     */
    private DataProvider createIgniteEmbeddedProvider(YamlConfiguration config, boolean clientMode) {
        String instanceName = config.getString("data.ignite.instance-name", "BuildBattleAI");
        boolean persistence = config.getBoolean("data.ignite.persistence-enabled", false);
        String workDir = config.getString("data.ignite.work-directory", "ignite");
        boolean quietMode = config.getBoolean("data.ignite.quiet-mode", true);
        long metricsFreq = config.getLong("data.ignite.metrics-log-frequency", 0L);
        int initialMb = config.getInt("data.ignite.initial-memory-mb", 64);
        int maxMb = config.getInt("data.ignite.max-memory-mb", 128);

        List<String> addresses = new ArrayList<>();
        List<?> rawAddresses = config.getList("data.ignite.discovery-addresses");
        if (rawAddresses != null)
            for (Object addr : rawAddresses)
                addresses.add(String.valueOf(addr));
        if (addresses.isEmpty())
            addresses.add("127.0.0.1:47500..47509");

        File workDirectory = new File(plugin.getDataFolder(), workDir);

        return new IgniteEmbeddedProvider(
                clientMode, instanceName, persistence, workDirectory,
                quietMode, metricsFreq, initialMb, maxMb, addresses);
    }

    /**
     * Creates a thin-client Ignite provider. Reads the thin-client
     * connection addresses from the {@code data.ignite.thin-client-addresses}
     * config section.
     *
     * @param config the plugin configuration
     * @return the thin-client provider
     */
    private DataProvider createIgniteThinProvider(YamlConfiguration config) {
        List<String> addresses = new ArrayList<>();
        List<?> rawAddresses = config.getList("data.ignite.thin-client-addresses");
        if (rawAddresses != null)
            for (Object addr : rawAddresses)
                addresses.add(String.valueOf(addr));
        if (addresses.isEmpty())
            addresses.add("127.0.0.1:10800");

        return new IgniteThinProvider(addresses);
    }

    /**
     * Schedules the periodic auto-save task for local file storage.
     * The task runs asynchronously via the Bukkit scheduler, which
     * ensures it is automatically cancelled when the plugin is disabled.
     *
     * @param config the plugin configuration
     */
    private void scheduleAutoSave(YamlConfiguration config) {
        int intervalSec = config.getInt("data.local.auto-save-interval", 300);
        if (intervalSec <= 0) {
            plugin.getPluginLogger().debug("Auto-save disabled (interval <= 0).");
            return;
        }

        plugin.getPluginLogger().debug("Scheduling auto-save every %d seconds.", intervalSec);
        long intervalTicks = intervalSec * 20L;
        autoSaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> {
                    // Capture provider into a local — shutdown() may null the
                    // field concurrently (DATA-02). volatile guarantees the
                    // local-and-the-flush-call see the same snapshot.
                    DataProvider p = provider;
                    if (p != null)
                        p.flush();
                },
                intervalTicks,
                intervalTicks
        );
    }
}

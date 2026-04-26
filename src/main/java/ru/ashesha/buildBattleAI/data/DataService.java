package ru.ashesha.buildBattleAI.data;

import lombok.NonNull;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.data.api.ArenaStats;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
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
public class DataService implements BBAIDataService, PluginService {

    /** Cache/collection name for player data. */
    private static final String PLAYERS_REPO = "players";

    /** Cache/collection name for arena statistics. */
    private static final String ARENA_STATS_REPO = "arena-stats";

    /** The plugin instance, used for config access, logging, and scheduling. */
    private final BuildBattleAI plugin;

    // ── runtime state (populated in enable, cleared in shutdown) ────────────

    /** The active storage backend, or {@code null} if disabled. */
    private DataProvider provider;

    /** Player data repository, backed by the active provider. */
    private DataRepository<String, PlayerData> playerRepo;

    /** Arena stats repository, backed by the active provider. */
    private DataRepository<String, ArenaStats> arenaStatsRepo;

    /** Periodic auto-save task for local storage, or {@code null}. */
    private BukkitTask autoSaveTask;

    /** Whether the service successfully started. */
    private boolean enabled;

    /**
     * Creates the data service. No I/O or resource allocation happens
     * here — all initialization is deferred to {@link #enable()}.
     *
     * @param plugin the plugin instance
     */
    public DataService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
    }

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

        // Check if data storage is enabled at all
        if (!config.getBoolean("data.enabled", true)) {
            plugin.getLogger().info("DataService is disabled in config.");
            return;
        }

        String providerType = config.getString("data.provider", "local");

        // Create the appropriate provider
        if ("ignite".equals(providerType))
            provider = createIgniteProvider(config);
        else
            provider = createLocalProvider(config);

        // Start the provider (open connections, create directories, etc.)
        try {
            if (provider == null) {
                plugin.getLogger().severe("Failed to create data provider. DataService disabled.");
                return;
            }
            provider.start();
        } catch (Throwable e) {
            plugin.getLogger().severe("Failed to start data provider: " + e.getMessage());
            provider = null;
            return;
        }

        // Obtain typed repositories
        playerRepo = provider.getRepository(PLAYERS_REPO, String.class, PlayerData.class);
        arenaStatsRepo = provider.getRepository(ARENA_STATS_REPO, String.class, ArenaStats.class);
        enabled = true;

        // Schedule auto-save for local provider
        if ("local".equals(providerType))
            scheduleAutoSave(config);

        plugin.getLogger().info("DataService enabled (provider: " + providerType + ").");
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
        }

        if (provider != null) {
            provider.stop();
            provider = null;
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
        return playerRepo.get(uuid.toString());
    }

    @Override
    public PlayerData getOrCreatePlayer(UUID uuid, String name) {
        if (!enabled)
            return new PlayerData(uuid, name);

        String key = uuid.toString();
        PlayerData data = playerRepo.get(key);
        if (data == null) {
            data = new PlayerData(uuid, name);
            playerRepo.put(key, data);
        } else if (!name.equals(data.name())) {
            // Update the stored name if the player renamed themselves
            data.name(name);
            playerRepo.put(key, data);
        }
        return data;
    }

    @Override
    public void savePlayer(PlayerData data) {
        if (!enabled)
            return;
        playerRepo.put(data.uuid().toString(), data);
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
        return new LocalDataProvider(dataDir);
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
        // TODO: Phase 2 — implement Ignite provider creation
        // This method will instantiate IgniteEmbeddedProvider or IgniteThinProvider
        // based on config.getString("data.ignite.mode"). For now, fall back to local.
        plugin.getLogger().warning("Ignite provider is not yet implemented. Falling back to local storage.");
        return createLocalProvider(config);
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
        if (intervalSec <= 0)
            return;

        long intervalTicks = intervalSec * 20L;
        autoSaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                new Runnable() {
                    @Override
                    public void run() {
                        provider.flush();
                    }
                },
                intervalTicks,
                intervalTicks
        );
    }
}

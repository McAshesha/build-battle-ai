package ru.ashesha.buildBattleAI.data.local;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.NonNull;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.data.DataProvider;
import ru.ashesha.buildBattleAI.data.DataRepository;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * File-based data provider that stores each repository as a single JSON file.
 * <p>
 * Each repository is persisted to its own file in the configured data
 * directory (e.g. {@code plugins/BuildBattleAI/data/players.json}). At
 * runtime, data lives in a {@link java.util.concurrent.ConcurrentHashMap}
 * per repository for lock-free reads; periodic {@link #flush()} calls
 * write dirty caches to disk.
 * <p>
 * The Gson instance is shared across all repositories and configured with
 * pretty-printing for human-readable files that server administrators can
 * inspect and manually edit if needed.
 *
 * @see LocalRepository
 */
public class LocalDataProvider implements DataProvider {

    /** The directory where JSON data files are stored. */
    private final File dataDir;

    /** Shared Gson instance with pretty-printing enabled. */
    private final Gson gson;

    /** Logger forwarded to each repository for I/O failure reporting. */
    private final PluginLogger logger;

    /** Name to repository mapping — repositories are lazily created and cached. */
    private final Map<String, LocalRepository<?, ?>> repositories = new HashMap<>();

    /**
     * Creates a local data provider that stores files in the given directory.
     *
     * @param dataDir the data directory (created on {@link #start()} if missing)
     * @param logger  logger forwarded to each repository for I/O failure reporting
     */
    public LocalDataProvider(@NonNull File dataDir, @NonNull PluginLogger logger) {
        this.dataDir = dataDir;
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void start() {
        if (!dataDir.exists())
            dataDir.mkdirs();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> DataRepository<K, V> getRepository(String name, Class<K> keyType, Class<V> valueType) {
        LocalRepository<K, V> repo = (LocalRepository<K, V>) repositories.get(name);
        if (repo == null) {
            File file = new File(dataDir, name + ".json");
            repo = new LocalRepository<>(file, gson, keyType, valueType, logger);
            repo.load();
            repositories.put(name, repo);
        }
        return repo;
    }

    @Override
    public void flush() {
        // Synchronize on the map so a concurrent stop() cannot clear entries
        // mid-iteration — the autosave lambda calls flush() while shutdown()
        // calls stop(), which could otherwise cause ConcurrentModificationException.
        synchronized (repositories) {
            for (LocalRepository<?, ?> repo : repositories.values())
                repo.flush();
        }
    }

    @Override
    public void stop() {
        // Hold the lock across flush+clear so no concurrent flush() interleaves
        // between the iteration and the clear (DATA-02 companion fix).
        synchronized (repositories) {
            flush();
            repositories.clear();
        }
    }
}

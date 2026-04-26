package ru.ashesha.buildBattleAI.data;

/**
 * Internal abstraction over storage backends (local JSON files, Apache Ignite).
 * <p>
 * Each provider manages its own connection/resource lifecycle and creates
 * {@link DataRepository} instances for named data collections. The provider
 * is responsible for:
 * <ul>
 *     <li>Opening connections / creating directories on {@link #start()}</li>
 *     <li>Vending typed repositories via {@link #getRepository(String, Class, Class)}</li>
 *     <li>Flushing pending writes on {@link #flush()}</li>
 *     <li>Releasing all resources on {@link #stop()}</li>
 * </ul>
 * <p>
 * Implementations are package-private and instantiated by {@link DataService}
 * based on the {@code data.provider} configuration key.
 */
interface DataProvider {

    /**
     * Starts the provider — opens connections, creates directories,
     * initializes caches, etc.
     *
     * @throws Exception if startup fails (caller handles gracefully)
     */
    void start() throws Exception;

    /**
     * Creates or retrieves a typed repository for the given collection name.
     * Repositories are lazily created on first request and cached for the
     * lifetime of the provider.
     *
     * @param name      the collection/cache name (e.g. {@code "players"})
     * @param keyType   the key class
     * @param valueType the value class
     * @param <K>       key type
     * @param <V>       value type
     * @return the repository (never {@code null})
     */
    <K, V> DataRepository<K, V> getRepository(String name, Class<K> keyType, Class<V> valueType);

    /**
     * Flushes all pending writes across all repositories to persistent storage.
     * No-op for backends that persist immediately.
     */
    void flush();

    /**
     * Stops the provider: flushes pending data, closes connections, and
     * releases all resources. Must be idempotent.
     */
    void stop();
}

package ru.ashesha.buildBattleAI.core;

/**
 * Internal lifecycle contract shared by every plugin-owned service.
 * <p>
 * All services managed by {@link PluginContext} implement this interface so
 * the plugin can be started, stopped, and reloaded uniformly. The contract
 * is intentionally small — {@link #enable()}, {@link #shutdown()}, and a
 * default {@link #reload()} that composes the two.
 * <p>
 * <b>Invariants every implementation MUST uphold:</b>
 * <ul>
 *     <li>Services are constructed exactly once — in
 *         {@link PluginContext}'s constructor — and the same instance is
 *         reused across as many {@code enable/shutdown} cycles as the
 *         plugin goes through.</li>
 *     <li>{@link #enable()} acquires all runtime resources the service
 *         needs (thread pools, scheduled tasks, registered commands /
 *         listeners, open connections, etc.).</li>
 *     <li>{@link #shutdown()} releases those resources <b>without</b>
 *         disturbing any other plugin, the server itself, or third-party
 *         state — e.g. by bulk-unregistering only commands and listeners
 *         that this service registered, and by stopping only thread pools
 *         it owns.</li>
 *     <li>After {@link #shutdown()} returns, a subsequent {@link #enable()}
 *         must leave the service fully functional, behaviorally identical
 *         to a fresh plugin start. This is what makes hot-reload via
 *         {@link #reload()} safe.</li>
 *     <li>{@link #shutdown()} must be safe to call even if {@link #enable()}
 *         was never invoked, so failed startups can be unwound cleanly.</li>
 * </ul>
 * <p>
 * This interface is intentionally <b>not</b> part of the public plugin API
 * (the {@code api/} packages). External consumers never invoke lifecycle
 * methods directly — lifecycle is the exclusive responsibility of
 * {@link PluginContext}. API interfaces therefore must not inherit from
 * this one; instead, service classes implement both their API interface
 * and {@link PluginService} independently.
 */
public interface PluginService {

    /**
     * Starts the service and allocates all runtime resources it needs.
     * <p>
     * Called by {@link PluginContext#enable()} in construction order.
     * Must leave the service in a fully operational state regardless of
     * whether this is a first start or a reload.
     */
    void enable();

    /**
     * Stops the service and releases every runtime resource it owns,
     * without affecting any other plugin, the server, or shared state.
     * <p>
     * Called by {@link PluginContext#shutdown()} in reverse construction
     * order. Must be idempotent and safe to call even if {@link #enable()}
     * was never invoked or has already been followed by a prior
     * {@code shutdown()}.
     */
    void shutdown();

    /**
     * Reloads the service by running {@link #shutdown()} followed by
     * {@link #enable()}.
     * <p>
     * The default implementation is correct for stateless services.
     * Implementations that need transactional behavior (for instance,
     * preserving in-memory state across the reload) may override this
     * method to coordinate the two phases atomically.
     */
    default void reload() {
        shutdown();
        enable();
    }
}

package ru.ashesha.buildBattleAI.config.api;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Set;

/**
 * Service contract for managing plugin configuration files.
 * <p>
 * Provides unified access to three configuration zones:
 * <ul>
 *     <li><b>Root config</b> ({@code config.yml}) — the main plugin settings file,
 *         auto-created from the bundled default and auto-migrated on every enable
 *         cycle so that new keys added in plugin updates appear automatically.</li>
 *     <li><b>Language directory</b> ({@code lang/}) — multiple translation files
 *         (e.g. {@code en.yml}, {@code ru.yml}) for per-player localization. Missing
 *         keys are filled from the default language, and bundled defaults are
 *         extracted on first run.</li>
 *     <li><b>Arena directory</b> ({@code arena/}) — dynamically created/deleted
 *         arena definition files.</li>
 * </ul>
 * All configurations are cached in memory after loading — read access is
 * zero-cost I/O. Write-back to disk happens only on explicit {@code save} calls.
 * <p>
 * Lifecycle management is intentionally excluded from this public API —
 * implementations handle {@code enable}/{@code shutdown} through the internal
 * {@code PluginService} contract.
 *
 * @see Lang
 */
public interface BBAIConfigService {

    // ── main config ────────────────────────────────────────────────────────

    /**
     * Returns the main plugin configuration ({@code config.yml}), cached in memory.
     * <p>
     * Callers may freely read and modify values; changes are only persisted
     * to disk when {@link #saveConfig()} is called.
     *
     * @return the main configuration (never {@code null} after enable)
     */
    YamlConfiguration config();

    /**
     * Saves the current in-memory state of {@code config.yml} to disk.
     */
    void saveConfig();

    // ── languages ──────────────────────────────────────────────────────────

    /**
     * Returns the default language, determined by the {@code default-language}
     * key in {@code config.yml}.
     *
     * @return the default {@link Lang} instance, or {@code null} if no
     *         language files were loaded
     */
    Lang getDefaultLang();

    /**
     * Returns the language with the given name (e.g. {@code "en"}, {@code "ru"}).
     * <p>
     * If the requested language does not exist, the default language is returned
     * as a fallback — callers never receive {@code null} unless no language
     * files exist at all.
     *
     * @param name language name (filename without {@code .yml})
     * @return the matching {@link Lang}, or the default language as fallback
     */
    Lang getLang(String name);

    /**
     * Returns the names of all loaded languages.
     *
     * @return unmodifiable set of language names (e.g. {@code ["en", "ru"]})
     */
    Set<String> getAvailableLangs();

    // ── arenas ─────────────────────────────────────────────────────────────

    /**
     * Returns the cached configuration for the named arena.
     *
     * @param name arena name (filename without {@code .yml})
     * @return the arena configuration, or {@code null} if no such arena exists
     */
    YamlConfiguration getArenaConfig(String name);

    /**
     * Creates a new arena configuration file and registers it in the cache.
     * An empty {@code .yml} file is written to the {@code arena/} directory
     * immediately.
     *
     * @param name arena name (will become the filename)
     * @return the new (empty) arena configuration
     */
    YamlConfiguration createArenaConfig(String name);

    /**
     * Saves the in-memory arena configuration to disk.
     * No-op if the named arena does not exist in the cache.
     *
     * @param name arena name
     */
    void saveArenaConfig(String name);

    /**
     * Deletes an arena configuration — removes both the in-memory cache entry
     * and the {@code .yml} file on disk.
     *
     * @param name arena name
     */
    void deleteArenaConfig(String name);

    /**
     * Returns the names of all loaded arenas.
     *
     * @return unmodifiable set of arena names
     */
    Set<String> getArenaNames();
}

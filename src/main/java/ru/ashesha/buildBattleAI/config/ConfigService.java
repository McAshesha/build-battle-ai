package ru.ashesha.buildBattleAI.config;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.data.api.BBAIDataService;
import ru.ashesha.buildBattleAI.data.api.PlayerData;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Default implementation of {@link BBAIConfigService}.
 * <p>
 * Manages three configuration zones:
 * <ul>
 *     <li><b>Root config</b> ({@code config.yml}) — single file, auto-migrated
 *         from the bundled default on every enable cycle.</li>
 *     <li><b>Language directory</b> ({@code lang/}) — multiple translation files,
 *         auto-created from bundled defaults, missing keys filled from the
 *         default language.</li>
 *     <li><b>Arena directory</b> ({@code arena/}) — multiple arena definition
 *         files, dynamically created and deleted through the API.</li>
 * </ul>
 * <p>
 * All YAML files are loaded and saved with explicit UTF-8 encoding to guarantee
 * correct handling of non-ASCII characters (translations, special symbols)
 * across all server platforms and JVM versions. The standard
 * {@link YamlConfiguration#loadConfiguration(File)} method is intentionally
 * avoided because older Spigot builds (1.8–1.12) use the platform-default
 * charset, which breaks non-Latin translations on Windows servers.
 * <p>
 * Implements both {@link BBAIConfigService} (the public API contract) and
 * {@link PluginService} (the internal lifecycle contract) independently.
 *
 * @see BBAIConfigService
 * @see Lang
 */
@RequiredArgsConstructor
public class ConfigService implements BBAIConfigService, PluginService {

    /** Name of the main configuration file. */
    private static final String CONFIG_FILE = "config.yml";

    /** Subdirectory for language/translation files. */
    private static final String LANG_DIR = "lang";

    /** Subdirectory for arena definition files. */
    private static final String ARENA_DIR = "arena";

    /**
     * Names of language files bundled in the plugin JAR (without {@code .yml}).
     * These are extracted to the {@code lang/} directory on first run and
     * have their keys auto-migrated on every enable cycle.
     * <p>
     * To bundle a new language, add its resource file under
     * {@code src/main/resources/lang/} and append the name here.
     */
    private static final String[] BUNDLED_LANGS = {"en", "ru"};

    /** Buffer size for stream reading operations. */
    private static final int BUFFER_SIZE = 4096;

    /** The plugin instance, used for resource loading and logging. */
    @NonNull
    private final BuildBattleAI plugin;

    // ── runtime state (populated in enable, cleared in shutdown) ────────────

    /** Cached main configuration. */
    private YamlConfiguration config;

    /** File reference for the main configuration. */
    private File configFile;

    /** Directory containing language files. */
    private File langDir;

    /** Directory containing arena definition files. */
    private File arenaDir;

    /** Language name to cached config — all loaded {@code .yml} files from {@code lang/}. */
    private final Map<String, YamlConfiguration> langConfigs = new LinkedHashMap<>();

    /** Arena name to cached config — all loaded {@code .yml} files from {@code arena/}. */
    private final Map<String, YamlConfiguration> arenaConfigs = new LinkedHashMap<>();

    /** Language name to {@link Lang} wrapper. Built after lang configs are loaded. */
    private final Map<String, Lang> langs = new HashMap<>();

    /** The default language wrapper, resolved from {@code default-language} in config.yml. */
    private Lang defaultLang;

    // ── PluginService lifecycle ────────────────────────────────────────────

    /**
     * Initializes all configuration zones:
     * <ol>
     *     <li>Creates the plugin data folder if missing.</li>
     *     <li>Loads and auto-migrates {@code config.yml} from the bundled default.</li>
     *     <li>Creates the {@code lang/} directory, extracts bundled languages,
     *         loads all language files, and merges missing keys from the
     *         default language into every other language.</li>
     *     <li>Creates the {@code arena/} directory and loads any existing
     *         arena definition files.</li>
     * </ol>
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void enable() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists())
            dataFolder.mkdirs();

        // Phase 1: main config — load from disk, merge any new keys from the
        // bundled default, and write back so the admin sees the new keys.
        configFile = new File(dataFolder, CONFIG_FILE);
        config = loadWithDefaults(CONFIG_FILE, configFile);

        // Apply the configured log level to the plugin logger immediately so
        // all subsequent service enable() calls log at the desired verbosity.
        PluginLogger log = plugin.getPluginLogger();
        String rawLevel = config.getString("log-level", "info");
        PluginLogger.LogLevel logLevel = PluginLogger.LogLevel.fromString(rawLevel);
        log.setLevel(logLevel);
        log.debug("Log level set to %s (from config value '%s').", logLevel, rawLevel);

        // Phase 2: language files
        langDir = new File(dataFolder, LANG_DIR);
        if (!langDir.exists())
            langDir.mkdirs();

        // Extract bundled language files from the JAR and merge defaults.
        // This ensures the default language file always has every key the
        // plugin expects, even after an update that adds new messages.
        for (String name : BUNDLED_LANGS) {
            String resourcePath = LANG_DIR + "/" + name + ".yml";
            File langFile = new File(langDir, name + ".yml");
            YamlConfiguration langConfig = loadWithDefaults(resourcePath, langFile);
            langConfigs.put(name, langConfig);
            log.debug("Loaded bundled language '%s' (%d keys).", name, langConfig.getKeys(true).size());
        }

        // Load any additional user-created language files (e.g. ru.yml, de.yml).
        // Files already present in the map (bundled langs) are skipped.
        loadDirectory(langDir, langConfigs);

        // Resolve the default language; fall back to the first available file
        // if the configured language does not exist on disk.
        String defaultLangName = config.getString("default-language", "en");
        YamlConfiguration defaultLangConfig = langConfigs.get(defaultLangName);
        if (defaultLangConfig == null && !langConfigs.isEmpty()) {
            Map.Entry<String, YamlConfiguration> first = langConfigs.entrySet().iterator().next();
            defaultLangName = first.getKey();
            defaultLangConfig = first.getValue();
            log.warn("Default language '%s' not found, falling back to '%s'.",
                    config.getString("default-language"), defaultLangName);
        }

        // Merge missing keys from the default language into every other
        // language so that incomplete translations still show messages
        // (in the default language) instead of raw key names.
        if (defaultLangConfig != null)
            for (Map.Entry<String, YamlConfiguration> entry : langConfigs.entrySet()) {
                if (entry.getKey().equals(defaultLangName))
                    continue;
                if (mergeDefaults(defaultLangConfig, entry.getValue()))
                    saveToFile(entry.getValue(), new File(langDir, entry.getKey() + ".yml"));
            }

        // Build Lang wrappers for convenient message access with fallback.
        for (Map.Entry<String, YamlConfiguration> entry : langConfigs.entrySet()) {
            YamlConfiguration fallback = entry.getKey().equals(defaultLangName)
                    ? null : defaultLangConfig;
            langs.put(entry.getKey(), new ConfigLang(entry.getKey(), entry.getValue(), fallback));
        }
        defaultLang = langs.get(defaultLangName);

        // Phase 3: arena configs — just load whatever is already on disk.
        arenaDir = new File(dataFolder, ARENA_DIR);
        if (!arenaDir.exists())
            arenaDir.mkdirs();
        loadDirectory(arenaDir, arenaConfigs);

        log.info("ConfigService enabled (%d language(s), %d arena(s)).",
                langConfigs.size(), arenaConfigs.size());
    }

    /**
     * Clears all in-memory caches. Does not auto-save — any unsaved
     * modifications to configurations are discarded. This is intentional:
     * persistence is the caller's responsibility via explicit save calls.
     */
    @Override
    public void shutdown() {
        config = null;
        configFile = null;
        langDir = null;
        arenaDir = null;
        langConfigs.clear();
        arenaConfigs.clear();
        langs.clear();
        defaultLang = null;
    }

    // ── BBAIConfigService: main config ─────────────────────────────────────

    @Override
    public YamlConfiguration config() {
        return config;
    }

    @Override
    public void saveConfig() {
        saveToFile(config, configFile);
    }

    // ── BBAIConfigService: languages ───────────────────────────────────────

    @Override
    public Lang getDefaultLang() {
        return defaultLang;
    }

    @Override
    public Lang getLang(@NonNull String name) {
        Lang lang = langs.get(name);
        return lang != null ? lang : defaultLang;
    }

    @Override
    public Set<String> getAvailableLangs() {
        return Collections.unmodifiableSet(langs.keySet());
    }

    @Override
    public Lang getLangFor(UUID playerId) {
        // Console / null context — fall through to the default. No-op cheap path.
        if (playerId == null)
            return defaultLang;
        // The data service is the source of truth for player preferences.
        // When disabled (config: data.enabled=false) or empty, fall through
        // to the default language — preferences just don't persist in that mode.
        BBAIDataService data = plugin.getContext().getDataService();
        if (data == null || !data.isEnabled())
            return defaultLang;
        PlayerData pd = data.getPlayer(playerId);
        if (pd == null || pd.language() == null || pd.language().isEmpty())
            return defaultLang;
        Lang lang = langs.get(pd.language());
        return lang != null ? lang : defaultLang;
    }

    // ── BBAIConfigService: arenas ──────────────────────────────────────────

    @Override
    public YamlConfiguration getArenaConfig(@NonNull String name) {
        return arenaConfigs.get(name);
    }

    @Override
    public YamlConfiguration createArenaConfig(@NonNull String name) {
        YamlConfiguration arenaConfig = new YamlConfiguration();
        arenaConfigs.put(name, arenaConfig);
        saveToFile(arenaConfig, new File(arenaDir, name + ".yml"));
        return arenaConfig;
    }

    @Override
    public void saveArenaConfig(@NonNull String name) {
        YamlConfiguration arenaConfig = arenaConfigs.get(name);
        if (arenaConfig != null)
            saveToFile(arenaConfig, new File(arenaDir, name + ".yml"));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void deleteArenaConfig(@NonNull String name) {
        arenaConfigs.remove(name);
        File file = new File(arenaDir, name + ".yml");
        if (file.exists())
            file.delete();
    }

    @Override
    public Set<String> getArenaNames() {
        return Collections.unmodifiableSet(arenaConfigs.keySet());
    }

    // ── private I/O helpers ────────────────────────────────────────────────

    /**
     * Loads a config from disk and merges any missing keys from the bundled
     * resource version.
     * <p>
     * If the file does <b>not</b> exist on disk, the raw resource is copied
     * byte-for-byte from the JAR, preserving comments and formatting. If the
     * file <b>does</b> exist, only missing leaf keys are added (preserving
     * admin customizations) and the merged result is saved back.
     *
     * @param resourcePath path inside the plugin JAR (e.g. {@code "lang/en.yml"})
     * @param file         target file on disk
     * @return the loaded (and possibly merged) configuration
     */
    private YamlConfiguration loadWithDefaults(String resourcePath, File file) {
        if (!file.exists()) {
            // First run — copy the raw resource to preserve comments and formatting.
            // The resource stream is consumed only once (by copyResource), then
            // the file is loaded from the written copy.
            if (copyResource(resourcePath, file))
                return loadFromFile(file);
            // Fallback: resource not found — create empty config
            return new YamlConfiguration();
        }

        // File exists — merge any new keys added in a plugin update.
        // This consumes the resource stream through loadFromResource (parsed),
        // then merges missing keys into the admin's existing file.
        YamlConfiguration internal = loadFromResource(resourcePath);
        YamlConfiguration fileConfig = loadFromFile(file);
        if (internal != null && mergeDefaults(internal, fileConfig))
            saveToFile(fileConfig, file);
        return fileConfig;
    }

    /**
     * Copies a raw resource from the plugin JAR to a file on disk,
     * preserving the original formatting, comments, and structure.
     *
     * @param resourcePath path inside the JAR
     * @param target       destination file
     * @return {@code true} if the copy succeeded
     */
    private boolean copyResource(String resourcePath, File target) {
        InputStream stream = plugin.getResource(resourcePath);
        if (stream == null)
            return false;
        try {
            FileOutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = stream.read(buf)) != -1)
                out.write(buf, 0, n);
            out.flush();
            out.close();
            stream.close();
            return true;
        } catch (IOException e) {
            plugin.getPluginLogger().warn("Failed to copy resource %s: %s",
                    resourcePath, e.getMessage());
            return false;
        }
    }

    /**
     * Loads a YAML configuration from the plugin JAR resources using UTF-8.
     *
     * @param path resource path inside the JAR
     * @return the loaded configuration, or {@code null} if the resource does not exist
     */
    private YamlConfiguration loadFromResource(String path) {
        InputStream stream = plugin.getResource(path);
        if (stream == null)
            return null;
        try {
            String content = readStream(stream);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(content);
            return config;
        } catch (Throwable e) {
            plugin.getPluginLogger().warn("Failed to load resource %s: %s", path, e.getMessage());
            return null;
        }
    }

    /**
     * Loads a YAML configuration from a file with explicit UTF-8 encoding.
     * <p>
     * Uses {@link YamlConfiguration#loadFromString(String)} instead of
     * {@link YamlConfiguration#loadConfiguration(File)} to guarantee UTF-8
     * on all server versions (1.8+), since the File-based method uses the
     * platform-default charset on older Spigot builds.
     *
     * @param file the file to load
     * @return the loaded configuration, or an empty configuration on error
     */
    private YamlConfiguration loadFromFile(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            String content = readStream(Files.newInputStream(file.toPath()));
            config.loadFromString(content);
        } catch (Throwable e) {
            plugin.getPluginLogger().warn("Failed to load %s: %s", file.getName(), e.getMessage());
        }
        return config;
    }

    /**
     * Saves a YAML configuration to a file with explicit UTF-8 encoding.
     * Unicode escape sequences (backslash-u hex sequences) produced by
     * SnakeYAML are converted back to real characters so the file remains
     * human-readable for server administrators working with non-Latin
     * translations.
     *
     * @param config the configuration to save
     * @param file   the target file
     */
    private void saveToFile(YamlConfiguration config, File file) {
        try {
            String content = config.saveToString();
            content = unescapeUnicode(content);
            OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8);
            writer.write(content);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            plugin.getPluginLogger().warn("Failed to save %s: %s", file.getName(), e.getMessage());
        }
    }

    /**
     * Copies all leaf-level keys from {@code source} into {@code target} that
     * do not already exist in {@code target}. Section (intermediate) paths are
     * skipped — they are created implicitly when their child keys are set.
     * <p>
     * Existing values in {@code target} are never overwritten, preserving any
     * customizations the server administrator has made.
     *
     * @param source the configuration to read default values from
     * @param target the configuration to write missing values into
     * @return {@code true} if at least one key was added
     */
    private static boolean mergeDefaults(YamlConfiguration source, YamlConfiguration target) {
        boolean modified = false;
        for (String path : source.getKeys(true)) {
            // Skip section paths — only copy leaf values to avoid overwriting
            // sections with MemorySection objects
            if (source.isConfigurationSection(path))
                continue;
            if (!target.contains(path)) {
                target.set(path, source.get(path));
                modified = true;
            }
        }
        return modified;
    }

    /**
     * Loads all {@code .yml} files from a directory into the given map.
     * Files whose name (without extension) already exists in the map are
     * skipped — this prevents re-loading bundled language files that were
     * already processed with defaults merging.
     *
     * @param dir    the directory to scan
     * @param target the map to populate (key = filename without {@code .yml})
     */
    private void loadDirectory(File dir, Map<String, YamlConfiguration> target) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null)
            return;
        for (File file : files) {
            String name = file.getName().replace(".yml", "");
            if (!target.containsKey(name))
                target.put(name, loadFromFile(file));
        }
    }

    /**
     * Reads an entire {@link InputStream} as a UTF-8 string and closes it.
     *
     * @param stream the input stream to read
     * @return the full content as a string
     * @throws IOException if an I/O error occurs
     */
    private static String readStream(InputStream stream) throws IOException {
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[BUFFER_SIZE];
        int n;
        while ((n = reader.read(buf)) != -1)
            sb.append(buf, 0, n);
        reader.close();
        return sb.toString();
    }

    /**
     * Converts backslash-u hex escape sequences in a string to their actual
     * Unicode characters. SnakeYAML may produce these escapes for non-ASCII
     * characters depending on the JVM locale and Spigot version — this
     * method ensures saved YAML files remain human-readable for server
     * administrators working with non-Latin scripts.
     *
     * @param input the string potentially containing Unicode escapes
     * @return the string with escapes replaced by real characters
     */
    private static String unescapeUnicode(String input) {
        // Fast path: most configs contain no escapes at all
        if (!input.contains("\\u"))
            return input;
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && i + 5 < input.length() && input.charAt(i + 1) == 'u') {
                String hex = input.substring(i + 2, i + 6);
                try {
                    sb.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not a valid escape — fall through and append the backslash
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ── Lang implementation ────────────────────────────────────────────────

    /**
     * Internal {@link Lang} implementation backed by a {@link YamlConfiguration}.
     * <p>
     * If a key is not found in this language's config, the lookup falls back
     * to the {@code fallback} config (typically the default language). If the
     * key is still not found, the raw key name is returned so the player sees
     * a visible placeholder instead of a blank or {@code null} message.
     */
    @RequiredArgsConstructor
    private static final class ConfigLang implements Lang {

        /** Language name (e.g. "en", "ru"). */
        @NonNull
        private final String name;

        /** This language's YAML config. */
        @NonNull
        private final YamlConfiguration config;

        /** Default language config for fallback, or {@code null} if this IS the default. */
        private final YamlConfiguration fallback;

        @Override
        public String name() {
            return name;
        }

        @Override
        public String get(@NonNull String key) {
            String value = config.getString(key);
            // Fall back to the default language if this translation is missing
            if (value == null && fallback != null)
                value = fallback.getString(key);
            // Return the key itself as a last resort — a visible placeholder
            // is better than null for debugging missing translations
            return value != null ? value : key;
        }

        @Override
        public String get(@NonNull String key, Object... replacements) {
            String message = get(key);
            // Replacements come in pairs: placeholder, value, placeholder, value, ...
            for (int i = 0; i < replacements.length - 1; i += 2)
                message = message.replace(
                        String.valueOf(replacements[i]),
                        String.valueOf(replacements[i + 1]));
            return message;
        }

        @Override
        public boolean has(@NonNull String key) {
            return config.contains(key);
        }

        @Override
        public List<String> getList(@NonNull String key) {
            // Honor scalar values too — admins may write either a single string
            // or a YAML list at the same key. A scalar gets wrapped as a
            // single-element list; an actual list is returned verbatim.
            List<String> list = readList(config, key);
            if (list == null && fallback != null)
                list = readList(fallback, key);
            return list != null ? list : Collections.emptyList();
        }

        /**
         * Reads the given key as either a string list or a scalar. Returns
         * {@code null} when the key is absent — this preserves the distinction
         * between "missing" (try the fallback) and "empty list" (admin
         * intentionally cleared the variants).
         */
        private static List<String> readList(YamlConfiguration src, String key) {
            if (!src.contains(key))
                return null;
            if (src.isList(key))
                return src.getStringList(key);
            String scalar = src.getString(key);
            return scalar == null ? null : Collections.singletonList(scalar);
        }
    }
}

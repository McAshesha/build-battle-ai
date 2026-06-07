package ru.ashesha.buildBattleAI.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link ConfigService} covering risk <b>THIN-CFG</b>:
 * <em>Config reload (shutdown → enable) preserves arena configs persisted to disk.</em>
 *
 * <h3>Invariant</h3>
 * After {@code shutdown()} clears all in-memory state, a subsequent {@code enable()} call
 * must re-read every {@code .yml} file present in the {@code arena/} directory from disk.
 * Any mutations saved via {@code saveArenaConfig} before shutdown must survive the reload
 * cycle unmodified. Likewise, arenas deleted via {@code deleteArenaConfig} must not
 * reappear after reload (the file must have been removed from disk).
 *
 * <h3>Why integration (not unit)</h3>
 * The correctness of this scenario depends on real filesystem interaction — two
 * distinct {@code enable()} calls touching the same directory tree, with the
 * in-memory map cleared between them. A pure unit test with mocked I/O cannot
 * exercise the YAML serialize → disk write → disk read → deserialize round-trip
 * that is the actual risk. MockBukkit is not needed because {@code ConfigService}
 * itself is Bukkit-free — only its {@code BuildBattleAI} dependency requires mocking.
 */
@Tag("integration")
class ConfigReloadIT {

    /** Minimal bundled {@code config.yml} content returned by the mock plugin. */
    private static final String CONFIG_YML = "default-language: en\n";

    /** Minimal bundled {@code lang/en.yml} content returned by the mock plugin. */
    private static final String EN_YML = "greeting: Hello!\n";

    /**
     * Isolated plugin data folder — created fresh for every test method and
     * cleaned up automatically by JUnit after the test completes.
     */
    @TempDir
    File dataFolder;

    /** Mocked plugin instance. Does not require a running Bukkit server. */
    private BuildBattleAI plugin;

    /** The service under test, backed by the temporary data folder. */
    private ConfigService service;

    /**
     * Sets up a mock plugin that returns a real {@link PluginLogger} and
     * a fresh stream for every bundled resource request. The service is
     * constructed but NOT enabled here — each test controls the lifecycle.
     */
    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("ConfigReloadIT")));
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigReloadIT"));
        service = new ConfigService(plugin);
    }

    /**
     * Ensures the service is always shut down after each test so no thread-local
     * or filesystem state leaks between test methods.
     */
    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ── helper: stub bundled resources ────────────────────────────────────────

    /**
     * Re-stubs all plugin resource streams. Must be called before every
     * {@code service.enable()} because each {@link java.io.InputStream} can
     * only be consumed once — re-using the same stream instance would yield
     * empty content on the second enable cycle.
     */
    private void stubResources() {
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(CONFIG_YML.getBytes(StandardCharsets.UTF_8)));
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream(EN_YML.getBytes(StandardCharsets.UTF_8)));
        // ConfigService iterates BUNDLED_LANGS = {"en", "ru"} — stub "ru" too so
        // copyResource gets a non-null stream and does not warn about a missing resource.
        when(plugin.getResource("lang/ru.yml"))
                .thenReturn(new ByteArrayInputStream("greeting: Privet!\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ── file I/O helpers ─────────────────────────────────────────────────────

    /**
     * Reads a file from the temporary data folder as a UTF-8 string.
     *
     * @param relativePath path relative to {@link #dataFolder}
     * @return file contents as a string
     */
    private String readFile(String relativePath) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(dataFolder, relativePath).toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes a UTF-8 string to a file under the temporary data folder,
     * creating parent directories as necessary.
     *
     * @param relativePath path relative to {@link #dataFolder}
     * @param content      content to write
     */
    private void writeFile(String relativePath, String content) throws IOException {
        File target = new File(dataFolder, relativePath);
        target.getParentFile().mkdirs();
        OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8);
        writer.write(content);
        writer.flush();
        writer.close();
    }

    // ── test methods ─────────────────────────────────────────────────────────

    /**
     * Core THIN-CFG scenario: two arena configs created, one mutated and saved,
     * then {@code shutdown()} + {@code enable()} is called. Both arenas must
     * reappear and the mutated value must survive the round-trip to disk.
     */
    @Test
    void arenaConfigsSurviveReload() {
        // ── first enable ────────────────────────────────────────────────────
        stubResources();
        service.enable();

        // Create two arenas.
        YamlConfiguration alpha = service.createArenaConfig("alpha");
        alpha.set("max-players", 4);
        service.saveArenaConfig("alpha");

        service.createArenaConfig("beta");

        // Confirm both exist in-memory and on disk.
        assertNotNull(service.getArenaConfig("alpha"), "alpha must exist before reload");
        assertNotNull(service.getArenaConfig("beta"), "beta must exist before reload");
        assertTrue(new File(dataFolder, "arena/alpha.yml").exists(), "alpha.yml must be on disk");
        assertTrue(new File(dataFolder, "arena/beta.yml").exists(), "beta.yml must be on disk");

        // ── shutdown clears in-memory state ─────────────────────────────────
        service.shutdown();

        // After shutdown the in-memory maps are empty; files still exist on disk.
        assertTrue(service.getArenaNames().isEmpty(),
                "getArenaNames() must be empty immediately after shutdown");
        assertNull(service.getArenaConfig("alpha"),
                "getArenaConfig must return null after shutdown");

        // ── second enable re-reads from disk ─────────────────────────────────
        stubResources();
        service.enable();

        Set<String> names = service.getArenaNames();
        assertTrue(names.contains("alpha"), "alpha must reappear after reload");
        assertTrue(names.contains("beta"), "beta must reappear after reload");

        // Mutation made before shutdown must be persisted and readable again.
        YamlConfiguration reloadedAlpha = service.getArenaConfig("alpha");
        assertNotNull(reloadedAlpha, "alpha config must not be null after reload");
        assertEquals(4, reloadedAlpha.getInt("max-players"),
                "max-players mutation must survive shutdown → enable cycle");
    }

    /**
     * An arena deleted via {@link ConfigService#deleteArenaConfig(String)} must
     * not reappear after a reload cycle, because {@code deleteArenaConfig}
     * removes the backing file from disk and {@code enable()} only loads files
     * that are present on disk.
     */
    @Test
    void deletedArenaDoesNotResurrectOnReload() {
        // ── first enable ────────────────────────────────────────────────────
        stubResources();
        service.enable();

        service.createArenaConfig("gamma");
        service.createArenaConfig("delta");

        // Delete "gamma" — this must remove it from disk.
        service.deleteArenaConfig("gamma");

        File gammaFile = new File(dataFolder, "arena/gamma.yml");
        assertFalse(gammaFile.exists(),
                "gamma.yml must be deleted from disk by deleteArenaConfig");

        // ── reload cycle ────────────────────────────────────────────────────
        service.shutdown();
        stubResources();
        service.enable();

        // "gamma" was removed from disk — it must not come back.
        assertNull(service.getArenaConfig("gamma"),
                "deleted arena must not resurface after reload");

        // "delta" was not deleted — it must still be present.
        assertNotNull(service.getArenaConfig("delta"),
                "non-deleted arena delta must survive reload");
    }

    /**
     * When the plugin data folder is empty on first run, {@code enable()} must
     * extract bundled language files into {@code lang/}. This exercises the
     * {@code copyResource} branch inside {@code loadWithDefaults} and verifies
     * that the extracted file is readable on disk after enable.
     *
     * <p>The risk being checked: if JAR extraction is broken, all translation
     * lookups silently fall through to raw key names, which players would see
     * instead of actual messages.
     */
    @Test
    void langConfigsRecreatedFromBundledOnFreshDataDir() {
        // Fresh dataFolder — nothing on disk yet.
        stubResources();
        service.enable();

        // The bundled en.yml must have been extracted.
        File enFile = new File(dataFolder, "lang/en.yml");
        assertTrue(enFile.exists(), "en.yml must be extracted to lang/ on fresh data folder");

        // Content must be readable and contain the bundled key.
        String enLang = service.getLang("en").get("greeting");
        assertNotNull(enLang, "greeting key must resolve after extraction");
        assertFalse(enLang.isEmpty(), "greeting value must not be empty");
    }

    /**
     * After a reload, mutated arena config values not explicitly saved before
     * shutdown are NOT expected to persist — this documents the intentional
     * design decision that persistence is the caller's responsibility. The test
     * verifies that an unsaved mutation is silently discarded, preventing any
     * future refactor from accidentally auto-saving on shutdown.
     */
    @Test
    void unsavedMutationsAreDiscardedOnReload() {
        // ── first enable ────────────────────────────────────────────────────
        stubResources();
        service.enable();

        YamlConfiguration epsilon = service.createArenaConfig("epsilon");
        // Mutate in-memory but do NOT call saveArenaConfig.
        epsilon.set("ephemeral-key", "should-not-persist");

        // ── reload without save ──────────────────────────────────────────────
        service.shutdown();
        stubResources();
        service.enable();

        // The arena file was created (empty) by createArenaConfig, so it exists
        // on disk and loads back — but without the unsaved mutation.
        YamlConfiguration reloaded = service.getArenaConfig("epsilon");
        assertNotNull(reloaded, "epsilon arena must reload from disk");
        assertFalse(reloaded.contains("ephemeral-key"),
                "unsaved mutation must not persist across shutdown → enable");
    }
}

package ru.ashesha.buildBattleAI.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.config.api.Lang;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ConfigService}.
 * <p>
 * Uses a temporary directory as the plugin data folder and mocks
 * {@link BuildBattleAI#getResource(String)} to simulate bundled JAR resources.
 * This exercises config loading, lang fallback, arena CRUD, defaults merging,
 * and the Unicode unescape pipeline without a live Bukkit server.
 */
class ConfigServiceTest {

    @TempDir
    File dataFolder;

    private BuildBattleAI plugin;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigServiceTest"));

        // Simulate bundled config.yml resource
        String configYml = "default-language: en\nsome-key: default-value\n";
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(configYml.getBytes(StandardCharsets.UTF_8)));

        // Simulate bundled lang/en.yml resource
        String enYml = "greeting: \"Hello, %player%!\"\nfarewell: Goodbye!\n";
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream(enYml.getBytes(StandardCharsets.UTF_8)));

        service = new ConfigService(plugin);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // -- main config tests --------------------------------------------------

    @Test
    void enableCreatesConfigFile() {
        File configFile = new File(dataFolder, "config.yml");
        assertTrue(configFile.exists(), "config.yml should be created on disk");
    }

    @Test
    void configReturnsLoadedYaml() {
        YamlConfiguration config = service.config();
        assertNotNull(config);
        assertEquals("en", config.getString("default-language"));
        assertEquals("default-value", config.getString("some-key"));
    }

    @Test
    void saveConfigPersistsToDisk() throws Exception {
        service.config().set("custom-key", "custom-value");
        service.saveConfig();

        // Read the file back and verify
        String content = readFile(new File(dataFolder, "config.yml"));
        assertTrue(content.contains("custom-key"), "Saved file should contain custom-key");
        assertTrue(content.contains("custom-value"), "Saved file should contain custom-value");
    }

    @Test
    void existingConfigPreservesUserValues() {
        // Write a config file with user-customized values before enable
        service.shutdown();
        File configFile = new File(dataFolder, "config.yml");
        writeFile(configFile, "default-language: ru\nsome-key: user-override\n");

        // Re-stub the resource since enable() reads it again
        String configYml = "default-language: en\nsome-key: default-value\nnew-key: new-value\n";
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(configYml.getBytes(StandardCharsets.UTF_8)));

        service.enable();

        // User values should be preserved, new keys should be merged
        assertEquals("ru", service.config().getString("default-language"));
        assertEquals("user-override", service.config().getString("some-key"));
        assertEquals("new-value", service.config().getString("new-key"));
    }

    // -- language tests -----------------------------------------------------

    @Test
    void enableCreatesLangDirectory() {
        File langDir = new File(dataFolder, "lang");
        assertTrue(langDir.exists() && langDir.isDirectory());
    }

    @Test
    void enableCreatesEnLangFile() {
        File enFile = new File(dataFolder, "lang/en.yml");
        assertTrue(enFile.exists(), "en.yml should be extracted from bundled resources");
    }

    @Test
    void defaultLangIsResolved() {
        Lang lang = service.getDefaultLang();
        assertNotNull(lang);
        assertEquals("en", lang.name());
    }

    @Test
    void langGetReturnsTranslatedMessage() {
        Lang lang = service.getDefaultLang();
        assertEquals("Hello, %player%!", lang.get("greeting"));
    }

    @Test
    void langGetWithReplacementsSubstitutesPlaceholders() {
        Lang lang = service.getDefaultLang();
        String result = lang.get("greeting", "%player%", "Steve");
        assertEquals("Hello, Steve!", result);
    }

    @Test
    void langGetReturnsKeyWhenMissing() {
        Lang lang = service.getDefaultLang();
        assertEquals("nonexistent.key", lang.get("nonexistent.key"));
    }

    @Test
    void langHasReturnsTrueForExistingKey() {
        Lang lang = service.getDefaultLang();
        assertTrue(lang.has("greeting"));
    }

    @Test
    void langHasReturnsFalseForMissingKey() {
        Lang lang = service.getDefaultLang();
        assertFalse(lang.has("nonexistent.key"));
    }

    @Test
    void getLangFallsBackToDefaultForUnknownLang() {
        Lang lang = service.getLang("unknown");
        assertNotNull(lang);
        // Should return the default language as fallback
        assertEquals("en", lang.name());
    }

    @Test
    void getLangReturnsRequestedLangWhenAvailable() {
        Lang lang = service.getLang("en");
        assertNotNull(lang);
        assertEquals("en", lang.name());
    }

    @Test
    void getAvailableLangsContainsEn() {
        Set<String> langs = service.getAvailableLangs();
        assertTrue(langs.contains("en"));
    }

    @Test
    void getAvailableLangsIsUnmodifiable() {
        Set<String> langs = service.getAvailableLangs();
        assertThrows(UnsupportedOperationException.class, () -> langs.add("test"));
    }

    @Test
    void additionalLangFilesAreLoaded() {
        service.shutdown();

        // Create an additional lang file on disk
        File langDir = new File(dataFolder, "lang");
        writeFile(new File(langDir, "ru.yml"), "greeting: \"Privet, %player%!\"\n");

        // Re-stub resources for enable
        String configYml = "default-language: en\n";
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(configYml.getBytes(StandardCharsets.UTF_8)));
        String enYml = "greeting: \"Hello, %player%!\"\nfarewell: Goodbye!\n";
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream(enYml.getBytes(StandardCharsets.UTF_8)));

        service.enable();

        Set<String> langs = service.getAvailableLangs();
        assertTrue(langs.contains("ru"), "Additional lang file ru.yml should be loaded");
        Lang ruLang = service.getLang("ru");
        assertEquals("ru", ruLang.name());
        assertEquals("Privet, %player%!", ruLang.get("greeting"));
    }

    @Test
    void secondaryLangFallsBackToDefaultForMissingKeys() {
        service.shutdown();

        // Create a partial translation file
        File langDir = new File(dataFolder, "lang");
        writeFile(new File(langDir, "de.yml"), "greeting: Hallo!\n");

        // Re-stub resources
        String configYml = "default-language: en\n";
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(configYml.getBytes(StandardCharsets.UTF_8)));
        String enYml = "greeting: Hello!\nfarewell: Goodbye!\n";
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream(enYml.getBytes(StandardCharsets.UTF_8)));

        service.enable();

        Lang deLang = service.getLang("de");
        assertEquals("Hallo!", deLang.get("greeting"));
        // "farewell" is missing in de.yml — should fall back to en
        assertEquals("Goodbye!", deLang.get("farewell"));
    }

    @Test
    void defaultLanguageFallbackWhenConfiguredLangMissing() {
        service.shutdown();

        // Config references a non-existent lang — service should fall back.
        // Overwrite config.yml to point at a non-existent language.
        writeFile(new File(dataFolder, "config.yml"), "default-language: nonexistent\n");

        // Re-stub resources for the enable cycle
        String configYml = "default-language: nonexistent\n";
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream(configYml.getBytes(StandardCharsets.UTF_8)));
        String enYml = "greeting: Hello!\n";
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream(enYml.getBytes(StandardCharsets.UTF_8)));

        service.enable();

        // Should fall back to the first available lang (en).
        // The en.yml on disk already has "greeting" from setUp — that value
        // is preserved (user overrides are not overwritten by bundled defaults).
        Lang defaultLang = service.getDefaultLang();
        assertNotNull(defaultLang);
        // The greeting value was written by setUp's enable(), so it matches
        // the original bundled resource used in setUp.
        assertNotNull(defaultLang.get("greeting"));
        assertNotEquals("greeting", defaultLang.get("greeting"),
                "Fallback lang should resolve an actual message, not the raw key");
    }

    // -- arena config tests -------------------------------------------------

    @Test
    void enableCreatesArenaDirectory() {
        File arenaDir = new File(dataFolder, "arena");
        assertTrue(arenaDir.exists() && arenaDir.isDirectory());
    }

    @Test
    void createArenaConfigCreatesFileAndReturnsConfig() {
        YamlConfiguration arenaConfig = service.createArenaConfig("lobby");
        assertNotNull(arenaConfig);

        File arenaFile = new File(dataFolder, "arena/lobby.yml");
        assertTrue(arenaFile.exists(), "Arena file should be written to disk");
    }

    @Test
    void getArenaConfigReturnsCreatedArena() {
        service.createArenaConfig("test-arena");
        YamlConfiguration config = service.getArenaConfig("test-arena");
        assertNotNull(config);
    }

    @Test
    void getArenaConfigReturnsNullForNonexistent() {
        assertNull(service.getArenaConfig("nonexistent"));
    }

    @Test
    void saveArenaConfigPersistsToDisk() throws Exception {
        YamlConfiguration arenaConfig = service.createArenaConfig("pvp");
        arenaConfig.set("max-players", 16);
        service.saveArenaConfig("pvp");

        String content = readFile(new File(dataFolder, "arena/pvp.yml"));
        assertTrue(content.contains("max-players"), "Saved arena file should contain max-players");
    }

    @Test
    void saveArenaConfigNoOpForNonexistent() {
        // Should not throw
        service.saveArenaConfig("nonexistent");
    }

    @Test
    void deleteArenaConfigRemovesFromCacheAndDisk() {
        service.createArenaConfig("temp");
        File arenaFile = new File(dataFolder, "arena/temp.yml");
        assertTrue(arenaFile.exists());

        service.deleteArenaConfig("temp");
        assertNull(service.getArenaConfig("temp"));
        assertFalse(arenaFile.exists(), "Arena file should be deleted from disk");
    }

    @Test
    void getArenaNamesReturnsAllCreatedArenas() {
        service.createArenaConfig("a1");
        service.createArenaConfig("a2");

        Set<String> names = service.getArenaNames();
        assertTrue(names.contains("a1"));
        assertTrue(names.contains("a2"));
    }

    @Test
    void getArenaNamesIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getArenaNames().add("hack"));
    }

    @Test
    void existingArenaFilesAreLoadedOnEnable() {
        service.shutdown();

        // Place an arena file on disk before enable
        File arenaDir = new File(dataFolder, "arena");
        writeFile(new File(arenaDir, "preexisting.yml"), "spawn-x: 100\n");

        // Re-stub resources
        when(plugin.getResource("config.yml"))
                .thenReturn(new ByteArrayInputStream("default-language: en\n".getBytes(StandardCharsets.UTF_8)));
        when(plugin.getResource("lang/en.yml"))
                .thenReturn(new ByteArrayInputStream("greeting: Hello!\n".getBytes(StandardCharsets.UTF_8)));

        service.enable();

        YamlConfiguration config = service.getArenaConfig("preexisting");
        assertNotNull(config, "Pre-existing arena file should be loaded");
        assertEquals(100, config.getInt("spawn-x"));
    }

    // -- shutdown tests -----------------------------------------------------

    @Test
    void shutdownClearsAllState() {
        service.createArenaConfig("test");
        service.shutdown();

        assertNull(service.config());
        assertNull(service.getDefaultLang());
        assertNull(service.getArenaConfig("test"));
        assertTrue(service.getArenaNames().isEmpty());
        assertTrue(service.getAvailableLangs().isEmpty());
    }

    // -- Unicode unescape (tested indirectly via save/load cycle) -----------

    @Test
    void unicodeCharactersPreservedThroughSaveCycle() throws Exception {
        service.config().set("message", "\u0410\u0411\u0412");
        service.saveConfig();

        String content = readFile(new File(dataFolder, "config.yml"));
        // The saved file should contain real Unicode chars, not \\uXXXX escapes
        assertFalse(content.contains("\\u0410"),
                "Unicode escapes should be converted to real characters");
    }

    // -- UTF-8 encoding test ------------------------------------------------

    @Test
    void utf8EncodingPreservedOnDisk() throws Exception {
        String cyrillic = "\u041F\u0440\u0438\u0432\u0435\u0442";
        service.config().set("test-utf8", cyrillic);
        service.saveConfig();

        // Read back with explicit UTF-8 and verify
        byte[] bytes = Files.readAllBytes(new File(dataFolder, "config.yml").toPath());
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains(cyrillic), "Cyrillic characters should be preserved in UTF-8");
    }

    // -- helpers ------------------------------------------------------------

    private static String readFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeFile(File file, String content) {
        try {
            file.getParentFile().mkdirs();
            OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8);
            writer.write(content);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

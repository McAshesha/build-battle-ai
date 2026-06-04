package ru.ashesha.buildBattleAI.integration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build-artifact smoke test that validates the pieces a real Bukkit server
 * needs to load this plugin, without actually booting one.
 * <p>
 * The plugin's main class is declared {@code final}, so MockBukkit's
 * ByteBuddy-based plugin loader cannot subclass it — a true "load the
 * real plugin under MockBukkit" test would require dropping the {@code final}
 * modifier on production code, which trades a design property for a single
 * test. This smoke test instead exercises the parts that are most likely
 * to silently break a release:
 * <ul>
 *   <li><b>{@code plugin.yml} on the classpath.</b> Maven resource
 *       filtering rewrites this file from {@code ${plugin.*}} placeholders in
 *       {@code pom.xml}. A misconfigured filter (or an accidental binary
 *       resource being filtered through) corrupts the file silently — the
 *       plugin won't load on any server, and only an in-JVM check catches
 *       it.</li>
 *   <li><b>{@code main:} field points at a loadable class.</b> Shading or
 *       obfuscation can rename the class out from under the manifest entry;
 *       the test reflectively resolves it to confirm both directions of the
 *       link remain consistent.</li>
 *   <li><b>{@code name:} and {@code version:} are non-empty.</b> Empty
 *       fields here cause every other Bukkit lookup to misbehave, but the
 *       failure mode on production servers is opaque.</li>
 * </ul>
 * Together with {@link ru.ashesha.buildBattleAI.mockbukkit.MockBukkitSmokeTest}
 * (framework wiring) and {@link ru.ashesha.buildBattleAI.core.PluginContextLifecycleTest}
 * (mocked service lifecycle), this rounds out the boot-time coverage that
 * existed before this commit.
 */
class RealPluginBootstrapTest {

    /**
     * Loads the filtered {@code plugin.yml} from the test classpath and
     * exposes it as a parsed {@link YamlConfiguration}. The classpath is the
     * same one a running Bukkit server sees, so any Maven-side regression
     * (filtering misconfigured, encoding mangled, file excluded) surfaces
     * here.
     */
    private static YamlConfiguration loadPluginYaml() throws IOException {
        try (InputStream in = RealPluginBootstrapTest.class
                .getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in,
                    "plugin.yml is missing from the test classpath — "
                            + "Maven probably excluded it from filtered resources.");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration cfg = new YamlConfiguration();
                try {
                    cfg.load(reader);
                } catch (Exception e) {
                    fail("plugin.yml on the classpath is not parseable as YAML — "
                            + "Maven resource filtering likely corrupted it. Root cause: " + e);
                }
                return cfg;
            }
        }
    }

    /**
     * Validates the placeholders that Maven resource filtering substitutes
     * have all been replaced — any {@code ${...}} that survives is a
     * silent release breakage.
     */
    @Test
    void pluginYamlHasNoUnresolvedMavenPlaceholders() throws IOException {
        try (InputStream in = RealPluginBootstrapTest.class
                .getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in, "plugin.yml must exist on the classpath");
            byte[] raw = readAllBytes(in);
            String text = new String(raw, StandardCharsets.UTF_8);
            // ${plugin.main} / ${plugin.prefix} / ${plugin.authors} should all
            // have been replaced. Any surviving "${" implies a misconfigured
            // filter and would be an opaque server-side error.
            int placeholder = text.indexOf("${");
            assertEquals(-1, placeholder,
                    "plugin.yml still contains an unresolved ${...} placeholder around byte "
                            + placeholder + " — Maven resource filtering missed it. Context:\n"
                            + safeWindow(text, placeholder, 80));
        }
    }

    /**
     * Verifies the {@code main:} field references a real, loadable class.
     * Catches refactor regressions where the plugin's main class is renamed
     * or moved without updating {@code pom.xml}'s {@code plugin.main}
     * property.
     */
    @Test
    void mainClassIsLoadable() throws IOException, ClassNotFoundException {
        YamlConfiguration cfg = loadPluginYaml();
        String mainClassName = cfg.getString("main");
        assertNotNull(mainClassName, "plugin.yml must declare a 'main:' field");
        assertFalse(mainClassName.isEmpty(), "'main:' field must be non-empty");

        Class<?> mainClass = Class.forName(mainClassName, false,
                RealPluginBootstrapTest.class.getClassLoader());
        assertTrue(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(mainClass),
                "main class " + mainClassName + " must extend JavaPlugin");
    }

    /**
     * Verifies the core plugin metadata is populated. A blank {@code name}
     * or {@code version} causes downstream Bukkit lookups to misbehave on
     * production servers in ways that are very hard to diagnose post-hoc.
     */
    @Test
    void pluginMetadataIsPopulated() throws IOException {
        YamlConfiguration cfg = loadPluginYaml();

        String name = cfg.getString("name");
        assertNotNull(name, "plugin.yml must declare 'name:'");
        assertFalse(name.trim().isEmpty(), "'name:' must be non-empty");

        String version = cfg.getString("version");
        assertNotNull(version, "plugin.yml must declare 'version:'");
        assertFalse(version.trim().isEmpty(), "'version:' must be non-empty");

        // 'authors:' is filtered from ${plugin.authors} in pom.xml. We accept
        // either a list, a single string, or absence (authors are optional);
        // what we really care about is that whatever exists isn't a stale
        // placeholder.
        Object authorsField = cfg.get("authors");
        if (authorsField != null)
            assertFalse(authorsField.toString().contains("${"),
                    "authors field still contains an unresolved placeholder: " + authorsField);
    }

    /**
     * Verifies the {@code api-version} declaration is present when targeting
     * 1.13+ servers — its absence triggers Bukkit's legacy material
     * conversion path, which silently rewrites modern resource references
     * to their pre-flattening counterparts.
     * <p>
     * Soft-asserts: missing api-version is permitted (it's optional for
     * legacy 1.8–1.12 builds), but a malformed entry that includes a
     * placeholder is an outright build break.
     */
    @Test
    void apiVersionFieldIsConsistent() throws IOException {
        YamlConfiguration cfg = loadPluginYaml();
        String apiVersion = cfg.getString("api-version");
        if (apiVersion != null)
            assertFalse(apiVersion.contains("${"),
                    "api-version still contains an unresolved placeholder: " + apiVersion);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Reads an InputStream to a byte array. Replaces {@code InputStream#readAllBytes}
     * which is Java 9+ — this project targets Java 8.
     */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Returns a windowed substring around the given index — used to provide
     * a focused context excerpt in failure messages without dumping the
     * whole file.
     */
    private static String safeWindow(String text, int index, int span) {
        if (index < 0)
            return "";
        int start = Math.max(0, index - span);
        int end = Math.min(text.length(), index + span);
        return "  ..." + text.substring(start, end).replace("\n", "\\n") + "...";
    }
}

package ru.ashesha.buildBattleAI.arena;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.arena.api.Arena;
import ru.ashesha.buildBattleAI.config.api.BBAIConfigService;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.world.api.BBAIWorldService;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test covering risk <b>ARENA-01</b>: "YAML validation reports ALL
 * missing fields — it does NOT short-circuit on the first error."
 *
 * <p>Invariant: when a YAML config has N distinct missing required fields,
 * {@code ArenaManager#deserializeArena} must log exactly N field-level error
 * messages before returning {@code null}. If the validator short-circuits after
 * the first missing field, fewer than N messages are captured and the test fails.
 *
 * <p>Tier rationale: "integration" rather than unit — the test wires a real
 * {@link ArenaManager} (not a mock) with Mockito-stubbed collaborators and
 * exercises the full validation code path, including the private method, to
 * catch regressions that a fragile unit mock might paper over.
 */
@Tag("integration")
class YamlValidationCompletenessIT {

    private BuildBattleAI plugin;
    private PluginLogger pluginLogger;
    private ArenaManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        pluginLogger = mock(PluginLogger.class);
        PluginContext context = mock(PluginContext.class);
        BBAIConfigService configService = mock(BBAIConfigService.class);
        BBAIWorldService worldService = mock(BBAIWorldService.class);

        when(plugin.getPluginLogger()).thenReturn(pluginLogger);
        when(plugin.getContext()).thenReturn(context);
        when(context.getConfigService()).thenReturn(configService);
        when(context.getWorldService()).thenReturn(worldService);
        when(configService.getArenaNames()).thenReturn(Collections.<String>emptySet());

        manager = new ArenaManager(plugin);
    }

    /**
     * Verifies that all missing required fields are reported in a single
     * validation pass without short-circuiting.
     *
     * <p>The config has {@code max-players=2} so the per-plot loop runs.
     * Deliberately omitted fields:
     * <ol>
     *   <li>{@code lobby} (global required)</li>
     *   <li>{@code plots.1.spawn} (plot spawn)</li>
     *   <li>{@code plots.1.corner1} (build-zone corner)</li>
     *   <li>{@code plots.1.camera2} (second camera)</li>
     *   <li>{@code plots.1.picture.corner1} (picture region corner)</li>
     * </ol>
     * All five must appear in the captured error log. If only the first appears,
     * the validator short-circuited and the assertion on subsequent fields fails.
     */
    @Test
    void allMissingFieldsReported() throws Exception {
        // Build a minimal config — only max-players is set so that the
        // per-plot loop is entered (maxPlayers >= 2), but no spatial fields
        // are provided at all.
        YamlConfiguration config = new YamlConfiguration();
        config.set("max-players", 2);
        // world has a default, enabled has a default — omit both intentionally.
        // lobby: deliberately missing (field 1)
        // plots.1.spawn: missing (field 2)
        // plots.1.corner1: missing (field 3)
        // plots.1.camera2: missing — we provide camera1 and camera3 to prove
        //   the loop doesn't bail on the first missing camera (field 4).
        config.set("plots.1.camera1.x", 10.0);
        config.set("plots.1.camera1.y", 70.0);
        config.set("plots.1.camera1.z", -5.0);
        config.set("plots.1.camera1.yaw", 0.0);
        config.set("plots.1.camera1.pitch", 30.0);
        // camera2: missing (field 4)
        config.set("plots.1.camera3.x", 12.0);
        config.set("plots.1.camera3.y", 70.0);
        config.set("plots.1.camera3.z", -5.0);
        config.set("plots.1.camera3.yaw", 60.0);
        config.set("plots.1.camera3.pitch", 30.0);
        // plots.1.picture.corner1: missing (field 5); corner2 and face also missing
        // (we assert only the 5 target fields to keep the test focused)

        // Invoke the private deserializeArena method via reflection.
        Method deserialize = ArenaManager.class.getDeclaredMethod(
                "deserializeArena", String.class, YamlConfiguration.class);
        deserialize.setAccessible(true);
        Arena result = (Arena) deserialize.invoke(manager, "completeness_check", config);

        // Contract: invalid config must return null.
        assertNull(result, "deserializeArena must return null when required fields are missing");

        // Capture all two-argument error calls so we can inspect every message.
        ArgumentCaptor<String> formatCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(pluginLogger, atLeastOnce()).error(formatCaptor.capture(), (Object[]) argsCaptor.capture());

        // Collect the second format argument (the field description) from each
        // call that matches the per-field pattern "Arena '%s': %s".
        List<String> formats = formatCaptor.getAllValues();
        List<Object[]> argsList = argsCaptor.getAllValues();
        java.util.Set<String> fieldMessages = new java.util.HashSet<String>();
        for (int i = 0; i < formats.size(); i++) {
            if ("Arena '%s': %s".equals(formats.get(i))) {
                Object[] a = argsList.get(i);
                if (a.length >= 2)
                    fieldMessages.add(String.valueOf(a[1]));
            }
        }

        // Assert every expected missing-field message was logged.
        // Failure of any single assertion proves the validator short-circuited.
        assertTrue(fieldMessages.contains("missing 'lobby'"),
                "Expected missing 'lobby' to be reported; got: " + fieldMessages);
        assertTrue(fieldMessages.contains("missing 'plots.1.spawn'"),
                "Expected missing 'plots.1.spawn' to be reported; got: " + fieldMessages);
        assertTrue(fieldMessages.contains("missing 'plots.1.corner1'"),
                "Expected missing 'plots.1.corner1' to be reported; got: " + fieldMessages);
        assertTrue(fieldMessages.contains("missing 'plots.1.camera2'"),
                "Expected missing 'plots.1.camera2' to be reported; got: " + fieldMessages);
        assertTrue(fieldMessages.contains("missing 'plots.1.picture.corner1'"),
                "Expected missing 'plots.1.picture.corner1' to be reported; got: " + fieldMessages);
    }
}

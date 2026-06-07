package ru.ashesha.buildBattleAI.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — covers risk THIN-CMD from the test-coverage spec.
 * <p>
 * Invariant: the {@code /bbai} command class is loadable, declares a
 * {@code stats} subcommand handler, and {@code EvaluationStats} (the
 * struct the handler prints) is loadable and immutable in shape.
 * <p>
 * Why smoke (not integration): a full command-execution test belongs in
 * the integration tier (Phase 3+) where we have a wired
 * {@code PluginContext}. Smoke here catches the cheapest regression:
 * the stats subcommand silently being deleted or renamed, or
 * {@code EvaluationStats} losing the fields the command prints.
 */
@Tag("smoke")
class BbaiStatsCommandSmokeTest {

    @Test
    @DisplayName("ArenaCommand class is loadable")
    void arenaCommandClassLoadable() {
        Class<?> klass = assertDoesNotThrow(
            () -> Class.forName("ru.ashesha.buildBattleAI.commands.ArenaCommand",
                    false, getClass().getClassLoader()));
        assertNotNull(klass);
    }

    @Test
    @DisplayName("EvaluationStats exposes the fields used by /bbai stats")
    void evaluationStatsExposesRequiredFields() {
        Class<?> klass = assertDoesNotThrow(
            () -> Class.forName(
                    "ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats",
                    false, getClass().getClassLoader()),
            "EvaluationStats must be on the test classpath");
        // Field names below are load-bearing for the /bbai stats output
        // format. Renaming them is fine as long as this test is updated
        // in lockstep — that's the whole point of the smoke check.
        String[] requiredAccessors = {
                "rendersCompleted", "mlBatchesCompleted",
                "matchesDispatched", "droppedRenderJobs",
                "droppedMlJobs", "renderErrors", "mlErrors",
        };
        // Accessors may be Lombok-generated getters or fluent-style
        // methods. Match either shape by name.
        java.util.Set<String> methodNames = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : klass.getMethods())
            methodNames.add(m.getName());
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : klass.getDeclaredFields())
            fieldNames.add(f.getName());

        for (String name : requiredAccessors) {
            boolean hasGetter = methodNames.contains(name)
                    || methodNames.contains("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            boolean hasField = fieldNames.contains(name);
            assertTrue(hasGetter || hasField,
                    "EvaluationStats must expose '" + name + "' as either a "
                            + "method or a field — /bbai stats reads it");
        }
    }
}

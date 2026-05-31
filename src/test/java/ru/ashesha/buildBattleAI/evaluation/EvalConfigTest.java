package ru.ashesha.buildBattleAI.evaluation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalConfigTest {

    @Test
    void defaults_appliedWhenSectionMissing() {
        YamlConfiguration y = new YamlConfiguration();
        EvalConfig c = EvalConfig.fromYaml(y);

        assertEquals(5000L, c.minCadenceMs());
        assertEquals(5, c.coordinatorTickPeriod());
        assertEquals(1, c.renderWorkers());
        assertEquals(64, c.renderQueueCapacity());
        assertEquals(8, c.mlBatchMaxSize());
        assertEquals(200L, c.mlBatchMaxWaitMs());
        assertEquals(64, c.mlQueueCapacity());
        assertEquals(2, c.mlTopK());
        assertEquals(60, c.metricsLogPeriodSeconds());
    }

    @Test
    void overrides_areRespected() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("evaluation.min-cadence-ms", 3000);
        y.set("evaluation.coordinator-tick-period", 10);
        y.set("evaluation.render-workers", 2);
        y.set("evaluation.render-queue-capacity", 128);
        y.set("evaluation.ml-batch-max-size", 16);
        y.set("evaluation.ml-batch-max-wait-ms", 100);
        y.set("evaluation.ml-queue-capacity", 128);
        y.set("evaluation.ml-top-k", 3);
        y.set("evaluation.metrics-log-period-seconds", 30);

        EvalConfig c = EvalConfig.fromYaml(y);
        assertEquals(3000L, c.minCadenceMs());
        assertEquals(10, c.coordinatorTickPeriod());
        assertEquals(2, c.renderWorkers());
        assertEquals(128, c.renderQueueCapacity());
        assertEquals(16, c.mlBatchMaxSize());
        assertEquals(100L, c.mlBatchMaxWaitMs());
        assertEquals(128, c.mlQueueCapacity());
        assertEquals(3, c.mlTopK());
        assertEquals(30, c.metricsLogPeriodSeconds());
    }
}

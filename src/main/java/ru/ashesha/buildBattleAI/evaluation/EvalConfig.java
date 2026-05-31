package ru.ashesha.buildBattleAI.evaluation;

import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable configuration snapshot for the evaluation pipeline. Loaded
 * once at {@code EvaluationService.enable()} from {@code config.yml}.
 */
@Value
@Accessors(fluent = true)
public class EvalConfig {

    long minCadenceMs;
    int coordinatorTickPeriod;
    int renderWorkers;
    int renderQueueCapacity;
    int mlBatchMaxSize;
    long mlBatchMaxWaitMs;
    int mlQueueCapacity;
    int mlTopK;
    int metricsLogPeriodSeconds;

    /**
     * Reads the {@code evaluation.*} section, applying documented defaults
     * for every missing key.
     */
    public static @NonNull EvalConfig fromYaml(@NonNull YamlConfiguration yaml) {
        return new EvalConfig(
                yaml.getLong("evaluation.min-cadence-ms", 5000L),
                yaml.getInt("evaluation.coordinator-tick-period", 5),
                yaml.getInt("evaluation.render-workers", 1),
                yaml.getInt("evaluation.render-queue-capacity", 64),
                yaml.getInt("evaluation.ml-batch-max-size", 8),
                yaml.getLong("evaluation.ml-batch-max-wait-ms", 200L),
                yaml.getInt("evaluation.ml-queue-capacity", 64),
                yaml.getInt("evaluation.ml-top-k", 2),
                yaml.getInt("evaluation.metrics-log-period-seconds", 60));
    }
}

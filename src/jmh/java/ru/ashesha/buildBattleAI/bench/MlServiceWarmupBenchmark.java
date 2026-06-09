package ru.ashesha.buildBattleAI.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.MLService;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JMH single-shot benchmark for {@link MLService} cold-start latency (risk ML-07).
 * <p>
 * Each iteration measures exactly one call to {@link MLService#enable()}, which
 * performs the full initialisation sequence: opening the {@code OrtEnvironment},
 * probing available execution providers (CoreML → CUDA → DirectML → ROCm → CPU),
 * loading and parsing {@code centroids.json}, then running two forward-pass warmup
 * calls at {@code batch=1} and {@code batch=TTA_VIEWS=4}. On a typical developer
 * machine this takes 1–5 seconds depending on which backend wins the probe.
 * <p>
 * <b>OrtEnvironment note:</b> {@code OrtEnvironment} is process-global — the first
 * call to {@code OrtEnvironment.getEnvironment()} initialises native ORT state for
 * the entire JVM and subsequent calls return the same singleton. This means the very
 * first {@code enable()} iteration (including the JMH warmup iteration) pays an extra
 * one-time native-library-loading cost that later iterations do not. The single warmup
 * iteration ({@code @Warmup(iterations = 1)}) is intentional: it absorbs this
 * amortised environment-init cost, so the five measurement iterations reflect the
 * repeatable session-open + centroid-load + forward-pass cost only.
 * <p>
 * <b>Model requirement:</b> The benchmark requires the bundled
 * {@code models/custom_convnext_embeddings.onnx} (~107 MiB) to be present on the
 * classpath. If the model is absent or no execution provider loads it successfully
 * the service enters DISABLED mode, and the {@code @Setup(Level.Trial)} method throws
 * a {@link RuntimeException} with a clear message so the JMH runner reports a setup
 * failure rather than silently measuring the no-op disabled path. This benchmark is
 * intended for local developer machines and the nightly CI run where the model is
 * always available; it is excluded from the PR-gate by the {@code -Pbench} source-root
 * attachment convention (no JUnit {@code @Tag} required here).
 * <p>
 * <b>Run command:</b>
 * <pre>{@code
 *   mvn test-compile -Pbench
 *   mvn -Pbench exec:java -Dexec.args="MlServiceWarmupBenchmark -rf json -rff target/jmh-ml-warmup.json"
 * }</pre>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
// One warmup iteration absorbs the process-global OrtEnvironment initialisation cost
// that only occurs on the very first native-library load in the JVM lifetime.
@Warmup(iterations = 1)
// Five cold-starts provide a meaningful sample for mean and variance.
@Measurement(iterations = 5)
// ORT native heap is significant — 2 GB gives each backend room to compile and cache
// its convolution kernels between invocations without GC pressure inflating results.
@Fork(value = 1, jvmArgsAppend = {"-Xms2G", "-Xmx2G"})
@State(Scope.Benchmark)
public class MlServiceWarmupBenchmark {

    // ── state ──────────────────────────────────────────────────────────────

    /**
     * Mock plugin stub — built once per trial and reused across invocations so
     * the Mockito overhead is not included in any measurement.
     */
    private BuildBattleAI plugin;

    /**
     * The service instance under measurement. A fresh instance is created per
     * invocation (outside the timed region) so each {@code @Benchmark} call
     * measures exactly one cold-start of a brand-new service object.
     */
    private MLService mlService;

    // ── lifecycle ──────────────────────────────────────────────────────────

    /**
     * Builds the minimal plugin stub needed by {@link MLService} for logging.
     * Also validates that the ONNX model is reachable on the classpath — if not,
     * the entire trial is aborted with a clear error rather than silently
     * benchmarking the meaningless DISABLED mode.
     * <p>
     * This runs once per trial (outside any timed region) and is intentionally
     * cheap — only Mockito wiring and a classpath probe.
     */
    @Setup(Level.Trial)
    public void setUpTrial() {
        // Validate model availability before any timed work begins.
        // MLService probes the same resource path during enable(); if the file is
        // absent the service silently enters DISABLED mode, which would make this
        // benchmark measure a trivial no-op rather than real inference overhead.
        if (MLService.class.getResource("/models/custom_convnext_embeddings.onnx") == null)
            throw new RuntimeException(
                    "ONNX model required for MlServiceWarmupBenchmark but "
                            + "/models/custom_convnext_embeddings.onnx is not on the classpath. "
                            + "Ensure the model is restored to src/main/resources/models/ before running.");

        // Build the minimal plugin stub that MLService needs for logging.
        // Using Mockito keeps the stub faithful to the real API contract and
        // prevents drift when PluginLogger gains new methods.
        Logger jdkLogger = Logger.getLogger("MlServiceWarmupBenchmark");
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(jdkLogger));
    }

    /**
     * Instantiates a fresh {@link MLService} but does NOT call {@code enable()}.
     * Construction is trivial (field assignment only) and intentionally excluded
     * from the timed region so the benchmark isolates the heavy initialisation
     * path in {@link MLService#enable()}.
     * <p>
     * This runs once per invocation, before the timed region begins.
     */
    @Setup(Level.Invocation)
    public void setUpInvocation() {
        mlService = new MLService(plugin);
    }

    /**
     * Calls {@link MLService#shutdown()} to release the ONNX session and any
     * native memory held by the execution provider after each invocation.
     * Without this, successive {@code enable()} calls would accumulate open ORT
     * sessions until the JVM runs out of native heap.
     * <p>
     * This runs once per invocation, after the timed region ends.
     */
    @TearDown(Level.Invocation)
    public void tearDownInvocation() {
        if (mlService != null)
            mlService.shutdown();
    }

    // ── benchmark ──────────────────────────────────────────────────────────

    /**
     * Measures one complete {@link MLService#enable()} cold-start sequence:
     * <ol>
     *   <li>Open {@code OrtEnvironment} and configure thread-pool settings.</li>
     *   <li>Probe execution providers (CoreML / CUDA / DirectML / ROCm / CPU).</li>
     *   <li>Load and parse {@code centroids.json}.</li>
     *   <li>Run two warmup forward passes ({@code batch=1}, {@code batch=TTA_VIEWS=4}).</li>
     * </ol>
     * The fresh {@link MLService} instance is pre-created by
     * {@link #setUpInvocation()} outside this timed region; the matching
     * {@link #tearDownInvocation()} runs shutdown outside the timed region too.
     */
    @Benchmark
    public void warmup() {
        mlService.enable();
    }
}

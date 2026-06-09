package ru.ashesha.buildBattleAI.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.MLService;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JMH micro-benchmark for the ML inference pipeline (risk ML-06).
 * <p>
 * Measures the average time for a single {@code predictBatchRgb} (plain) or
 * {@code predictBatchWithTTA} (4-view TTA) call as a function of batch size.
 * The cross product {@code batchSize ∈ {1,4,8,16}} × {@code useTTA ∈ {false,true}}
 * gives 8 configurations. At 3 warmup × 2s + 5 measurement × 3s per config the
 * whole suite finishes in roughly 3.5 minutes, keeping the per-PR nightly run
 * practical.
 * <p>
 * <b>Model requirement:</b> The benchmark requires the bundled
 * {@code models/custom_convnext_embeddings.onnx} (~107 MiB) to be present on
 * the classpath. When the model is absent or no execution provider loads it
 * successfully the service enters DISABLED mode and the {@code @Setup} method
 * throws a {@link RuntimeException} with a clear message so the JMH runner
 * reports a setup failure rather than silently measuring no-op code paths that
 * do not reflect real inference cost. This benchmark is therefore intended for
 * <em>local developer machines</em> and the nightly CI run where the model is
 * always available; it is excluded from PR-gate via the {@code @Tag("bench")}
 * convention (no tag on this class — bench source root attachment via
 * {@code -Pbench} is the gate).
 * <p>
 * <b>Run command:</b>
 * <pre>{@code
 *   mvn test-compile -Pbench
 *   mvn -Pbench exec:java -Dexec.args="MlBatchingBenchmark -rf json -rff target/jmh-ml-batching.json"
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
// ORT native heap is significant — 2 GB gives each backend room to compile and
// cache its convolution kernels without GC pressure inflating the measurements.
@Fork(value = 1, jvmArgsAppend = {"-Xms2G", "-Xmx2G"})
@State(Scope.Benchmark)
public class MlBatchingBenchmark {

    // ── parameters ─────────────────────────────────────────────────────────

    /**
     * Number of 224×224 RGB frames in each inference call.
     * Covers the EvaluationService's default {@code ml-batch-max-size=8} and
     * the extremes of the practical range.
     */
    @Param({"1", "4", "8", "16"})
    public int batchSize;

    /**
     * Whether to use the 4-view TTA path ({@code predictBatchWithTTA}) instead
     * of the plain path ({@code predictBatchRgb}). TTA inflates the actual ONNX
     * batch by a factor of {@code TTA_VIEWS=4}, so a logical batch of 16 frames
     * becomes a 64-frame super-batch — worth measuring separately.
     */
    @Param({"false", "true"})
    public boolean useTTA;

    // ── state ──────────────────────────────────────────────────────────────

    /** The service instance under measurement. */
    private MLService mlService;

    /**
     * Pre-allocated input batch; populated once per trial with deterministic
     * pixel data so the benchmark body allocates nothing beyond what the
     * service itself allocates. Each frame is a flat row-major HWC RGB array
     * of length {@code 224 * 224 * 3}.
     */
    private byte[][] batch;

    // ── lifecycle ──────────────────────────────────────────────────────────

    /**
     * Brings the {@link MLService} online and pre-allocates the input batch.
     * Fails loudly if the ONNX model is absent — see class Javadoc.
     */
    @Setup(Level.Trial)
    public void setUp() {
        // Build the minimal plugin stub that MLService needs for logging.
        // We use Mockito (already on the test/bench classpath) rather than a
        // hand-rolled stub so the bench doesn't drift if PluginLogger gains
        // new APIs.
        Logger jdkLogger = Logger.getLogger("MlBatchingBenchmark");
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(jdkLogger));

        mlService = new MLService(plugin);
        mlService.enable();

        // Fail fast if there is no model — measuring zero-op disabled mode is
        // meaningless and could silently hide a misconfigured environment.
        if ("DISABLED".equals(mlService.backend()))
            throw new RuntimeException(
                    "ONNX model required for MlBatchingBenchmark but MLService is DISABLED. "
                            + "Ensure models/custom_convnext_embeddings.onnx is on the classpath.");

        // Pre-allocate input frames once per trial.
        // Content: a deterministic ramp pattern — every pixel component is
        // ((pixelIndex * 7 + frameIndex * 31) & 0xFF) so different frames look
        // distinct to the model without requiring file I/O.
        int frameBytes = 224 * 224 * 3;
        batch = new byte[batchSize][];
        for (int f = 0; f < batchSize; f++) {
            byte[] frame = new byte[frameBytes];
            for (int i = 0; i < frameBytes; i++)
                frame[i] = (byte) ((i * 7 + f * 31) & 0xFF);
            batch[f] = frame;
        }
    }

    /**
     * Releases the ONNX session so the JVM can exit cleanly after the bench run.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (mlService != null)
            mlService.shutdown();
    }

    // ── benchmarks ─────────────────────────────────────────────────────────

    /**
     * Measures one complete batch prediction call — preprocessing + ONNX
     * inference + centroid scoring — for the current {@link #batchSize} and
     * {@link #useTTA} parameter combination.
     * <p>
     * The result array is consumed by the {@link Blackhole} to prevent
     * dead-code elimination.
     */
    @Benchmark
    public void predictBatch(Blackhole bh) {
        if (useTTA)
            bh.consume(mlService.predictBatchWithTTA(batch, 224, 224, 2));
        else
            bh.consume(mlService.predictBatchRgb(batch, 224, 224, 2));
    }
}

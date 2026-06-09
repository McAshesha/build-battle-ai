package ru.ashesha.buildBattleAI.evaluation;

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
import org.openjdk.jmh.infra.Blackhole;
import ru.ashesha.buildBattleAI.render.data.MutablePlotScene;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;

/**
 * JMH micro-benchmark for the inner-loop throughput of the evaluation pipeline.
 * <p>
 * This class lives in the {@code ru.ashesha.buildBattleAI.evaluation} package so
 * it can access package-private classes ({@link RenderQueue}, {@link MlQueue},
 * {@link EvalJob}, {@link EvalFrame}, {@link EvaluationMetrics}) without any
 * production visibility changes.
 * <p>
 * <b>Scope:</b> the coordinator-tick cost is dominated by
 * {@code MLService.predictBatchRgb} (covered by {@code MlBatchingBenchmark}).
 * This benchmark targets the data-structure cost that surrounds inference:
 * <ol>
 *   <li><b>renderQueueOfferTake</b> — {@link RenderQueue#offer}/{@link RenderQueue#take}
 *       with dedup + {@link java.util.concurrent.LinkedBlockingQueue} overhead.</li>
 *   <li><b>mlQueueOfferDrain</b> — {@link MlQueue#offer}/{@link MlQueue#drainBatch}
 *       with the zero-wait opportunistic-drain path exercised by the coalescer.</li>
 *   <li><b>metricsHotPath</b> — simulated coordinator tick of
 *       {@link LongAdder}/{@link java.util.concurrent.atomic.AtomicLong}/
 *       {@link java.util.concurrent.atomic.AtomicLongArray} increments plus a
 *       full {@link EvaluationMetrics#snapshot} materialisation.</li>
 * </ol>
 * All three benchmarks run single-threaded (no contention) to isolate the pure
 * data-structure cost; contention behaviour is covered by the stress tests.
 * <p>
 * <b>Batch size:</b> 16 jobs/frames per operation — large enough to amortise
 * per-call overhead across a realistic coordinator burst while keeping each
 * operation well under 1 ms so JMH can collect enough samples in 1-second
 * measurement windows.
 * <p>
 * <b>Run command:</b>
 * <pre>{@code
 *   mvn test-compile -Pbench
 *   mvn -Pbench exec:java -Dexec.args="EvaluationPipelineBenchmark -rf json \
 *       -rff target/jmh-eval-pipeline.json"
 * }</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
// Small heap — these are pure in-process data structures with no native heap.
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class EvaluationPipelineBenchmark {

    // ── constants ──────────────────────────────────────────────────────────

    /** Number of jobs/frames processed per benchmark operation. */
    private static final int BATCH = 16;

    /**
     * Queue capacity — large enough that the bounded offer() never rejects
     * during the benchmark, keeping measurements free of back-pressure retries.
     */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * Pre-allocated pixel buffer size (224×224×3). Each EvalFrame holds a
     * reference to a pre-allocated buffer so the benchmark doesn't conflate
     * allocation cost with queue cost.
     */
    private static final int FRAME_BYTES = 224 * 224 * 3;

    /** Maximum batch size stored in EvaluationMetrics histogram. */
    private static final int MAX_BATCH_SIZE = 16;

    // ── state ──────────────────────────────────────────────────────────────

    private RenderQueue renderQueue;
    private MlQueue mlQueue;
    private EvaluationMetrics metrics;

    /**
     * Pre-built array of {@link EvalJob} instances, one per BATCH slot.
     * Each job has a distinct {@link UUID} so dedup fires on every offer
     * (no previously-queued job to mark stale — we want pure offer+take cost).
     * Created once per trial; regenerated to ensure no stale-flag state bleeds
     * across iterations (see {@link #setUp}).
     */
    private EvalJob[] jobs;

    /**
     * Pre-built array of {@link EvalFrame} instances, one per BATCH slot.
     * Each frame wraps the corresponding pre-built job and a dedicated RGB
     * byte buffer. Created once per trial.
     */
    private EvalFrame[] frames;

    // ── lifecycle ──────────────────────────────────────────────────────────

    /**
     * Initialises queues, metrics, jobs, and frames once per trial.
     * The {@link MutablePlotScene} dependency of {@link EvalJob} is satisfied
     * with a Mockito mock — the render queue benchmark never reads from the
     * scene, so no production behaviour is exercised on it.
     */
    @Setup(Level.Trial)
    public void setUp() {
        renderQueue = new RenderQueue(QUEUE_CAPACITY);
        mlQueue = new MlQueue(QUEUE_CAPACITY);
        metrics = new EvaluationMetrics(MAX_BATCH_SIZE);

        // Shared mock scene: construction cost of MutablePlotScene would pull in
        // Bukkit world-data paths that cannot run outside a server JVM. A mock is
        // the correct boundary here — the benchmark is measuring queue cost, not
        // scene access cost.
        MutablePlotScene sharedScene = mock(MutablePlotScene.class);

        jobs = new EvalJob[BATCH];
        frames = new EvalFrame[BATCH];
        for (int i = 0; i < BATCH; i++) {
            // Distinct UUIDs so the dedup map never sees a collision on the first
            // round of offers — this measures baseline offer+take, not dedup eviction.
            UUID playerId = UUID.randomUUID();
            jobs[i] = EvalJob.builder()
                    .arenaName("bench-arena")
                    .playerId(playerId)
                    .playerName("bench-player-" + i)
                    .plotIndex(i)
                    .themeIndex(0)
                    .expectedTheme("cat")
                    .mirror(sharedScene)
                    .cameraX(0.0).cameraY(64.0).cameraZ(0.0)
                    .cameraYaw(0.0f).cameraPitch(0.0f)
                    .enqueuedAtNanos(System.nanoTime())
                    .build();

            // Fresh, deterministic pixel buffer per frame; content is irrelevant —
            // we're measuring queue data-structure cost, not pixel data processing.
            byte[] rgb = new byte[FRAME_BYTES];
            for (int p = 0; p < FRAME_BYTES; p++)
                rgb[p] = (byte) ((p * 7 + i * 31) & 0xFF);

            frames[i] = new EvalFrame(jobs[i], rgb, System.nanoTime());
        }
    }

    /**
     * Clears both queues at the end of each trial to prevent any left-over
     * entries from affecting subsequent benchmarks in the same JVM fork.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        renderQueue.clear();
        mlQueue.clear();
    }

    // ── benchmarks ─────────────────────────────────────────────────────────

    /**
     * Measures the cost of offering {@value #BATCH} jobs to the
     * {@link RenderQueue} and then taking them back out.
     * <p>
     * The take() calls block until a job is ready; because offers and takes are
     * interleaved in the same thread, each take() returns immediately. This
     * isolates the {@link java.util.concurrent.LinkedBlockingQueue} round-trip
     * cost plus the ConcurrentHashMap dedup-index bookkeeping.
     */
    @Benchmark
    public void renderQueueOfferTake(Blackhole bh) throws InterruptedException {
        // Offer phase: load BATCH jobs into the queue.
        for (int i = 0; i < BATCH; i++) {
            // Re-offer the pre-built job. Since each job was already taken in the
            // previous iteration its stale flag is clear (AtomicBoolean starts false;
            // take() does not mutate the flag). UUIDs are distinct, so no prior
            // dedup entry exists.
            renderQueue.offer(jobs[i]);
        }
        // Take phase: drain all BATCH jobs; consume result to prevent DCE.
        for (int i = 0; i < BATCH; i++)
            bh.consume(renderQueue.take());
    }

    /**
     * Measures the cost of offering {@value #BATCH} frames to the
     * {@link MlQueue} and draining them as a single batch with zero wait time.
     * <p>
     * The zero-wait drainBatch mirrors the coalescer's fast-path: it calls
     * {@code drainBatch(maxSize, 0)} once there is at least one frame in the
     * queue. No blocking occurs because all {@value #BATCH} frames are already
     * present by the time drainBatch is invoked.
     */
    @Benchmark
    public void mlQueueOfferDrain(Blackhole bh) throws InterruptedException {
        // Offer phase.
        for (int i = 0; i < BATCH; i++)
            mlQueue.offer(frames[i]);
        // Drain phase: maxSize=BATCH, waitMs=0 so drainBatch returns immediately.
        List<EvalFrame> batch = mlQueue.drainBatch(BATCH, 0);
        bh.consume(batch);
    }

    /**
     * Measures the cost of a full coordinator-tick metrics update followed by
     * a snapshot materialisation.
     * <p>
     * Simulates one tick of the coalescer reporting results: {@value #BATCH}
     * render completions, one ML batch, {@value #BATCH} matches, a set of
     * latency recordings, and a batch-size recording — then materialises a
     * complete {@link ru.ashesha.buildBattleAI.evaluation.api.EvaluationStats}
     * snapshot. Exercises the {@link java.util.concurrent.atomic.LongAdder},
     * {@link java.util.concurrent.atomic.AtomicLong}, and
     * {@link java.util.concurrent.atomic.AtomicLongArray} hot paths in
     * {@link EvaluationMetrics}.
     */
    @Benchmark
    public void metricsHotPath(Blackhole bh) {
        // Simulate BATCH render completions with realistic latency values.
        for (int i = 0; i < BATCH; i++) {
            metrics.incRendersCompleted();
            // Latency ramp in the 10–25 ms range — realistic render times.
            metrics.recordRenderLatencyNanos(10_000_000L + i * 1_000_000L);
        }

        // Simulate one ML batch completing with BATCH frames.
        metrics.incMlBatchesCompleted();
        metrics.recordMlLatencyNanos(50_000_000L); // 50 ms — realistic ORT inference

        // Simulate BATCH successful matches dispatched.
        for (int i = 0; i < BATCH; i++)
            metrics.incMatchesDispatched();

        // Record the batch size in the histogram.
        metrics.recordBatchSize(BATCH);

        // Materialise a full snapshot — exercises the AtomicLongArray histogram copy.
        bh.consume(metrics.snapshot(8, 4, 2, BATCH));
    }
}

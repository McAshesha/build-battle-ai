package ru.ashesha.buildBattleAI.evaluation;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;

/**
 * JMH micro-benchmark measuring {@link MlQueue} throughput under both
 * single-threaded and contended multi-producer/single-consumer workloads.
 * <p>
 * <b>Why this benchmark exists:</b> {@link EvaluationPipelineBenchmark} already
 * covers the single-threaded offer → drain round-trip in isolation. This bench
 * focuses on <em>contention</em> — specifically the worst-case scenario of
 * {@code N} producers racing to offer frames while one consumer drains batches.
 * Although production has exactly one producer (the coordinator enqueue path),
 * correctness under concurrent offers is a safety property that should be
 * measured, not assumed.
 * <p>
 * <b>Benchmarks:</b>
 * <ol>
 *   <li><b>{@code queue/produce}</b> — 2 producer threads each racing to
 *       {@link MlQueue#offer} frames from a per-thread pre-allocated pool.
 *       Models back-pressure and CAS contention on the internal
 *       {@link java.util.concurrent.LinkedBlockingQueue}.</li>
 *   <li><b>{@code queue/drain}</b> — 1 consumer thread continuously calling
 *       {@link MlQueue#drainBatch(int, long)} with a 1 ms wait, discarding
 *       results. Models the ML coalescer's tight drain loop.</li>
 *   <li><b>{@code offerDrainSingleThreaded}</b> — baseline: offer 8 frames then
 *       drain 8 frames in the same thread. Measures sequential throughput free of
 *       lock contention, providing a lower-bound for the contended benchmarks.</li>
 * </ol>
 * <p>
 * <b>Run command:</b>
 * <pre>{@code
 *   mvn test-compile -Pbench
 *   mvn -Pbench exec:java -Dexec.args="MlQueueBenchmark -rf json \
 *       -rff target/jmh-mlqueue.json"
 * }</pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
// Fixed heap so GC pause variance is consistent across runs.
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class MlQueueBenchmark {

    // ── constants ──────────────────────────────────────────────────────────

    /**
     * Queue capacity — matches the production default ({@code ml-queue-capacity=64}).
     * Large enough to avoid systematic back-pressure during the benchmark, so
     * measured throughput reflects queue data-structure cost, not offer rejection
     * retry cost.
     */
    private static final int QUEUE_CAPACITY = 64;

    /**
     * Pre-allocated pixel buffer size (224×224×3 RGB bytes). Content is
     * deterministic but irrelevant — the benchmark measures queue mechanics,
     * not pixel processing.
     */
    private static final int FRAME_BYTES = 224 * 224 * 3;

    /**
     * Pool size for the per-thread frame pool. 64 is chosen to be exactly the
     * queue capacity; producers cycle through the pool modulo this size so every
     * offer uses a distinct but pre-allocated {@link EvalFrame}.
     */
    private static final int POOL_SIZE = 64;

    /**
     * Batch size for the single-threaded baseline benchmark. Matches the ML
     * coalescer's default {@code ml-batch-max-size=8}.
     */
    private static final int BASELINE_BATCH = 8;

    // ── shared benchmark state ─────────────────────────────────────────────

    /**
     * Shared benchmark state visible to every thread in the group.
     * Holds the single {@link MlQueue} instance and a global pool of
     * {@link EvalFrame}s. The shared pool is used by producer threads; each
     * thread advances an independent counter so producers do not contend on
     * the pool index itself.
     */
    @State(Scope.Benchmark)
    public static class SharedState {

        MlQueue mlQueue;

        /**
         * Global frame pool shared across all producer threads.
         * Pre-built once per trial at {@link Level#Trial} so frame allocation
         * is never included in measurement.
         */
        EvalFrame[] framePool;

        /**
         * Initialises the queue and pre-allocates all frames for the trial.
         * The mock {@link MutablePlotScene} is constructed once and shared
         * across all frames — scene access is not exercised during the benchmark.
         */
        @Setup(Level.Trial)
        public void setUp() {
            mlQueue = new MlQueue(QUEUE_CAPACITY);

            MutablePlotScene sharedScene = mock(MutablePlotScene.class);
            framePool = new EvalFrame[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) {
                UUID playerId = UUID.randomUUID();
                EvalJob job = EvalJob.builder()
                        .arenaName("bench-arena")
                        .playerId(playerId)
                        .playerName("bench-player-" + i)
                        .plotIndex(i % 4)
                        .themeIndex(0)
                        .expectedTheme("cat")
                        .mirror(sharedScene)
                        .cameraX(0.0).cameraY(64.0).cameraZ(0.0)
                        .cameraYaw(0.0f).cameraPitch(0.0f)
                        .enqueuedAtNanos(System.nanoTime())
                        .build();

                // Deterministic pixel content — content irrelevant, avoids
                // zeroed-buffer fast-paths that could skew allocation measurements.
                byte[] rgb = new byte[FRAME_BYTES];
                for (int p = 0; p < FRAME_BYTES; p++)
                    rgb[p] = (byte) ((p * 7 + i * 31) & 0xFF);

                framePool[i] = new EvalFrame(job, rgb, System.nanoTime());
            }
        }

        /**
         * Clears the queue after each trial so residual frames from one
         * benchmark do not inflate queue depth for the next.
         */
        @TearDown(Level.Trial)
        public void tearDown() {
            mlQueue.clear();
        }
    }

    /**
     * Per-thread state used by producer threads.
     * Each producer keeps its own monotonic counter so the modulo index into
     * the shared pool advances independently per thread, eliminating false
     * sharing on the counter itself.
     */
    @State(Scope.Thread)
    public static class ProducerState {

        /**
         * Monotonically increasing counter; wraps modulo {@link #POOL_SIZE}
         * to select the next pre-built frame to offer.
         */
        int index;

        @Setup(Level.Iteration)
        public void init() {
            // Reset counter each iteration so index drift does not cause
            // long-running tests to exhibit different pool-access patterns.
            index = 0;
        }
    }

    // ── contended group benchmarks ─────────────────────────────────────────

    /**
     * Producer side of the contended group benchmark.
     * <p>
     * Two threads race to offer frames from the shared pool to the queue.
     * The non-blocking {@link MlQueue#offer} returns {@code false} when the
     * queue is full; the result is consumed via {@link Blackhole#consume} so
     * the boolean is not dead-code-eliminated and the branch is preserved in
     * the measured path.
     * <p>
     * <b>Note:</b> JMH groups the {@code produce} and {@code drain} benchmarks
     * into a single {@code queue} group with a fixed 2:1 thread ratio. Both
     * methods share the same {@link SharedState} instance.
     */
    @Benchmark
    @Group("queue")
    @GroupThreads(2)
    public void produce(SharedState shared, ProducerState producer, Blackhole bh) {
        // Cycle through the pre-allocated pool to avoid re-allocating frames.
        int idx = producer.index % POOL_SIZE;
        producer.index++;
        bh.consume(shared.mlQueue.offer(shared.framePool[idx]));
    }

    /**
     * Consumer side of the contended group benchmark.
     * <p>
     * A single thread continuously drains the queue in batches of up to 8
     * frames, waiting at most 1 ms for the first frame. The 1 ms wait
     * mirrors the production coalescer's {@code ml-batch-max-wait-ms=1}
     * fast-path when load is steady. The returned list is consumed to prevent
     * dead-code elimination.
     */
    @Benchmark
    @Group("queue")
    @GroupThreads(1)
    public void drain(SharedState shared, Blackhole bh) throws InterruptedException {
        List<EvalFrame> batch = shared.mlQueue.drainBatch(8, 1);
        bh.consume(batch);
    }

    // ── single-threaded baseline ───────────────────────────────────────────

    /**
     * Baseline: offer {@value #BASELINE_BATCH} frames then drain them all in
     * a single thread. No contention — measures the pure sequential throughput
     * of the queue data structure.
     * <p>
     * This establishes the performance floor; contended group results should be
     * compared against this to quantify the cost of lock contention.
     * <p>
     * Uses a dedicated {@link State} field to avoid interfering with the
     * contended group's shared queue state.
     */
    @State(Scope.Thread)
    public static class SingleThreadedState {

        MlQueue mlQueue;
        EvalFrame[] frames;

        @Setup(Level.Trial)
        public void setUp() {
            mlQueue = new MlQueue(QUEUE_CAPACITY);

            MutablePlotScene sharedScene = mock(MutablePlotScene.class);
            frames = new EvalFrame[BASELINE_BATCH];
            for (int i = 0; i < BASELINE_BATCH; i++) {
                EvalJob job = EvalJob.builder()
                        .arenaName("bench-arena")
                        .playerId(UUID.randomUUID())
                        .playerName("bench-player-" + i)
                        .plotIndex(i)
                        .themeIndex(0)
                        .expectedTheme("cat")
                        .mirror(sharedScene)
                        .cameraX(0.0).cameraY(64.0).cameraZ(0.0)
                        .cameraYaw(0.0f).cameraPitch(0.0f)
                        .enqueuedAtNanos(System.nanoTime())
                        .build();

                byte[] rgb = new byte[FRAME_BYTES];
                for (int p = 0; p < FRAME_BYTES; p++)
                    rgb[p] = (byte) ((p * 7 + i * 31) & 0xFF);

                frames[i] = new EvalFrame(job, rgb, System.nanoTime());
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            mlQueue.clear();
        }
    }

    /**
     * Measures sequential offer-then-drain throughput for {@value #BASELINE_BATCH}
     * frames in a single thread. Provides a contention-free lower bound for the
     * contended group benchmark results.
     */
    @Benchmark
    public void offerDrainSingleThreaded(SingleThreadedState st, Blackhole bh)
            throws InterruptedException {
        // Offer BASELINE_BATCH frames — all fit because capacity == 64.
        for (int i = 0; i < BASELINE_BATCH; i++)
            st.mlQueue.offer(st.frames[i]);

        // Drain with zero wait: all frames are already present so this returns
        // immediately without touching the timed-poll code path.
        List<EvalFrame> batch = st.mlQueue.drainBatch(BASELINE_BATCH, 0);
        bh.consume(batch);
    }
}

package ru.ashesha.buildBattleAI.stress.ml;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.MLService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stress test covering risk <b>ML-05</b>: concurrent {@code embedBatchRgb}
 * calls from N threads × M iterations produce no NaN values, no Infinity, and
 * all returned embeddings are L2-normalised to unit norm.
 *
 * <h3>Invariant under test</h3>
 * The {@link MLService} Javadoc states: "ORT sessions are thread-safe for
 * concurrent {@code run()} calls on the same session." This stress test verifies
 * that invariant holds in practice: N=4 threads can each independently call
 * {@link MLService#embedBatchRgb} M=50 times with a batch of B=2 images without
 * producing any NaN, Infinity, or non-unit-norm embedding.
 *
 * <h3>Secondary invariant</h3>
 * Inference is deterministic: for any given seed image, calling
 * {@code embedBatchRgb} with the same pixel buffer always returns an embedding
 * that is element-wise identical across calls (subject to float representation).
 * This is spot-checked for two of the K=8 seed images across a pair of
 * back-to-back single-image batches run on the same thread after all concurrent
 * load has completed.
 *
 * <h3>Why stress tier (not unit or integration)</h3>
 * The thread-safety contract of the ONNX Runtime session can only be violated
 * under genuine concurrent load. Single-threaded unit tests and sequential
 * integration tests trivially satisfy the invariant without exercising the
 * concurrent {@code run()} code path that is the subject of this risk. The
 * stress tier is the minimum tier that exposes the interleaving required to
 * exercise ORT's internal synchronisation.
 *
 * <h3>Skip condition</h3>
 * When the bundled ONNX model is absent from the test classpath (all CI runs
 * except {@code -Pml-it}) the test is skipped via {@link Assumptions#assumeTrue}.
 * Locally, with the model present (Maven copies it from
 * {@code src/main/resources/models/} into the test classpath), the test runs
 * and is expected to pass in 10–30 s on a typical development machine.
 */
@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MlConcurrentInferenceStress {

    // ── knobs ─────────────────────────────────────────────────────────────────

    /** Number of distinct seed images generated once and reused across threads. */
    private static final int K_SEEDS = 8;

    /** Number of concurrent inference threads. */
    private static final int N_THREADS = 4;

    /**
     * Number of {@code embedBatchRgb} calls each thread issues. At B=2 frames
     * per call this is 4 × 50 × 2 = 400 forward passes total — enough to
     * exercise ORT's concurrent-run path without taking more than ~30 s on a
     * CPU-only device.
     */
    private static final int M_ITERATIONS = 50;

    /** Number of frames per batch call. */
    private static final int B_BATCH = 2;

    /** Image side length (must match the model's expected input resolution). */
    private static final int IMG_SIZE = 224;

    /** Expected embedding dimension from the ConvNeXt-Tiny embedder. */
    private static final int EMBEDDING_DIM = 128;

    /**
     * L2-norm tolerance: the service always normalises embeddings to unit
     * length, so {@code |norm - 1.0| < NORM_TOLERANCE} must hold for every
     * returned embedding.
     */
    private static final double NORM_TOLERANCE = 1e-4;

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** Shared logger used to satisfy {@link PluginLogger} injection. */
    private static final Logger LOGGER = Logger.getLogger("MlConcurrentInferenceStress");

    /**
     * K distinct 224×224×3 RGB byte buffers generated with deterministic seeds.
     * Each buffer has a unique all-byte fill pattern so the model receives
     * meaningfully different inputs and we can spot-check determinism per seed.
     */
    private byte[][] seeds;

    /** Service under test — shared across all threads in a single test run. */
    private MLService service;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Instantiates and enables a real {@link MLService} against the bundled
     * ONNX model. Skips the entire class immediately when either:
     * <ul>
     *   <li>the ONNX model resource is absent from the test classpath, or</li>
     *   <li>the service enters disabled mode (no backend could load the model
     *       — uncommon but possible on headless CI boxes that somehow acquired
     *       the model file without the matching native ONNX Runtime library).</li>
     * </ul>
     */
    @BeforeAll
    void setUp() {
        // Skip when the model resource is absent — this is the normal CI path.
        Assumptions.assumeTrue(
                MLService.class.getResourceAsStream("/models/custom_convnext_embeddings.onnx") != null,
                "ONNX model not present on test classpath — skipping ML-05 stress");

        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(LOGGER));

        service = new MLService(plugin);
        service.enable();

        // Skip when the model loaded but no execution provider accepted it.
        Assumptions.assumeTrue(
                !"DISABLED".equals(service.backend()),
                "MLService backend is DISABLED — no EP could load the model; skipping ML-05 stress");

        // Generate K distinct seed images once. Seed i fills every byte with
        // value (i + 1) & 0xFF so each image is a solid colour block, giving
        // the model a non-zero, non-trivial input while keeping generation O(1).
        seeds = new byte[K_SEEDS][];
        int bufLen = IMG_SIZE * IMG_SIZE * 3;
        for (int i = 0; i < K_SEEDS; i++) {
            seeds[i] = new byte[bufLen];
            byte fill = (byte) ((i + 1) & 0xFF);
            Arrays.fill(seeds[i], fill);
        }
    }

    @AfterAll
    void tearDown() {
        if (service != null)
            service.shutdown();
    }

    // ── test ──────────────────────────────────────────────────────────────────

    /**
     * Spawns N=4 threads, each issuing M=50 calls to
     * {@link MLService#embedBatchRgb} with a B=2 frame batch built from the
     * pre-generated seed images. For every returned embedding the test asserts:
     * <ol>
     *   <li>Non-null and exactly 128 dimensions.</li>
     *   <li>No element is NaN or Infinity.</li>
     *   <li>L2 norm is within {@value NORM_TOLERANCE} of 1.0.</li>
     * </ol>
     * After all threads complete, two pairs of seed images are embedded again
     * in a single thread and compared element-wise to confirm determinism.
     */
    @Test
    void concurrentInferenceProducesValidEmbeddings() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(N_THREADS);

        // Latch so every thread starts its inference loop simultaneously,
        // maximising the concurrency window on the shared ORT session.
        CountDownLatch startGun = new CountDownLatch(1);

        // Capture the first error encountered across all threads.
        AtomicReference<Throwable> firstError = new AtomicReference<Throwable>(null);

        List<Future<?>> futures = new ArrayList<Future<?>>(N_THREADS);

        for (int t = 0; t < N_THREADS; t++) {
            // Snapshot the thread index as a final local so it can be captured
            // by the lambda — Java 8 requires effectively-final variables.
            final int threadIdx = t;
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Wait until every thread is ready before starting the
                        // inference loop. This tightens the concurrency window
                        // and increases the probability of exposing races.
                        startGun.await();

                        for (int iter = 0; iter < M_ITERATIONS; iter++) {
                            // Build a B-image batch from the seed pool. Rotate
                            // seed selection across threads and iterations so no
                            // two threads see identical batches at the same time.
                            byte[][] batch = new byte[B_BATCH][];
                            for (int b = 0; b < B_BATCH; b++)
                                batch[b] = seeds[(threadIdx * B_BATCH + iter + b) % K_SEEDS];

                            float[][] embeddings = service.embedBatchRgb(batch, IMG_SIZE, IMG_SIZE);

                            // ── batch-level assertions ──────────────────────
                            assertNotNull(embeddings,
                                    "embedBatchRgb must not return null (thread=" + threadIdx
                                            + ", iter=" + iter + ")");
                            assertEquals(B_BATCH, embeddings.length,
                                    "returned array length must equal batch size (thread=" + threadIdx
                                            + ", iter=" + iter + ")");

                            for (int b = 0; b < B_BATCH; b++) {
                                float[] emb = embeddings[b];

                                // ── per-embedding shape ──────────────────────
                                assertNotNull(emb,
                                        "embedding[" + b + "] must not be null (thread=" + threadIdx
                                                + ", iter=" + iter + ")");
                                assertEquals(EMBEDDING_DIM, emb.length,
                                        "embedding dim must be " + EMBEDDING_DIM
                                                + " (thread=" + threadIdx + ", iter=" + iter
                                                + ", b=" + b + ")");

                                // ── NaN / Infinity check ─────────────────────
                                for (int d = 0; d < EMBEDDING_DIM; d++) {
                                    float v = emb[d];
                                    assertFalse(Float.isNaN(v),
                                            "NaN at embedding[" + b + "][" + d + "] (thread="
                                                    + threadIdx + ", iter=" + iter + ")");
                                    assertFalse(Float.isInfinite(v),
                                            "Infinity at embedding[" + b + "][" + d + "] (thread="
                                                    + threadIdx + ", iter=" + iter + ")");
                                }

                                // ── L2 norm ~~1.0 ────────────────────────────
                                double norm = l2Norm(emb);
                                assertEquals(1.0, norm, NORM_TOLERANCE,
                                        "L2 norm must be ~1.0 (thread=" + threadIdx
                                                + ", iter=" + iter + ", b=" + b
                                                + ", actual=" + norm + ")");
                            }
                        }
                    } catch (Throwable ex) {
                        // Store the first failure; subsequent ones are dropped
                        // to keep assertion messages focused.
                        firstError.compareAndSet(null, ex);
                    }
                }
            }));
        }

        // Release all threads simultaneously.
        startGun.countDown();

        pool.shutdown();
        boolean finished = pool.awaitTermination(120, TimeUnit.SECONDS);
        assertTrue(finished,
                "Inference threads did not finish within 120 s — possible deadlock or livelock");

        // Propagate any assertion failure captured inside the worker threads.
        Throwable err = firstError.get();
        if (err != null)
            fail("Concurrent inference worker failed: " + err.getMessage());

        // ── determinism spot-check ────────────────────────────────────────────
        // Run two pairs of seed images twice on the calling thread and confirm
        // element-wise equality. Seed 0 and seed 3 are far apart in the byte
        // fill pattern, giving diversity in the inputs being checked.
        for (int seedIdx : new int[]{0, 3}) {
            byte[][] singleBatch = new byte[][]{seeds[seedIdx]};

            float[][] first  = service.embedBatchRgb(singleBatch, IMG_SIZE, IMG_SIZE);
            float[][] second = service.embedBatchRgb(singleBatch, IMG_SIZE, IMG_SIZE);

            assertNotNull(first,  "First embedBatchRgb call returned null for seed " + seedIdx);
            assertNotNull(second, "Second embedBatchRgb call returned null for seed " + seedIdx);
            assertEquals(1, first.length,  "Expected 1-element result for seed " + seedIdx);
            assertEquals(1, second.length, "Expected 1-element result for seed " + seedIdx);

            float[] embFirst  = first[0];
            float[] embSecond = second[0];

            for (int d = 0; d < EMBEDDING_DIM; d++)
                assertEquals(embFirst[d], embSecond[d], 1e-6f,
                        "Embedding is non-deterministic at dim " + d + " for seed " + seedIdx
                                + " (first=" + embFirst[d] + ", second=" + embSecond[d] + ")");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Computes the L2 norm of a float array using double accumulation to
     * minimise floating-point cancellation on small-magnitude values.
     *
     * @param v the input vector
     * @return the Euclidean length of {@code v}
     */
    private static double l2Norm(float[] v) {
        double sumSq = 0.0;
        for (float x : v)
            sumSq += (double) x * x;
        return Math.sqrt(sumSq);
    }
}

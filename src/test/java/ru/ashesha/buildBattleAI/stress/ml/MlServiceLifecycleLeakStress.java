package ru.ashesha.buildBattleAI.stress.ml;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.MLService;

import java.util.Arrays;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stress test covering risk <b>ML-07</b>: repeated {@code enable()} /
 * {@code shutdown()} cycles do not leak native OrtSession handles or
 * accumulate unbounded heap pressure.
 *
 * <h3>Invariant under test</h3>
 * Every call to {@link MLService#shutdown()} must close the underlying
 * {@code OrtSession} and null out all transient state so that a subsequent
 * {@link MLService#enable()} starts from a clean slate. Across N cycles:
 * <ul>
 *   <li>After each {@code enable()}: {@link MLService#backend()} must not be
 *       {@code "DISABLED"} — confirming a real session was opened.</li>
 *   <li>After each {@code shutdown()}: {@link MLService#backend()} must return
 *       {@code "DISABLED"} — confirming session handles were released.</li>
 *   <li>Heap growth from baseline to post-loop (after forced GC) must be
 *       bounded (≤ {@value #MAX_HEAP_GROWTH_BYTES} bytes) — a gross native-handle
 *       leak would cause steadily climbing retained heap even under GC pressure.</li>
 *   <li>Mean per-cycle wall time must not degrade: the last 10 % of cycles must
 *       complete within {@value #DEGRADATION_FACTOR}× the mean of the first 10 %
 *       — accumulating overhead (e.g., thread-pool proliferation) would show here.</li>
 * </ul>
 *
 * <h3>Cycle count adjustment</h3>
 * The CLAUDE.md spec originally called for 100 cycles. ConvNeXt-Tiny takes
 * ~0.5–3 s to load, making 100 cycles (≈ 50–300 s) far outside the stress
 * tier budget of 5–60 s. {@value #N_CYCLES} cycles were chosen so that on a
 * typical development machine the whole test completes within 30–60 s while
 * still providing meaningful coverage of the leak invariant: a handle leak
 * that grows linearly with cycle count is reliably detectable at N=20.
 *
 * <h3>Why stress tier (not unit or integration)</h3>
 * The leak risk only manifests under repeated load: a single open+close cycle
 * passes trivially. The stress tier is the minimum tier that exercises the
 * repeated lifecycle path needed to detect accumulating native-handle or heap
 * growth. The test is skipped in CI (where the bundled ONNX model is absent)
 * and runs locally on the developer's machine.
 *
 * <h3>Skip condition</h3>
 * When the bundled ONNX model is absent from the test classpath (all CI runs
 * except {@code -Pml-it}) the entire class is skipped via
 * {@link Assumptions#assumeTrue}.
 */
@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MlServiceLifecycleLeakStress {

    // ── knobs ─────────────────────────────────────────────────────────────────

    /**
     * Number of enable/shutdown cycles. Kept at 20 (rather than the 100
     * mentioned in the spec) to stay within the stress tier's 60 s budget
     * while still being large enough to surface linear handle leaks.
     */
    private static final int N_CYCLES = 20;

    /**
     * Maximum tolerated heap growth from baseline to post-loop (after two
     * forced GC passes). The OrtEnvironment is process-global and its
     * one-time allocation is excluded from the growth estimate by measuring
     * the baseline after the environment has been initialised by the first
     * enable(). 200 MiB is deliberately generous — a real handle leak
     * accumulating an OrtSession per cycle would easily exceed this.
     */
    private static final long MAX_HEAP_GROWTH_BYTES = 200L * 1024 * 1024;

    /**
     * Maximum allowed ratio of mean cycle time in the last 10 % of cycles
     * versus mean cycle time in the first 10 %. Values above this indicate
     * accumulating overhead (thread leaks, growing internal state, etc.).
     */
    private static final double DEGRADATION_FACTOR = 3.0;

    /**
     * Size of the "window" (in number of cycles) used for the degradation
     * check. Derived as 10 % of {@value #N_CYCLES}, with a floor of 1.
     */
    private static final int WINDOW = Math.max(1, N_CYCLES / 10);

    /**
     * A minimal 224×224 solid-colour RGB buffer. Used to exercise a single
     * inference call per cycle so the session is confirmed to be truly open
     * (not just lazily allocated).
     */
    private static final int IMG_SIZE = 224;

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** Shared logger used to satisfy {@link PluginLogger} injection. */
    private static final Logger LOGGER = Logger.getLogger("MlServiceLifecycleLeakStress");

    /**
     * Minimal 224×224 RGB image filled with a mid-grey value. Synthetic and
     * deterministic — the model output is not inspected; we only verify the
     * call completes without exception.
     */
    private static final byte[] PROBE_IMAGE = new byte[IMG_SIZE * IMG_SIZE * 3];

    static {
        // Fill with value 128 (mid-grey) so the model receives a non-degenerate
        // input while keeping the setup entirely allocation-free.
        Arrays.fill(PROBE_IMAGE, (byte) 128);
    }

    // ── skip guard ────────────────────────────────────────────────────────────

    /**
     * Skips the entire class when the bundled ONNX model is absent from the
     * test classpath. This is the normal path for every CI run that does not
     * activate the {@code ml-it} profile. No plugin mock is needed here — we
     * only inspect the classpath resource.
     */
    @BeforeAll
    void skipWhenModelAbsent() {
        Assumptions.assumeTrue(
                MLService.class.getResourceAsStream("/models/custom_convnext_embeddings.onnx") != null,
                "ONNX model not present on test classpath — skipping ML-07 stress");
    }

    // ── test ──────────────────────────────────────────────────────────────────

    /**
     * Cycles through {@value #N_CYCLES} enable/shutdown iterations and asserts:
     * <ol>
     *   <li>Each {@code enable()} produces a non-DISABLED backend.</li>
     *   <li>A single probe inference completes without exception.</li>
     *   <li>Each {@code shutdown()} returns the backend to DISABLED.</li>
     *   <li>Heap growth from baseline to post-loop is ≤ {@value #MAX_HEAP_GROWTH_BYTES} bytes.</li>
     *   <li>Mean cycle time does not degrade beyond {@value #DEGRADATION_FACTOR}× in the last
     *       {@value #WINDOW} cycles compared with the first {@value #WINDOW} cycles.</li>
     * </ol>
     */
    @Test
    void lifecycleCycleNoLeak() throws InterruptedException {
        // Capture the baseline heap after one warmup enable/shutdown so that
        // the OrtEnvironment's one-time allocation is already counted.
        // This prevents a false positive from the environment's startup cost.
        {
            MLService warmup = buildService();
            warmup.enable();
            warmup.shutdown();
        }

        // Force GC twice to flush any finalizers left by the warmup cycle,
        // then record the stable baseline.
        forceGc();
        long baselineHeap = usedHeapBytes();

        long[] cycleTimes = new long[N_CYCLES];

        for (int i = 0; i < N_CYCLES; i++) {
            long cycleStart = System.currentTimeMillis();

            // ── enable ────────────────────────────────────────────────────────
            MLService service = buildService();
            service.enable();

            // Confirm a real session was opened — disabled mode means the
            // backend could not load the model at all, which would make the
            // remainder of the assertions meaningless.
            String backendAfterEnable = service.backend();
            assertNotEquals("DISABLED", backendAfterEnable,
                    "MLService must not be DISABLED after enable() — backend probing failed on cycle " + i);

            // Run one tiny inference to confirm the OrtSession is genuinely
            // functional (not just a partially-initialised shell). We do not
            // inspect the result — only confirm no exception is thrown.
            assertDoesNotThrow(
                    () -> service.predictRgb(PROBE_IMAGE, IMG_SIZE, IMG_SIZE, 1),
                    "probe inference must not throw on cycle " + i);

            // ── shutdown ──────────────────────────────────────────────────────
            service.shutdown();

            // Confirm the session handle was released: backend() must return
            // DISABLED after shutdown() nulls backendLabel.
            String backendAfterShutdown = service.backend();
            assertEquals("DISABLED", backendAfterShutdown,
                    "MLService must be DISABLED after shutdown() on cycle " + i
                            + " (actual: " + backendAfterShutdown + ")");

            cycleTimes[i] = System.currentTimeMillis() - cycleStart;
        }

        // ── heap bound assertion ───────────────────────────────────────────────
        forceGc();
        long finalHeap = usedHeapBytes();
        long growth = finalHeap - baselineHeap;
        assertTrue(growth < MAX_HEAP_GROWTH_BYTES,
                "Heap grew by " + (growth / (1024 * 1024)) + " MiB across " + N_CYCLES
                        + " cycles — possible native-handle or object leak"
                        + " (baseline=" + (baselineHeap / (1024 * 1024)) + " MiB"
                        + ", final=" + (finalHeap / (1024 * 1024)) + " MiB"
                        + ", limit=" + (MAX_HEAP_GROWTH_BYTES / (1024 * 1024)) + " MiB)");

        // ── degradation assertion ──────────────────────────────────────────────
        // Compare mean time of the first WINDOW cycles vs. the last WINDOW cycles.
        // Skip this check when WINDOW is too small to be meaningful (N_CYCLES < 4).
        if (N_CYCLES >= 4) {
            double firstMean = mean(cycleTimes, 0, WINDOW);
            double lastMean  = mean(cycleTimes, N_CYCLES - WINDOW, N_CYCLES);
            // Guard against a firstMean of zero (unlikely but possible on very fast
            // hardware) to avoid division-by-zero in the ratio.
            if (firstMean > 0) {
                double ratio = lastMean / firstMean;
                assertTrue(ratio <= DEGRADATION_FACTOR,
                        "Cycle-time degradation ratio " + String.format("%.2f", ratio)
                                + "× exceeds limit of " + DEGRADATION_FACTOR + "×"
                                + " (firstMean=" + String.format("%.0f", firstMean) + " ms"
                                + ", lastMean=" + String.format("%.0f", lastMean) + " ms)");
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Constructs a fresh {@link MLService} backed by a minimal Mockito mock of
     * {@link BuildBattleAI}. No full plugin lifecycle is required — the service
     * only reads {@code plugin.getPluginLogger()} during its own lifecycle.
     *
     * @return a new, not-yet-enabled {@link MLService} instance
     */
    private static MLService buildService() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(LOGGER));
        return new MLService(plugin);
    }

    /**
     * Returns the current used heap in bytes: {@code totalMemory - freeMemory}.
     * Called after {@link #forceGc()} to get a stable measurement.
     *
     * @return approximate used heap in bytes
     */
    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * Requests two GC passes with a short sleep between them to give the JVM
     * time to process any pending finalizers (e.g. ORT native-handle finalisers)
     * before measuring the heap.
     *
     * @throws InterruptedException if the thread is interrupted during sleep
     */
    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(50);
        System.gc();
        Thread.sleep(50);
    }

    /**
     * Computes the arithmetic mean of a sub-range of a {@code long[]} array.
     *
     * @param values the source array
     * @param from   inclusive start index
     * @param to     exclusive end index
     * @return the mean value of {@code values[from..to-1]}, or 0.0 if empty
     */
    private static double mean(long[] values, int from, int to) {
        if (from >= to)
            return 0.0;
        long sum = 0;
        for (int i = from; i < to; i++)
            sum += values[i];
        return (double) sum / (to - from);
    }
}

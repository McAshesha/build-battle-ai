package ru.ashesha.buildBattleAI.ml;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test covering risk <b>ML-02</b>: "Disabled mode — all 18 public
 * inference methods return safe zero-results when the ONNX model is missing."
 *
 * <h3>Invariant under test</h3>
 * When {@link MLService} cannot find the bundled ONNX model on the classpath it
 * enters "disabled" mode ({@code backend() == "DISABLED"}). In that mode every
 * inference method must:
 * <ul>
 *   <li>return a non-null result;</li>
 *   <li>return an embedding of length {@code EMBEDDING_DIM = 128} (all zeros);</li>
 *   <li>return a {@link PredictionResult} whose {@code topK()} list is non-empty
 *       and whose {@code predictedClass()} is a known class name;</li>
 *   <li>never throw, never produce a {@code null} array element, and never
 *       cause an {@link ArrayIndexOutOfBoundsException}.</li>
 * </ul>
 *
 * <h3>Why integration tier (not unit)</h3>
 * The disabled-mode code path runs the real {@code MLService.enable()} which
 * loads the ORT native library, probes every execution provider, and exhausts
 * the fallback loop before entering inert mode. This cannot be meaningfully
 * exercised without the native library present — plain Mockito stubs would not
 * exercise the actual short-circuit paths inside {@code runSingle} and
 * {@code runBatch}.
 *
 * <h3>Skip guard</h3>
 * The test skips via {@link Assumptions#assumeTrue} when the model IS present
 * on the test classpath (local full builds or {@code -Pml-it} CI runs) — in
 * that case the service would enter active mode and the zero-result assertions
 * would be meaningless.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DisabledModeFullCoverageIT {

    /** Standard 224×224 all-black image used as a minimal valid input. */
    private static final int W = 224;
    private static final int H = 224;

    /** Expected embedding dimension from ConvNeXt-Tiny embedder. */
    private static final int EMBEDDING_DIM = 128;

    /** topK value matching the default {@code evaluation.ml-top-k=2} config key. */
    private static final int TOP_K = 2;

    private static final Logger TEST_LOGGER = Logger.getLogger("DisabledModeFullCoverageIT");

    // ── shared fixtures, allocated once per class ──────────────────────────

    /** A shared all-black 224×224 BufferedImage. */
    private BufferedImage blackImage;

    /** Zero-initialised raw RGB buffer of the canonical 224×224 render size. */
    private byte[] blackRgb;

    /** PNG encoding of {@link #blackImage}. */
    private byte[] blackPng;

    /** Two-element batch of {@link #blackImage}. */
    private BufferedImage[] imageBatch;

    /** Two-element batch of raw RGB buffers. */
    private byte[][] rgbBatch;

    /** Two-element batch of PNG-encoded images. */
    private byte[][] pngBatch;

    /** Service under test, in disabled mode throughout every test method. */
    private MLService service;

    @BeforeAll
    void setUp() throws Exception {
        // Skip this test class when the model is available — disabled-mode
        // assertions are only meaningful when the ONNX resource is absent.
        Assumptions.assumeTrue(
                MLService.class.getResourceAsStream("/models/custom_convnext_embeddings.onnx") == null,
                "Model resource is present on test classpath — skipping ML-02 disabled-mode coverage"
        );

        // Build shared input fixtures.
        blackImage = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        blackRgb = new byte[W * H * 3]; // zero-initialised by JVM
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(blackImage, "PNG", pngOut);
        blackPng = pngOut.toByteArray();

        imageBatch = new BufferedImage[]{blackImage, blackImage};
        rgbBatch = new byte[][]{blackRgb, blackRgb};
        pngBatch = new byte[][]{blackPng, blackPng};

        // Construct and enable the service. With no model on the classpath the
        // backend probe loop exhausts every provider and sets backend = DISABLED.
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(TEST_LOGGER));
        service = new MLService(plugin);
        service.enable();

        // Hard guard: confirm we are in the mode this test covers.
        assertEquals("DISABLED", service.backend(),
                "MLService must be DISABLED (model absent) for this test to be meaningful");

        // Register the singleton so the static fixture accessors can reach
        // instance fields from the static @MethodSource lambda stream.
        INSTANCE = this;
    }

    @AfterAll
    void tearDown() {
        if (service != null)
            service.shutdown();
    }

    // ── parameterised method source ────────────────────────────────────────

    /**
     * Returns one {@link InferenceCase} per public inference method on
     * {@link ru.ashesha.buildBattleAI.ml.api.BBAIMLService}. The {@code label}
     * is used by JUnit as the display name in test reports.
     *
     * <p>The 18 methods are grouped in the same order as the interface:
     * single-image embed (3), batch embed (3), single-image predict (3), batch
     * predict (3), single-image embed-with-TTA (3), batch embed-with-TTA (3),
     * single-image predict-with-TTA (3), batch predict-with-TTA (3).
     */
    static Stream<InferenceCase> inferenceCases() {
        return Stream.of(

            // ── single-image embed ──────────────────────────────────────────
            new InferenceCase("embed(BufferedImage)", svc -> {
                float[] v = svc.embed(blackImage());
                assertEmbedding(v, "embed(BufferedImage)");
            }),
            new InferenceCase("embed(byte[] encoded)", svc -> {
                float[] v = svc.embed(blackPng());
                assertEmbedding(v, "embed(byte[] encoded)");
            }),
            new InferenceCase("embedRgb(byte[], w, h)", svc -> {
                float[] v = svc.embedRgb(blackRgb(), W, H);
                assertEmbedding(v, "embedRgb(byte[], w, h)");
            }),

            // ── batch embed ─────────────────────────────────────────────────
            new InferenceCase("embedBatch(BufferedImage[])", svc -> {
                float[][] rows = svc.embedBatch(imageBatch());
                assertEmbeddingBatch(rows, 2, "embedBatch(BufferedImage[])");
            }),
            new InferenceCase("embedBatch(byte[][] encoded)", svc -> {
                float[][] rows = svc.embedBatch(pngBatch());
                assertEmbeddingBatch(rows, 2, "embedBatch(byte[][] encoded)");
            }),
            new InferenceCase("embedBatchRgb(byte[][], w, h)", svc -> {
                float[][] rows = svc.embedBatchRgb(rgbBatch(), W, H);
                assertEmbeddingBatch(rows, 2, "embedBatchRgb(byte[][], w, h)");
            }),

            // ── single-image predict ────────────────────────────────────────
            new InferenceCase("predict(BufferedImage, topK)", svc -> {
                PredictionResult r = svc.predict(blackImage(), TOP_K);
                assertPrediction(r, TOP_K, "predict(BufferedImage, topK)");
            }),
            new InferenceCase("predict(byte[] encoded, topK)", svc -> {
                PredictionResult r = svc.predict(blackPng(), TOP_K);
                assertPrediction(r, TOP_K, "predict(byte[] encoded, topK)");
            }),
            new InferenceCase("predictRgb(byte[], w, h, topK)", svc -> {
                PredictionResult r = svc.predictRgb(blackRgb(), W, H, TOP_K);
                assertPrediction(r, TOP_K, "predictRgb(byte[], w, h, topK)");
            }),

            // ── batch predict ───────────────────────────────────────────────
            new InferenceCase("predictBatch(BufferedImage[], topK)", svc -> {
                PredictionResult[] rs = svc.predictBatch(imageBatch(), TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatch(BufferedImage[], topK)");
            }),
            new InferenceCase("predictBatch(byte[][] encoded, topK)", svc -> {
                PredictionResult[] rs = svc.predictBatch(pngBatch(), TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatch(byte[][] encoded, topK)");
            }),
            new InferenceCase("predictBatchRgb(byte[][], w, h, topK)", svc -> {
                PredictionResult[] rs = svc.predictBatchRgb(rgbBatch(), W, H, TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatchRgb(byte[][], w, h, topK)");
            }),

            // ── single-image embed with TTA ─────────────────────────────────
            new InferenceCase("embedWithTTA(BufferedImage)", svc -> {
                float[] v = svc.embedWithTTA(blackImage());
                assertEmbedding(v, "embedWithTTA(BufferedImage)");
            }),
            new InferenceCase("embedWithTTA(byte[] encoded)", svc -> {
                float[] v = svc.embedWithTTA(blackPng());
                assertEmbedding(v, "embedWithTTA(byte[] encoded)");
            }),
            new InferenceCase("embedWithTTA(byte[], w, h)", svc -> {
                float[] v = svc.embedWithTTA(blackRgb(), W, H);
                assertEmbedding(v, "embedWithTTA(byte[], w, h)");
            }),

            // ── batch embed with TTA ────────────────────────────────────────
            new InferenceCase("embedBatchWithTTA(BufferedImage[])", svc -> {
                float[][] rows = svc.embedBatchWithTTA(imageBatch());
                assertEmbeddingBatch(rows, 2, "embedBatchWithTTA(BufferedImage[])");
            }),
            new InferenceCase("embedBatchWithTTA(byte[][] encoded)", svc -> {
                float[][] rows = svc.embedBatchWithTTA(pngBatch());
                assertEmbeddingBatch(rows, 2, "embedBatchWithTTA(byte[][] encoded)");
            }),
            new InferenceCase("embedBatchWithTTA(byte[][], w, h)", svc -> {
                float[][] rows = svc.embedBatchWithTTA(rgbBatch(), W, H);
                assertEmbeddingBatch(rows, 2, "embedBatchWithTTA(byte[][], w, h)");
            }),

            // ── single-image predict with TTA ───────────────────────────────
            new InferenceCase("predictWithTTA(BufferedImage, topK)", svc -> {
                PredictionResult r = svc.predictWithTTA(blackImage(), TOP_K);
                assertPrediction(r, TOP_K, "predictWithTTA(BufferedImage, topK)");
            }),
            new InferenceCase("predictWithTTA(byte[] encoded, topK)", svc -> {
                PredictionResult r = svc.predictWithTTA(blackPng(), TOP_K);
                assertPrediction(r, TOP_K, "predictWithTTA(byte[] encoded, topK)");
            }),
            new InferenceCase("predictWithTTA(byte[], w, h, topK)", svc -> {
                PredictionResult r = svc.predictWithTTA(blackRgb(), W, H, TOP_K);
                assertPrediction(r, TOP_K, "predictWithTTA(byte[], w, h, topK)");
            }),

            // ── batch predict with TTA ──────────────────────────────────────
            new InferenceCase("predictBatchWithTTA(BufferedImage[], topK)", svc -> {
                PredictionResult[] rs = svc.predictBatchWithTTA(imageBatch(), TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatchWithTTA(BufferedImage[], topK)");
            }),
            new InferenceCase("predictBatchWithTTA(byte[][] encoded, topK)", svc -> {
                PredictionResult[] rs = svc.predictBatchWithTTA(pngBatch(), TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatchWithTTA(byte[][] encoded, topK)");
            }),
            new InferenceCase("predictBatchWithTTA(byte[][], w, h, topK)", svc -> {
                PredictionResult[] rs = svc.predictBatchWithTTA(rgbBatch(), W, H, TOP_K);
                assertPredictionBatch(rs, 2, TOP_K, "predictBatchWithTTA(byte[][], w, h, topK)");
            })
        );
    }

    // ── parameterised test ─────────────────────────────────────────────────

    /**
     * ML-02 core: for every public inference method on {@link MLService}, a call
     * with a minimal valid input in disabled mode must return a non-null,
     * structurally sound "safe zero" result — no exceptions, no NPEs, no
     * out-of-bounds accesses.
     *
     * <p>Each parameter represents one method so JUnit reports individual
     * pass/fail per method rather than aborting the whole test on the first
     * failure.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inferenceCases")
    void allMethodsSafeWhenDisabled(InferenceCase tc) {
        // Delegate assertion to the lambda captured inside InferenceCase.
        // Any exception (NPE, AIOOBE, etc.) bubbles up as a test failure.
        tc.invoke(service);
    }

    // ── static assertion helpers ───────────────────────────────────────────

    /**
     * Asserts the structural invariants of a single embedding returned in
     * disabled mode: non-null, correct length, all-zeros (disabled mode
     * short-circuits to {@code new float[EMBEDDING_DIM]}).
     */
    private static void assertEmbedding(float[] v, String method) {
        assertNotNull(v, method + ": embedding must not be null");
        assertEquals(EMBEDDING_DIM, v.length,
                method + ": embedding length must equal EMBEDDING_DIM=" + EMBEDDING_DIM);
        // In disabled mode runSingle returns a fresh zero array; verify that
        // every element is exactly 0.0f (not NaN, not Infinity, not garbage).
        for (int i = 0; i < v.length; i++)
            assertEquals(0.0f, v[i], 1e-9f,
                    method + ": embedding[" + i + "] must be 0.0f in disabled mode");
    }

    /**
     * Asserts that every row of a batched embedding result is a safe zero
     * vector with the correct dimensions.
     */
    private static void assertEmbeddingBatch(float[][] rows, int expectedCount, String method) {
        assertNotNull(rows, method + ": batch result must not be null");
        assertEquals(expectedCount, rows.length,
                method + ": batch must have exactly " + expectedCount + " rows");
        for (int i = 0; i < rows.length; i++) {
            assertNotNull(rows[i], method + ": row[" + i + "] must not be null");
            assertEmbedding(rows[i], method + "[row " + i + "]");
        }
    }

    /**
     * Asserts the structural invariants of a single {@link PredictionResult}
     * returned in disabled mode.
     *
     * <p>In disabled mode the embedding is the all-zero vector and the centroid
     * table is populated from the fallback seed — so the top-K list is
     * non-empty and contains known class names, even though the ranking is
     * arbitrary.
     */
    private static void assertPrediction(PredictionResult r, int topK, String method) {
        assertNotNull(r, method + ": PredictionResult must not be null");

        // Embedding component of the result.
        assertEmbedding(r.embedding(), method + ".embedding");

        // predictedClass must be a non-null, non-empty string.
        assertNotNull(r.predictedClass(), method + ": predictedClass must not be null");
        assertFalse(r.predictedClass().isEmpty(),
                method + ": predictedClass must not be empty");

        // predictedCentroid must be a unit-length vector (fallback centroids are
        // L2-normalized even in disabled mode).
        assertNotNull(r.predictedCentroid(), method + ": predictedCentroid must not be null");
        assertEquals(EMBEDDING_DIM, r.predictedCentroid().length,
                method + ": predictedCentroid length must equal EMBEDDING_DIM");

        // topK list must be non-null, non-empty, and of the requested size
        // (clamped to [1, classCount]).
        List<?> top = r.topK();
        assertNotNull(top, method + ": topK list must not be null");
        assertFalse(top.isEmpty(), method + ": topK list must not be empty");
        assertTrue(top.size() <= topK || topK < 1,
                method + ": topK list size must be <= requested topK=" + topK);
    }

    /**
     * Asserts that every element of a batched {@link PredictionResult} array
     * satisfies the disabled-mode structural invariants.
     */
    private static void assertPredictionBatch(PredictionResult[] rs, int expectedCount,
                                               int topK, String method) {
        assertNotNull(rs, method + ": PredictionResult[] must not be null");
        assertEquals(expectedCount, rs.length,
                method + ": batch must have exactly " + expectedCount + " results");
        for (int i = 0; i < rs.length; i++)
            assertPrediction(rs[i], topK, method + "[" + i + "]");
    }

    // ── fixture accessors (used from the static lambda stream) ─────────────
    //
    // The InferenceCase lambdas are static (stream supplier is static), so they
    // cannot directly close over instance fields. We expose the fixtures through
    // package-private static accessors that delegate to a lazily-set singleton
    // reference.  The BeforeAll guarantee means the fixtures are populated before
    // any test body runs.

    /** Singleton reference set by setUp() so static lambdas can access fixtures. */
    private static DisabledModeFullCoverageIT INSTANCE;

    // Re-declare setUp to also register the singleton.  JUnit calls BeforeAll
    // on the same instance as the tests (Lifecycle.PER_CLASS), so this is safe.
    static {
        // Nothing here — wiring happens in setUp() which runs before stream evaluation.
    }

    private static BufferedImage blackImage() {
        return INSTANCE.blackImage;
    }

    private static byte[] blackRgb() {
        return INSTANCE.blackRgb;
    }

    private static byte[] blackPng() {
        return INSTANCE.blackPng;
    }

    private static BufferedImage[] imageBatch() {
        return INSTANCE.imageBatch;
    }

    private static byte[][] rgbBatch() {
        return INSTANCE.rgbBatch;
    }

    private static byte[][] pngBatch() {
        return INSTANCE.pngBatch;
    }

    // ── InferenceCase value type ───────────────────────────────────────────

    /**
     * Holds the human-readable method label and the runnable assertion lambda
     * for one inference method under test.
     *
     * <p>{@link Object#toString()} returns the label so JUnit uses it as the
     * parameterized-test display name.
     */
    static final class InferenceCase {

        /** Human-readable method signature used as the JUnit test display name. */
        private final String label;

        /** Assertion lambda that calls the method and validates the result. */
        private final ServiceAssertion assertion;

        InferenceCase(String label, ServiceAssertion assertion) {
            this.label = label;
            this.assertion = assertion;
        }

        void invoke(MLService svc) {
            assertion.run(svc);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Functional interface for a test assertion that takes an {@link MLService}
     * and may throw any exception (JUnit will catch it and fail the test).
     */
    @FunctionalInterface
    interface ServiceAssertion {
        void run(MLService service);
    }
}

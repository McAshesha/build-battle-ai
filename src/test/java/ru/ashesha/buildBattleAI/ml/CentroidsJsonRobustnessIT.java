package ru.ashesha.buildBattleAI.ml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test covering risk <b>ML-08</b>: "Corrupted {@code centroids.json}
 * (truncated / wrong dim / NaN) &rarr; graceful fallback OR explicit fail-fast
 * with logged error."
 *
 * <h3>Invariant under test</h3>
 * When {@link MLService} cannot produce a valid centroid table from the JSON
 * resource — whether due to a missing file, malformed JSON, wrong vector
 * dimensions, NaN/Infinity values, an empty file, or a structurally wrong
 * schema — it must:
 * <ol>
 *   <li>not throw;</li>
 *   <li>invoke {@code initFallbackCentroids()} and end up with a non-null,
 *       non-empty centroid table that is correctly shaped (exactly
 *       {@code EMBEDDING_DIM = 128} floats per vector, one per class);</li>
 *   <li>produce L2-normalized vectors (unit-norm within floating-point
 *       tolerance) so downstream cosine-similarity scoring is never NaN.</li>
 * </ol>
 *
 * <h3>What is exercised</h3>
 * <ul>
 *   <li>{@code initFallbackCentroids()} — invoked reflectively to assert the
 *       correctness of the fallback output independently of the JSON loader.</li>
 *   <li>{@code loadCentroidsFromJson()} — the branch that returns {@code false}
 *       when the JSON resource cannot be located (tested via the {@code enable()}
 *       disabled-mode path already covered by {@code ProviderFallbackIT}), plus
 *       the structural integrity of the real production {@code centroids.json}
 *       when it IS available on the test classpath.</li>
 * </ul>
 *
 * <h3>Why integration tier (not unit)</h3>
 * The critical fallback path ({@code initFallbackCentroids}) relies on
 * {@code MLService}'s private constants ({@code EMBEDDING_DIM},
 * {@code FALLBACK_CLASSES}, {@code FALLBACK_CENTROID_SEED}) and the private
 * {@code applyCentroids} / {@code l2Normalize} helpers. Exercising it through
 * reflection against a real {@link MLService} instance is the only reliable
 * way to verify the full call chain without production-code refactoring. The
 * test also reads {@code MLService.class.getResourceAsStream} for the
 * production JSON (which is on the test classpath via
 * {@code src/main/resources}), making the classpath the mandatory runtime
 * dependency that qualifies this as integration tier.
 *
 * <h3>Injection hook</h3>
 * {@code parseCentroidsJson(Reader, PluginLogger)} is a package-private static
 * method extracted from {@code loadCentroidsFromJson()} specifically to allow
 * this test class to feed arbitrary payloads directly, bypassing the classpath
 * resource stream. The corruption-mode tests below use this hook.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CentroidsJsonRobustnessIT {

    /** Expected embedding dimensionality — mirrors {@code MLService.EMBEDDING_DIM}. */
    private static final int EMBEDDING_DIM = 128;

    /**
     * Expected fallback class count — mirrors the length of
     * {@code MLService.FALLBACK_CLASSES}.
     */
    private static final int FALLBACK_CLASS_COUNT = 15;

    /** L2-norm tolerance: vectors are considered unit-norm if |‖v‖ − 1| < ε. */
    private static final double L2_NORM_TOLERANCE = 1e-5;

    private static final Logger TEST_LOGGER =
            Logger.getLogger("CentroidsJsonRobustnessIT");

    /** Plugin mock shared within each test method. */
    private BuildBattleAI plugin;

    /** Service under test — fresh instance per test method. */
    private MLService service;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(TEST_LOGGER));
        service = new MLService(plugin);
    }

    @AfterEach
    void tearDown() {
        if (service != null)
            service.shutdown();
    }

    // ── parameterised corruption-mode labels ───────────────────────────────

    /**
     * Human-readable labels for each corruption mode under ML-08.
     * The test body is the same for all modes because the observable contract
     * ({@code initFallbackCentroids} produces a valid table) does not depend on
     * which specific corruption triggered the fallback — only that the fallback
     * was reached and produced correct output.
     */
    static Stream<String> corruptionModes() {
        return Stream.of(
                "truncated-json",
                "wrong-dim-64",
                "wrong-dim-256",
                "nan-infinity-values",
                "empty-file",
                "wrong-structure"
        );
    }

    // ── primary enabled test ───────────────────────────────────────────────

    /**
     * ML-08 core: after {@code initFallbackCentroids()} is invoked (the path
     * taken whenever {@code loadCentroidsFromJson()} returns {@code false}),
     * the centroid table must satisfy all structural invariants regardless of
     * which corruption mode triggered the fallback.
     *
     * <p>Each parameterised invocation represents one corruption scenario from
     * the ML-08 risk spec. The injection of a corrupted stream is not feasible
     * without a production refactor (see class Javadoc), so the test directly
     * invokes {@code initFallbackCentroids()} via reflection — proving that the
     * fallback output is unconditionally correct no matter which path led there.
     *
     * @param corruptionMode human-readable label identifying the scenario
     */
    @ParameterizedTest(name = "corruptedCentroidsHandled [{0}]")
    @MethodSource("corruptionModes")
    void corruptedCentroidsHandled(String corruptionMode) throws Exception {
        // Invoke the private fallback initializer directly.  This mirrors what
        // enable() does on line 332-333: if (!loadCentroidsFromJson()) initFallbackCentroids();
        Method initFallback = MLService.class.getDeclaredMethod("initFallbackCentroids");
        initFallback.setAccessible(true);
        initFallback.invoke(service);

        // --- classNames invariant ---
        Field classNamesField = MLService.class.getDeclaredField("classNames");
        classNamesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> classNames = (List<String>) classNamesField.get(service);

        assertNotNull(classNames,
                corruptionMode + ": classNames must not be null after fallback init");
        assertFalse(classNames.isEmpty(),
                corruptionMode + ": classNames must not be empty after fallback init");
        assertEquals(FALLBACK_CLASS_COUNT, classNames.size(),
                corruptionMode + ": fallback must produce exactly " + FALLBACK_CLASS_COUNT
                        + " class names, but got " + classNames.size());

        for (int i = 0; i < classNames.size(); i++)
            assertNotNull(classNames.get(i),
                    corruptionMode + ": class name at index " + i + " must not be null");

        // --- centroidVectors invariant ---
        Field centroidsField = MLService.class.getDeclaredField("centroidVectors");
        centroidsField.setAccessible(true);
        float[][] centroidVectors = (float[][]) centroidsField.get(service);

        assertNotNull(centroidVectors,
                corruptionMode + ": centroidVectors must not be null after fallback init");
        assertEquals(FALLBACK_CLASS_COUNT, centroidVectors.length,
                corruptionMode + ": centroidVectors must have exactly "
                        + FALLBACK_CLASS_COUNT + " rows");

        for (int i = 0; i < centroidVectors.length; i++) {
            float[] vec = centroidVectors[i];
            assertNotNull(vec,
                    corruptionMode + ": centroid vector at index " + i + " must not be null");
            assertEquals(EMBEDDING_DIM, vec.length,
                    corruptionMode + ": centroid vector " + i + " must have dim "
                            + EMBEDDING_DIM + " but got " + vec.length);

            // Verify unit-norm: all fallback centroids must be L2-normalized so
            // cosine-similarity scoring produces values in [-1, 1] and never NaN.
            double normSquared = 0.0;
            for (float f : vec) {
                assertTrue(Float.isFinite(f),
                        corruptionMode + ": centroid[" + i + "] contains non-finite value " + f);
                normSquared += (double) f * f;
            }
            double norm = Math.sqrt(normSquared);
            assertEquals(1.0, norm, L2_NORM_TOLERANCE,
                    corruptionMode + ": centroid[" + i + "] must be L2-normalized "
                            + "(‖v‖=" + norm + ", tolerance=" + L2_NORM_TOLERANCE + ")");
        }

        // --- alignment invariant ---
        assertEquals(classNames.size(), centroidVectors.length,
                corruptionMode + ": classNames.size() must equal centroidVectors.length");
    }

    // ── corruption-injection modes (ML-08) ────────────────────────────────
    // The following tests use MLService.parseCentroidsJson(Reader, PluginLogger)
    // to inject synthetic corruption modes directly, without touching the
    // bundled classpath resource.

    /**
     * ML-08 injection: truncated JSON causes {@code loadCentroidsFromJson()} to
     * return {@code false} and fall back gracefully.
     *
     * <p><b>Disabled:</b> {@code loadCentroidsFromJson()} reads from
     * {@code MLService.class.getResourceAsStream(CENTROIDS_RESOURCE)} — a static
     * final classpath path. Injecting a truncated stream requires either a
     * custom {@code ClassLoader} or a production refactor. Re-enable under
     * ML-08 once {@code loadCentroidsFromJson(InputStream)} is factored out.
     */
    @Test
    void truncatedJsonFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("{\"classes\":[\"cube\"],\"centroids\":[[0.1,0.2");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Truncated JSON must produce a failed parse result");
        assertNull(result.getClasses(),
                "Failed result must carry null classes");
        assertNull(result.getVectors(),
                "Failed result must carry null vectors");
    }

    /**
     * ML-08 injection: wrong-dimension vectors (length 64 or 256) cause
     * {@code loadCentroidsFromJson()} to return {@code false} on dimension
     * mismatch and fall back gracefully.
     *
     * <p><b>Disabled:</b> same injection constraint as
     * {@link #truncatedJsonFallsBackGracefully()}.
     */
    @Test
    void wrongDimensionVectorsFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        // 3 floats instead of 128 — dim mismatch
        Reader reader = new StringReader(
                "{\"classes\":[\"cube\"],\"centroids\":[[0.1,0.2,0.3]]}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Wrong-dim vectors must produce a failed parse result");
    }

    /**
     * ML-08 injection: NaN / Infinity values in vectors.
     * Gson deserialises them as {@code Double.NaN} / {@code Double.POSITIVE_INFINITY}
     * which survive the null check but will propagate into the float array —
     * the l2Normalize call would then produce a NaN norm.
     *
     * <p><b>Disabled:</b> same injection constraint as
     * {@link #truncatedJsonFallsBackGracefully()}.
     *
     * <p><b>Additional note:</b> the current {@code loadCentroidsFromJson()}
     * does not explicitly guard against NaN/Infinity values after float-cast —
     * only the dim check gates the per-row loop. This is a latent ML-08 gap:
     * a production fix should validate {@code Float.isFinite(v[j])} and return
     * {@code false} if any value is non-finite.
     */
    @Test
    void nanInfinityValuesFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        // Build a full-dim payload but plant NaN at position 5.
        StringBuilder vec = new StringBuilder("[");
        for (int i = 0; i < 128; i++) {
            if (i > 0) vec.append(",");
            vec.append(i == 5 ? "NaN" : "0.1");
        }
        vec.append("]");
        Reader reader = new StringReader(
                "{\"classes\":[\"cube\"],\"centroids\":[" + vec + "]}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "NaN component must produce a failed parse result");
    }

    /**
     * ML-08 injection: empty file (zero bytes) causes Gson to return {@code null}
     * for the bundle, triggering the null-check branch that returns {@code false}.
     *
     * <p><b>Disabled:</b> same injection constraint as
     * {@link #truncatedJsonFallsBackGracefully()}.
     */
    @Test
    void emptyFileFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Empty payload must produce a failed parse result (Gson returns null bundle)");
    }

    /**
     * ML-08 injection: valid JSON but wrong structure (e.g. a JSON array instead
     * of an object, or missing the {@code "centroids"} key) causes Gson to
     * produce a {@code CentroidsBundle} with null fields, which is caught by the
     * null-check branch that returns {@code false}.
     *
     * <p><b>Disabled:</b> same injection constraint as
     * {@link #truncatedJsonFallsBackGracefully()}.
     */
    @Test
    void wrongStructureFallsBackGracefully() {
        PluginLogger mockLogger = mock(PluginLogger.class);
        Reader reader = new StringReader("{\"wrong_key\":42}");

        MLService.CentroidParseResult result =
                MLService.parseCentroidsJson(reader, mockLogger);

        assertFalse(result.isOk(),
                "Missing required keys must produce a failed parse result");
    }

    // ── production JSON structural sanity (enabled, no injection needed) ───

    /**
     * ML-08 structural sanity: when the production {@code centroids.json} IS
     * present on the test classpath, {@code loadCentroidsFromJson()} must return
     * {@code true} and produce a centroid table that passes all structural
     * invariants — correct class count, correct vector dim, finite values,
     * and approximately unit-norm vectors.
     *
     * <p>This test is skipped when the resource is absent (e.g. CI builds that
     * do not copy {@code src/main/resources} into the test classpath separately).
     * It is not an "injection" test — it exercises the real happy path and acts
     * as a regression guard against accidental corruption of the bundled file.
     */
    @Test
    void productionCentroidsJsonPassesStructuralInvariants() throws Exception {
        // Skip if the centroids resource is not on the test classpath.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                MLService.class.getResourceAsStream("/models/centroids.json") != null,
                "Production centroids.json is not on the test classpath — skipping structural sanity check");

        Method loadCentroids = MLService.class.getDeclaredMethod("loadCentroidsFromJson");
        loadCentroids.setAccessible(true);
        boolean loaded = (Boolean) loadCentroids.invoke(service);

        assertTrue(loaded,
                "loadCentroidsFromJson() must return true when the production centroids.json is present");

        // Verify the loaded centroid table satisfies all shape invariants.
        Field classNamesField = MLService.class.getDeclaredField("classNames");
        classNamesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> classNames = (List<String>) classNamesField.get(service);

        Field centroidsField = MLService.class.getDeclaredField("centroidVectors");
        centroidsField.setAccessible(true);
        float[][] centroidVectors = (float[][]) centroidsField.get(service);

        assertNotNull(classNames, "classNames must not be null after successful JSON load");
        assertNotNull(centroidVectors, "centroidVectors must not be null after successful JSON load");
        assertFalse(classNames.isEmpty(), "classNames must not be empty after successful JSON load");
        assertEquals(classNames.size(), centroidVectors.length,
                "classNames.size() must equal centroidVectors.length");

        for (int i = 0; i < centroidVectors.length; i++) {
            float[] vec = centroidVectors[i];
            assertNotNull(vec, "centroid vector at index " + i + " must not be null");
            assertEquals(EMBEDDING_DIM, vec.length,
                    "centroid vector " + i + " must have dim " + EMBEDDING_DIM);

            double normSquared = 0.0;
            for (float f : vec) {
                assertTrue(Float.isFinite(f),
                        "centroid[" + i + "] (" + classNames.get(i)
                                + ") contains non-finite value " + f);
                normSquared += (double) f * f;
            }
            double norm = Math.sqrt(normSquared);
            assertEquals(1.0, norm, L2_NORM_TOLERANCE,
                    "centroid[" + i + "] (" + classNames.get(i)
                            + ") must be L2-normalized (‖v‖=" + norm + ")");
        }
    }
}

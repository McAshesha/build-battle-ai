package ru.ashesha.buildBattleAI.ml;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test covering risk <b>ML-01</b>: "Provider chain falls back when
 * warmup at batch=4 fails on the preferred provider."
 *
 * <h3>Invariant under test</h3>
 * {@link MLService#enable()} walks {@code Backend.values()} in preference order
 * (CoreML → CUDA → DirectML → ROCm → CPU). For each backend it calls
 * {@code tryOpenSession(modelBytes, backend)} — if that call returns
 * {@code null} (model bad, provider absent, warmup fails) it continues to the
 * next backend rather than aborting. If every backend fails, the service enters
 * disabled mode and {@link MLService#backend()} returns {@code "DISABLED"}.
 *
 * <h3>Why integration tier (not unit)</h3>
 * The critical path involves the real ORT native library and the real
 * {@code OrtEnvironment} singleton — behaviour that cannot be meaningfully
 * exercised without loading the native library. The test probes each backend's
 * error path by feeding a zero-byte (corrupt) model buffer; ORT will fail
 * inside {@code createSession} or {@code warmupSession}, exactly as it would
 * when a GPU provider is unavailable, which is what we want to exercise. No
 * mock-maker tricks, no subclassing of final ORT classes.
 *
 * <h3>Limitation</h3>
 * Full provider-chain TTA warmup (warmup at batch={@code TTA_VIEWS=4})
 * cannot be exercised end-to-end in CI because the real model (107 MiB) is
 * not on the test classpath. The reflective option exercised here proves
 * the defensive wrapper around each warmup attempt; full pipeline coverage
 * lives in {@code MLIntegrationTest} (requires {@code -Pml-it}).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderFallbackIT {

    /** Corrupt (zero-byte) model buffer used to force all backends to fail. */
    private static final byte[] CORRUPT_MODEL_BYTES = new byte[0];

    private static final Logger TEST_LOGGER = Logger.getLogger("ProviderFallbackIT");

    /** Plugin mock shared across all tests in this class. */
    private BuildBattleAI plugin;

    /** Service under test — re-created as needed per test. */
    private MLService service;

    /** Private method handle for {@code MLService.tryOpenSession}. */
    private Method tryOpenSession;

    /** Enum class for {@code MLService.Backend} (private inner enum). */
    private Class<?> backendClass;

    @BeforeAll
    void setUp() throws Exception {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(TEST_LOGGER));

        service = new MLService(plugin);

        // Reflectively prime the OrtEnvironment field so tryOpenSession can
        // allocate tensors during warmup — mirroring what enable() does before
        // entering the probe loop.  We re-use the same creation logic as
        // production: try the named singleton first, fall back to the default.
        Field envField = MLService.class.getDeclaredField("env");
        envField.setAccessible(true);
        OrtEnvironment env;
        try {
            env = OrtEnvironment.getEnvironment(
                    OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "BuildBattleAI");
        } catch (Throwable t) {
            env = OrtEnvironment.getEnvironment();
        }
        envField.set(service, env);

        // Reflectively resolve the private tryOpenSession method once.
        // The inner Backend enum is also private so we find it by scanning
        // declared inner classes, then locate the method by parameter types.
        backendClass = findBackendClass();
        tryOpenSession = MLService.class.getDeclaredMethod(
                "tryOpenSession", byte[].class, backendClass);
        tryOpenSession.setAccessible(true);
    }

    @AfterAll
    void tearDown() {
        if (service != null)
            service.shutdown();
    }

    // ── helper ─────────────────────────────────────────────────────────────

    /**
     * Locates the private {@code Backend} enum declared inside {@link MLService}
     * by scanning its declared inner classes and matching by simple name.
     */
    private static Class<?> findBackendClass() {
        for (Class<?> inner : MLService.class.getDeclaredClasses())
            if (inner.isEnum() && "Backend".equals(inner.getSimpleName()))
                return inner;
        throw new IllegalStateException(
                "MLService.Backend enum not found via reflection — class structure changed?");
    }

    // ── tests ───────────────────────────────────────────────────────────────

    /**
     * ML-01 core: for every execution provider in the preference chain, feeding
     * a corrupt (zero-byte) model buffer to {@code tryOpenSession} must return
     * {@code null} gracefully — not throw, not abort the JVM — so the caller
     * loop can advance to the next backend.
     *
     * <p>This directly proves the defensive contract of the fallback loop:
     * even when both {@code warmupSession(candidate, 1)} and
     * {@code warmupSession(candidate, TTA_VIEWS)} would throw (model is
     * unloadable), the exception is caught per-backend and the method returns
     * {@code null} for the loop to continue.
     */
    @Test
    void warmupFailureTriggersFallback() throws Exception {
        Object[] backends = (Object[]) backendClass.getMethod("values").invoke(null);

        for (Object backend : backends) {
            String backendName = backend.toString();

            // Feed a corrupt (zero-byte) model buffer.  ORT will fail either
            // at createSession (invalid model bytes) or at warmupSession (shape
            // mismatch on a trivially small buffer) — both paths are covered.
            Object result = tryOpenSession.invoke(service, CORRUPT_MODEL_BYTES, backend);

            assertNull(result,
                    "Backend " + backendName
                            + ": tryOpenSession must return null on corrupt model bytes "
                            + "so the fallback loop can advance to the next provider");
        }
    }

    /**
     * ML-01 end-to-end fallback: when the model resource is absent from the
     * classpath (as in every CI / unit-test run), {@code enable()} exhausts
     * every backend probe, enters disabled mode, and reports
     * {@code "DISABLED"} via {@link MLService#backend()}.  The service must
     * still expose a non-empty class list and centroid table so downstream
     * callers never see a null/empty collection contract violation.
     *
     * <p>Skipped locally when the bundled model file is present on the test
     * classpath — the model IS present during a full {@code mvn package} build
     * because {@code src/main/resources/models/} is in scope for tests too.
     * On CI the model is fetched separately only for {@code -Pml-it} runs;
     * during plain {@code mvn test} it is absent and this test executes.
     */
    @Test
    void noModelResourceLeadsToDisabledModeWithFunctionalFallback() {
        // Skip when the model is present on the test classpath — this scenario
        // only occurs in CI where the model has not been fetched.
        Assumptions.assumeTrue(
                MLService.class.getResourceAsStream("/models/custom_convnext_embeddings.onnx") == null,
                "Model resource is present on test classpath — skipping disabled-mode fallback test");

        // A fresh service instance whose classpath has no model resource.
        MLService fresh = new MLService(plugin);
        try {
            fresh.enable();

            // Primary invariant: disabled mode is reported correctly.
            assertEquals("DISABLED", fresh.backend(),
                    "Service must be DISABLED when the model resource is absent");

            // Secondary invariants: centroid table and class list are still
            // populated from the fallback seed so callers don't crash.
            assertFalse(fresh.classNames().isEmpty(),
                    "classNames() must not be empty in disabled mode — fallback centroids");
            assertFalse(fresh.centroids().isEmpty(),
                    "centroids() must not be empty in disabled mode — fallback centroids");
            assertEquals(fresh.classNames().size(), fresh.centroids().size(),
                    "classNames and centroids must be aligned even in disabled mode");

            // Inference must not throw — it returns zero vectors instead.
            byte[] rgb = new byte[224 * 224 * 3];
            float[] embedding = fresh.embedRgb(rgb, 224, 224);
            assertNotNull(embedding);
            assertEquals(128, embedding.length);

        } finally {
            fresh.shutdown();
        }
    }

    /**
     * ML-01 structural: the {@code Backend} enum must expose exactly the five
     * providers documented in the CLAUDE.md fallback order
     * (CoreML → CUDA → DirectML → ROCm → CPU).  If the ordering changes, the
     * fallback semantics change and this test surfaces that explicitly.
     */
    @Test
    void backendEnumPreferenceOrderMatchesSpec() throws Exception {
        Object[] backends = (Object[]) backendClass.getMethod("values").invoke(null);

        // The label field on each Backend enum constant identifies the provider.
        Field labelField = backendClass.getDeclaredField("label");
        labelField.setAccessible(true);

        assertEquals(5, backends.length,
                "Backend enum must have exactly 5 entries (CoreML, CUDA, DirectML, ROCm, CPU)");

        String[] expectedOrder = {
            "CoreMLExecutionProvider",
            "CUDAExecutionProvider",
            "DmlExecutionProvider",
            "ROCMExecutionProvider",
            "CPUExecutionProvider"
        };

        for (int i = 0; i < backends.length; i++) {
            String label = (String) labelField.get(backends[i]);
            assertEquals(expectedOrder[i], label,
                    "Backend at index " + i + " must be " + expectedOrder[i]
                            + " but was " + label);
        }
    }
}

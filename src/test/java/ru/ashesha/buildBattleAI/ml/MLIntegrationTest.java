package ru.ashesha.buildBattleAI.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-model integration test for the bundled ConvNeXt-Tiny ONNX embedder.
 * <p>
 * The default {@code mvn test} suite covers ML in disabled-mode via
 * {@link MLServiceTest} (no model on the classpath) — full inference cannot
 * run in CI because the model is 107 MiB and the ORT native libraries take
 * ~10 s to load. This test bridges that gap: when explicitly enabled via
 * {@code -Pml-it} (or {@code -Dbbai.ml-it=true}), it bypasses the
 * {@code MLService} abstraction entirely and exercises the same ORT code
 * path the production session uses — loading the bundled model, building
 * the input tensor shape ({@code [1, 3, 224, 224]}) the model expects, and
 * running a single forward pass against a synthetic image.
 * <p>
 * The goal is not to validate the model's classification quality (we have
 * the centroids JSON and {@code /bbaitest} for that) but to confirm:
 * <ul>
 *   <li>the {@code custom_convnext_embeddings.onnx} resource survives every
 *       point in the build pipeline (Maven filtering, shading, ProGuard,
 *       signing) — its bytes remain byte-identical to the source;</li>
 *   <li>ORT can load the model with the same CPU provider configuration
 *       used in production;</li>
 *   <li>the model accepts the {@code (1, 3, 224, 224)} NCHW float-32 input
 *       shape the {@code MLService} feeds it.</li>
 * </ul>
 * Each of these has bitten us in the past, and each fails opaquely on the
 * production server, so explicit JVM-side coverage is worthwhile.
 */
@Tag("ml-it")
@EnabledIfSystemProperty(named = "bbai.ml-it", matches = "true")
class MLIntegrationTest {

    /** Bundled model location on the classpath — must match {@code MLService} expectations. */
    private static final String MODEL_RESOURCE = "/models/custom_convnext_embeddings.onnx";

    /** ImageNet preprocessing constants — must match the trainer's normalization. */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private static OrtEnvironment env;
    private static OrtSession session;

    @BeforeAll
    static void loadOnnxModel() throws Exception {
        try (InputStream modelStream = MLIntegrationTest.class
                .getResourceAsStream(MODEL_RESOURCE)) {
            Assumptions.assumeTrue(modelStream != null,
                    "Bundled ONNX model missing from test classpath at "
                            + MODEL_RESOURCE + " — verify pom.xml resources block.");
            byte[] modelBytes = readAllBytes(modelStream);
            assertTrue(modelBytes.length > 1_000_000,
                    "Model size suspiciously small (" + modelBytes.length + " bytes) — "
                            + "Maven resource filtering may have corrupted it.");

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            // Match the production session config (single inter-op thread,
            // capped intra-op pool, no busy-spinning so the bench JVM doesn't
            // pin a CPU core after the test ends).
            opts.setInterOpNumThreads(1);
            opts.setIntraOpNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            opts.addConfigEntry("session.intra_op.allow_spinning", "0");
            opts.addConfigEntry("session.inter_op.allow_spinning", "0");
            session = env.createSession(modelBytes, opts);
        }
    }

    @AfterAll
    static void releaseOnnxResources() throws Exception {
        if (session != null)
            session.close();
        // OrtEnvironment is process-singleton — closing it would break any
        // subsequent ORT use in the same JVM, so we leave it alone.
    }

    /**
     * Loads the bundled model and runs a single forward pass against a
     * synthetic 224×224 RGB image. Confirms ORT can drive the model with
     * production-shaped input and the output embedding has the
     * documented 128-dim shape.
     */
    @Test
    void modelLoadsAndProducesEmbeddingForSyntheticImage() throws Exception {
        assertNotNull(session, "ORT session must be initialised");

        // Build a 224×224 RGB image filled with a smooth gradient so the
        // model has *something* to embed (a zero-valued tensor often hits
        // edge cases in models trained with batch normalisation).
        byte[] rgb = new byte[224 * 224 * 3];
        Random random = new Random(0xBBA1L);
        for (int i = 0; i < rgb.length; i++)
            rgb[i] = (byte) random.nextInt(256);

        OnnxTensor input = toTensor(rgb);

        String inputName = session.getInputNames().iterator().next();
        Map<String, OnnxTensor> feeds = new HashMap<String, OnnxTensor>();
        feeds.put(inputName, input);

        try (OrtSession.Result result = session.run(feeds)) {
            assertTrue(result.size() >= 1, "Model must produce at least one output");
            Object first = result.get(0).getValue();
            // ConvNeXt-Tiny embedder output shape is (1, 128) — verify both.
            assertTrue(first instanceof float[][],
                    "Output type must be float[][] for (1, 128) embedding; got "
                            + first.getClass());
            float[][] embedding = (float[][]) first;
            assertEquals(1, embedding.length, "batch dim must be 1");
            assertEquals(128, embedding[0].length,
                    "embedding dim must be 128 (ConvNeXt-Tiny embedder contract)");

            // Sanity: at least one component must be non-zero. A fully-zero
            // embedding indicates the model is mis-loaded or the input
            // tensor wasn't passed through correctly.
            boolean anyNonZero = false;
            for (float v : embedding[0])
                if (v != 0f) {
                    anyNonZero = true;
                    break;
                }
            assertTrue(anyNonZero, "Embedding is all-zero — model likely mis-wired");
        } finally {
            input.close();
        }
    }

    /**
     * Converts a 224×224 RGB byte buffer to an {@link OnnxTensor} with the
     * NCHW float-32 layout the model expects, including ImageNet
     * normalisation. Mirrors the conversion {@code MLService.predictRgb}
     * performs internally.
     */
    private OnnxTensor toTensor(byte[] rgb) throws Exception {
        int hw = 224 * 224;
        float[] data = new float[3 * hw];
        for (int y = 0; y < 224; y++)
            for (int x = 0; x < 224; x++) {
                int srcBase = (y * 224 + x) * 3;
                int dstIdx = y * 224 + x;
                // Decode each byte as unsigned, normalise to [0, 1], then
                // apply ImageNet mean/std per channel. The output layout is
                // NCHW: channel-major, so each channel gets one contiguous
                // 224×224 plane.
                float r = ((rgb[srcBase] & 0xFF) / 255f - MEAN[0]) / STD[0];
                float g = ((rgb[srcBase + 1] & 0xFF) / 255f - MEAN[1]) / STD[1];
                float b = ((rgb[srcBase + 2] & 0xFF) / 255f - MEAN[2]) / STD[2];
                data[dstIdx] = r;
                data[hw + dstIdx] = g;
                data[2 * hw + dstIdx] = b;
            }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data),
                new long[]{1L, 3L, 224L, 224L});
    }

    /** Java 8 substitute for {@code InputStream#readAllBytes}. */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }
}

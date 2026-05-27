package ru.ashesha.buildBattleAI.ml;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for the ONNX-backed {@link MLService}. A single service
 * instance is brought online in {@link BeforeAll} and reused across tests —
 * model loading is the slow part (~hundreds of ms) so we pay for it once.
 * <p>
 * Tests work in either of two regimes:
 * <ul>
 *     <li>If ONNX Runtime can load the bundled ResNet50 weights, embeddings
 *         are real and the test asserts the model's invariants
 *         (length, L2-normalized, identical inputs → identical embeddings).</li>
 *     <li>If the model file is absent or ORT fails to start, the service
 *         enters DISABLED mode and tests still pass — every public method
 *         remains well-behaved.</li>
 * </ul>
 * Tests that are only meaningful in active mode call
 * {@link #assumeActive()} to skip when DISABLED.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MLServiceTest {

    private static final Logger TEST_LOGGER = Logger.getLogger("MLServiceTest");

    private BuildBattleAI plugin;
    private MLService service;

    @BeforeAll
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(TEST_LOGGER));
        service = new MLService(plugin);
        service.enable();
    }

    @AfterAll
    void tearDown() {
        if (service != null)
            service.shutdown();
    }

    private void assumeActive() {
        if ("DISABLED".equals(service.backend()))
            // JUnit 5 doesn't include Assumptions on the classpath of every
            // runner, so use plain assertTrue to skip gracefully.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "MLService is DISABLED — skipping inference-dependent test");
    }

    // ── lifecycle + metadata ────────────────────────────────────────────────

    @Test
    void embeddingDimensionMatchesResNet50() {
        assertEquals(2048, service.embeddingDim());
    }

    @Test
    void backendIsReportedAsAStableString() {
        String backend = service.backend();
        assertNotNull(backend);
        assertFalse(backend.isEmpty());
        // One of the supported labels — or DISABLED if loading failed.
        assertTrue(backend.endsWith("ExecutionProvider") || "DISABLED".equals(backend),
                "Unexpected backend label: " + backend);
    }

    @Test
    void classNamesContainsTenDefaultEntries() {
        List<String> names = service.classNames();
        assertEquals(10, names.size());
        assertTrue(names.contains("cat"));
        assertTrue(names.contains("house"));
        assertTrue(names.contains("tree"));
        assertTrue(names.contains("glasses"));
    }

    @Test
    void classNamesIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> service.classNames().add("extra"));
    }

    @Test
    void centroidsAreUnitVectorsAlignedWithClassNames() {
        Map<String, float[]> centroids = service.centroids();
        assertEquals(service.classNames().size(), centroids.size());
        for (String name : service.classNames())
            assertNotNull(centroids.get(name), "Missing centroid for class " + name);
        for (float[] v : centroids.values()) {
            assertEquals(2048, v.length);
            float norm = norm(v);
            assertEquals(1.0f, norm, 1e-4f, "Centroid not L2-normalized (norm=" + norm + ")");
        }
    }

    @Test
    void centroidsAreReproducibleAcrossEnableCycles() {
        // Take a snapshot, hot-reload the service, compare.
        float[] before = service.centroids().get("cat").clone();
        service.shutdown();
        service.enable();
        float[] after = service.centroids().get("cat");
        assertArrayEquals(before, after, 1e-7f,
                "Centroids should be deterministic across enable cycles");
    }

    // ── embedding tests (require working model) ─────────────────────────────

    @Test
    void embedBufferedImageProducesNormalizedVector() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.MAGENTA);
        float[] v = service.embed(img);
        assertEquals(2048, v.length);
        assertEquals(1.0f, norm(v), 1e-3f);
    }

    @Test
    void embedEncodedImageProducesNormalizedVector() throws Exception {
        assumeActive();
        BufferedImage img = solidColor(80, 60, Color.CYAN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        float[] v = service.embed(out.toByteArray());
        assertEquals(2048, v.length);
        assertEquals(1.0f, norm(v), 1e-3f);
    }

    @Test
    void embedRgbExact224ProducesNormalizedVector() {
        assumeActive();
        byte[] rgb = solidRgb(224, 224, 100, 150, 200);
        float[] v = service.embedRgb(rgb, 224, 224);
        assertEquals(2048, v.length);
        assertEquals(1.0f, norm(v), 1e-3f);
    }

    @Test
    void embedRgbWithResizeProducesNormalizedVector() {
        assumeActive();
        byte[] rgb = solidRgb(64, 64, 200, 50, 50);
        float[] v = service.embedRgb(rgb, 64, 64);
        assertEquals(2048, v.length);
        assertEquals(1.0f, norm(v), 1e-3f);
    }

    @Test
    void identicalInputsProduceIdenticalEmbeddings() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.GREEN);
        float[] a = service.embed(img);
        float[] b = service.embed(img);
        assertArrayEquals(a, b, 1e-5f);
    }

    @Test
    void differentInputsProduceDifferentEmbeddings() {
        assumeActive();
        float[] white = service.embed(solidColor(224, 224, Color.WHITE));
        float[] black = service.embed(solidColor(224, 224, Color.BLACK));
        // Cosine similarity should be < 1 — the two patches are clearly different.
        float dot = 0f;
        for (int i = 0; i < white.length; i++)
            dot += white[i] * black[i];
        assertTrue(dot < 0.999f, "Embeddings of white vs black should not be identical (dot=" + dot + ")");
    }

    @Test
    void embedRgbRejectsMismatchedBufferLength() {
        // Even in DISABLED mode the dimension check fires before inference.
        byte[] bad = new byte[100];
        assertThrows(IllegalArgumentException.class,
                () -> service.embedRgb(bad, 224, 224));
    }

    // ── batch embedding tests ───────────────────────────────────────────────

    @Test
    void embedBatchBufferedImagesMatchesSingleCalls() {
        assumeActive();
        BufferedImage a = solidColor(224, 224, Color.RED);
        BufferedImage b = solidColor(224, 224, Color.BLUE);
        float[][] batch = service.embedBatch(new BufferedImage[]{a, b});
        assertEquals(2, batch.length);
        assertArrayEquals(service.embed(a), batch[0], 1e-4f);
        assertArrayEquals(service.embed(b), batch[1], 1e-4f);
    }

    @Test
    void embedBatchHandlesEmptyInput() {
        float[][] batch = service.embedBatch(new BufferedImage[0]);
        assertEquals(0, batch.length);
    }

    @Test
    void embedBatchRgbProducesNormalizedRows() {
        assumeActive();
        byte[] r = solidRgb(224, 224, 255, 0, 0);
        byte[] g = solidRgb(224, 224, 0, 255, 0);
        float[][] batch = service.embedBatchRgb(new byte[][]{r, g}, 224, 224);
        assertEquals(2, batch.length);
        for (float[] row : batch) {
            assertEquals(2048, row.length);
            assertEquals(1.0f, norm(row), 1e-3f);
        }
    }

    // ── prediction tests ────────────────────────────────────────────────────

    @Test
    void predictReturnsRequestedTopK() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.ORANGE);
        PredictionResult result = service.predict(img, 3);
        assertEquals(3, result.topK().size());
        assertNotNull(result.predictedClass());
        assertEquals(result.topK().get(0).className(), result.predictedClass());
        assertEquals(result.topK().get(0).score(), result.predictedScore(), 1e-6f);
        assertEquals(2048, result.embedding().length);
        assertEquals(2048, result.predictedCentroid().length);
    }

    @Test
    void predictTopKIsRankedDescending() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.YELLOW);
        PredictionResult result = service.predict(img, 5);
        for (int i = 1; i < result.topK().size(); i++)
            assertTrue(result.topK().get(i - 1).score() >= result.topK().get(i).score(),
                    "Top-K ordering broken at index " + i);
    }

    @Test
    void predictClampsTopKToClassCount() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.WHITE);
        PredictionResult result = service.predict(img, 999);
        assertEquals(service.classNames().size(), result.topK().size());
    }

    @Test
    void predictClampsTopKToAtLeastOne() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.PINK);
        PredictionResult result = service.predict(img, 0);
        assertEquals(1, result.topK().size());
    }

    @Test
    void predictRgbMatchesPredictOnSameImage() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.LIGHT_GRAY);
        byte[] rgb = bufferedImageToRgb(img);
        PredictionResult fromImage = service.predict(img, 5);
        PredictionResult fromRgb = service.predictRgb(rgb, 224, 224, 5);
        assertEquals(fromImage.predictedClass(), fromRgb.predictedClass());
        assertArrayEquals(fromImage.embedding(), fromRgb.embedding(), 1e-4f);
    }

    @Test
    void predictBatchProducesOneResultPerImage() {
        assumeActive();
        BufferedImage a = solidColor(224, 224, Color.RED);
        BufferedImage b = solidColor(224, 224, Color.GREEN);
        BufferedImage c = solidColor(224, 224, Color.BLUE);
        PredictionResult[] batch = service.predictBatch(new BufferedImage[]{a, b, c}, 2);
        assertEquals(3, batch.length);
        for (PredictionResult r : batch) {
            assertNotNull(r);
            assertEquals(2, r.topK().size());
        }
    }

    // ── embedding numerical invariants ──────────────────────────────────────

    @Test
    void embeddingValuesAreFiniteAndBounded() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.MAGENTA);
        float[] v = service.embed(img);
        for (int i = 0; i < v.length; i++) {
            float x = v[i];
            assertTrue(!Float.isNaN(x), "Embedding contains NaN at index " + i);
            assertTrue(!Float.isInfinite(x), "Embedding contains Infinity at index " + i);
            // Components of an L2-normalized vector must each lie in [-1, 1].
            assertTrue(x >= -1.0f - 1e-3f && x <= 1.0f + 1e-3f,
                    "Embedding component out of range at " + i + ": " + x);
        }
    }

    @Test
    void embeddingIsNotAllZeros() {
        assumeActive();
        // Even a flat image should produce a non-zero embedding from the
        // pre-trained ResNet50 — its receptive field always sees something.
        float[] v = service.embed(solidColor(224, 224, Color.GRAY));
        float maxAbs = 0;
        for (float x : v)
            maxAbs = Math.max(maxAbs, Math.abs(x));
        assertTrue(maxAbs > 0.0f, "Embedding is degenerate (all zeros)");
    }

    @Test
    void embedFromBufferedImageEqualsEmbedFromEncodedPng() throws Exception {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.MAGENTA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        float[] fromImage = service.embed(img);
        float[] fromEncoded = service.embed(out.toByteArray());
        // PNG round-trip is lossless; embeddings must be virtually identical.
        assertArrayEquals(fromImage, fromEncoded, 1e-4f);
    }

    // ── batch parity & sizing ───────────────────────────────────────────────

    @Test
    void embedBatchSingletonMatchesSingleEmbed() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.RED);
        float[] single = service.embed(img);
        float[][] batch = service.embedBatch(new BufferedImage[]{img});
        assertEquals(1, batch.length);
        assertArrayEquals(single, batch[0], 1e-4f);
    }

    @Test
    void embedBatchPreservesOrder() {
        assumeActive();
        BufferedImage red = solidColor(224, 224, Color.RED);
        BufferedImage blue = solidColor(224, 224, Color.BLUE);
        BufferedImage green = solidColor(224, 224, Color.GREEN);
        float[] r = service.embed(red);
        float[] g = service.embed(green);
        float[] b = service.embed(blue);
        float[][] batch = service.embedBatch(new BufferedImage[]{red, blue, green});
        // Position 0 must match red, 1 must match blue, 2 must match green.
        assertArrayEquals(r, batch[0], 1e-4f);
        assertArrayEquals(b, batch[1], 1e-4f);
        assertArrayEquals(g, batch[2], 1e-4f);
    }

    @Test
    void embedBatchEncodedMatchesSingleEmbedEncoded() throws Exception {
        assumeActive();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(solidColor(224, 224, Color.RED), "PNG", out);
        byte[] encoded = out.toByteArray();
        float[] single = service.embed(encoded);
        float[][] batch = service.embedBatch(new byte[][]{encoded, encoded});
        assertEquals(2, batch.length);
        assertArrayEquals(single, batch[0], 1e-4f);
        assertArrayEquals(single, batch[1], 1e-4f);
    }

    @Test
    void embedBatchEncodedEmptyArrayReturnsEmpty() {
        float[][] batch = service.embedBatch(new byte[0][]);
        assertEquals(0, batch.length);
    }

    @Test
    void embedBatchRgbEmptyArrayReturnsEmpty() {
        float[][] batch = service.embedBatchRgb(new byte[0][], 224, 224);
        assertEquals(0, batch.length);
    }

    @Test
    void embedBatchRgbSingletonMatchesSingleEmbedRgb() {
        assumeActive();
        byte[] rgb = solidRgb(224, 224, 90, 90, 90);
        float[] single = service.embedRgb(rgb, 224, 224);
        float[][] batch = service.embedBatchRgb(new byte[][]{rgb}, 224, 224);
        assertEquals(1, batch.length);
        assertArrayEquals(single, batch[0], 1e-4f);
    }

    @Test
    void embedBatchRgbWithMixedRowsKeepsRowsIndependent() {
        assumeActive();
        byte[] white = solidRgb(224, 224, 255, 255, 255);
        byte[] black = solidRgb(224, 224, 0, 0, 0);
        float[][] batch = service.embedBatchRgb(new byte[][]{white, black, white}, 224, 224);
        assertArrayEquals(batch[0], batch[2], 1e-4f);
        // White != black ⇒ embeddings must differ.
        float dot = 0f;
        for (int i = 0; i < batch[0].length; i++)
            dot += batch[0][i] * batch[1][i];
        assertTrue(dot < 0.999f, "White vs black batch rows should not be identical");
    }

    @Test
    void embedRgbRejectsAnyWrongLength() {
        // Multiple wrong sizes — guards against the dimension check
        // accidentally only firing for a single special case.
        assertThrows(IllegalArgumentException.class,
                () -> service.embedRgb(new byte[223 * 224 * 3], 224, 224));
        assertThrows(IllegalArgumentException.class,
                () -> service.embedRgb(new byte[224 * 224 * 4], 224, 224));
        assertThrows(IllegalArgumentException.class,
                () -> service.embedRgb(new byte[0], 224, 224));
    }

    @Test
    void embedEncodedRejectsCorruptBytes() {
        // Random bytes are not a decodable image format.
        byte[] junk = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
        assertThrows(RuntimeException.class, () -> service.embed(junk));
    }

    // ── centroid table invariants ───────────────────────────────────────────

    @Test
    void centroidsAreAllDistinct() {
        // Random Gaussian draws guarantee uniqueness with overwhelming
        // probability — any duplicate would indicate an init bug.
        Map<String, float[]> centroids = service.centroids();
        List<String> names = new java.util.ArrayList<>(centroids.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                float[] a = centroids.get(names.get(i));
                float[] b = centroids.get(names.get(j));
                assertFalse(java.util.Arrays.equals(a, b),
                        "Duplicate centroid for " + names.get(i) + " vs " + names.get(j));
            }
        }
    }

    @Test
    void centroidsPairwiseCosineSimilaritiesAreBounded() {
        Map<String, float[]> centroids = service.centroids();
        for (float[] a : centroids.values())
            for (float[] b : centroids.values()) {
                float dot = 0f;
                for (int i = 0; i < a.length; i++)
                    dot += a[i] * b[i];
                // Numerical noise tolerance — strict bound is [-1, 1].
                assertTrue(dot >= -1.0f - 1e-3f && dot <= 1.0f + 1e-3f,
                        "Cosine similarity out of range: " + dot);
            }
    }

    @Test
    void centroidsViewIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.centroids().put("nope", new float[2048]));
    }

    @Test
    void centroidsAreInDeclaredClassOrder() {
        // The map view must preserve the order declared in classNames().
        List<String> names = service.classNames();
        List<String> mapOrder = new java.util.ArrayList<>(service.centroids().keySet());
        assertEquals(names, mapOrder);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Test
    void backendIsDisabledAfterShutdown() {
        // Snapshot what's running so we can put it back at the end.
        String activeBackend = service.backend();
        service.shutdown();
        assertEquals("DISABLED", service.backend());
        assertEquals(0, service.classNames().size());
        assertTrue(service.centroids().isEmpty());
        // Restore service so subsequent @TestInstance tests still pass.
        service.enable();
        assertEquals(activeBackend, service.backend(),
                "Backend should be redetected identically after re-enable");
    }

    @Test
    void shutdownIsIdempotent() {
        service.shutdown();
        // Calling shutdown twice must not throw.
        service.shutdown();
        assertEquals("DISABLED", service.backend());
        service.enable();
    }

    // ── prediction extras ───────────────────────────────────────────────────

    @Test
    void predictEncodedReturnsValidResult() throws Exception {
        assumeActive();
        BufferedImage img = solidColor(80, 60, Color.CYAN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        PredictionResult result = service.predict(out.toByteArray(), 4);
        assertEquals(4, result.topK().size());
        assertNotNull(result.predictedClass());
        assertEquals(2048, result.embedding().length);
    }

    @Test
    void predictTopKContainsOnlyKnownClasses() {
        assumeActive();
        PredictionResult result = service.predict(solidColor(224, 224, Color.RED), 10);
        for (TopKEntry entry : result.topK())
            assertTrue(service.classNames().contains(entry.className()),
                    "Top-K entry " + entry.className() + " not in declared classes");
    }

    @Test
    void predictTopKHasNoDuplicateClasses() {
        assumeActive();
        PredictionResult result = service.predict(solidColor(224, 224, Color.YELLOW), 10);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TopKEntry entry : result.topK())
            assertTrue(seen.add(entry.className()),
                    "Duplicate top-K class: " + entry.className());
    }

    @Test
    void predictScoresAreBoundedCosineRange() {
        assumeActive();
        PredictionResult result = service.predict(solidColor(224, 224, Color.RED), 10);
        for (TopKEntry entry : result.topK()) {
            float s = entry.score();
            assertTrue(!Float.isNaN(s) && !Float.isInfinite(s),
                    "Score is not finite: " + s);
            assertTrue(s >= -1.0f - 1e-3f && s <= 1.0f + 1e-3f,
                    "Score out of cosine range: " + s);
        }
    }

    @Test
    void predictClampsNegativeTopKToOne() {
        assumeActive();
        PredictionResult result = service.predict(solidColor(224, 224, Color.WHITE), -5);
        assertEquals(1, result.topK().size());
    }

    @Test
    void predictBatchEncodedProducesOneResultPerImage() throws Exception {
        assumeActive();
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ImageIO.write(solidColor(224, 224, Color.RED), "PNG", out1);
        ImageIO.write(solidColor(224, 224, Color.BLUE), "PNG", out2);
        PredictionResult[] batch = service.predictBatch(
                new byte[][]{out1.toByteArray(), out2.toByteArray()}, 3);
        assertEquals(2, batch.length);
        assertEquals(3, batch[0].topK().size());
        assertEquals(3, batch[1].topK().size());
        // Encoded path must reach the same answer as the BufferedImage path.
        PredictionResult fromImage = service.predict(solidColor(224, 224, Color.RED), 3);
        assertEquals(fromImage.predictedClass(), batch[0].predictedClass());
    }

    @Test
    void predictBatchRgbProducesOneResultPerImage() {
        assumeActive();
        byte[] r = solidRgb(224, 224, 255, 0, 0);
        byte[] g = solidRgb(224, 224, 0, 255, 0);
        byte[] b = solidRgb(224, 224, 0, 0, 255);
        PredictionResult[] batch = service.predictBatchRgb(new byte[][]{r, g, b}, 224, 224, 2);
        assertEquals(3, batch.length);
        for (PredictionResult result : batch) {
            assertEquals(2, result.topK().size());
            assertNotNull(result.predictedClass());
        }
    }

    @Test
    void predictBatchSingletonMatchesSinglePredict() {
        assumeActive();
        BufferedImage img = solidColor(224, 224, Color.ORANGE);
        PredictionResult single = service.predict(img, 3);
        PredictionResult[] batch = service.predictBatch(new BufferedImage[]{img}, 3);
        assertEquals(1, batch.length);
        assertEquals(single.predictedClass(), batch[0].predictedClass());
        assertEquals(single.predictedScore(), batch[0].predictedScore(), 1e-4f);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static BufferedImage solidColor(int width, int height, Color color) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static byte[] solidRgb(int width, int height, int r, int g, int b) {
        byte[] out = new byte[width * height * 3];
        for (int i = 0; i < width * height; i++) {
            out[i * 3] = (byte) r;
            out[i * 3 + 1] = (byte) g;
            out[i * 3 + 2] = (byte) b;
        }
        return out;
    }

    private static byte[] bufferedImageToRgb(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        byte[] out = new byte[w * h * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[i * 3] = (byte) ((p >> 16) & 0xFF);
            out[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);
            out[i * 3 + 2] = (byte) (p & 0xFF);
        }
        return out;
    }

    private static float norm(float[] v) {
        double s = 0;
        for (float x : v)
            s += (double) x * x;
        return (float) Math.sqrt(s);
    }
}

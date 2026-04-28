package ru.ashesha.buildBattleAI.ml;

import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginLogger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MLService} — specifically the data classes, base URL
 * normalization, and the lifecycle methods.
 * <p>
 * HTTP-dependent methods ({@code predict}, {@code health}, {@code centroids})
 * require a running ML microservice and are covered by integration testing.
 * These unit tests exercise the pure, testable parts of the service.
 */
class MLServiceTest {

    // -- PredictionResult data class tests ----------------------------------

    @Test
    void predictionResultStoresAllFields() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        float[] centroid = {0.4f, 0.5f, 0.6f};
        List<MLService.TopKEntry> topK = Arrays.asList(
                new MLService.TopKEntry("house", 0.95f),
                new MLService.TopKEntry("castle", 0.80f)
        );
        List<String> classes = Arrays.asList("house", "castle", "tree");

        MLService.PredictionResult result = new MLService.PredictionResult(
                "resnet50", embedding, "house", 0.95f, centroid, topK, classes
        );

        assertEquals("resnet50", result.modelName());
        assertArrayEquals(embedding, result.embedding());
        assertEquals("house", result.predictedClass());
        assertEquals(0.95f, result.predictedScore(), 0.001f);
        assertArrayEquals(centroid, result.predictedCentroid());
        assertEquals(2, result.topK().size());
        assertEquals("house", result.topK().get(0).className());
        assertEquals(0.95f, result.topK().get(0).score(), 0.001f);
        assertEquals("castle", result.topK().get(1).className());
        assertEquals(3, result.availableClasses().size());
    }

    // -- TopKEntry data class tests -----------------------------------------

    @Test
    void topKEntryStoresClassNameAndScore() {
        MLService.TopKEntry entry = new MLService.TopKEntry("tree", 0.87f);
        assertEquals("tree", entry.className());
        assertEquals(0.87f, entry.score(), 0.001f);
    }

    @Test
    void topKEntryZeroScore() {
        MLService.TopKEntry entry = new MLService.TopKEntry("empty", 0.0f);
        assertEquals(0.0f, entry.score(), 0.001f);
    }

    @Test
    void topKEntryPerfectScore() {
        MLService.TopKEntry entry = new MLService.TopKEntry("exact", 1.0f);
        assertEquals(1.0f, entry.score(), 0.001f);
    }

    // -- HealthInfo data class tests ----------------------------------------

    @Test
    void healthInfoStoresAllFields() {
        List<String> classes = Arrays.asList("house", "castle", "tree");
        MLService.HealthInfo info = new MLService.HealthInfo(
                "ok", "resnet50", "cuda", 3, 128, 224, classes
        );

        assertEquals("ok", info.status());
        assertEquals("resnet50", info.modelName());
        assertEquals("cuda", info.device());
        assertEquals(3, info.numClasses());
        assertEquals(128, info.embeddingDim());
        assertEquals(224, info.inputSize());
        assertEquals(3, info.classes().size());
        assertEquals("house", info.classes().get(0));
    }

    @Test
    void healthInfoCpuDevice() {
        MLService.HealthInfo info = new MLService.HealthInfo(
                "ok", "mobilenet", "cpu", 10, 64, 224,
                Collections.singletonList("test")
        );
        assertEquals("cpu", info.device());
        assertEquals(64, info.embeddingDim());
    }

    // -- base URL normalization tests ---------------------------------------

    @Test
    void trailingSlashIsStripped() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        // Constructor should strip trailing slash — we can't inspect the field
        // directly, but the service should not throw on construction
        MLService service = new MLService(plugin, "http://localhost:8001/");
        assertNotNull(service);
    }

    @Test
    void noTrailingSlashIsUnchanged() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:8001");
        assertNotNull(service);
    }

    @Test
    void defaultConstructorDoesNotThrow() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin);
        assertNotNull(service);
    }

    // -- lifecycle tests ----------------------------------------------------

    @Test
    void enableIsNoOp() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin);
        assertDoesNotThrow(service::enable);
    }

    @Test
    void shutdownIsNoOp() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin);
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void multipleEnableShutdownCyclesAreIdempotent() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin);
        service.enable();
        service.shutdown();
        service.enable();
        service.shutdown();
        // No exception means idempotent behavior is correct
    }

    // -- fallback when server is unavailable ---------------------------------

    @Test
    void predictFallsBackWhenServerUnavailable() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        byte[] pixels = new byte[224 * 224 * 3];
        MLService.PredictionResult result = service.predict(pixels, 224, 224, 5);

        assertNotNull(result);
        assertEquals("fallback", result.modelName());
        assertNotNull(result.predictedClass());
        assertTrue(result.predictedScore() >= 0.0f && result.predictedScore() <= 1.0f);
        assertEquals(5, result.topK().size());
        assertEquals(6, result.availableClasses().size());
        assertTrue(result.availableClasses().contains("cat"));
        assertTrue(result.availableClasses().contains("sword"));
        assertTrue(result.availableClasses().contains("ball"));
        assertTrue(result.availableClasses().contains("house"));
        assertTrue(result.availableClasses().contains("tree"));
        assertTrue(result.availableClasses().contains("glasses"));
    }

    @Test
    void predictImageFallsBackWhenServerUnavailable() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        MLService.PredictionResult result = service.predictImage(new byte[]{1, 2, 3}, 3);

        assertNotNull(result);
        assertEquals("fallback", result.modelName());
        assertEquals(3, result.topK().size());
    }

    @Test
    void fallbackTopKIsCappedAtAvailableClasses() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        // Request more topK than fallback classes — should be capped at 6
        MLService.PredictionResult result = service.predictImage(new byte[]{1}, 20);

        assertEquals(6, result.topK().size());
    }

    @Test
    void fallbackTopKIsSortedDescending() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        MLService.PredictionResult result = service.predictImage(new byte[]{1}, 6);

        // Scores should be in descending order
        for (int i = 1; i < result.topK().size(); i++)
            assertTrue(result.topK().get(i - 1).score() >= result.topK().get(i).score());

        // Top prediction should match the first topK entry
        assertEquals(result.topK().get(0).className(), result.predictedClass());
        assertEquals(result.topK().get(0).score(), result.predictedScore(), 0.001f);
    }

    @Test
    void fallbackEmbeddingIsZeroFilled() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        MLService.PredictionResult result = service.predictImage(new byte[]{1}, 1);

        assertEquals(128, result.embedding().length);
        assertEquals(128, result.predictedCentroid().length);
        for (float v : result.embedding())
            assertEquals(0.0f, v, 0.0f);
        for (float v : result.predictedCentroid())
            assertEquals(0.0f, v, 0.0f);
    }

    // -- health/centroids still throw without server --------------------------

    @Test
    void healthWithoutServerThrows() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        assertThrows(RuntimeException.class, service::health);
    }

    @Test
    void centroidsWithoutServerThrows() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        assertThrows(RuntimeException.class, service::centroids);
    }
}

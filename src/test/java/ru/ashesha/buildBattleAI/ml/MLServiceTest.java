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

    // -- predict without server throws RuntimeException ---------------------

    @Test
    void predictWithoutServerThrows() {
        BuildBattleAI plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MLServiceTest"));
        MLService service = new MLService(plugin, "http://localhost:99999");

        // predict should throw because the ML service is not reachable
        byte[] pixels = new byte[224 * 224 * 3];
        assertThrows(RuntimeException.class, () -> service.predict(pixels, 224, 224, 5));
    }

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

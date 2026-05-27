package ru.ashesha.buildBattleAI.ml.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link PredictionResult} value object.
 * <p>
 * Verifies the Lombok-generated accessors expose every field in declaration
 * order and that the DTO does not defensively copy — the contract is that the
 * service hands out arrays the caller is free to inspect (and that, by
 * extension, can race if mutated, so we lock that behavior in here).
 */
class PredictionResultTest {

    private static final float[] EMBEDDING = {0.1f, 0.2f, 0.3f, 0.4f};
    private static final float[] CENTROID = {0.5f, 0.5f, 0.5f, 0.5f};

    @Test
    void allFieldsRoundTripThroughAccessors() {
        // Bind every field via the canonical constructor and read it back.
        List<TopKEntry> top = Arrays.asList(
                new TopKEntry("a", 0.9f),
                new TopKEntry("b", 0.8f));
        PredictionResult r = new PredictionResult(EMBEDDING, "a", 0.9f, CENTROID, top);
        assertArrayEquals(EMBEDDING, r.embedding(), 0f);
        assertEquals("a", r.predictedClass());
        assertEquals(0.9f, r.predictedScore(), 0f);
        assertArrayEquals(CENTROID, r.predictedCentroid(), 0f);
        assertEquals(top, r.topK());
    }

    @Test
    void storesArrayReferenceWithoutDefensiveCopy() {
        // Documented contract: the service is responsible for not aliasing its
        // internal arrays, but the DTO itself does not clone. Future changes
        // that introduce a defensive copy would surprise callers, so freeze it.
        float[] emb = {1.0f, 2.0f};
        float[] cen = {3.0f, 4.0f};
        PredictionResult r = new PredictionResult(emb, "x", 0f, cen, Collections.<TopKEntry>emptyList());
        assertSame(emb, r.embedding(), "Embedding must not be defensively copied");
        assertSame(cen, r.predictedCentroid(), "Centroid must not be defensively copied");
    }

    @Test
    void storesTopKListReferenceWithoutCopy() {
        // Same reasoning as the array fields — the service builds the list
        // with Collections.unmodifiableList so passing the live reference is safe.
        List<TopKEntry> live = Collections.singletonList(new TopKEntry("c", 0.7f));
        PredictionResult r = new PredictionResult(EMBEDDING, "c", 0.7f, CENTROID, live);
        assertSame(live, r.topK());
    }

    @Test
    void emptyTopKIsAllowed() {
        // The DTO doesn't enforce a non-empty ranking — useful for fallback
        // paths that don't have any candidate to surface.
        PredictionResult r = new PredictionResult(EMBEDDING, "none", 0f, CENTROID,
                Collections.<TopKEntry>emptyList());
        assertTrue(r.topK().isEmpty());
    }

    @Test
    void allowsAllNullValuesWithoutCrashing() {
        // Permissive constructor — none of the parameters are @NonNull, so
        // null is a valid state that simply round-trips back through accessors.
        PredictionResult r = new PredictionResult(null, null, 0f, null, null);
        assertNull(r.embedding());
        assertNull(r.predictedClass());
        assertNull(r.predictedCentroid());
        assertNull(r.topK());
    }

    @Test
    void predictedScorePreservesSpecialFloats() {
        // NaN and ±Infinity must propagate through unchanged.
        PredictionResult nan = new PredictionResult(EMBEDDING, "x", Float.NaN, CENTROID,
                Collections.<TopKEntry>emptyList());
        PredictionResult inf = new PredictionResult(EMBEDDING, "x", Float.POSITIVE_INFINITY, CENTROID,
                Collections.<TopKEntry>emptyList());
        assertTrue(Float.isNaN(nan.predictedScore()));
        assertTrue(Float.isInfinite(inf.predictedScore()));
    }

    @Test
    void distinctInstancesAreIndependent() {
        // Mutating the local array reference does change what the DTO returns
        // (no defensive copy), but two separate DTOs must not share state.
        PredictionResult a = new PredictionResult(new float[]{1f}, "a", 0.1f,
                new float[]{2f}, Collections.<TopKEntry>emptyList());
        PredictionResult b = new PredictionResult(new float[]{3f}, "b", 0.2f,
                new float[]{4f}, Collections.<TopKEntry>emptyList());
        assertNotSame(a.embedding(), b.embedding());
        assertNotSame(a.predictedCentroid(), b.predictedCentroid());
        assertNotEquals(a.predictedClass(), b.predictedClass());
        assertNotEquals(a.predictedScore(), b.predictedScore());
    }
}

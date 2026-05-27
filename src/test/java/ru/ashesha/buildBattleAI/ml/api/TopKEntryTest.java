package ru.ashesha.buildBattleAI.ml.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link TopKEntry} data holder.
 * <p>
 * The class is deliberately a minimal value carrier — the tests pin down its
 * accessor contract and make sure the Lombok-generated constructor binds
 * fields in the correct declared order.
 */
class TopKEntryTest {

    @Test
    void constructorBindsFieldsInDeclarationOrder() {
        // If the Lombok @RequiredArgsConstructor ever drifted from the field
        // order in the source file, this test would flip className/score.
        TopKEntry entry = new TopKEntry("cat", 0.42f);
        assertEquals("cat", entry.className());
        assertEquals(0.42f, entry.score(), 1e-9f);
    }

    @Test
    void scoreIsExposedExactlyAsConstructed() {
        // No coercion — TopKEntry must not clamp or round the raw cosine score.
        TopKEntry positive = new TopKEntry("a", 1.0f);
        TopKEntry negative = new TopKEntry("b", -1.0f);
        TopKEntry zero = new TopKEntry("c", 0.0f);
        assertEquals(1.0f, positive.score());
        assertEquals(-1.0f, negative.score());
        assertEquals(0.0f, zero.score());
    }

    @Test
    void classNameAcceptsAnyStringIncludingEmpty() {
        // Empty / unusual strings shouldn't be rejected — the DTO is dumb on purpose.
        assertEquals("", new TopKEntry("", 0f).className());
        assertEquals("with spaces", new TopKEntry("with spaces", 0f).className());
        assertEquals("Σ", new TopKEntry("Σ", 0f).className());
    }

    @Test
    void allowsNullClassNameWithoutCrashing() {
        // No explicit non-null contract — the DTO is permissive so callers
        // can use it for fallbacks without special-casing.
        TopKEntry e = new TopKEntry(null, 0.5f);
        assertNull(e.className());
        assertEquals(0.5f, e.score(), 1e-9f);
    }

    @Test
    void preservesExtremeAndSubnormalFloats() {
        // Infinity / NaN are not valid cosine scores but the DTO must propagate
        // them verbatim so callers see the bug and can fail loudly.
        assertTrue(Float.isNaN(new TopKEntry("x", Float.NaN).score()));
        assertTrue(Float.isInfinite(new TopKEntry("x", Float.POSITIVE_INFINITY).score()));
        assertTrue(Float.isInfinite(new TopKEntry("x", Float.NEGATIVE_INFINITY).score()));
        assertEquals(Float.MIN_VALUE, new TopKEntry("x", Float.MIN_VALUE).score());
    }

    @Test
    void distinctInstancesAreIndependent() {
        // Sanity: mutating one field-by-construction must not leak into another instance.
        TopKEntry a = new TopKEntry("a", 0.1f);
        TopKEntry b = new TopKEntry("b", 0.2f);
        assertNotEquals(a.className(), b.className());
        assertNotEquals(a.score(), b.score());
    }
}

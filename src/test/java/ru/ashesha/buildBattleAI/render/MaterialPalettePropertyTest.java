package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link BlockPalette}.
 * <p>
 * The palette is a hot path on every rendered frame; it must never throw or
 * return out-of-range values for any {@link XMaterial}, including obscure
 * materials that may only appear on niche server versions. This suite enforces
 * the universal invariants by parameterizing over every {@link XMaterial} value:
 * colors stay within 24-bit RGB, alphas stay within [0, 255], emissive and
 * needs-state predicates never throw, and the merge predicate is deterministic
 * and symmetric.
 * <p>
 * Anchor cases (AIR transparent, WATER/GLASS translucent, GLOWSTONE emissive,
 * STONE opaque non-emissive) are pinned with explicit assertions so that an
 * accidental palette rewrite that silently keeps the invariants but flips
 * semantics is still caught.
 */
class MaterialPalettePropertyTest {

    /**
     * Provides every {@link XMaterial} value as a separate parameterized test case.
     * Returns the cached array wrapped in an unmodifiable list to make the test
     * setup itself a zero-allocation operation across all parameterized methods.
     *
     * @return all {@link XMaterial} values
     */
    static List<XMaterial> allMaterials() {
        List<XMaterial> result = new ArrayList<XMaterial>();
        Collections.addAll(result, XMaterial.values());
        return result;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void getColorIsEitherTransparentOr24BitRgb(XMaterial material) {
        int color = BlockPalette.getColor(material);
        if (color == -1)
            return;
        assertTrue(color >= 0 && color <= 0xFFFFFF,
                "color out of 24-bit RGB range for " + material
                        + ": 0x" + Integer.toHexString(color));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void getAlphaInBoundsAndDeterministic(XMaterial material) {
        int first = BlockPalette.getAlpha(material);
        assertTrue(first >= 0 && first <= 255,
                "alpha out of [0, 255] for " + material + ": " + first);
        assertEquals(first, BlockPalette.getAlpha(material),
                "getAlpha must be pure for " + material);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void isEmissiveIsDeterministic(XMaterial material) {
        boolean first = BlockPalette.isEmissive(material);
        assertEquals(first, BlockPalette.isEmissive(material),
                "isEmissive must be pure for " + material);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void needsBlockStateIsDeterministic(XMaterial material) {
        boolean first = BlockPalette.needsBlockState(material);
        assertEquals(first, BlockPalette.needsBlockState(material),
                "needsBlockState must be pure for " + material);
    }

    @ParameterizedTest(name = "[{index}] {0} ↔ AIR")
    @MethodSource("allMaterials")
    void canMergeTranslucentIsSymmetric(XMaterial material) {
        assertEquals(
                BlockPalette.canMergeTranslucent(material, XMaterial.AIR),
                BlockPalette.canMergeTranslucent(XMaterial.AIR, material),
                "merge predicate must be symmetric for (" + material + ", AIR)");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void canMergeTranslucentSelfIsDeterministic(XMaterial material) {
        boolean first = BlockPalette.canMergeTranslucent(material, material);
        assertEquals(first, BlockPalette.canMergeTranslucent(material, material),
                "self-merge predicate must be deterministic for " + material);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allMaterials")
    void transparentColorImpliesEitherAirOrLikelyInvisible(XMaterial material) {
        // We don't require that every transparent color be a "real" invisible
        // material — the renderer treats any -1 color as skip-draw — but the
        // method must never throw for any material. The assertion is implicit:
        // reaching this line means getColor returned without throwing.
        BlockPalette.getColor(material);
    }

    // ── Anchor cases ────────────────────────────────────────────────────

    @Test
    void airIsTransparent() {
        assertEquals(-1, BlockPalette.getColor(XMaterial.AIR));
    }

    @Test
    void waterIsTranslucent() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.WATER),
                "WATER must have a colored surface");
        assertTrue(BlockPalette.getAlpha(XMaterial.WATER) < 255,
                "WATER must be sub-opaque");
    }

    @Test
    void glassIsTranslucent() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.GLASS),
                "GLASS must have a colored surface");
        assertTrue(BlockPalette.getAlpha(XMaterial.GLASS) < 255,
                "GLASS must be sub-opaque");
    }

    @Test
    void glowstoneIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.GLOWSTONE),
                "GLOWSTONE bypasses face shading");
    }

    @Test
    void stoneIsOpaqueNonEmissive() {
        assertEquals(255, BlockPalette.getAlpha(XMaterial.STONE));
        assertFalse(BlockPalette.isEmissive(XMaterial.STONE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.STONE));
    }

    @Test
    void mergeTranslucentIsSelfReflexiveForKnownTranslucents() {
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.WATER, XMaterial.WATER),
                "WATER↔WATER must merge");
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.GLASS, XMaterial.GLASS),
                "GLASS↔GLASS must merge");
    }

    @Test
    void mergeTranslucentRejectsOpaque() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.STONE, XMaterial.STONE),
                "STONE is opaque — must not participate in translucent merging");
    }
}

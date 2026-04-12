package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SceneData} interface default methods.
 * <p>
 * Uses a minimal anonymous implementation to verify that default
 * methods return sensible values when not overridden.
 */
class SceneDataTest {

    /**
     * Creates a minimal SceneData implementation with only the required
     * abstract methods, leaving all default methods at their base behavior.
     */
    private static SceneData minimalScene() {
        return new SceneData() {
            @Override
            public int minX() {
                return 0;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int minZ() {
                return 0;
            }

            @Override
            public int maxX() {
                return 0;
            }

            @Override
            public int maxY() {
                return 0;
            }

            @Override
            public int maxZ() {
                return 0;
            }

            @Override
            public XMaterial getBlockType(int wx, int wy, int wz) {
                return XMaterial.STONE;
            }
        };
    }

    // ===== getLegacyBlockData default =====

    @Test
    void defaultGetLegacyBlockDataReturnsZero() {
        SceneData scene = minimalScene();
        assertEquals(0, scene.getLegacyBlockData(0, 0, 0));
    }

    @Test
    void defaultGetLegacyBlockDataReturnsZeroForArbitraryCoords() {
        SceneData scene = minimalScene();
        assertEquals(0, scene.getLegacyBlockData(100, -50, 200));
    }

    // ===== hasLegacyBlockData default =====

    @Test
    void defaultHasLegacyBlockDataReturnsFalse() {
        SceneData scene = minimalScene();
        assertFalse(scene.hasLegacyBlockData());
    }

    // ===== getBlockState default =====

    @Test
    void defaultGetBlockStateReturnsNull() {
        SceneData scene = minimalScene();
        assertNull(scene.getBlockState(0, 0, 0));
    }

    @Test
    void defaultGetBlockStateReturnsNullForArbitraryCoords() {
        SceneData scene = minimalScene();
        assertNull(scene.getBlockState(100, -50, 200));
    }

    // ===== Override behavior =====

    @Test
    void overriddenLegacyDataIsRespected() {
        SceneData scene = new SceneData() {
            @Override
            public int minX() {
                return 0;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int minZ() {
                return 0;
            }

            @Override
            public int maxX() {
                return 0;
            }

            @Override
            public int maxY() {
                return 0;
            }

            @Override
            public int maxZ() {
                return 0;
            }

            @Override
            public XMaterial getBlockType(int wx, int wy, int wz) {
                return XMaterial.STONE;
            }

            @Override
            public byte getLegacyBlockData(int wx, int wy, int wz) {
                return 5;
            }

            @Override
            public boolean hasLegacyBlockData() {
                return true;
            }
        };
        assertEquals(5, scene.getLegacyBlockData(0, 0, 0));
        assertTrue(scene.hasLegacyBlockData());
    }

    @Test
    void overriddenBlockStateIsRespected() {
        SceneData scene = new SceneData() {
            @Override
            public int minX() {
                return 0;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int minZ() {
                return 0;
            }

            @Override
            public int maxX() {
                return 0;
            }

            @Override
            public int maxY() {
                return 0;
            }

            @Override
            public int maxZ() {
                return 0;
            }

            @Override
            public XMaterial getBlockType(int wx, int wy, int wz) {
                return XMaterial.OAK_STAIRS;
            }

            @Override
            public String getBlockState(int wx, int wy, int wz) {
                return "minecraft:oak_stairs[facing=north,half=bottom]";
            }
        };
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]",
                scene.getBlockState(0, 0, 0));
    }
}

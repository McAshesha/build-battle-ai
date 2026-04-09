package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import static org.junit.jupiter.api.Assertions.*;

class BlockRenderStateTest {

    @BeforeEach
    void clearCaches() {
        BlockRenderState.clearCache();
    }

    // ===== Default state =====

    @Test
    void defaultStateValues() {
        BlockRenderState def = BlockRenderState.DEFAULT;
        assertEquals("north", def.facing());
        assertEquals("y", def.axis());
        assertEquals("bottom", def.type());
        assertEquals("bottom", def.half());
        assertEquals("straight", def.shape());
        assertFalse(def.open());
        assertEquals(1, def.layers());
        assertEquals(0, def.rotation());
    }

    // ===== Parsing block states =====

    @Test
    void parseFacing() {
        SceneData scene = singleBlockScene("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("south", state.facing());
        assertEquals("bottom", state.half());
        assertEquals("straight", state.shape());
    }

    @Test
    void parseAxis() {
        SceneData scene = singleBlockScene("minecraft:oak_log[axis=z]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("z", state.axis());
    }

    @Test
    void parseSlabType() {
        SceneData scene = singleBlockScene("minecraft:stone_slab[type=top]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("top", state.type());
    }

    @Test
    void parseSlabDouble() {
        SceneData scene = singleBlockScene("minecraft:stone_slab[type=double]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("double", state.type());
    }

    @Test
    void parseOpenTrue() {
        SceneData scene = singleBlockScene("minecraft:oak_trapdoor[facing=north,half=bottom,open=true]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertTrue(state.open());
        assertEquals("north", state.facing());
        assertEquals("bottom", state.half());
    }

    @Test
    void parseOpenFalse() {
        SceneData scene = singleBlockScene("minecraft:oak_trapdoor[facing=north,half=bottom,open=false]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertFalse(state.open());
    }

    @Test
    void parseLayers() {
        SceneData scene = singleBlockScene("minecraft:snow[layers=4]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals(4, state.layers());
    }

    @Test
    void parseRotation() {
        SceneData scene = singleBlockScene("minecraft:oak_sign[rotation=12]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals(12, state.rotation());
    }

    @Test
    void parseStairsFull() {
        SceneData scene = singleBlockScene("minecraft:oak_stairs[facing=east,half=top,shape=inner_left]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("east", state.facing());
        assertEquals("top", state.half());
        assertEquals("inner_left", state.shape());
    }

    // ===== Edge cases =====

    @Test
    void nullBlockStateReturnsDefault() {
        SceneData scene = singleBlockScene(null);
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertSame(BlockRenderState.DEFAULT, state);
    }

    @Test
    void emptyBlockStateReturnsDefault() {
        SceneData scene = singleBlockScene("");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertSame(BlockRenderState.DEFAULT, state);
    }

    @Test
    void blockStateWithoutBracketsUsesDefaults() {
        SceneData scene = singleBlockScene("minecraft:stone");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("north", state.facing());
        assertEquals("y", state.axis());
    }

    @Test
    void missingPropertyUsesDefault() {
        SceneData scene = singleBlockScene("minecraft:something[facing=west]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("west", state.facing());
        assertEquals("y", state.axis());
        assertEquals("bottom", state.type());
    }

    @Test
    void invalidLayersValueUsesDefault() {
        SceneData scene = singleBlockScene("minecraft:snow[layers=abc]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals(1, state.layers());
    }

    @Test
    void invalidRotationValueUsesDefault() {
        SceneData scene = singleBlockScene("minecraft:oak_sign[rotation=not_a_number]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals(0, state.rotation());
    }

    @Test
    void propertyAtEndOfString() {
        SceneData scene = singleBlockScene("minecraft:stairs[facing=west]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("west", state.facing());
    }

    @Test
    void multipleProperties() {
        SceneData scene = singleBlockScene("minecraft:stairs[facing=east,half=top,shape=outer_right,waterlogged=false]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("east", state.facing());
        assertEquals("top", state.half());
        assertEquals("outer_right", state.shape());
    }

    // ===== Caching =====

    @Test
    void sameBlockStateReturnsCachedInstance() {
        String blockState = "minecraft:test_block[facing=north,half=bottom]";
        SceneData scene = singleBlockScene(blockState);
        BlockRenderState first = BlockRenderState.of(scene, 0, 0, 0);
        BlockRenderState second = BlockRenderState.of(scene, 0, 0, 0);
        assertSame(first, second);
    }

    // ===== Value equality =====

    @Test
    void statesWithSameValuesAreEqual() {
        SceneData scene1 = singleBlockScene("minecraft:a[facing=south]");
        SceneData scene2 = singleBlockScene("minecraft:b[facing=south]");
        BlockRenderState s1 = BlockRenderState.of(scene1, 0, 0, 0);
        BlockRenderState s2 = BlockRenderState.of(scene2, 0, 0, 0);
        assertEquals(s1, s2);
    }

    // ===== Cache eviction =====

    @Test
    void cacheStaysBoundedAfterManyUniqueInserts() {
        // Insert more entries than the max cache size
        int insertCount = BlockRenderState.MAX_CACHE_SIZE + 500;
        for (int i = 0; i < insertCount; i++) {
            SceneData scene = singleBlockScene("minecraft:block_" + i + "[facing=north]");
            BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
            assertNotNull(state);
        }
        // Cache should have been cleared and only holds the post-clear entries
        assertTrue(BlockRenderState.cacheSize() <= BlockRenderState.MAX_CACHE_SIZE + 1,
                "Cache size should be bounded, was: " + BlockRenderState.cacheSize());
    }

    @Test
    void correctnessPreservedAfterEviction() {
        // Fill past the limit to trigger a clear
        for (int i = 0; i < BlockRenderState.MAX_CACHE_SIZE + 100; i++) {
            SceneData scene = singleBlockScene("minecraft:filler_" + i + "[facing=east]");
            BlockRenderState.of(scene, 0, 0, 0);
        }
        // Now parse a specific block state and verify correctness
        SceneData scene = singleBlockScene("minecraft:oak_stairs[facing=south,half=top,shape=inner_left]");
        BlockRenderState state = BlockRenderState.of(scene, 0, 0, 0);
        assertEquals("south", state.facing());
        assertEquals("top", state.half());
        assertEquals("inner_left", state.shape());
    }

    // ===== Helper =====

    /**
     * Creates a minimal SceneData backed by a single block whose block state
     * string is the provided value.
     */
    private static SceneData singleBlockScene(final String blockState) {
        return new SceneData() {
            @Override public int minX() { return 0; }
            @Override public int minY() { return 0; }
            @Override public int minZ() { return 0; }
            @Override public int maxX() { return 0; }
            @Override public int maxY() { return 0; }
            @Override public int maxZ() { return 0; }
            @Override public XMaterial getBlockType(int wx, int wy, int wz) { return XMaterial.STONE; }
            @Override public String getBlockState(int wx, int wy, int wz) { return blockState; }
        };
    }
}

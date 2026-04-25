package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FlatScene#fromSnapshot(ChunkScene)} — conversion from
 * a {@link ChunkScene} to the flat-array format.
 * <p>
 * Uses Mockito to mock {@link ChunkScene} since its constructor is private
 * and {@code capture()} requires a live Bukkit server.
 */
class FlatSceneFromSnapshotTest {

    // ===== Helper =====

    /**
     * Creates a mocked ChunkScene with the given bounds.
     * All blocks default to {@link XMaterial#AIR} and all block states to {@code null}.
     *
     * @param minX      inclusive min X
     * @param minY      inclusive min Y
     * @param minZ      inclusive min Z
     * @param maxX      inclusive max X
     * @param maxY      inclusive max Y
     * @param maxZ      inclusive max Z
     * @param hasLegacy whether the scene should report legacy block data
     */
    private static ChunkScene createMockScene(int minX, int minY, int minZ,
                                              int maxX, int maxY, int maxZ,
                                              boolean hasLegacy) {
        ChunkScene scene = mock(ChunkScene.class);
        when(scene.minX()).thenReturn(minX);
        when(scene.minY()).thenReturn(minY);
        when(scene.minZ()).thenReturn(minZ);
        when(scene.maxX()).thenReturn(maxX);
        when(scene.maxY()).thenReturn(maxY);
        when(scene.maxZ()).thenReturn(maxZ);
        when(scene.hasLegacyBlockData()).thenReturn(hasLegacy);
        when(scene.getBlockType(anyInt(), anyInt(), anyInt())).thenReturn(XMaterial.AIR);
        when(scene.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(null);
        when(scene.getLegacyBlockData(anyInt(), anyInt(), anyInt())).thenReturn((byte) 0);
        return scene;
    }

    @Test
    void fromSnapshotPreservesBlockTypes() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 2, 2, 2, false);
        when(mockScene.getBlockType(1, 1, 1)).thenReturn(XMaterial.STONE);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(XMaterial.STONE, flat.getBlockType(1, 1, 1));
    }

    @Test
    void fromSnapshotPreservesBounds() {
        ChunkScene mockScene = createMockScene(10, 20, 30, 15, 25, 35, false);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(10, flat.minX());
        assertEquals(20, flat.minY());
        assertEquals(30, flat.minZ());
        assertEquals(15, flat.maxX());
        assertEquals(25, flat.maxY());
        assertEquals(35, flat.maxZ());
    }

    @Test
    void fromSnapshotComputesSizeCorrectly() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 3, 4, 5, false);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(4, flat.sizeX());
        assertEquals(5, flat.sizeY());
        assertEquals(6, flat.sizeZ());
    }

    // ===== Basic conversion =====

    @Test
    void fromSnapshotSetsRuntimeFormat() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 0, 0, 0, false);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(FlatScene.SourceFormat.RUNTIME, flat.sourceFormat());
        assertEquals("runtime", flat.sourceName());
    }

    // ===== Block states =====

    @Test
    void fromSnapshotPreservesBlockStates() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 0, 0, 0, false);
        when(mockScene.getBlockType(0, 0, 0)).thenReturn(XMaterial.OAK_STAIRS);
        when(mockScene.getBlockState(0, 0, 0))
                .thenReturn("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]");

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
                flat.getBlockState(0, 0, 0));
        assertTrue(flat.hasBlockStates());
    }

    @Test
    void fromSnapshotPreservesLegacyData() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 0, 0, 0, true);
        when(mockScene.getBlockType(0, 0, 0)).thenReturn(XMaterial.OAK_SLAB);
        when(mockScene.getLegacyBlockData(0, 0, 0)).thenReturn((byte) 8);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals((byte) 8, flat.getLegacyBlockData(0, 0, 0));
        assertTrue(flat.hasLegacyBlockData());
    }

    // ===== Legacy block data =====

    @Test
    void fromSnapshotWithoutLegacyDataHasNoLegacyArray() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 0, 0, 0, false);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertFalse(flat.hasLegacyBlockData());
        assertEquals(0, flat.getLegacyBlockData(0, 0, 0));
    }

    @Test
    void fromSnapshotCopiesMultipleBlocks() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 2, 2, 2, false);
        when(mockScene.getBlockType(0, 0, 0)).thenReturn(XMaterial.STONE);
        when(mockScene.getBlockType(1, 1, 1)).thenReturn(XMaterial.DIRT);
        when(mockScene.getBlockType(2, 2, 2)).thenReturn(XMaterial.SAND);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(XMaterial.STONE, flat.getBlockType(0, 0, 0));
        assertEquals(XMaterial.DIRT, flat.getBlockType(1, 1, 1));
        assertEquals(XMaterial.SAND, flat.getBlockType(2, 2, 2));
    }

    // ===== Multi-block scene =====

    @Test
    void fromSnapshotDefaultsAirForUnsetBlocks() {
        ChunkScene mockScene = createMockScene(0, 0, 0, 1, 1, 1, false);
        // Only set one block, rest are AIR from the mock default
        when(mockScene.getBlockType(0, 0, 0)).thenReturn(XMaterial.GOLD_BLOCK);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(XMaterial.GOLD_BLOCK, flat.getBlockType(0, 0, 0));
        assertEquals(XMaterial.AIR, flat.getBlockType(1, 1, 1));
    }

    // ===== Non-zero origin =====

    @Test
    void fromSnapshotWithNonZeroOrigin() {
        ChunkScene mockScene = createMockScene(-10, 50, 100, -8, 52, 102, false);
        when(mockScene.getBlockType(-10, 50, 100)).thenReturn(XMaterial.DIAMOND_BLOCK);
        when(mockScene.getBlockType(-8, 52, 102)).thenReturn(XMaterial.EMERALD_BLOCK);

        FlatScene flat = FlatScene.fromSnapshot(mockScene);

        assertEquals(XMaterial.DIAMOND_BLOCK, flat.getBlockType(-10, 50, 100));
        assertEquals(XMaterial.EMERALD_BLOCK, flat.getBlockType(-8, 52, 102));
    }
}

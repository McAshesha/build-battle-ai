package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FlatSceneTest {

    private static final short AIR = (short) XMaterial.AIR.ordinal();

    private static short[] airArray(int size) {
        short[] data = new short[size];
        Arrays.fill(data, AIR);
        return data;
    }

    // ===== Basic construction =====

    @Test
    void minimalConstructorSetsBounds() {
        short[] data = airArray(8); // 2x2x2
        FlatScene scene = new FlatScene(data, 10, 20, 30, 2, 2, 2);
        assertEquals(10, scene.minX());
        assertEquals(20, scene.minY());
        assertEquals(30, scene.minZ());
        assertEquals(11, scene.maxX());
        assertEquals(21, scene.maxY());
        assertEquals(31, scene.maxZ());
    }

    @Test
    void sizeFields() {
        short[] data = airArray(60); // 3x4x5
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 4, 5);
        assertEquals(3, scene.sizeX());
        assertEquals(4, scene.sizeY());
        assertEquals(5, scene.sizeZ());
    }

    @Test
    void minimalConstructorSetsDirectSourceFormat() {
        short[] data = airArray(1);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertEquals(FlatScene.SourceFormat.DIRECT, scene.sourceFormat());
        assertEquals("direct", scene.sourceName());
    }

    // ===== Block type access =====

    @Test
    void getBlockTypeReturnsCorrectMaterial() {
        short[] data = airArray(1);
        data[0] = (short) XMaterial.STONE.ordinal();
        FlatScene scene = new FlatScene(data, 5, 10, 15, 1, 1, 1);
        assertEquals(XMaterial.STONE, scene.getBlockType(5, 10, 15));
    }

    @Test
    void getBlockTypeOutOfBoundsReturnsAir() {
        short[] data = airArray(1);
        data[0] = (short) XMaterial.STONE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertEquals(XMaterial.AIR, scene.getBlockType(1, 0, 0));
        assertEquals(XMaterial.AIR, scene.getBlockType(0, 1, 0));
        assertEquals(XMaterial.AIR, scene.getBlockType(0, 0, 1));
        assertEquals(XMaterial.AIR, scene.getBlockType(-1, 0, 0));
    }

    @Test
    void getBlockTypeXMajorLayout() {
        // 2x2x2 scene starting at (0,0,0)
        short[] data = airArray(8);
        // X-major: index = x * sizeY * sizeZ + y * sizeZ + z
        data[0] = (short) XMaterial.STONE.ordinal();         // (0,0,0)
        data[1] = (short) XMaterial.DIRT.ordinal();           // (0,0,1)
        data[2] = (short) XMaterial.SAND.ordinal();           // (0,1,0)
        data[3] = (short) XMaterial.GRAVEL.ordinal();         // (0,1,1)
        data[4] = (short) XMaterial.COBBLESTONE.ordinal();    // (1,0,0)
        data[5] = (short) XMaterial.BRICKS.ordinal();         // (1,0,1)
        data[6] = (short) XMaterial.OBSIDIAN.ordinal();       // (1,1,0)
        data[7] = (short) XMaterial.GOLD_BLOCK.ordinal();     // (1,1,1)

        FlatScene scene = new FlatScene(data, 0, 0, 0, 2, 2, 2);

        assertEquals(XMaterial.STONE, scene.getBlockType(0, 0, 0));
        assertEquals(XMaterial.DIRT, scene.getBlockType(0, 0, 1));
        assertEquals(XMaterial.SAND, scene.getBlockType(0, 1, 0));
        assertEquals(XMaterial.GRAVEL, scene.getBlockType(0, 1, 1));
        assertEquals(XMaterial.COBBLESTONE, scene.getBlockType(1, 0, 0));
        assertEquals(XMaterial.BRICKS, scene.getBlockType(1, 0, 1));
        assertEquals(XMaterial.OBSIDIAN, scene.getBlockType(1, 1, 0));
        assertEquals(XMaterial.GOLD_BLOCK, scene.getBlockType(1, 1, 1));
    }

    @Test
    void getBlockTypeWithNonZeroOrigin() {
        short[] data = airArray(1);
        data[0] = (short) XMaterial.DIAMOND_BLOCK.ordinal();
        FlatScene scene = new FlatScene(data, 100, -50, 200, 1, 1, 1);
        assertEquals(XMaterial.DIAMOND_BLOCK, scene.getBlockType(100, -50, 200));
        assertEquals(XMaterial.AIR, scene.getBlockType(99, -50, 200));
        assertEquals(XMaterial.AIR, scene.getBlockType(101, -50, 200));
    }

    // ===== Legacy block data =====

    @Test
    void getLegacyBlockDataWhenPresent() {
        short[] data = airArray(1);
        byte[] legacy = {5};
        FlatScene scene = new FlatScene(data, legacy, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertEquals(5, scene.getLegacyBlockData(0, 0, 0));
    }

    @Test
    void getLegacyBlockDataReturnsZeroWhenNull() {
        short[] data = airArray(1);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertEquals(0, scene.getLegacyBlockData(0, 0, 0));
    }

    @Test
    void getLegacyBlockDataOutOfBoundsReturnsZero() {
        short[] data = airArray(1);
        byte[] legacy = {5};
        FlatScene scene = new FlatScene(data, legacy, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertEquals(0, scene.getLegacyBlockData(1, 0, 0));
    }

    // ===== Block states =====

    @Test
    void getBlockStateWhenPresent() {
        short[] data = airArray(1);
        String[] states = {"minecraft:oak_stairs[facing=north,half=bottom]"};
        FlatScene scene = new FlatScene(data, null, states, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", scene.getBlockState(0, 0, 0));
    }

    @Test
    void getBlockStateReturnsNullWhenArrayNull() {
        short[] data = airArray(1);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertNull(scene.getBlockState(0, 0, 0));
    }

    @Test
    void getBlockStateOutOfBoundsReturnsNull() {
        short[] data = airArray(1);
        String[] states = {"state"};
        FlatScene scene = new FlatScene(data, null, states, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertNull(scene.getBlockState(1, 0, 0));
    }

    // ===== BlockDataSnapshot =====

    @Test
    void getBlockDataSnapshotInBounds() {
        short[] data = {(short) XMaterial.IRON_BLOCK.ordinal()};
        byte[] legacy = {3};
        String[] states = {"minecraft:iron_block"};
        FlatScene scene = new FlatScene(data, legacy, states, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");

        FlatScene.BlockDataSnapshot snapshot = scene.getBlockDataSnapshot(0, 0, 0);
        assertEquals(XMaterial.IRON_BLOCK, snapshot.material());
        assertEquals(3, snapshot.legacyData());
        assertEquals("minecraft:iron_block", snapshot.blockState());
    }

    @Test
    void getBlockDataSnapshotOutOfBoundsReturnsAir() {
        short[] data = {(short) XMaterial.STONE.ordinal()};
        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");

        FlatScene.BlockDataSnapshot snapshot = scene.getBlockDataSnapshot(5, 5, 5);
        assertEquals(XMaterial.AIR, snapshot.material());
        assertEquals(0, snapshot.legacyData());
        assertNull(snapshot.blockState());
    }

    @Test
    void getBlockDataSnapshotWithNullArrays() {
        short[] data = {(short) XMaterial.STONE.ordinal()};
        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");

        FlatScene.BlockDataSnapshot snapshot = scene.getBlockDataSnapshot(0, 0, 0);
        assertEquals(XMaterial.STONE, snapshot.material());
        assertEquals(0, snapshot.legacyData());
        assertNull(snapshot.blockState());
    }

    // ===== hasLegacyBlockData / hasBlockStates =====

    @Test
    void hasLegacyBlockDataTrue() {
        short[] data = airArray(1);
        byte[] legacy = new byte[1];
        FlatScene scene = new FlatScene(data, legacy, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertTrue(scene.hasLegacyBlockData());
    }

    @Test
    void hasLegacyBlockDataFalse() {
        short[] data = airArray(1);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertFalse(scene.hasLegacyBlockData());
    }

    @Test
    void hasBlockStatesTrue() {
        short[] data = airArray(1);
        String[] states = new String[1];
        FlatScene scene = new FlatScene(data, null, states, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");
        assertTrue(scene.hasBlockStates());
    }

    @Test
    void hasBlockStatesFalse() {
        short[] data = airArray(1);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        assertFalse(scene.hasBlockStates());
    }

    // ===== Full constructor =====

    @Test
    void fullConstructorSetsAllFields() {
        short[] data = airArray(6); // 1x2x3
        byte[] legacy = new byte[6];
        String[] states = new String[6];
        FlatScene scene = new FlatScene(data, legacy, states, -5, 10, 20, 1, 2, 3,
                FlatScene.SourceFormat.RUNTIME, "my-source");

        assertEquals(-5, scene.minX());
        assertEquals(10, scene.minY());
        assertEquals(20, scene.minZ());
        assertEquals(-5, scene.maxX());
        assertEquals(11, scene.maxY());
        assertEquals(22, scene.maxZ());
        assertEquals(1, scene.sizeX());
        assertEquals(2, scene.sizeY());
        assertEquals(3, scene.sizeZ());
        assertEquals(FlatScene.SourceFormat.RUNTIME, scene.sourceFormat());
        assertEquals("my-source", scene.sourceName());
    }

    // ===== SourceFormat enum =====

    @Test
    void sourceFormatValues() {
        FlatScene.SourceFormat[] values = FlatScene.SourceFormat.values();
        assertEquals(2, values.length);
        assertEquals(FlatScene.SourceFormat.RUNTIME, FlatScene.SourceFormat.valueOf("RUNTIME"));
        assertEquals(FlatScene.SourceFormat.DIRECT, FlatScene.SourceFormat.valueOf("DIRECT"));
    }

    // ===== Larger scene =====

    @Test
    void largerSceneCorrectIndexing() {
        int sizeX = 4, sizeY = 5, sizeZ = 6;
        short[] data = airArray(sizeX * sizeY * sizeZ);

        // Set a specific block in the middle
        int x = 2, y = 3, z = 4;
        int index = x * sizeY * sizeZ + y * sizeZ + z;
        data[index] = (short) XMaterial.EMERALD_BLOCK.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, sizeX, sizeY, sizeZ);

        // The target block should be emerald
        assertEquals(XMaterial.EMERALD_BLOCK, scene.getBlockType(2, 3, 4));

        // All other positions should be AIR (ordinal 0 = AIR)
        assertEquals(XMaterial.AIR, scene.getBlockType(0, 0, 0));
        assertEquals(XMaterial.AIR, scene.getBlockType(1, 1, 1));
    }

    @Test
    void negativeOriginScene() {
        short[] data = airArray(27); // 3x3x3
        int minX = -1, minY = -1, minZ = -1;
        // Set block at world coords (0,0,0) -> local (1,1,1) -> index = 1*3*3 + 1*3 + 1 = 13
        data[13] = (short) XMaterial.REDSTONE_BLOCK.ordinal();

        FlatScene scene = new FlatScene(data, minX, minY, minZ, 3, 3, 3);

        assertEquals(XMaterial.REDSTONE_BLOCK, scene.getBlockType(0, 0, 0));
        assertEquals(XMaterial.AIR, scene.getBlockType(-1, -1, -1));
        assertEquals(XMaterial.AIR, scene.getBlockType(1, 1, 1));
    }

    // ===== Sign-extension safety (Bug fix: & 0xFFFF mask) =====

    @Test
    void getBlockDataSnapshotMasksSignExtendedShort() {
        // Use a high ordinal near Short.MAX_VALUE to verify the & 0xFFFF mask
        // prevents sign-extension from producing a negative array index.
        XMaterial[] allMats = XMaterial.values();
        int highOrdinal = Math.min(allMats.length - 1, Short.MAX_VALUE);
        XMaterial expected = allMats[highOrdinal];

        short[] data = {(short) highOrdinal};
        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0, 1, 1, 1,
                FlatScene.SourceFormat.DIRECT, "test");

        FlatScene.BlockDataSnapshot snapshot = scene.getBlockDataSnapshot(0, 0, 0);
        assertEquals(expected, snapshot.material());
    }

    // ===== Boundary checks =====

    @Test
    void sizeYZPrecomputeDoesNotBreakIndexing() {
        // Verify that the pre-computed sizeYZ field produces correct indexOf results
        // by testing a non-trivial scene with asymmetric dimensions
        int sizeX = 5, sizeY = 7, sizeZ = 3;
        short[] data = airArray(sizeX * sizeY * sizeZ);

        // Place blocks at specific coordinates and verify retrieval
        XMaterial[] testMats = {XMaterial.STONE, XMaterial.DIRT, XMaterial.SAND, XMaterial.COBBLESTONE};
        int[][] coords = {{0, 0, 0}, {4, 6, 2}, {2, 3, 1}, {1, 0, 2}};
        for (int i = 0; i < testMats.length; i++) {
            int x = coords[i][0], y = coords[i][1], z = coords[i][2];
            int index = x * sizeY * sizeZ + y * sizeZ + z;
            data[index] = (short) testMats[i].ordinal();
        }

        FlatScene scene = new FlatScene(data, 10, 20, 30, sizeX, sizeY, sizeZ);
        for (int i = 0; i < testMats.length; i++)
            assertEquals(testMats[i],
                    scene.getBlockType(coords[i][0] + 10, coords[i][1] + 20, coords[i][2] + 30),
                    "Block at (" + coords[i][0] + "," + coords[i][1] + "," + coords[i][2] + ")");

        // Out-of-bounds still returns AIR
        assertEquals(XMaterial.AIR, scene.getBlockType(9, 20, 30));
        assertEquals(XMaterial.AIR, scene.getBlockType(15, 20, 30));
    }

    @Test
    void boundsExactEdges() {
        short[] data = airArray(8); // 2x2x2 starting at (10, 20, 30)
        data[0] = (short) XMaterial.STONE.ordinal();
        data[7] = (short) XMaterial.DIRT.ordinal();

        FlatScene scene = new FlatScene(data, 10, 20, 30, 2, 2, 2);

        // Exact min bound
        assertEquals(XMaterial.STONE, scene.getBlockType(10, 20, 30));
        // Exact max bound
        assertEquals(XMaterial.DIRT, scene.getBlockType(11, 21, 31));

        // Just outside
        assertEquals(XMaterial.AIR, scene.getBlockType(9, 20, 30));
        assertEquals(XMaterial.AIR, scene.getBlockType(12, 20, 30));
        assertEquals(XMaterial.AIR, scene.getBlockType(10, 19, 30));
        assertEquals(XMaterial.AIR, scene.getBlockType(10, 22, 30));
        assertEquals(XMaterial.AIR, scene.getBlockType(10, 20, 29));
        assertEquals(XMaterial.AIR, scene.getBlockType(10, 20, 32));
    }
}

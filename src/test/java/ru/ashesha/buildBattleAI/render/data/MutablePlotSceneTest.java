package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.arena.api.Arena;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutablePlotSceneTest {

    @Test
    void constructorFillsAllCellsWithAir() {
        // XMaterial.AIR.ordinal() != 0 — a default-initialized short[] is filled with ACACIA_BOAT.
        // Guard against this trap from CLAUDE.md.
        assertNotEquals(0, XMaterial.AIR.ordinal(),
                "Test premise broken: XMaterial.AIR.ordinal() unexpectedly became 0");

        MutablePlotScene scene = new MutablePlotScene(
                10, 64, 20, 4, 4, 4, false);

        for (int x = 10; x < 14; x++)
            for (int y = 64; y < 68; y++)
                for (int z = 20; z < 24; z++)
                    assertEquals(XMaterial.AIR, scene.getBlockType(x, y, z),
                            "Cell (" + x + "," + y + "," + z + ") not AIR");
    }

    @Test
    void setBlockStorePlainMaterial() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);

        scene.setBlock(1, 2, 3, XMaterial.STONE, "minecraft:stone");

        assertEquals(XMaterial.STONE, scene.getBlockType(1, 2, 3));
        // Stateless material — state string must NOT be retained.
        assertNull(scene.getBlockState(1, 2, 3));
        // Other cells untouched.
        assertEquals(XMaterial.AIR, scene.getBlockType(0, 0, 0));
        assertEquals(XMaterial.AIR, scene.getBlockType(2, 2, 3));
    }

    @Test
    void setBlockStorefulMaterialKeepsState() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);
        String state = "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=false]";

        scene.setBlock(1, 2, 3, XMaterial.OAK_STAIRS, state);

        assertEquals(XMaterial.OAK_STAIRS, scene.getBlockType(1, 2, 3));
        assertEquals(state, scene.getBlockState(1, 2, 3));
    }

    @Test
    void setBlockStatefulThenStatelessClearsState() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);
        scene.setBlock(1, 2, 3, XMaterial.OAK_STAIRS,
                "minecraft:oak_stairs[facing=north,half=bottom]");
        assertNotNull(scene.getBlockState(1, 2, 3));

        scene.setBlock(1, 2, 3, XMaterial.STONE, "minecraft:stone");

        assertEquals(XMaterial.STONE, scene.getBlockType(1, 2, 3));
        assertNull(scene.getBlockState(1, 2, 3));
    }

    @Test
    void setBlockOutOfBoundsIsNoOp() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);

        scene.setBlock(100, 100, 100, XMaterial.STONE, "minecraft:stone");
        scene.setBlock(-1, 0, 0, XMaterial.STONE, "minecraft:stone");

        // No exception; nothing changed.
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    assertEquals(XMaterial.AIR, scene.getBlockType(x, y, z));
    }

    @Test
    void clearBlockReturnsCellToAir() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);
        scene.setBlock(1, 2, 3, XMaterial.OAK_STAIRS,
                "minecraft:oak_stairs[facing=north,half=bottom]");

        scene.clearBlock(1, 2, 3);

        assertEquals(XMaterial.AIR, scene.getBlockType(1, 2, 3));
        assertNull(scene.getBlockState(1, 2, 3));
    }

    @Test
    void clearBlockLegacyReturnsByteToZero() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, true);
        scene.setBlock(1, 2, 3, XMaterial.STONE, (byte) 5);
        assertEquals(5, scene.getLegacyBlockData(1, 2, 3));

        scene.clearBlock(1, 2, 3);

        assertEquals(XMaterial.AIR, scene.getBlockType(1, 2, 3));
        assertEquals(0, scene.getLegacyBlockData(1, 2, 3));
    }

    @Test
    void legacyModeStoresByteData() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, true);

        scene.setBlock(1, 2, 3, XMaterial.OAK_STAIRS, (byte) 5);

        assertEquals(XMaterial.OAK_STAIRS, scene.getBlockType(1, 2, 3));
        assertEquals(5, scene.getLegacyBlockData(1, 2, 3));
        assertTrue(scene.hasLegacyBlockData());
        // In legacy mode there is no block-state array — getBlockState should
        // return null (the SceneData default).
        assertNull(scene.getBlockState(1, 2, 3));
    }

    @Test
    void clearAllResetsAllCells() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);
        // Populate scene with mixed stateful + stateless materials.
        scene.setBlock(0, 0, 0, XMaterial.STONE, "minecraft:stone");
        scene.setBlock(1, 1, 1, XMaterial.OAK_STAIRS,
                "minecraft:oak_stairs[facing=south,half=top]");
        scene.setBlock(3, 3, 3, XMaterial.DIRT, "minecraft:dirt");

        scene.clearAll();

        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++) {
                    assertEquals(XMaterial.AIR, scene.getBlockType(x, y, z));
                    assertNull(scene.getBlockState(x, y, z));
                }
    }

    @Test
    void readLockIsExposed() {
        MutablePlotScene scene = new MutablePlotScene(0, 0, 0, 4, 4, 4, false);
        assertNotNull(scene.readLock());
        // tryLock must succeed when uncontended.
        assertTrue(scene.readLock().tryLock());
        scene.readLock().unlock();
    }

    @Test
    void forPlotComputesBoundsFromCorners() {
        // Build a PlotData whose corner1 is the LARGER corner; ensure forPlot
        // correctly normalises into min/max.
        Arena.Position spawn = new Arena.Position(0.5, 64.5, 0.5, 0f, 0f);
        Arena.Position cam = new Arena.Position(0.5, 70.5, 0.5, 0f, 0f);
        // PictureRegion requires @NonNull — build a minimal valid 1×1 region.
        Arena.PictureRegion picture = new Arena.PictureRegion(
                0, 64, 0, 0, 64, 0, BlockFace.NORTH);
        Arena.PlotData plot = new Arena.PlotData(spawn,
                /*c1*/ 30, 70, 25,
                /*c2*/ 10, 60, 20,
                Arrays.asList(cam, cam, cam),
                picture);

        MutablePlotScene scene = MutablePlotScene.forPlot(plot, false);

        assertEquals(10, scene.minX());
        assertEquals(60, scene.minY());
        assertEquals(20, scene.minZ());
        assertEquals(30, scene.maxX());
        assertEquals(70, scene.maxY());
        assertEquals(25, scene.maxZ());
    }
}

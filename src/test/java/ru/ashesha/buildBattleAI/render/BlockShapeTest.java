package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.FlatScene;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BlockShapeTest {

    private static final short AIR = (short) XMaterial.AIR.ordinal();

    private static short[] airArray(int size) {
        short[] data = new short[size];
        Arrays.fill(data, AIR);
        return data;
    }

    // ===== Full blocks return null =====

    @Test
    void stoneIsFullBlock() {
        assertNull(BlockShape.getShape(XMaterial.STONE));
    }

    @Test
    void dirtIsFullBlock() {
        assertNull(BlockShape.getShape(XMaterial.DIRT));
    }

    @Test
    void ironBlockIsFullBlock() {
        assertNull(BlockShape.getShape(XMaterial.IRON_BLOCK));
    }

    @Test
    void oakPlanksIsFullBlock() {
        assertNull(BlockShape.getShape(XMaterial.OAK_PLANKS));
    }

    @Test
    void obsidianIsFullBlock() {
        assertNull(BlockShape.getShape(XMaterial.OBSIDIAN));
    }

    // ===== Slabs =====

    @Test
    void oakSlabHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_SLAB);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // Bottom slab: minY=0, maxY=0.5
        assertArrayEquals(new double[]{0, 0, 0, 1, 0.5, 1}, shape[0], 1e-9);
    }

    @Test
    void stoneSlabHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.STONE_SLAB));
    }

    @Test
    void cobblestonSlabHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.COBBLESTONE_SLAB));
    }

    // ===== Stairs =====

    @Test
    void oakStairsHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_STAIRS);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // Stairs approx: {0, 0, 0, 1, 12/16.0, 1}
        assertEquals(12 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void stoneStairsHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.STONE_STAIRS));
    }

    // ===== Fences =====

    @Test
    void oakFenceHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_FENCE);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // Fence post: 6/16 to 10/16 on X and Z
        assertEquals(6 / 16.0, shape[0][0], 1e-9);
        assertEquals(10 / 16.0, shape[0][3], 1e-9);
    }

    @Test
    void netherBrickFenceHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.NETHER_BRICK_FENCE));
    }

    // ===== Walls =====

    @Test
    void cobblestoneWallHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.COBBLESTONE_WALL);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // Wall post: 4/16 to 12/16 on X and Z
        assertEquals(4 / 16.0, shape[0][0], 1e-9);
        assertEquals(12 / 16.0, shape[0][3], 1e-9);
    }

    // ===== Panes and bars =====

    @Test
    void glassPaneHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.GLASS_PANE);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Two cross panels
    }

    @Test
    void ironBarsHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.IRON_BARS);
        assertNotNull(shape);
        assertEquals(2, shape.length);
    }

    // ===== Trapdoors =====

    @Test
    void oakTrapdoorHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_TRAPDOOR);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // Trapdoor plate: {0, 0, 0, 1, 3/16.0, 1}
        assertEquals(3 / 16.0, shape[0][4], 1e-9);
    }

    // ===== Doors =====

    @Test
    void oakDoorHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_DOOR);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Cross shape
    }

    // ===== Fence gates =====

    @Test
    void oakFenceGateHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.OAK_FENCE_GATE);
        assertNotNull(shape);
    }

    // ===== Carpets =====

    @Test
    void whiteCarpetHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.WHITE_CARPET);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(1 / 16.0, shape[0][4], 1e-9); // Very thin
    }

    @Test
    void redCarpetHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.RED_CARPET));
    }

    // ===== Torches =====

    @Test
    void torchHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.TORCH);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(10 / 16.0, shape[0][4], 1e-9); // 10px tall
    }

    @Test
    void soulTorchHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.SOUL_TORCH));
    }

    @Test
    void redstoneTorchHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.REDSTONE_TORCH));
    }

    // ===== Thin posts =====

    @Test
    void endRodHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.END_ROD));
    }

    @Test
    void lightningRodHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.LIGHTNING_ROD));
    }

    // ===== Flora =====

    @Test
    void dandelionHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.DANDELION);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Small cross
    }

    @Test
    void poppyHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.POPPY));
    }

    @Test
    void shortGrassHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.SHORT_GRASS));
    }

    @Test
    void fernHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.FERN));
    }

    @Test
    void deadBushHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.DEAD_BUSH));
    }

    @Test
    void oakSaplingHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.OAK_SAPLING));
    }

    // ===== Pressure plates =====

    @Test
    void stonePressurePlateHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.STONE_PRESSURE_PLATE);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(2 / 16.0, shape[0][4], 1e-9);
    }

    // ===== Buttons =====

    @Test
    void stoneButtonHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.STONE_BUTTON);
        assertNotNull(shape);
        assertEquals(1, shape.length);
    }

    // ===== Rails =====

    @Test
    void railHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.RAIL));
    }

    @Test
    void poweredRailHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.POWERED_RAIL));
    }

    // ===== Special blocks =====

    @Test
    void chestHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.CHEST);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(1 / 16.0, shape[0][0], 1e-9); // Slightly inset
        assertEquals(14 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void enderChestHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.ENDER_CHEST));
    }

    @Test
    void enchantingTableHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.ENCHANTING_TABLE);
        assertNotNull(shape);
        assertEquals(12 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void campfireHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.CAMPFIRE);
        assertNotNull(shape);
        assertEquals(7 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void lanternHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.LANTERN);
        assertNotNull(shape);
    }

    @Test
    void hopperHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.HOPPER);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Bowl + neck
    }

    @Test
    void brewingStandHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.BREWING_STAND);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Base + post
    }

    @Test
    void cactusHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.CACTUS);
        assertNotNull(shape);
        assertEquals(1 / 16.0, shape[0][0], 1e-9); // 1px inset
    }

    @Test
    void anvilHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.ANVIL));
        assertNotNull(BlockShape.getShape(XMaterial.CHIPPED_ANVIL));
        assertNotNull(BlockShape.getShape(XMaterial.DAMAGED_ANVIL));
    }

    @Test
    void bedHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.RED_BED));
    }

    @Test
    void candleHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.CANDLE));
    }

    @Test
    void snowHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.SNOW));
    }

    @Test
    void flowerPotHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.FLOWER_POT));
    }

    @Test
    void daylightDetectorHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.DAYLIGHT_DETECTOR);
        assertNotNull(shape);
        assertEquals(6 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void endPortalFrameHasShape() {
        double[][] shape = BlockShape.getShape(XMaterial.END_PORTAL_FRAME);
        assertNotNull(shape);
        assertEquals(13 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void barrelHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.BARREL));
    }

    @Test
    void composterHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.COMPOSTER));
    }

    @Test
    void cauldronHasShape() {
        assertNotNull(BlockShape.getShape(XMaterial.CAULDRON));
    }

    // ===== Shape box validity =====

    @Test
    void allShapeBoxesHaveValidCoordinates() {
        for (XMaterial mat : XMaterial.values()) {
            double[][] shape = BlockShape.getShape(mat);
            if (shape == null) continue;
            for (int i = 0; i < shape.length; i++) {
                double[] box = shape[i];
                assertEquals(6, box.length, mat.name() + " box " + i + " should have 6 elements");
                assertTrue(box[0] >= 0 && box[0] <= 1, mat.name() + " box " + i + " minX out of range: " + box[0]);
                assertTrue(box[1] >= 0 && box[1] <= 1, mat.name() + " box " + i + " minY out of range: " + box[1]);
                assertTrue(box[2] >= 0 && box[2] <= 1, mat.name() + " box " + i + " minZ out of range: " + box[2]);
                assertTrue(box[3] >= 0 && box[3] <= 1, mat.name() + " box " + i + " maxX out of range: " + box[3]);
                assertTrue(box[4] >= 0 && box[4] <= 1, mat.name() + " box " + i + " maxY out of range: " + box[4]);
                assertTrue(box[5] >= 0 && box[5] <= 1, mat.name() + " box " + i + " maxZ out of range: " + box[5]);
                assertTrue(box[3] >= box[0], mat.name() + " box " + i + " maxX < minX");
                assertTrue(box[4] >= box[1], mat.name() + " box " + i + " maxY < minY");
                assertTrue(box[5] >= box[2], mat.name() + " box " + i + " maxZ < minZ");
            }
        }
    }

    // ===== Context-sensitive getShape(scene,...) =====

    @Test
    void slabTopHalfShapeViaHalfProperty() {
        // slabShape checks state.half(), so use half=top to trigger the top slab branch
        SceneData scene = sceneWithState(XMaterial.OAK_SLAB, "minecraft:oak_slab[half=top]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_SLAB);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(0.5, shape[0][1], 1e-9); // minY = 0.5
        assertEquals(1.0, shape[0][4], 1e-9); // maxY = 1.0
    }

    @Test
    void slabWithTypePropertyUsesDefaultBottom() {
        // Slab states use type=top/bottom, but slabShape checks state.half() which defaults to "bottom"
        SceneData scene = sceneWithState(XMaterial.OAK_SLAB, "minecraft:oak_slab[type=top,waterlogged=false]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_SLAB);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(0.0, shape[0][1], 1e-9); // minY = 0 (bottom slab)
        assertEquals(0.5, shape[0][4], 1e-9); // maxY = 0.5
    }

    @Test
    void slabBottomHalfShape() {
        SceneData scene = sceneWithState(XMaterial.OAK_SLAB, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_SLAB);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(0.0, shape[0][1], 1e-9); // minY = 0
        assertEquals(0.5, shape[0][4], 1e-9); // maxY = 0.5
    }

    @Test
    void slabDoubleUsesDefaultShape() {
        SceneData scene = sceneWithState(XMaterial.OAK_SLAB, "minecraft:oak_slab[type=double,waterlogged=false]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_SLAB);
        // Double slab: buildStatefulShape returns null, falls back to default slab shape
        assertNotNull(shape);
        assertEquals(1, shape.length);
    }

    @Test
    void stairsBottomNorthFacing() {
        SceneData scene = sceneWithState(XMaterial.OAK_STAIRS,
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_STAIRS);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Base + step
    }

    @Test
    void stairsTopSouthFacing() {
        SceneData scene = sceneWithState(XMaterial.OAK_STAIRS,
                "minecraft:oak_stairs[facing=south,half=top,shape=straight]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_STAIRS);
        assertNotNull(shape);
        assertEquals(2, shape.length);
    }

    @Test
    void trapdoorClosedBottom() {
        SceneData scene = sceneWithState(XMaterial.OAK_TRAPDOOR,
                "minecraft:oak_trapdoor[facing=north,half=bottom,open=false]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_TRAPDOOR);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(3 / 16.0, shape[0][4], 1e-9);
    }

    @Test
    void trapdoorClosedTop() {
        SceneData scene = sceneWithState(XMaterial.OAK_TRAPDOOR,
                "minecraft:oak_trapdoor[facing=north,half=top,open=false]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_TRAPDOOR);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        assertEquals(13 / 16.0, shape[0][1], 1e-9); // Top trapdoor
        assertEquals(1.0, shape[0][4], 1e-9);
    }

    @Test
    void trapdoorOpenNorth() {
        SceneData scene = sceneWithState(XMaterial.OAK_TRAPDOOR,
                "minecraft:oak_trapdoor[facing=north,half=bottom,open=true]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_TRAPDOOR);
        assertNotNull(shape);
        assertEquals(1, shape.length);
        // North open trapdoor: {0, 0, 0, 1, 1, 3/16.0}
        assertEquals(3 / 16.0, shape[0][5], 1e-9);
    }

    @Test
    void trapdoorOpenSouth() {
        SceneData scene = sceneWithState(XMaterial.OAK_TRAPDOOR,
                "minecraft:oak_trapdoor[facing=south,half=bottom,open=true]");
        double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.OAK_TRAPDOOR);
        assertNotNull(shape);
        assertEquals(13 / 16.0, shape[0][2], 1e-9);
    }

    @Test
    void snowLayersScaleHeight() {
        for (int layers = 1; layers <= 8; layers++) {
            SceneData scene = sceneWithState(XMaterial.SNOW,
                    "minecraft:snow[layers=" + layers + "]");
            double[][] shape = BlockShape.getShape(scene, 0, 0, 0, XMaterial.SNOW);
            assertNotNull(shape);
            double expectedHeight = layers / 8.0;
            assertEquals(expectedHeight, shape[0][4], 1e-9,
                    "Snow with " + layers + " layers should have height " + expectedHeight);
        }
    }

    // ===== Fence connectivity =====

    @Test
    void isolatedFenceIsJustPost() {
        // Single fence surrounded by air
        short[] data = airArray(27); // 3x3x3
        data[13] = (short) XMaterial.OAK_FENCE.ordinal(); // center (1,1,1)
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.OAK_FENCE);
        assertNotNull(shape);
        // Just a post — 1 box
        assertEquals(1, shape.length);
    }

    @Test
    void fenceConnectsToAdjacentFence() {
        short[] data = airArray(27); // 3x3x3
        data[13] = (short) XMaterial.OAK_FENCE.ordinal(); // center (1,1,1)
        // Place fence at (1,1,2) = z+1 => south
        data[14] = (short) XMaterial.OAK_FENCE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.OAK_FENCE);
        assertNotNull(shape);
        // Post + 1 arm
        assertEquals(2, shape.length);
    }

    @Test
    void fenceConnectsToOpaqueFullBlock() {
        short[] data = airArray(27); // 3x3x3
        data[13] = (short) XMaterial.OAK_FENCE.ordinal(); // center (1,1,1)
        // Place stone at (1,1,0) = z-1 => north
        data[12] = (short) XMaterial.STONE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.OAK_FENCE);
        assertNotNull(shape);
        assertEquals(2, shape.length); // Post + north arm
    }

    // ===== Wall connectivity =====

    @Test
    void isolatedWallIsPost() {
        short[] data = airArray(27);
        data[13] = (short) XMaterial.COBBLESTONE_WALL.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.COBBLESTONE_WALL);
        assertNotNull(shape);
        assertEquals(1, shape.length);
    }

    @Test
    void wallStraightLineNorthSouthOptimized() {
        short[] data = airArray(27); // 3x3x3
        data[12] = (short) XMaterial.COBBLESTONE_WALL.ordinal(); // (1,1,0) north
        data[13] = (short) XMaterial.COBBLESTONE_WALL.ordinal(); // (1,1,1) center
        data[14] = (short) XMaterial.COBBLESTONE_WALL.ordinal(); // (1,1,2) south
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.COBBLESTONE_WALL);
        assertNotNull(shape);
        // Straight line optimization: single box spanning full Z
        assertEquals(1, shape.length);
    }

    // ===== Glass pane connectivity =====

    @Test
    void isolatedGlassPaneShowsCross() {
        short[] data = airArray(27);
        data[13] = (short) XMaterial.GLASS_PANE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, 3, 3, 3);

        double[][] shape = BlockShape.getShape(scene, 1, 1, 1, XMaterial.GLASS_PANE);
        assertNotNull(shape);
        // Isolated pane shows full cross (crossWhenIsolated=true)
        assertEquals(3, shape.length); // center + 2 arms
    }

    // ===== Helper =====

    private static SceneData sceneWithState(final XMaterial material, final String blockState) {
        return new SceneData() {
            @Override public int minX() { return 0; }
            @Override public int minY() { return 0; }
            @Override public int minZ() { return 0; }
            @Override public int maxX() { return 0; }
            @Override public int maxY() { return 0; }
            @Override public int maxZ() { return 0; }
            @Override public XMaterial getBlockType(int wx, int wy, int wz) {
                return (wx == 0 && wy == 0 && wz == 0) ? material : XMaterial.AIR;
            }
            @Override public String getBlockState(int wx, int wy, int wz) {
                return (wx == 0 && wy == 0 && wz == 0) ? blockState : null;
            }
        };
    }
}

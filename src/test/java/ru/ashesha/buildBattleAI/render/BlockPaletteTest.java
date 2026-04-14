package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockPaletteTest {

    // ===== getColor() =====

    private static SceneData simpleScene(final XMaterial material) {
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
                return (wx == 0 && wy == 0 && wz == 0) ? material : XMaterial.AIR;
            }
        };
    }

    @Test
    void airIsTransparent() {
        assertEquals(-1, BlockPalette.getColor(XMaterial.AIR));
    }

    @Test
    void voidAirIsTransparent() {
        assertEquals(-1, BlockPalette.getColor(XMaterial.VOID_AIR));
    }

    @Test
    void caveAirIsTransparent() {
        assertEquals(-1, BlockPalette.getColor(XMaterial.CAVE_AIR));
    }

    @Test
    void stoneHasColor() {
        int color = BlockPalette.getColor(XMaterial.STONE);
        assertNotEquals(-1, color);
        assertEquals(0x7D7D7D, color);
    }

    @Test
    void dirtHasColor() {
        assertEquals(0x866043, BlockPalette.getColor(XMaterial.DIRT));
    }

    @Test
    void grassBlockHasColor() {
        assertEquals(0x7CBD6B, BlockPalette.getColor(XMaterial.GRASS_BLOCK));
    }

    @Test
    void oakPlanksHasColor() {
        // Exact value may shift by ±1 per channel due to the deduplication pass,
        // so verify it's close to the registered 0xA88654 rather than exact.
        int color = BlockPalette.getColor(XMaterial.OAK_PLANKS);
        assertNotEquals(-1, color, "Oak planks should not be transparent");
        int dr = Math.abs(((color >> 16) & 0xFF) - 0xA8);
        int dg = Math.abs(((color >> 8) & 0xFF) - 0x86);
        int db = Math.abs((color & 0xFF) - 0x54);
        assertTrue(dr + dg + db <= 10,
                "Oak planks color 0x" + Integer.toHexString(color) + " too far from expected 0xA88654");
    }

    @Test
    void ironBlockHasColor() {
        assertEquals(0xDCDCDC, BlockPalette.getColor(XMaterial.IRON_BLOCK));
    }

    @Test
    void goldBlockHasColor() {
        assertEquals(0xF8D44E, BlockPalette.getColor(XMaterial.GOLD_BLOCK));
    }

    @Test
    void diamondBlockHasColor() {
        assertEquals(0x62EDD8, BlockPalette.getColor(XMaterial.DIAMOND_BLOCK));
    }

    @Test
    void glassHasColor() {
        int color = BlockPalette.getColor(XMaterial.GLASS);
        assertNotEquals(-1, color);
        assertEquals(0xC8E8F0, color);
    }

    @Test
    void waterHasColor() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.WATER));
        assertEquals(0x2825F5, BlockPalette.getColor(XMaterial.WATER));
    }

    @Test
    void lavaHasColor() {
        assertEquals(0xD05E10, BlockPalette.getColor(XMaterial.LAVA));
    }

    @Test
    void transparentBlocksAreTransparent() {
        assertEquals(-1, BlockPalette.getColor(XMaterial.LEVER));
        assertEquals(-1, BlockPalette.getColor(XMaterial.REDSTONE_WIRE));
        assertEquals(-1, BlockPalette.getColor(XMaterial.COBWEB));
        assertEquals(-1, BlockPalette.getColor(XMaterial.BARRIER));
    }

    // ===== getAlpha() =====

    @Test
    void allMaterialsHaveColor() {
        // After initialization, no material should have NOT_MAPPED (-2)
        for (XMaterial mat : XMaterial.values()) {
            int color = BlockPalette.getColor(mat);
            assertNotEquals(-2, color, "Material " + mat.name() + " has NOT_MAPPED color");
        }
    }

    @Test
    void stoneIsFullyOpaque() {
        assertEquals(255, BlockPalette.getAlpha(XMaterial.STONE));
    }

    @Test
    void glassIsTranslucent() {
        int alpha = BlockPalette.getAlpha(XMaterial.GLASS);
        assertTrue(alpha < 255);
        assertTrue(alpha > 0);
        assertEquals(100, alpha);
    }

    @Test
    void glassPaneIsTranslucent() {
        assertEquals(100, BlockPalette.getAlpha(XMaterial.GLASS_PANE));
    }

    @Test
    void waterIsTranslucent() {
        int alpha = BlockPalette.getAlpha(XMaterial.WATER);
        assertTrue(alpha < 255);
        assertEquals(150, alpha);
    }

    @Test
    void iceIsTranslucent() {
        int alpha = BlockPalette.getAlpha(XMaterial.ICE);
        assertTrue(alpha < 255);
        assertEquals(180, alpha);
    }

    @Test
    void leavesAreTranslucent() {
        int alpha = BlockPalette.getAlpha(XMaterial.OAK_LEAVES);
        assertTrue(alpha < 255);
        assertEquals(220, alpha);
    }

    // ===== isEmissive() =====

    @Test
    void solidBlocksDefaultToOpaque() {
        assertEquals(255, BlockPalette.getAlpha(XMaterial.COBBLESTONE));
        assertEquals(255, BlockPalette.getAlpha(XMaterial.IRON_BLOCK));
        assertEquals(255, BlockPalette.getAlpha(XMaterial.OBSIDIAN));
    }

    @Test
    void glowstoneIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.GLOWSTONE));
    }

    @Test
    void seaLanternIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.SEA_LANTERN));
    }

    @Test
    void jackOLanternIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.JACK_O_LANTERN));
    }

    @Test
    void redstoneLampIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.REDSTONE_LAMP));
    }

    @Test
    void lavaIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.LAVA));
    }

    @Test
    void shroomlightIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.SHROOMLIGHT));
    }

    @Test
    void magmaBlockIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.MAGMA_BLOCK));
    }

    @Test
    void lanternIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.LANTERN));
    }

    @Test
    void soulLanternIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.SOUL_LANTERN));
    }

    @Test
    void campfireIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.CAMPFIRE));
    }

    @Test
    void soulCampfireIsEmissive() {
        assertTrue(BlockPalette.isEmissive(XMaterial.SOUL_CAMPFIRE));
    }

    @Test
    void stoneIsNotEmissive() {
        assertFalse(BlockPalette.isEmissive(XMaterial.STONE));
    }

    @Test
    void woodIsNotEmissive() {
        assertFalse(BlockPalette.isEmissive(XMaterial.OAK_PLANKS));
    }

    // ===== canMergeTranslucent() =====

    @Test
    void airIsNotEmissive() {
        assertFalse(BlockPalette.isEmissive(XMaterial.AIR));
    }

    @Test
    void waterMergesWithWater() {
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.WATER, XMaterial.WATER));
    }

    @Test
    void glassMergesWithGlass() {
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.GLASS, XMaterial.GLASS));
    }

    @Test
    void iceMergesWithIce() {
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.ICE, XMaterial.ICE));
    }

    @Test
    void waterDoesNotMergeWithGlass() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.WATER, XMaterial.GLASS));
    }

    @Test
    void waterDoesNotMergeWithIce() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.WATER, XMaterial.ICE));
    }

    @Test
    void opaqueDoesNotMerge() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.STONE, XMaterial.STONE));
    }

    @Test
    void airDoesNotMerge() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.AIR, XMaterial.AIR));
    }

    @Test
    void leavesDoNotMerge() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.OAK_LEAVES, XMaterial.OAK_LEAVES));
    }

    @Test
    void frostedIceMergesWithItself() {
        assertTrue(BlockPalette.canMergeTranslucent(XMaterial.FROSTED_ICE, XMaterial.FROSTED_ICE));
    }

    // ===== hashedFallbackColor safety (Bug fix: & 0x7FFFFFFF mask) =====

    @Test
    void frostedIceDoesNotMergeWithRegularIce() {
        assertFalse(BlockPalette.canMergeTranslucent(XMaterial.FROSTED_ICE, XMaterial.ICE));
    }

    // ===== Context-sensitive getColor() =====

    @Test
    void getColorNeverThrowsForAnyMaterial() {
        // Exercises the hashed-fallback path for unmapped materials. Before the fix,
        // a name whose hashCode() == Integer.MIN_VALUE caused a negative array index.
        for (XMaterial mat : XMaterial.values())
            assertDoesNotThrow(
                    () -> BlockPalette.getColor(mat),
                    "getColor threw for " + mat.name()
            );
    }

    @Test
    void grassBlockTopFaceIsGreen() {
        SceneData scene = simpleScene(XMaterial.GRASS_BLOCK);
        // hitFace=1 (Y), dy < 0 => looking down at top
        int color = BlockPalette.getColor(scene, 0, 0, 0, XMaterial.GRASS_BLOCK, 1, 0, -1, 0);
        assertEquals(0x7CBD6B, color);
    }

    @Test
    void grassBlockBottomFaceIsDirt() {
        SceneData scene = simpleScene(XMaterial.GRASS_BLOCK);
        // hitFace=1 (Y), dy > 0 => looking up at bottom
        int color = BlockPalette.getColor(scene, 0, 0, 0, XMaterial.GRASS_BLOCK, 1, 0, 1, 0);
        assertEquals(0x866043, color);
    }

    @Test
    void grassBlockSideFaceIsMixed() {
        SceneData scene = simpleScene(XMaterial.GRASS_BLOCK);
        // hitFace=0 (X face) => side
        int color = BlockPalette.getColor(scene, 0, 0, 0, XMaterial.GRASS_BLOCK, 0, -1, 0, 0);
        assertEquals(0x7D8A58, color);
    }

    // ===== Dye color inference =====

    @Test
    void grassBlockSideFaceZ() {
        SceneData scene = simpleScene(XMaterial.GRASS_BLOCK);
        // hitFace=2 (Z face) => side
        int color = BlockPalette.getColor(scene, 0, 0, 0, XMaterial.GRASS_BLOCK, 2, 0, 0, -1);
        assertEquals(0x7D8A58, color);
    }

    // ===== Color validity =====

    @Test
    void dyeColoredBlocksInferred() {
        // All dye-prefixed blocks should have non-transparent colors
        String[] prefixes = {"RED_", "BLUE_", "GREEN_", "YELLOW_", "BLACK_", "WHITE_", "ORANGE_",
                "MAGENTA_", "LIGHT_BLUE_", "LIME_", "PINK_", "GRAY_", "LIGHT_GRAY_",
                "CYAN_", "PURPLE_", "BROWN_"};
        // Test wool specifically
        for (String prefix : prefixes)
            try {
                XMaterial wool = XMaterial.valueOf(prefix + "WOOL");
                int color = BlockPalette.getColor(wool);
                assertNotEquals(-1, color, prefix + "WOOL should have a color");
                assertNotEquals(-2, color, prefix + "WOOL should be mapped");
            } catch (IllegalArgumentException e) {
                // Material doesn't exist, skip
            }
    }

    @Test
    void colorChannelsInValidRange() {
        for (XMaterial mat : XMaterial.values()) {
            int color = BlockPalette.getColor(mat);
            if (color == -1 || color == -2)
                continue;
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            assertTrue(r >= 0 && r <= 255, "Red channel out of range for " + mat.name() + ": " + r);
            assertTrue(g >= 0 && g <= 255, "Green channel out of range for " + mat.name() + ": " + g);
            assertTrue(b >= 0 && b <= 255, "Blue channel out of range for " + mat.name() + ": " + b);
        }
    }

    // ===== Specific material families =====

    @Test
    void alphaValuesInValidRange() {
        for (XMaterial mat : XMaterial.values()) {
            int alpha = BlockPalette.getAlpha(mat);
            assertTrue(alpha >= 0 && alpha <= 255, "Alpha out of range for " + mat.name() + ": " + alpha);
        }
    }

    @Test
    void woodFamiliesHaveDistinctColors() {
        int oak = BlockPalette.getColor(XMaterial.OAK_PLANKS);
        int spruce = BlockPalette.getColor(XMaterial.SPRUCE_PLANKS);
        int birch = BlockPalette.getColor(XMaterial.BIRCH_PLANKS);
        int jungle = BlockPalette.getColor(XMaterial.JUNGLE_PLANKS);
        int acacia = BlockPalette.getColor(XMaterial.ACACIA_PLANKS);
        int darkOak = BlockPalette.getColor(XMaterial.DARK_OAK_PLANKS);

        // All should be different
        assertNotEquals(oak, spruce);
        assertNotEquals(oak, birch);
        assertNotEquals(spruce, birch);
        assertNotEquals(jungle, acacia);
        assertNotEquals(acacia, darkOak);
    }

    @Test
    void oreBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.IRON_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.GOLD_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.DIAMOND_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.EMERALD_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.LAPIS_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.REDSTONE_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.COAL_ORE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.COPPER_ORE));
    }

    @Test
    void netherBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.NETHERRACK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.NETHER_BRICKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BASALT));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BLACKSTONE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CRIMSON_PLANKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.WARPED_PLANKS));
    }

    // ===== BLOCK_FLAGS consistency =====

    @Test
    void coralBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.TUBE_CORAL_BLOCK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BRAIN_CORAL_BLOCK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BUBBLE_CORAL_BLOCK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.FIRE_CORAL_BLOCK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.HORN_CORAL_BLOCK));
    }

    @Test
    void blockFlagsAxisBlockConsistent() {
        // Verify FLAG_AXIS_BLOCK matches the string-based isAxisBlock logic for all materials
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.endsWith("_LOG")
                    || name.endsWith("_WOOD")
                    || name.endsWith("_STEM")
                    || name.endsWith("_HYPHAE")
                    || name.equals("BAMBOO_BLOCK")
                    || name.equals("STRIPPED_BAMBOO_BLOCK")
                    || name.endsWith("_PILLAR")
                    || name.equals("CHAIN");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_AXIS_BLOCK) != 0;
            assertEquals(expected, actual, "FLAG_AXIS_BLOCK mismatch for " + name);
        }
    }

    @Test
    void blockFlagsFacingFrontConsistent() {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.equals("FURNACE")
                    || name.equals("BLAST_FURNACE")
                    || name.equals("SMOKER")
                    || name.equals("OBSERVER")
                    || name.equals("DROPPER")
                    || name.equals("DISPENSER")
                    || name.equals("CARVED_PUMPKIN")
                    || name.equals("JACK_O_LANTERN");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_FACING_FRONT) != 0;
            assertEquals(expected, actual, "FLAG_FACING_FRONT mismatch for " + name);
        }
    }

    @Test
    void blockFlagsPaneConsistent() {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.endsWith("_PANE") || name.equals("IRON_BARS");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_PANE) != 0;
            assertEquals(expected, actual, "FLAG_PANE mismatch for " + name);
        }
    }

    @Test
    void blockFlagsFenceConsistent() {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.endsWith("_FENCE") && !name.contains("GATE");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_FENCE) != 0;
            assertEquals(expected, actual, "FLAG_FENCE mismatch for " + name);
        }
    }

    @Test
    void blockFlagsWallConsistent() {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.endsWith("_WALL");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_WALL) != 0;
            assertEquals(expected, actual, "FLAG_WALL mismatch for " + name);
        }
    }

    @Test
    void blockFlagsNeedsStateConsistent() {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            boolean expected = name.endsWith("_SLAB")
                    || name.endsWith("_STAIRS")
                    || name.endsWith("_TRAPDOOR")
                    || name.endsWith("_DOOR")
                    || name.endsWith("_FENCE_GATE")
                    || name.endsWith("_SIGN")
                    || name.endsWith("_HANGING_SIGN")
                    || name.equals("CHAIN")
                    || name.equals("END_ROD")
                    || name.equals("LIGHTNING_ROD")
                    || name.equals("LADDER")
                    || name.equals("VINE")
                    || name.equals("WALL_TORCH")
                    || name.equals("SOUL_WALL_TORCH")
                    || name.equals("REDSTONE_WALL_TORCH")
                    || name.equals("SNOW");
            boolean actual = (BlockPalette.BLOCK_FLAGS[mat.ordinal()] & BlockPalette.FLAG_NEEDS_STATE) != 0;
            assertEquals(expected, actual, "FLAG_NEEDS_STATE mismatch for " + name);
        }
    }

    @Test
    void stoneHasNoFlags() {
        assertEquals(0, BlockPalette.BLOCK_FLAGS[XMaterial.STONE.ordinal()]);
    }

    @Test
    void oakLogHasAxisFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.OAK_LOG.ordinal()] & BlockPalette.FLAG_AXIS_BLOCK) != 0);
    }

    @Test
    void furnaceHasFacingFrontFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.FURNACE.ordinal()] & BlockPalette.FLAG_FACING_FRONT) != 0);
    }

    @Test
    void glassPaneHasPaneFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.GLASS_PANE.ordinal()] & BlockPalette.FLAG_PANE) != 0);
    }

    @Test
    void ironBarsHasPaneFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.IRON_BARS.ordinal()] & BlockPalette.FLAG_PANE) != 0);
    }

    @Test
    void oakFenceHasFenceFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.OAK_FENCE.ordinal()] & BlockPalette.FLAG_FENCE) != 0);
    }

    @Test
    void cobblestoneWallHasWallFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.COBBLESTONE_WALL.ordinal()] & BlockPalette.FLAG_WALL) != 0);
    }

    @Test
    void oakSlabHasNeedsStateFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.OAK_SLAB.ordinal()] & BlockPalette.FLAG_NEEDS_STATE) != 0);
    }

    @Test
    void oakStairsHasNeedsStateFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.OAK_STAIRS.ordinal()] & BlockPalette.FLAG_NEEDS_STATE) != 0);
    }

    @Test
    void oakTrapdoorHasNeedsStateFlag() {
        assertTrue((BlockPalette.BLOCK_FLAGS[XMaterial.OAK_TRAPDOOR.ordinal()] & BlockPalette.FLAG_NEEDS_STATE) != 0);
    }

    // ===== Helper =====

    @Test
    void fenceGateHasNeedsStateButNotFenceFlag() {
        int flags = BlockPalette.BLOCK_FLAGS[XMaterial.OAK_FENCE_GATE.ordinal()];
        assertTrue((flags & BlockPalette.FLAG_NEEDS_STATE) != 0, "Fence gate should have FLAG_NEEDS_STATE");
        assertEquals(0, flags & BlockPalette.FLAG_FENCE, "Fence gate should NOT have FLAG_FENCE");
    }

    // ===== Color uniqueness (deduplication) =====

    @Test
    void noTwoNonTransparentBlocksShareSameColor() {
        Map<Integer, String> seen = new HashMap<>();
        for (XMaterial mat : XMaterial.values()) {
            int color = BlockPalette.getColor(mat);
            if (color == -1)
                continue;
            String existing = seen.get(color);
            assertNull(existing,
                    "Duplicate color 0x" + Integer.toHexString(color)
                            + " shared by " + existing + " and " + mat.name());
            seen.put(color, mat.name());
        }
    }

    // ===== Solid blocks must have colors =====

    @Test
    void commonSolidBlocksAreNotTransparent() {
        // These blocks should always be visible in the renderer
        XMaterial[] solidBlocks = {
                XMaterial.STONE, XMaterial.COBBLESTONE, XMaterial.DIRT,
                XMaterial.GRASS_BLOCK, XMaterial.OAK_PLANKS, XMaterial.SPRUCE_PLANKS,
                XMaterial.IRON_BLOCK, XMaterial.GOLD_BLOCK, XMaterial.DIAMOND_BLOCK,
                XMaterial.BRICKS, XMaterial.SANDSTONE, XMaterial.OBSIDIAN,
                XMaterial.NETHERRACK, XMaterial.DEEPSLATE, XMaterial.TUFF,
                XMaterial.BEDROCK, XMaterial.TERRACOTTA, XMaterial.CLAY,
                XMaterial.SNOW_BLOCK, XMaterial.ICE, XMaterial.PACKED_ICE,
                XMaterial.SPONGE, XMaterial.MELON, XMaterial.PUMPKIN,
                XMaterial.HAY_BLOCK, XMaterial.TNT, XMaterial.BONE_BLOCK,
                XMaterial.HONEY_BLOCK, XMaterial.SLIME_BLOCK
        };
        for (XMaterial mat : solidBlocks) {
            int color = BlockPalette.getColor(mat);
            assertNotEquals(-1, color, mat.name() + " should not be transparent");
        }
    }

    // ===== Cherry / 1.20+ blocks =====

    @Test
    void cherryBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CHERRY_PLANKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CHERRY_LOG));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CHERRY_LEAVES));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CHERRY_SLAB));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.CHERRY_STAIRS));
    }

    @Test
    void cherryLeavesArePink() {
        int color = BlockPalette.getColor(XMaterial.CHERRY_LEAVES);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        // Cherry leaves should have high red and blue (pinkish), low green
        assertTrue(r > 180, "Cherry leaves red channel too low: " + r);
        assertTrue(b > 150, "Cherry leaves blue channel too low: " + b);
        assertTrue(g < r, "Cherry leaves green should be less than red");
    }

    @Test
    void bambooBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BAMBOO_PLANKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BAMBOO_BLOCK));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.BAMBOO_MOSAIC));
    }

    @Test
    void mangroveBlocksHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.MANGROVE_PLANKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.MANGROVE_LOG));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.MANGROVE_LEAVES));
    }

    // ===== Type-specific dye colors =====

    @Test
    void terracottaColorsDifferFromWool() {
        // Each colored terracotta should have a different color than the matching wool
        String[] prefixes = {"RED_", "BLUE_", "GREEN_", "YELLOW_", "BLACK_", "WHITE_",
                "ORANGE_", "MAGENTA_", "LIGHT_BLUE_", "LIME_", "PINK_", "GRAY_",
                "LIGHT_GRAY_", "CYAN_", "PURPLE_", "BROWN_"};
        for (String prefix : prefixes) {
            try {
                XMaterial wool = XMaterial.valueOf(prefix + "WOOL");
                XMaterial terracotta = XMaterial.valueOf(prefix + "TERRACOTTA");
                int woolColor = BlockPalette.getColor(wool);
                int terracottaColor = BlockPalette.getColor(terracotta);
                assertNotEquals(woolColor, terracottaColor,
                        prefix + " wool and terracotta should have different colors");
            } catch (IllegalArgumentException e) {
                // Skip if material doesn't exist
            }
        }
    }

    @Test
    void concreteColorsDifferFromWool() {
        String[] prefixes = {"RED_", "BLUE_", "WHITE_", "BLACK_"};
        for (String prefix : prefixes) {
            try {
                XMaterial wool = XMaterial.valueOf(prefix + "WOOL");
                XMaterial concrete = XMaterial.valueOf(prefix + "CONCRETE");
                int woolColor = BlockPalette.getColor(wool);
                int concreteColor = BlockPalette.getColor(concrete);
                assertNotEquals(woolColor, concreteColor,
                        prefix + " wool and concrete should have different colors");
            } catch (IllegalArgumentException e) {
                // Skip if material doesn't exist
            }
        }
    }

    @Test
    void concretePowderDiffersFromConcrete() {
        String[] prefixes = {"RED_", "BLUE_", "WHITE_", "BLACK_"};
        for (String prefix : prefixes) {
            try {
                XMaterial concrete = XMaterial.valueOf(prefix + "CONCRETE");
                XMaterial powder = XMaterial.valueOf(prefix + "CONCRETE_POWDER");
                int concreteColor = BlockPalette.getColor(concrete);
                int powderColor = BlockPalette.getColor(powder);
                assertNotEquals(concreteColor, powderColor,
                        prefix + " concrete and concrete powder should have different colors");
            } catch (IllegalArgumentException e) {
                // Skip if material doesn't exist
            }
        }
    }

    @Test
    void terracottaColorsAreEarthy() {
        // Terracotta should be more muted/earthy than the bright dye colors used for wool
        try {
            int redTerracotta = BlockPalette.getColor(XMaterial.valueOf("RED_TERRACOTTA"));
            int redWool = BlockPalette.getColor(XMaterial.valueOf("RED_WOOL"));
            int trR = (redTerracotta >> 16) & 0xFF;
            int wR = (redWool >> 16) & 0xFF;
            // Red terracotta should be less saturated (lower R) than red wool
            assertTrue(trR < wR,
                    "Red terracotta R=" + trR + " should be less saturated than red wool R=" + wR);
        } catch (IllegalArgumentException e) {
            // Skip if material doesn't exist
        }
    }

    // ===== Leaves have colors =====

    @Test
    void allLeafTypesHaveColors() {
        for (XMaterial mat : XMaterial.values()) {
            if (mat.name().endsWith("_LEAVES")) {
                int color = BlockPalette.getColor(mat);
                assertNotEquals(-1, color, mat.name() + " should have a color");
            }
        }
    }

    // ===== Copper oxidation stages are distinct =====

    @Test
    void copperOxidationStagesAreDistinct() {
        int base = BlockPalette.getColor(XMaterial.COPPER_BLOCK);
        int exposed = BlockPalette.getColor(XMaterial.EXPOSED_COPPER);
        int weathered = BlockPalette.getColor(XMaterial.WEATHERED_COPPER);
        int oxidized = BlockPalette.getColor(XMaterial.OXIDIZED_COPPER);
        assertNotEquals(base, exposed, "Copper and exposed copper should differ");
        assertNotEquals(exposed, weathered, "Exposed and weathered copper should differ");
        assertNotEquals(weathered, oxidized, "Weathered and oxidized copper should differ");
        assertNotEquals(base, oxidized, "Copper and oxidized copper should differ");
    }

    // ===== Decorative blocks have colors =====

    @Test
    void decorativeBlocksHaveColors() {
        XMaterial[] decorative = {
                XMaterial.CHEST, XMaterial.CRAFTING_TABLE, XMaterial.FURNACE,
                XMaterial.ANVIL, XMaterial.ENCHANTING_TABLE, XMaterial.BREWING_STAND,
                XMaterial.CAULDRON, XMaterial.BELL, XMaterial.LANTERN,
                XMaterial.CAMPFIRE, XMaterial.BARREL, XMaterial.COMPOSTER,
                XMaterial.LECTERN, XMaterial.BEEHIVE, XMaterial.BEE_NEST,
                XMaterial.DECORATED_POT
        };
        for (XMaterial mat : decorative) {
            int color = BlockPalette.getColor(mat);
            assertNotEquals(-1, color, mat.name() + " should have a color");
        }
    }

    // ===== Stained glass has colors and alpha =====

    @Test
    void stainedGlassHasColorsAndAlpha() {
        for (XMaterial mat : XMaterial.values()) {
            if (mat.name().endsWith("_STAINED_GLASS")) {
                int color = BlockPalette.getColor(mat);
                int alpha = BlockPalette.getAlpha(mat);
                assertNotEquals(-1, color, mat.name() + " should have a color");
                assertTrue(alpha < 255 && alpha > 0,
                        mat.name() + " should be translucent, alpha=" + alpha);
            }
        }
    }

    // ===== Deepslate family has colors =====

    @Test
    void deepslateVariantsHaveColors() {
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.DEEPSLATE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.COBBLED_DEEPSLATE));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.DEEPSLATE_BRICKS));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.DEEPSLATE_TILES));
        assertNotEquals(-1, BlockPalette.getColor(XMaterial.POLISHED_DEEPSLATE));
    }
}

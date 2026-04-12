package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import lombok.experimental.UtilityClass;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps block materials to their sub-block collision shapes for ray intersection.
 * Full blocks have no shape entry (null) — the renderer treats them as 1×1×1 cubes.
 * Sub-block shapes are defined as arrays of AABB boxes in voxel-local coordinates [0,1]³.
 * Each box is {@code {minX, minY, minZ, maxX, maxY, maxZ}}.
 */
@UtilityClass
public class BlockShape {

    /**
     * Maximum state shape cache size before eviction. Higher than BlockRenderState's limit
     * because keys include the material ordinal, producing more unique entries.
     * Clearing is safe — entries are purely a performance cache and will be recomputed on miss.
     */
    static final int MAX_STATE_SHAPE_CACHE_SIZE = 2048;
    /**
     * Cache for shapes resolved from neighbor connectivity (fences, walls, panes).
     */
    private static final Map<String, double[][]> CONNECTIVITY_SHAPE_CACHE = new ConcurrentHashMap<>();
    // Fence: 4×16×4 center post
    private static final double[] FENCE_POST = {6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 1, 10 / 16.0};

    // === Individual box definitions (Minecraft pixel coordinates / 16.0) ===
    // Wall: 8×16×8 center post (walls are wider than fences)
    private static final double[] WALL_POST = {4 / 16.0, 0, 4 / 16.0, 12 / 16.0, 1, 12 / 16.0};
    // Cross: two intersecting thin 2px panels (bars, panes, doors, fence gates)
    private static final double[] CROSS_NS = {7 / 16.0, 0, 0, 9 / 16.0, 1, 1};
    private static final double[] CROSS_EW = {0, 0, 7 / 16.0, 1, 1, 9 / 16.0};
    // Small cross for flora / torches
    private static final double[] SMALL_CROSS_NS = {7 / 16.0, 0, 2 / 16.0, 9 / 16.0, 14 / 16.0, 14 / 16.0};
    private static final double[] SMALL_CROSS_EW = {2 / 16.0, 0, 7 / 16.0, 14 / 16.0, 14 / 16.0, 9 / 16.0};
    // Thin center post: chains, end rods, lightning rods (2×16×2)
    private static final double[] THIN_POST = {7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 1, 9 / 16.0};
    // Torch: 2×10×2 center post
    private static final double[] TORCH_POST = {7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 10 / 16.0, 9 / 16.0};
    // Slab (bottom half): 16×8×16
    private static final double[] SLAB_BOTTOM = {0, 0, 0, 1, 0.5, 1};
    private static final double[] SLAB_TOP = {0, 0.5, 0, 1, 1, 1};
    // Stairs: 3/4 height approximation (bottom half always filled, top varies by orientation)
    private static final double[] STAIRS_APPROX = {0, 0, 0, 1, 12 / 16.0, 1};
    private static final double[] STAIRS_BOTTOM_BASE = {0, 0, 0, 1, 0.5, 1};
    private static final double[] STAIRS_BOTTOM_STEP_NORTH = {0, 0.5, 0, 1, 1, 0.5};
    private static final double[] STAIRS_BOTTOM_STEP_SOUTH = {0, 0.5, 0.5, 1, 1, 1};
    private static final double[] STAIRS_BOTTOM_STEP_WEST = {0, 0.5, 0, 0.5, 1, 1};
    private static final double[] STAIRS_BOTTOM_STEP_EAST = {0.5, 0.5, 0, 1, 1, 1};
    private static final double[] STAIRS_TOP_BASE = {0, 0.5, 0, 1, 1, 1};
    private static final double[] STAIRS_TOP_STEP_NORTH = {0, 0, 0, 1, 0.5, 0.5};
    private static final double[] STAIRS_TOP_STEP_SOUTH = {0, 0, 0.5, 1, 0.5, 1};
    private static final double[] STAIRS_TOP_STEP_WEST = {0, 0, 0, 0.5, 0.5, 1};
    private static final double[] STAIRS_TOP_STEP_EAST = {0.5, 0, 0, 1, 0.5, 1};
    // Carpet: 16×1×16
    private static final double[] CARPET_LAYER = {0, 0, 0, 1, 1 / 16.0, 1};
    // Pressure plate / rail / flat decoration
    private static final double[] THIN_FLOOR_LAYER = {0, 0, 0, 1, 2 / 16.0, 1};
    // Button placed on floor as generic small block
    private static final double[] BUTTON_BOX = {5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 2 / 16.0, 11 / 16.0};
    // Trapdoor (closed, bottom position): 16×3×16
    private static final double[] TRAPDOOR_PLATE = {0, 0, 0, 1, 3 / 16.0, 1};
    private static final double[] TRAPDOOR_TOP = {0, 13 / 16.0, 0, 1, 1, 1};
    private static final double[] TRAPDOOR_NORTH = {0, 0, 0, 1, 1, 3 / 16.0};
    private static final double[] TRAPDOOR_SOUTH = {0, 0, 13 / 16.0, 1, 1, 1};
    private static final double[] TRAPDOOR_WEST = {0, 0, 0, 3 / 16.0, 1, 1};
    private static final double[] TRAPDOOR_EAST = {13 / 16.0, 0, 0, 1, 1, 1};
    // Generic wall plate used for ladders, vines, signs
    private static final double[] WALL_PLATE = {0, 0, 14 / 16.0, 1, 1, 1};
    private static final double[] WALL_PLATE_NORTH = {0, 0, 0, 1, 1, 2 / 16.0};
    private static final double[] WALL_PLATE_SOUTH = {0, 0, 14 / 16.0, 1, 1, 1};
    private static final double[] WALL_PLATE_WEST = {0, 0, 0, 2 / 16.0, 1, 1};
    private static final double[] WALL_PLATE_EAST = {14 / 16.0, 0, 0, 1, 1, 1};
    // Sign board + post
    private static final double[] SIGN_BOARD = {1 / 16.0, 7 / 16.0, 7 / 16.0, 15 / 16.0, 15 / 16.0, 9 / 16.0};
    private static final double[] SIGN_POST = {7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 9 / 16.0, 9 / 16.0};
    private static final double[] SIGN_BOARD_NORTH = {1 / 16.0, 7 / 16.0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 3 / 16.0};
    private static final double[] SIGN_BOARD_SOUTH = {1 / 16.0, 7 / 16.0, 13 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0};
    private static final double[] SIGN_BOARD_WEST = {1 / 16.0, 7 / 16.0, 1 / 16.0, 3 / 16.0, 15 / 16.0, 15 / 16.0};
    private static final double[] SIGN_BOARD_EAST = {13 / 16.0, 7 / 16.0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0};
    // Bed: 16×9×16
    private static final double[] BED_BOX = {0, 0, 0, 1, 9 / 16.0, 1};
    // Enchanting table: 16×12×16
    private static final double[] ENCHANTING_TABLE_BOX = {0, 0, 0, 1, 12 / 16.0, 1};
    // Snow layer (default 1 layer): 16×2×16
    private static final double[] SNOW_LAYER_BOX = {0, 0, 0, 1, 2 / 16.0, 1};
    // Stonecutter: 16×9×16
    private static final double[] STONECUTTER_BOX = {0, 0, 0, 1, 9 / 16.0, 1};
    // Candle: 2×6×2 center
    private static final double[] CANDLE_BOX = {7 / 16.0, 0, 7 / 16.0, 9 / 16.0, 6 / 16.0, 9 / 16.0};
    // Bamboo plant: thin 3×16×3 center post
    private static final double[] BAMBOO_POST = {6.5 / 16.0, 0, 6.5 / 16.0, 9.5 / 16.0, 1, 9.5 / 16.0};
    // Amethyst cluster/bud: medium centered
    private static final double[] AMETHYST_BOX = {3 / 16.0, 0, 3 / 16.0, 13 / 16.0, 7 / 16.0, 13 / 16.0};
    // Pointed dripstone: thin stalactite/stalagmite
    private static final double[] DRIPSTONE_BOX = {5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 11 / 16.0, 11 / 16.0};
    // Chest: 14×14×14 (slightly smaller than full block)
    private static final double[] CHEST_BOX = {1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 14 / 16.0, 15 / 16.0};
    // Campfire: full width, 7/16 tall
    private static final double[] CAMPFIRE_BOX = {0, 0, 0, 1, 7 / 16.0, 1};
    // Lantern: 6×8×6 centered on ground
    private static final double[] LANTERN_BOX = {5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 8 / 16.0, 11 / 16.0};
    // End portal frame: 16×13×16
    private static final double[] END_PORTAL_FRAME_BOX = {0, 0, 0, 1, 13 / 16.0, 1};
    // Skull/Head: 8×8×8 centered on ground
    private static final double[] SKULL_BOX = {4 / 16.0, 0, 4 / 16.0, 12 / 16.0, 8 / 16.0, 12 / 16.0};
    // Flower pot: 6×6×6 centered on ground
    private static final double[] FLOWER_POT_BOX = {5 / 16.0, 0, 5 / 16.0, 11 / 16.0, 6 / 16.0, 11 / 16.0};
    // Daylight detector: 16×6×16
    private static final double[] DAYLIGHT_BOX = {0, 0, 0, 1, 6 / 16.0, 1};
    // Cactus: 14×16×14 (1px inset on all sides)
    private static final double[] CACTUS_BOX = {1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 1, 15 / 16.0};
    // Anvil: 12×12×12 centered (symmetric approximation, no orientation data)
    private static final double[] ANVIL_BOX = {2 / 16.0, 0, 2 / 16.0, 14 / 16.0, 12 / 16.0, 14 / 16.0};
    // Barrel / composter / beehive: almost full but slightly inset
    private static final double[] INSET_FULL_BOX = {1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 1, 15 / 16.0};
    // Hopper / cauldron / brewing stand approximations
    private static final double[] HOPPER_BOX = {2 / 16.0, 4 / 16.0, 2 / 16.0, 14 / 16.0, 12 / 16.0, 14 / 16.0};
    private static final double[] HOPPER_NECK = {6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 4 / 16.0, 10 / 16.0};
    private static final double[] CAULDRON_BOX = {1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 12 / 16.0, 15 / 16.0};
    private static final double[] BREWING_STAND_BASE = {3 / 16.0, 0, 3 / 16.0, 13 / 16.0, 2 / 16.0, 13 / 16.0};
    private static final double[] BREWING_STAND_POST = {7 / 16.0, 2 / 16.0, 7 / 16.0, 9 / 16.0, 14 / 16.0, 9 / 16.0};
    private static final double[][] SHAPE_FENCE = {FENCE_POST};

    // Sculk sensor / shrieker: 16×8×16 (half height)
    // reuses SLAB_BOTTOM

    // === Composite shape arrays ===
    private static final double[][] SHAPE_WALL = {WALL_POST};
    private static final double[][] SHAPE_CROSS = {CROSS_NS, CROSS_EW};
    private static final double[][] SHAPE_SMALL_CROSS = {SMALL_CROSS_NS, SMALL_CROSS_EW};
    private static final double[][] SHAPE_THIN = {THIN_POST};
    private static final double[][] SHAPE_TORCH = {TORCH_POST};
    private static final double[][] SHAPE_SLAB = {SLAB_BOTTOM};
    private static final double[][] SHAPE_STAIRS = {STAIRS_APPROX};
    private static final double[][] SHAPE_CARPET = {CARPET_LAYER};
    private static final double[][] SHAPE_THIN_FLOOR = {THIN_FLOOR_LAYER};
    private static final double[][] SHAPE_BUTTON = {BUTTON_BOX};
    private static final double[][] SHAPE_TRAPDOOR = {TRAPDOOR_PLATE};
    private static final double[][] SHAPE_WALL_PLATE = {WALL_PLATE};
    private static final double[][] SHAPE_SIGN = {SIGN_BOARD, SIGN_POST};
    private static final double[][] SHAPE_BED = {BED_BOX};
    private static final double[][] SHAPE_ENCHANTING_TABLE = {ENCHANTING_TABLE_BOX};
    private static final double[][] SHAPE_SNOW_LAYER = {SNOW_LAYER_BOX};
    private static final double[][] SHAPE_STONECUTTER = {STONECUTTER_BOX};
    private static final double[][] SHAPE_CANDLE = {CANDLE_BOX};
    private static final double[][] SHAPE_BAMBOO = {BAMBOO_POST};
    private static final double[][] SHAPE_AMETHYST = {AMETHYST_BOX};
    private static final double[][] SHAPE_DRIPSTONE = {DRIPSTONE_BOX};
    private static final double[][] SHAPE_CHEST = {CHEST_BOX};
    private static final double[][] SHAPE_CAMPFIRE = {CAMPFIRE_BOX};
    private static final double[][] SHAPE_LANTERN = {LANTERN_BOX};
    private static final double[][] SHAPE_END_PORTAL_FRAME = {END_PORTAL_FRAME_BOX};
    private static final double[][] SHAPE_SKULL = {SKULL_BOX};
    private static final double[][] SHAPE_FLOWER_POT = {FLOWER_POT_BOX};
    private static final double[][] SHAPE_DAYLIGHT = {DAYLIGHT_BOX};
    private static final double[][] SHAPE_CACTUS = {CACTUS_BOX};
    private static final double[][] SHAPE_ANVIL = {ANVIL_BOX};
    private static final double[][] SHAPE_INSET_FULL = {INSET_FULL_BOX};
    private static final double[][] SHAPE_HOPPER = {HOPPER_BOX, HOPPER_NECK};
    private static final double[][] SHAPE_CAULDRON = {CAULDRON_BOX};
    private static final double[][] SHAPE_BREWING_STAND = {BREWING_STAND_BASE, BREWING_STAND_POST};
    /**
     * Default shape for each material indexed by ordinal, or {@code null} for full blocks.
     */
    private static final double[][][] SHAPES = new double[XMaterial.values().length][][];
    /**
     * Cache for shapes resolved from block state properties (slabs, stairs, trapdoors, etc.).
     * Keyed by {@code (ordinal << 32) | hashCode}. Volatile for atomic swap eviction.
     */
    private static volatile Map<Long, double[][]> STATE_SHAPE_CACHE = new ConcurrentHashMap<>();

    // Assigns shapes to materials based on name patterns.
    // The static initializer iterates all XMaterial values and maps each one to
    // the appropriate shape template by matching name suffixes and specific names.
    static {
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();

            // Fences (not gates)
            if (name.endsWith("_FENCE") && !name.contains("GATE"))
                SHAPES[mat.ordinal()] = SHAPE_FENCE;
            // Walls
            else if (name.endsWith("_WALL"))
                SHAPES[mat.ordinal()] = SHAPE_WALL;
            // Iron bars and glass panes
            else if (name.equals("IRON_BARS") || name.endsWith("_PANE"))
                SHAPES[mat.ordinal()] = SHAPE_CROSS;
            // Flora / coral fans / fungi
            else if (name.endsWith("_SAPLING") || name.endsWith("_FLOWER")
                    || name.equals("DANDELION") || name.equals("POPPY")
                    || name.equals("BLUE_ORCHID") || name.equals("ALLIUM")
                    || name.equals("AZURE_BLUET") || name.equals("OXEYE_DAISY")
                    || name.equals("CORNFLOWER") || name.equals("LILY_OF_THE_VALLEY")
                    || name.equals("WITHER_ROSE") || name.equals("TORCHFLOWER")
                    || name.equals("SHORT_GRASS") || name.equals("TALL_GRASS")
                    || name.equals("FERN") || name.equals("LARGE_FERN")
                    || name.equals("DEAD_BUSH") || name.endsWith("_TULIP")
                    || name.endsWith("_MUSHROOM") || name.endsWith("_FUNGUS")
                    || name.endsWith("_ROOTS") || name.endsWith("_CORAL")
                    || name.endsWith("_CORAL_FAN") || name.endsWith("_SEAGRASS"))
                SHAPES[mat.ordinal()] = SHAPE_SMALL_CROSS;
            // Thin posts: chains, end rods, lightning rods
            else if (name.equals("CHAIN") || name.equals("END_ROD") || name.equals("LIGHTNING_ROD"))
                SHAPES[mat.ordinal()] = SHAPE_THIN;
            // Torches
            else if (name.equals("TORCH") || name.equals("SOUL_TORCH") || name.equals("REDSTONE_TORCH"))
                SHAPES[mat.ordinal()] = SHAPE_TORCH;
            // Slabs (bottom half)
            else if (name.endsWith("_SLAB"))
                SHAPES[mat.ordinal()] = SHAPE_SLAB;
            // Stairs (3/4 height)
            else if (name.endsWith("_STAIRS"))
                SHAPES[mat.ordinal()] = SHAPE_STAIRS;
            // Carpet
            else if (name.endsWith("_CARPET"))
                SHAPES[mat.ordinal()] = SHAPE_CARPET;
            // Trapdoors
            else if (name.endsWith("_TRAPDOOR"))
                SHAPES[mat.ordinal()] = SHAPE_TRAPDOOR;
            // Thin floor blocks
            else if (name.endsWith("_PRESSURE_PLATE") || name.equals("RAIL")
                    || name.equals("POWERED_RAIL") || name.equals("DETECTOR_RAIL")
                    || name.equals("ACTIVATOR_RAIL") || name.equals("STRING"))
                SHAPES[mat.ordinal()] = SHAPE_THIN_FLOOR;
            // Buttons
            else if (name.endsWith("_BUTTON"))
                SHAPES[mat.ordinal()] = SHAPE_BUTTON;
            // Ladders / vines / wall torches / wall signs
            else if (name.equals("LADDER") || name.equals("VINE")
                    || name.equals("WALL_TORCH") || name.equals("SOUL_WALL_TORCH")
                    || name.equals("REDSTONE_WALL_TORCH")
                    || name.endsWith("_WALL_SIGN") || name.endsWith("_WALL_HANGING_SIGN"))
                SHAPES[mat.ordinal()] = SHAPE_WALL_PLATE;
            // Standing signs / hanging signs
            else if (name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN"))
                SHAPES[mat.ordinal()] = SHAPE_SIGN;
            // Doors (thin cross — no orientation data available)
            else if (name.endsWith("_DOOR"))
                SHAPES[mat.ordinal()] = SHAPE_CROSS;
            // Fence gates (thin cross — no orientation data available)
            else if (name.endsWith("_FENCE_GATE"))
                SHAPES[mat.ordinal()] = SHAPE_CROSS;
            // Beds
            else if (name.endsWith("_BED"))
                SHAPES[mat.ordinal()] = SHAPE_BED;
            // Candles (colored + plain)
            else if (name.endsWith("_CANDLE") || name.equals("CANDLE"))
                SHAPES[mat.ordinal()] = SHAPE_CANDLE;
            // Skulls and heads (exclude piston head)
            else if ((name.endsWith("_HEAD") || name.endsWith("_SKULL")) && !name.equals("PISTON_HEAD"))
                SHAPES[mat.ordinal()] = SHAPE_SKULL;
            // Flower pots
            else if (name.startsWith("POTTED_") || name.equals("FLOWER_POT"))
                SHAPES[mat.ordinal()] = SHAPE_FLOWER_POT;
            // Amethyst clusters and buds
            else if (name.endsWith("_AMETHYST_BUD") || name.equals("AMETHYST_CLUSTER"))
                SHAPES[mat.ordinal()] = SHAPE_AMETHYST;
            // Anvils
            else if (name.equals("ANVIL") || name.equals("CHIPPED_ANVIL") || name.equals("DAMAGED_ANVIL"))
                SHAPES[mat.ordinal()] = SHAPE_ANVIL;
            // Specific named blocks
            else if (name.equals("ENCHANTING_TABLE"))
                SHAPES[mat.ordinal()] = SHAPE_ENCHANTING_TABLE;
            else if (name.equals("SNOW"))
                SHAPES[mat.ordinal()] = SHAPE_SNOW_LAYER;
            else if (name.equals("STONECUTTER"))
                SHAPES[mat.ordinal()] = SHAPE_STONECUTTER;
            else if (name.equals("BAMBOO"))
                SHAPES[mat.ordinal()] = SHAPE_BAMBOO;
            else if (name.equals("POINTED_DRIPSTONE"))
                SHAPES[mat.ordinal()] = SHAPE_DRIPSTONE;
            else if (name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("ENDER_CHEST"))
                SHAPES[mat.ordinal()] = SHAPE_CHEST;
            else if (name.equals("CAMPFIRE") || name.equals("SOUL_CAMPFIRE"))
                SHAPES[mat.ordinal()] = SHAPE_CAMPFIRE;
            else if (name.equals("LANTERN") || name.equals("SOUL_LANTERN"))
                SHAPES[mat.ordinal()] = SHAPE_LANTERN;
            else if (name.equals("END_PORTAL_FRAME"))
                SHAPES[mat.ordinal()] = SHAPE_END_PORTAL_FRAME;
            else if (name.equals("DAYLIGHT_DETECTOR"))
                SHAPES[mat.ordinal()] = SHAPE_DAYLIGHT;
            else if (name.equals("CACTUS"))
                SHAPES[mat.ordinal()] = SHAPE_CACTUS;
            else if (name.equals("BARREL") || name.equals("COMPOSTER")
                    || name.equals("BEEHIVE") || name.equals("BEE_NEST"))
                SHAPES[mat.ordinal()] = SHAPE_INSET_FULL;
            else if (name.equals("HOPPER"))
                SHAPES[mat.ordinal()] = SHAPE_HOPPER;
            else if (name.equals("CAULDRON"))
                SHAPES[mat.ordinal()] = SHAPE_CAULDRON;
            else if (name.equals("BREWING_STAND"))
                SHAPES[mat.ordinal()] = SHAPE_BREWING_STAND;
            else if (name.equals("SCULK_SENSOR") || name.equals("CALIBRATED_SCULK_SENSOR")
                    || name.equals("SCULK_SHRIEKER"))
                SHAPES[mat.ordinal()] = SHAPE_SLAB;
        }
    }

    /**
     * Returns the sub-block shape for the given material, or {@code null} for full blocks.
     */
    public static double[][] getShape(XMaterial material) {
        return SHAPES[material.ordinal()];
    }

    /**
     * Returns the context-sensitive sub-block shape for a block at the given position.
     * Considers neighbor connectivity (fences, walls, panes) and block state properties
     * (slab half, stair facing, trapdoor open state, etc.). Falls back to the default
     * shape for the material, or {@code null} for full blocks.
     *
     * @param scene    the scene for reading neighbors and block state
     * @param x        world X coordinate
     * @param y        world Y coordinate
     * @param z        world Z coordinate
     * @param material the block material
     * @return array of AABB boxes in voxel-local [0,1]^3 coordinates, or {@code null} for full blocks
     */
    public static double[][] getShape(SceneData scene, int x, int y, int z, XMaterial material) {
        double[][] shape = getConnectivityShape(scene, x, y, z, material);
        if (shape == null)
            shape = getStatefulShape(scene, x, y, z, material);
        return shape != null ? shape : SHAPES[material.ordinal()];
    }

    /**
     * Resolves the shape for blocks that connect to neighbors (fences, walls, glass panes).
     * Builds a bitmask of connected directions (north=1, east=2, south=4, west=8) and
     * generates the appropriate center post + arm boxes.
     *
     * @return the connectivity-based shape, or {@code null} if the block is not a connectable type
     */
    private static double[][] getConnectivityShape(SceneData scene, int x, int y, int z, XMaterial material) {
        int flags = BlockPalette.BLOCK_FLAGS[material.ordinal()];
        if ((flags & (BlockPalette.FLAG_PANE | BlockPalette.FLAG_FENCE | BlockPalette.FLAG_WALL)) == 0)
            return null;

        int mask = 0;
        if (connects(scene, x, y, z - 1, material))
            mask |= 1;
        if (connects(scene, x + 1, y, z, material))
            mask |= 2;
        if (connects(scene, x, y, z + 1, material))
            mask |= 4;
        if (connects(scene, x - 1, y, z, material))
            mask |= 8;

        String family = (flags & BlockPalette.FLAG_PANE) != 0 ? "pane"
                : (flags & BlockPalette.FLAG_FENCE) != 0 ? "fence" : "wall";
        String key = family + "|" + mask;
        double[][] cached = CONNECTIVITY_SHAPE_CACHE.get(key);
        if (cached != null)
            return cached;

        double[][] resolved = buildConnectivityShape(family, mask);
        CONNECTIVITY_SHAPE_CACHE.put(key, resolved);
        return resolved;
    }

    /**
     * Resolves the shape for blocks whose geometry depends on block state properties
     * (slab half, stair facing/half, trapdoor open state, door/gate orientation, etc.).
     *
     * @return the state-dependent shape, or {@code null} if no state-based shape applies
     */
    private static double[][] getStatefulShape(SceneData scene, int x, int y, int z, XMaterial material) {
        if ((BlockPalette.BLOCK_FLAGS[material.ordinal()] & BlockPalette.FLAG_NEEDS_STATE) == 0)
            return null;

        String blockState = scene.getBlockState(x, y, z);
        if (blockState == null || blockState.isEmpty())
            return null;

        long key = ((long) material.ordinal() << 32) | (blockState.hashCode() & 0xFFFFFFFFL);
        double[][] cached = STATE_SHAPE_CACHE.get(key);
        if (cached != null)
            return cached;

        BlockRenderState state = BlockRenderState.of(scene, x, y, z);
        double[][] resolved = buildStatefulShape(material, state);
        if (resolved != null) {
            if (STATE_SHAPE_CACHE.size() > MAX_STATE_SHAPE_CACHE_SIZE)
                STATE_SHAPE_CACHE = new ConcurrentHashMap<>();
            STATE_SHAPE_CACHE.put(key, resolved);
        }
        return resolved;
    }

    /**
     * Dispatches to the appropriate shape builder based on the block type suffix.
     */
    private static double[][] buildStatefulShape(XMaterial material, BlockRenderState state) {
        String name = material.name();
        if (name.endsWith("_SLAB"))
            return slabShape(state);
        if (name.endsWith("_STAIRS"))
            return stairsShape(state);
        if (name.endsWith("_TRAPDOOR"))
            return trapdoorShape(state);
        if (name.endsWith("_DOOR"))
            return doorShape(state);
        if (name.endsWith("_FENCE_GATE"))
            return fenceGateShape(state);
        if (name.equals("CHAIN"))
            return axisColumnShape(state.axis());
        if (name.equals("END_ROD") || name.equals("LIGHTNING_ROD"))
            return facingRodShape(state.facing());
        if (name.equals("LADDER") || name.equals("VINE")
                || name.equals("WALL_TORCH") || name.equals("SOUL_WALL_TORCH")
                || name.equals("REDSTONE_WALL_TORCH")
                || name.endsWith("_WALL_SIGN") || name.endsWith("_WALL_HANGING_SIGN"))
            return wallPlateShape(state.facing());
        if (name.endsWith("_SIGN") || name.endsWith("_HANGING_SIGN"))
            return signShape(state.facing(), state.rotation());
        if (name.equals("SNOW"))
            return snowShape(state.layers());
        return null;
    }

    private static boolean isPaneBlock(String name) {
        return name.endsWith("_PANE") || name.equals("IRON_BARS");
    }

    private static boolean isFenceBlock(String name) {
        return name.endsWith("_FENCE") && !name.contains("GATE");
    }

    private static boolean isWallBlock(String name) {
        return name.endsWith("_WALL");
    }

    /**
     * Checks whether a source block connects to its neighbor for shape connectivity.
     * Panes connect to other panes and opaque full blocks; fences connect to other fences,
     * fence gates, and opaque full blocks; walls connect to other walls and opaque full blocks.
     */
    private static boolean connects(SceneData scene, int x, int y, int z, XMaterial source) {
        XMaterial neighbor = scene.getBlockType(x, y, z);
        if (neighbor == XMaterial.AIR || BlockPalette.getColor(neighbor) == -1)
            return false;

        int sourceFlags = BlockPalette.BLOCK_FLAGS[source.ordinal()];
        int neighborFlags = BlockPalette.BLOCK_FLAGS[neighbor.ordinal()];
        if ((sourceFlags & BlockPalette.FLAG_PANE) != 0)
            return (neighborFlags & BlockPalette.FLAG_PANE) != 0
                    || (BlockPalette.getAlpha(neighbor) >= 255 && BlockShape.getShape(neighbor) == null);
        if ((sourceFlags & BlockPalette.FLAG_FENCE) != 0)
            return (neighborFlags & BlockPalette.FLAG_FENCE) != 0
                    || neighbor.name().endsWith("_FENCE_GATE")
                    || (BlockPalette.getAlpha(neighbor) >= 255 && BlockShape.getShape(neighbor) == null);
        if ((sourceFlags & BlockPalette.FLAG_WALL) != 0)
            return (neighborFlags & BlockPalette.FLAG_WALL) != 0
                    || (BlockPalette.getAlpha(neighbor) >= 255 && BlockShape.getShape(neighbor) == null);
        return false;
    }

    /**
     * Dispatches to the appropriate connectivity box builder for the block family.
     */
    private static double[][] buildConnectivityShape(String family, int mask) {
        if ("pane".equals(family))
            return paneConnectivityBoxes(mask);
        if ("fence".equals(family))
            return fenceConnectivityBoxes(mask);
        return wallConnectivityBoxes(mask);
    }

    /**
     * Builds connectivity shape for glass panes and iron bars (thin 2px center + arms).
     */
    private static double[][] paneConnectivityBoxes(int mask) {
        return connectivityBoxes(mask, 7 / 16.0, 9 / 16.0, 7 / 16.0, 9 / 16.0, 1.0, true);
    }

    /**
     * Builds connectivity shape for fences (4px center post + shorter arms).
     */
    private static double[][] fenceConnectivityBoxes(int mask) {
        return connectivityBoxes(mask, 6 / 16.0, 10 / 16.0, 6 / 16.0, 10 / 16.0, 14 / 16.0, false);
    }

    /**
     * Builds connectivity shape for walls (8px center post + arms, with straight-line optimization).
     */
    private static double[][] wallConnectivityBoxes(int mask) {
        if (mask == 0)
            return new double[][]{WALL_POST};
        if (mask == (1 | 4))
            return new double[][]{{4 / 16.0, 0, 0, 12 / 16.0, 14 / 16.0, 1}};
        if (mask == (2 | 8))
            return new double[][]{{0, 0, 4 / 16.0, 1, 14 / 16.0, 12 / 16.0}};
        return connectivityBoxes(mask, 4 / 16.0, 12 / 16.0, 4 / 16.0, 12 / 16.0, 14 / 16.0, false);
    }

    /**
     * Builds a center post + directional arm boxes based on a 4-bit connectivity mask.
     * Bit 0=north, 1=east, 2=south, 3=west. If isolated and {@code crossWhenIsolated}
     * is true, generates a full cross shape (e.g., isolated glass pane).
     */
    private static double[][] connectivityBoxes(int mask,
                                                double centerMinX, double centerMaxX,
                                                double centerMinZ, double centerMaxZ,
                                                double armHeight,
                                                boolean crossWhenIsolated) {
        double[][] boxes = new double[5][];
        int count = 0;
        boxes[count++] = new double[]{centerMinX, 0, centerMinZ, centerMaxX, 1, centerMaxZ};
        if ((mask & 1) != 0)
            boxes[count++] = new double[]{centerMinX, 0, 0, centerMaxX, armHeight, centerMinZ};
        if ((mask & 2) != 0)
            boxes[count++] = new double[]{centerMaxX, 0, centerMinZ, 1, armHeight, centerMaxZ};
        if ((mask & 4) != 0)
            boxes[count++] = new double[]{centerMinX, 0, centerMaxZ, centerMaxX, armHeight, 1};
        if ((mask & 8) != 0)
            boxes[count++] = new double[]{0, 0, centerMinZ, centerMinX, armHeight, centerMaxZ};
        if (count == 1 && crossWhenIsolated) {
            boxes[count++] = new double[]{centerMinX, 0, 0, centerMaxX, armHeight, 1};
            boxes[count++] = new double[]{0, 0, centerMinZ, 1, armHeight, centerMaxZ};
        }

        double[][] resolved = new double[count][];
        System.arraycopy(boxes, 0, resolved, 0, count);
        return resolved;
    }

    /**
     * Returns the slab shape based on half (top/bottom) and type (double = full block).
     */
    private static double[][] slabShape(BlockRenderState state) {
        if ("top".equals(state.half()))
            return new double[][]{SLAB_TOP};
        if ("double".equals(state.type()))
            return null;
        return new double[][]{SLAB_BOTTOM};
    }

    /**
     * Returns the two-box stair shape: a half slab plus a step in the facing direction.
     */
    private static double[][] stairsShape(BlockRenderState state) {
        String facing = state.facing();
        boolean top = "top".equals(state.half());

        if (top)
            return new double[][]{STAIRS_TOP_BASE, topStepForFacing(facing)};
        return new double[][]{STAIRS_BOTTOM_BASE, bottomStepForFacing(facing)};
    }

    private static double[] bottomStepForFacing(String facing) {
        if ("south".equals(facing))
            return STAIRS_BOTTOM_STEP_SOUTH;
        if ("west".equals(facing))
            return STAIRS_BOTTOM_STEP_WEST;
        if ("east".equals(facing))
            return STAIRS_BOTTOM_STEP_EAST;
        return STAIRS_BOTTOM_STEP_NORTH;
    }

    private static double[] topStepForFacing(String facing) {
        if ("south".equals(facing))
            return STAIRS_TOP_STEP_SOUTH;
        if ("west".equals(facing))
            return STAIRS_TOP_STEP_WEST;
        if ("east".equals(facing))
            return STAIRS_TOP_STEP_EAST;
        return STAIRS_TOP_STEP_NORTH;
    }

    /**
     * Returns the trapdoor shape: flat plate when closed, wall plate when open.
     */
    private static double[][] trapdoorShape(BlockRenderState state) {
        if (!state.open())
            return new double[][]{"top".equals(state.half()) ? TRAPDOOR_TOP : TRAPDOOR_PLATE};

        String facing = state.facing();
        if ("south".equals(facing))
            return new double[][]{TRAPDOOR_SOUTH};
        if ("west".equals(facing))
            return new double[][]{TRAPDOOR_WEST};
        if ("east".equals(facing))
            return new double[][]{TRAPDOOR_EAST};
        return new double[][]{TRAPDOOR_NORTH};
    }

    /**
     * Returns the door shape as a thin wall plate on the facing side.
     */
    private static double[][] doorShape(BlockRenderState state) {
        return wallPlateShape(state.facing());
    }

    /**
     * Returns the fence gate shape as a thin panel aligned to the facing axis.
     */
    private static double[][] fenceGateShape(BlockRenderState state) {
        if ("east".equals(state.facing()) || "west".equals(state.facing()))
            return new double[][]{{0, 0, 7 / 16.0, 1, 1, 9 / 16.0}};
        return new double[][]{{7 / 16.0, 0, 0, 9 / 16.0, 1, 1}};
    }

    /**
     * Returns a thin column shape rotated to the specified axis (for chains).
     */
    private static double[][] axisColumnShape(String axis) {
        if ("x".equals(axis))
            return new double[][]{{0, 6 / 16.0, 6 / 16.0, 1, 10 / 16.0, 10 / 16.0}};
        if ("z".equals(axis))
            return new double[][]{{6 / 16.0, 6 / 16.0, 0, 10 / 16.0, 10 / 16.0, 1}};
        return new double[][]{{6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 1, 10 / 16.0}};
    }

    /**
     * Returns a thin rod shape oriented along the facing direction (end rods, lightning rods).
     */
    private static double[][] facingRodShape(String facing) {
        if ("up".equals(facing) || "down".equals(facing))
            return new double[][]{THIN_POST};
        if ("east".equals(facing) || "west".equals(facing))
            return new double[][]{{0, 7 / 16.0, 7 / 16.0, 1, 9 / 16.0, 9 / 16.0}};
        return new double[][]{{7 / 16.0, 7 / 16.0, 0, 9 / 16.0, 9 / 16.0, 1}};
    }

    /**
     * Returns a flat wall plate shape on the specified facing side (ladders, wall torches, wall signs).
     */
    private static double[][] wallPlateShape(String facing) {
        if ("south".equals(facing))
            return new double[][]{WALL_PLATE_SOUTH};
        if ("west".equals(facing))
            return new double[][]{WALL_PLATE_WEST};
        if ("east".equals(facing))
            return new double[][]{WALL_PLATE_EAST};
        return new double[][]{WALL_PLATE_NORTH};
    }

    /**
     * Returns the sign shape (board + post) oriented by facing or rotation value.
     * Standing signs use the rotation (0–15) to determine cardinal direction.
     */
    private static double[][] signShape(String facing, int rotation) {
        if (rotation >= 0)
            if (rotation >= 2 && rotation <= 5)
                facing = "west";
            else if (rotation >= 6 && rotation <= 9)
                facing = "north";
            else if (rotation >= 10 && rotation <= 13)
                facing = "east";
            else
                facing = "south";

        if ("south".equals(facing))
            return new double[][]{SIGN_BOARD_SOUTH, SIGN_POST};
        if ("west".equals(facing))
            return new double[][]{SIGN_BOARD_WEST, SIGN_POST};
        if ("east".equals(facing))
            return new double[][]{SIGN_BOARD_EAST, SIGN_POST};
        return new double[][]{SIGN_BOARD_NORTH, SIGN_POST};
    }

    /**
     * Returns a flat box shape whose height scales with the number of snow layers (1–8).
     */
    private static double[][] snowShape(int layers) {
        double height = Math.max(1, Math.min(8, layers)) / 8.0;
        return new double[][]{{0, 0, 0, 1, height, 1}};
    }

    /**
     * Returns the current number of state shape cache entries. Package-visible for testing.
     */
    static int stateShapeCacheSize() {
        return STATE_SHAPE_CACHE.size();
    }

    /**
     * Replaces the state shape cache with a fresh empty map. Package-visible for testing.
     */
    static void clearStateShapeCache() {
        STATE_SHAPE_CACHE = new ConcurrentHashMap<>();
    }

}

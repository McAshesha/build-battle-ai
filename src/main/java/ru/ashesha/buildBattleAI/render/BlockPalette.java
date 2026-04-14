package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import lombok.experimental.UtilityClass;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.Arrays;

/**
 * Maps every {@link XMaterial} to its render properties: base color, alpha (opacity),
 * and emissive flag. This is the renderer's "texture atlas" — each block is represented
 * by a single flat color rather than a full texture.
 * <p>
 * The palette is organized in three parallel arrays indexed by {@link XMaterial#ordinal()}:
 * <ul>
 *     <li>{@code COLORS} — 24-bit RGB base color, or {@code -1} (transparent/invisible)</li>
 *     <li>{@code ALPHAS} — opacity 0–255 (255 = fully opaque, lower = translucent)</li>
 *     <li>{@code EMISSIVE} — whether the block bypasses face shading (always full brightness)</li>
 * </ul>
 * Colors are assigned in three tiers:
 * <ol>
 *     <li>Explicit registration in the static initializer (highest priority)</li>
 *     <li>Name-based inference from dye colors, wood families, stone variants, etc.</li>
 *     <li>Hash-based fallback for completely unmapped materials</li>
 * </ol>
 * Provides context-sensitive color via {@link #getColor(SceneData, int, int, int, XMaterial, int, double, double, double)}
 * for blocks with face-dependent textures (grass block top/side, logs end/side, furnace front, etc.).
 * <p>
 * All methods are thread-safe and can be called from any thread.
 */
@SuppressWarnings("DataFlowIssue")
@UtilityClass
public class BlockPalette {

    /**
     * Bit flag: block has an orientation axis (logs, pillars, chains, stems, hyphae).
     */
    static final int FLAG_AXIS_BLOCK = 1;
    /**
     * Bit flag: block has a distinct front face (furnace, observer, dispenser, carved pumpkin, etc.).
     */
    static final int FLAG_FACING_FRONT = 1 << 1;
    /**
     * Bit flag: glass pane or iron bars (connects to neighbors via pane connectivity).
     */
    static final int FLAG_PANE = 1 << 2;
    /**
     * Bit flag: fence post, not gates (connects to neighbors via fence connectivity).
     */
    static final int FLAG_FENCE = 1 << 3;
    /**
     * Bit flag: wall block (connects to neighbors via wall connectivity).
     */
    static final int FLAG_WALL = 1 << 4;
    /**
     * Bit flag: block whose shape depends on block state (slabs, stairs, trapdoors, doors, gates, signs, snow, chains, end rods, ladders, vines, wall torches).
     */
    static final int FLAG_NEEDS_STATE = 1 << 5;
    /**
     * Pre-computed per-material flag bits, indexed by {@link XMaterial#ordinal()}. Eliminates runtime string checks in the render hot path.
     */
    static final int[] BLOCK_FLAGS = new int[XMaterial.values().length];
    /**
     * Sentinel value indicating the block is fully transparent and should not be rendered.
     */
    private static final int TRANSPARENT = -1;
    /**
     * Sentinel value used during initialization for materials not yet assigned a color.
     */
    private static final int NOT_MAPPED = -2;
    /**
     * Base RGB color for each material, indexed by ordinal. -1 = transparent.
     */
    private static final int[] COLORS = new int[XMaterial.values().length];
    /**
     * Opacity for each material (0–255), indexed by ordinal. Default = 255 (opaque).
     */
    private static final int[] ALPHAS = new int[XMaterial.values().length];
    /**
     * Whether each material emits light and bypasses face shading.
     */
    private static final boolean[] EMISSIVE = new boolean[XMaterial.values().length];
    /**
     * Prefix strings for Minecraft's 16 dye colors, used for name-based color inference.
     */
    private static final String[] DYE_PREFIXES = {
            "LIGHT_BLUE_", "LIGHT_GRAY_",
            "MAGENTA_", "ORANGE_", "YELLOW_", "PURPLE_",
            "GREEN_", "BROWN_", "BLACK_", "WHITE_",
            "LIME_", "PINK_", "GRAY_", "CYAN_", "BLUE_", "RED_"
    };

    /**
     * Corresponding RGB colors for each dye prefix — standard Minecraft dye item colors.
     * Used as default for wool, beds, banners, candles, and stained glass.
     */
    private static final int[] DYE_COLORS = {
            0x3AB3DA, 0x9D9D97,  // light_blue, light_gray
            0xC74EBD, 0xF9801D, 0xFED83D, 0x8932B8,  // magenta, orange, yellow, purple
            0x5E7C16, 0x835432, 0x1D1D21, 0xF9FFFE,  // green, brown, black, white
            0x80C71F, 0xF38BAA, 0x474F52, 0x169C9C, 0x3C44AA, 0xB02E26  // lime, pink, gray, cyan, blue, red
    };

    /**
     * Concrete colors — darker and more saturated than wool.
     * Same index order as {@link #DYE_PREFIXES}.
     */
    private static final int[] CONCRETE_COLORS = {
            0x2389C7, 0x7D7D73,  // light_blue, light_gray
            0xA9309F, 0xE06101, 0xF0AF15, 0x7B2FBE,  // magenta, orange, yellow, purple
            0x495B24, 0x603C20, 0x080A0F, 0xCFD5D6,  // green, brown, black, white
            0x5EA818, 0xD5658F, 0x363A3E, 0x157788, 0x2C2E8F, 0x8E2121  // lime, pink, gray, cyan, blue, red
    };

    /**
     * Concrete powder colors — softer/more pastel than concrete.
     * Same index order as {@link #DYE_PREFIXES}.
     */
    private static final int[] CONCRETE_POWDER_COLORS = {
            0x4AB4D5, 0x9A9A8E,  // light_blue, light_gray
            0xC074BB, 0xE38429, 0xE8C736, 0x8337B3,  // magenta, orange, yellow, purple
            0x61772D, 0x7A5535, 0x1B1D22, 0xE1E3E3,  // green, brown, black, white
            0x7DB836, 0xE39AB4, 0x4D5155, 0x24939B, 0x4649A6, 0xA33535  // lime, pink, gray, cyan, blue, red
    };

    /**
     * Terracotta (glazed and stained) colors — earthy, muted tones from Minecraft's MapColor system.
     * These are completely different from dye colors.
     * Same index order as {@link #DYE_PREFIXES}.
     */
    private static final int[] TERRACOTTA_COLORS = {
            0x706C8A, 0x876B62,  // light_blue, light_gray
            0x95576C, 0x9F5224, 0xBA8524, 0x7A4958,  // magenta, orange, yellow, purple
            0x4C522A, 0x4C3223, 0x251610, 0xD1B1A1,  // green, brown, black, white
            0x677535, 0xA04D4E, 0x392923, 0x575C5C, 0x4C3E5C, 0x8E3C2E  // lime, pink, gray, cyan, blue, red
    };

    static {
        Arrays.fill(COLORS, NOT_MAPPED);
        Arrays.fill(ALPHAS, 255);

        // Air
        put(XMaterial.AIR, TRANSPARENT);
        put(XMaterial.VOID_AIR, TRANSPARENT);
        put(XMaterial.CAVE_AIR, TRANSPARENT);

        // Transparent / non-solid blocks (iterate runtime materials by name)
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.equals("LEVER") || name.equals("TRIPWIRE") || name.equals("TRIPWIRE_HOOK")
                    || name.equals("REDSTONE_WIRE") || name.equals("REPEATER") || name.equals("COMPARATOR")
                    || name.equals("SUGAR_CANE") || name.equals("LILY_PAD")
                    || name.equals("FIRE") || name.equals("SOUL_FIRE")
                    || name.equals("COBWEB") || name.equals("STRUCTURE_VOID")
                    || name.equals("BARRIER") || name.equals("LIGHT"))
                put(mat, TRANSPARENT);
        }

        // Stone family
        put(XMaterial.STONE, 0x7D7D7D);
        put(XMaterial.COBBLESTONE, 0x6B6B6B);
        put(XMaterial.STONE_BRICKS, 0x7A7A7A);
        put(XMaterial.MOSSY_STONE_BRICKS, 0x6B7A5C);
        put(XMaterial.CRACKED_STONE_BRICKS, 0x767676);
        put(XMaterial.CHISELED_STONE_BRICKS, 0x787878);
        put(XMaterial.SMOOTH_STONE, 0x9E9E9E);
        put(XMaterial.STONE_BRICK_SLAB, 0x7A7A7A);
        put(XMaterial.STONE_SLAB, 0x9E9E9E);
        put(XMaterial.COBBLESTONE_SLAB, 0x6B6B6B);
        put(XMaterial.COBBLESTONE_WALL, 0x6B6B6B);
        put(XMaterial.STONE_BRICK_WALL, 0x7A7A7A);
        put(XMaterial.STONE_BRICK_STAIRS, 0x7A7A7A);
        put(XMaterial.COBBLESTONE_STAIRS, 0x6B6B6B);
        put(XMaterial.MOSSY_COBBLESTONE, 0x6A7D52);
        put(XMaterial.MOSSY_COBBLESTONE_WALL, 0x6A7D52);
        put(XMaterial.MOSSY_COBBLESTONE_STAIRS, 0x6A7D52);
        put(XMaterial.MOSSY_COBBLESTONE_SLAB, 0x6A7D52);
        put(XMaterial.ANDESITE, 0x888888);
        put(XMaterial.POLISHED_ANDESITE, 0x848484);
        put(XMaterial.DIORITE, 0xBCBCBC);
        put(XMaterial.POLISHED_DIORITE, 0xC4C4C4);
        put(XMaterial.GRANITE, 0x9A6C50);
        put(XMaterial.POLISHED_GRANITE, 0x9A6959);

        // Deepslate
        put(XMaterial.DEEPSLATE, 0x505050);
        put(XMaterial.COBBLED_DEEPSLATE, 0x4D4D4D);
        put(XMaterial.DEEPSLATE_BRICKS, 0x464646);
        put(XMaterial.DEEPSLATE_TILES, 0x3C3C3C);
        put(XMaterial.POLISHED_DEEPSLATE, 0x484848);
        put(XMaterial.CHISELED_DEEPSLATE, 0x3A3A3A);
        put(XMaterial.DEEPSLATE_BRICK_SLAB, 0x464646);
        put(XMaterial.DEEPSLATE_TILE_SLAB, 0x3C3C3C);
        put(XMaterial.DEEPSLATE_BRICK_STAIRS, 0x464646);
        put(XMaterial.DEEPSLATE_TILE_STAIRS, 0x3C3C3C);
        put(XMaterial.DEEPSLATE_BRICK_WALL, 0x464646);
        put(XMaterial.DEEPSLATE_TILE_WALL, 0x3C3C3C);

        // Tuff / Calcite
        put(XMaterial.TUFF, 0x6C6C5E);
        put(XMaterial.TUFF_BRICKS, 0x6A6A5A);
        put(XMaterial.CALCITE, 0xDDDCC8);

        // Earth / Dirt
        put(XMaterial.GRASS_BLOCK, 0x7CBD6B);
        put(XMaterial.DIRT, 0x866043);
        put(XMaterial.COARSE_DIRT, 0x77553B);
        put(XMaterial.ROOTED_DIRT, 0x866043);
        put(XMaterial.PODZOL, 0x6B5B28);
        put(XMaterial.MYCELIUM, 0x6B6168);
        put(XMaterial.MUD, 0x3C3832);
        put(XMaterial.PACKED_MUD, 0x8E7560);
        put(XMaterial.MUD_BRICKS, 0x897158);
        put(XMaterial.CLAY, 0x9EA5B1);
        put(XMaterial.GRAVEL, 0x838080);
        put(XMaterial.FARMLAND, 0x6B4826);
        put(XMaterial.DIRT_PATH, 0x946B2D);
        put(XMaterial.MOSS_BLOCK, 0x4D6B28);
        put(XMaterial.MOSS_CARPET, 0x4D6B28);

        // Sand
        put(XMaterial.SAND, 0xDBCFA3);
        put(XMaterial.RED_SAND, 0xBE6621);
        put(XMaterial.SANDSTONE, 0xD8CB8A);
        put(XMaterial.CHISELED_SANDSTONE, 0xD8CB8A);
        put(XMaterial.CUT_SANDSTONE, 0xD8CB8A);
        put(XMaterial.SMOOTH_SANDSTONE, 0xDDD2A0);
        put(XMaterial.RED_SANDSTONE, 0xBA5A22);
        put(XMaterial.CHISELED_RED_SANDSTONE, 0xBA5A22);
        put(XMaterial.CUT_RED_SANDSTONE, 0xBA5A22);
        put(XMaterial.SMOOTH_RED_SANDSTONE, 0xC06428);
        put(XMaterial.SANDSTONE_STAIRS, 0xD8CB8A);
        put(XMaterial.SANDSTONE_SLAB, 0xD8CB8A);
        put(XMaterial.SANDSTONE_WALL, 0xD8CB8A);
        put(XMaterial.RED_SANDSTONE_STAIRS, 0xBA5A22);
        put(XMaterial.RED_SANDSTONE_SLAB, 0xBA5A22);
        put(XMaterial.RED_SANDSTONE_WALL, 0xBA5A22);

        // Soul
        put(XMaterial.SOUL_SAND, 0x514234);
        put(XMaterial.SOUL_SOIL, 0x4B3C2F);

        // Wood: Oak
        put(XMaterial.OAK_PLANKS, 0xA88654);
        put(XMaterial.OAK_LOG, 0x6B5438);
        put(XMaterial.STRIPPED_OAK_LOG, 0xB29155);
        put(XMaterial.OAK_WOOD, 0x6B5438);
        put(XMaterial.STRIPPED_OAK_WOOD, 0xB29155);
        put(XMaterial.OAK_SLAB, 0xA88654);
        put(XMaterial.OAK_STAIRS, 0xA88654);
        put(XMaterial.OAK_FENCE, 0xA88654);
        put(XMaterial.OAK_FENCE_GATE, 0xA88654);
        put(XMaterial.OAK_DOOR, 0x7E6535);
        put(XMaterial.OAK_TRAPDOOR, 0x7E6535);

        // Wood: Spruce
        put(XMaterial.SPRUCE_PLANKS, 0x734B2E);
        put(XMaterial.SPRUCE_LOG, 0x3B2814);
        put(XMaterial.STRIPPED_SPRUCE_LOG, 0x6E5028);
        put(XMaterial.SPRUCE_WOOD, 0x3B2814);
        put(XMaterial.STRIPPED_SPRUCE_WOOD, 0x6E5028);
        put(XMaterial.SPRUCE_SLAB, 0x734B2E);
        put(XMaterial.SPRUCE_STAIRS, 0x734B2E);
        put(XMaterial.SPRUCE_FENCE, 0x734B2E);
        put(XMaterial.SPRUCE_FENCE_GATE, 0x734B2E);
        put(XMaterial.SPRUCE_DOOR, 0x5C3A20);
        put(XMaterial.SPRUCE_TRAPDOOR, 0x5C3A20);

        // Wood: Birch
        put(XMaterial.BIRCH_PLANKS, 0xC8B77A);
        put(XMaterial.BIRCH_LOG, 0xD5CEA2);
        put(XMaterial.STRIPPED_BIRCH_LOG, 0xC8B276);
        put(XMaterial.BIRCH_WOOD, 0xD5CEA2);
        put(XMaterial.STRIPPED_BIRCH_WOOD, 0xC8B276);
        put(XMaterial.BIRCH_SLAB, 0xC8B77A);
        put(XMaterial.BIRCH_STAIRS, 0xC8B77A);
        put(XMaterial.BIRCH_FENCE, 0xC8B77A);
        put(XMaterial.BIRCH_FENCE_GATE, 0xC8B77A);
        put(XMaterial.BIRCH_DOOR, 0xD3C792);
        put(XMaterial.BIRCH_TRAPDOOR, 0xD3C792);

        // Wood: Jungle
        put(XMaterial.JUNGLE_PLANKS, 0xA0724B);
        put(XMaterial.JUNGLE_LOG, 0x59451B);
        put(XMaterial.STRIPPED_JUNGLE_LOG, 0xAB7E4F);
        put(XMaterial.JUNGLE_SLAB, 0xA0724B);
        put(XMaterial.JUNGLE_STAIRS, 0xA0724B);
        put(XMaterial.JUNGLE_FENCE, 0xA0724B);
        put(XMaterial.JUNGLE_FENCE_GATE, 0xA0724B);
        put(XMaterial.JUNGLE_DOOR, 0x8C5F38);
        put(XMaterial.JUNGLE_TRAPDOOR, 0x8C5F38);

        // Wood: Acacia
        put(XMaterial.ACACIA_PLANKS, 0xA85A2C);
        put(XMaterial.ACACIA_LOG, 0x676259);
        put(XMaterial.STRIPPED_ACACIA_LOG, 0xAD5D30);
        put(XMaterial.ACACIA_SLAB, 0xA85A2C);
        put(XMaterial.ACACIA_STAIRS, 0xA85A2C);
        put(XMaterial.ACACIA_FENCE, 0xA85A2C);
        put(XMaterial.ACACIA_FENCE_GATE, 0xA85A2C);
        put(XMaterial.ACACIA_DOOR, 0x8E4A20);
        put(XMaterial.ACACIA_TRAPDOOR, 0x8E4A20);

        // Wood: Dark Oak
        put(XMaterial.DARK_OAK_PLANKS, 0x422B12);
        put(XMaterial.DARK_OAK_LOG, 0x3E2D0C);
        put(XMaterial.STRIPPED_DARK_OAK_LOG, 0x604020);
        put(XMaterial.DARK_OAK_SLAB, 0x422B12);
        put(XMaterial.DARK_OAK_STAIRS, 0x422B12);
        put(XMaterial.DARK_OAK_FENCE, 0x422B12);
        put(XMaterial.DARK_OAK_FENCE_GATE, 0x422B12);
        put(XMaterial.DARK_OAK_DOOR, 0x4C3418);
        put(XMaterial.DARK_OAK_TRAPDOOR, 0x4C3418);

        // Wood: Cherry
        put(XMaterial.CHERRY_PLANKS, 0xE7C3B5);
        put(XMaterial.CHERRY_LOG, 0xD6A496);
        put(XMaterial.STRIPPED_CHERRY_LOG, 0xDFB1A3);
        put(XMaterial.CHERRY_SLAB, 0xE7C3B5);
        put(XMaterial.CHERRY_STAIRS, 0xE7C3B5);
        put(XMaterial.CHERRY_FENCE, 0xE7C3B5);
        put(XMaterial.CHERRY_FENCE_GATE, 0xE7C3B5);
        put(XMaterial.CHERRY_DOOR, 0xDFB1A3);
        put(XMaterial.CHERRY_TRAPDOOR, 0xDFB1A3);

        // Wood: Mangrove
        put(XMaterial.MANGROVE_PLANKS, 0x773535);
        put(XMaterial.MANGROVE_LOG, 0x5A4230);
        put(XMaterial.STRIPPED_MANGROVE_LOG, 0x6E3030);
        put(XMaterial.MANGROVE_SLAB, 0x773535);
        put(XMaterial.MANGROVE_STAIRS, 0x773535);
        put(XMaterial.MANGROVE_FENCE, 0x773535);
        put(XMaterial.MANGROVE_FENCE_GATE, 0x773535);
        put(XMaterial.MANGROVE_DOOR, 0x6B3030);
        put(XMaterial.MANGROVE_TRAPDOOR, 0x6B3030);

        // Wood: Bamboo
        put(XMaterial.BAMBOO_PLANKS, 0xC8B348);
        put(XMaterial.BAMBOO_BLOCK, 0x7C9A2E);
        put(XMaterial.STRIPPED_BAMBOO_BLOCK, 0xC8B348);
        put(XMaterial.BAMBOO_MOSAIC, 0xBDA740);
        put(XMaterial.BAMBOO_SLAB, 0xC8B348);
        put(XMaterial.BAMBOO_STAIRS, 0xC8B348);
        put(XMaterial.BAMBOO_FENCE, 0xC8B348);
        put(XMaterial.BAMBOO_FENCE_GATE, 0xC8B348);
        put(XMaterial.BAMBOO_DOOR, 0xC8B348);
        put(XMaterial.BAMBOO_TRAPDOOR, 0xC8B348);

        // Wood: Crimson / Warped (Nether)
        put(XMaterial.CRIMSON_PLANKS, 0x653046);
        put(XMaterial.CRIMSON_STEM, 0x6C2032);
        put(XMaterial.STRIPPED_CRIMSON_STEM, 0x8A3858);
        put(XMaterial.CRIMSON_SLAB, 0x653046);
        put(XMaterial.CRIMSON_STAIRS, 0x653046);
        put(XMaterial.CRIMSON_FENCE, 0x653046);
        put(XMaterial.CRIMSON_FENCE_GATE, 0x653046);
        put(XMaterial.CRIMSON_DOOR, 0x653046);
        put(XMaterial.CRIMSON_TRAPDOOR, 0x653046);
        put(XMaterial.WARPED_PLANKS, 0x2B6D5B);
        put(XMaterial.WARPED_STEM, 0x3A5B54);
        put(XMaterial.STRIPPED_WARPED_STEM, 0x3A8C72);
        put(XMaterial.WARPED_SLAB, 0x2B6D5B);
        put(XMaterial.WARPED_STAIRS, 0x2B6D5B);
        put(XMaterial.WARPED_FENCE, 0x2B6D5B);
        put(XMaterial.WARPED_FENCE_GATE, 0x2B6D5B);
        put(XMaterial.WARPED_DOOR, 0x2B6D5B);
        put(XMaterial.WARPED_TRAPDOOR, 0x2B6D5B);

        // Leaves
        put(XMaterial.OAK_LEAVES, 0x4A7A32);
        put(XMaterial.SPRUCE_LEAVES, 0x3C5C28);
        put(XMaterial.BIRCH_LEAVES, 0x6BAA36);
        put(XMaterial.JUNGLE_LEAVES, 0x347C24);
        put(XMaterial.ACACIA_LEAVES, 0x4A7A32);
        put(XMaterial.DARK_OAK_LEAVES, 0x3A6A22);
        put(XMaterial.AZALEA_LEAVES, 0x5E8330);
        put(XMaterial.FLOWERING_AZALEA_LEAVES, 0x6B8A38);
        put(XMaterial.CHERRY_LEAVES, 0xEDB5C8);
        put(XMaterial.MANGROVE_LEAVES, 0x4F8B34);

        // Ores & metal blocks
        put(XMaterial.IRON_BLOCK, 0xDCDCDC);
        put(XMaterial.GOLD_BLOCK, 0xF8D44E);
        put(XMaterial.DIAMOND_BLOCK, 0x62EDD8);
        put(XMaterial.EMERALD_BLOCK, 0x41C13E);
        put(XMaterial.LAPIS_BLOCK, 0x2456A8);
        put(XMaterial.REDSTONE_BLOCK, 0xAB1207);
        put(XMaterial.COAL_BLOCK, 0x101010);
        put(XMaterial.COPPER_BLOCK, 0xC06840);
        put(XMaterial.EXPOSED_COPPER, 0xA08258);
        put(XMaterial.WEATHERED_COPPER, 0x6C9862);
        put(XMaterial.OXIDIZED_COPPER, 0x52A384);
        put(XMaterial.NETHERITE_BLOCK, 0x434040);
        put(XMaterial.AMETHYST_BLOCK, 0x8664C5);
        put(XMaterial.RAW_IRON_BLOCK, 0xA48D6E);
        put(XMaterial.RAW_GOLD_BLOCK, 0xDCA134);
        put(XMaterial.RAW_COPPER_BLOCK, 0x9A6844);
        put(XMaterial.IRON_ORE, 0x8A8177);
        put(XMaterial.GOLD_ORE, 0x8A8468);
        put(XMaterial.DIAMOND_ORE, 0x7BA8A1);
        put(XMaterial.EMERALD_ORE, 0x6F8D6C);
        put(XMaterial.LAPIS_ORE, 0x6B7A99);
        put(XMaterial.REDSTONE_ORE, 0x8A6660);
        put(XMaterial.COAL_ORE, 0x636363);
        put(XMaterial.COPPER_ORE, 0x7D8067);

        // Glass
        put(XMaterial.GLASS, 0xC8E8F0);
        put(XMaterial.GLASS_PANE, 0xC8E8F0);
        put(XMaterial.TINTED_GLASS, 0x2D2024);

        // Bricks
        put(XMaterial.BRICKS, 0x966456);
        put(XMaterial.BRICK_SLAB, 0x966456);
        put(XMaterial.BRICK_STAIRS, 0x966456);
        put(XMaterial.BRICK_WALL, 0x966456);
        put(XMaterial.NETHER_BRICKS, 0x2D1520);
        put(XMaterial.NETHER_BRICK_SLAB, 0x2D1520);
        put(XMaterial.NETHER_BRICK_STAIRS, 0x2D1520);
        put(XMaterial.NETHER_BRICK_WALL, 0x2D1520);
        put(XMaterial.NETHER_BRICK_FENCE, 0x2D1520);
        put(XMaterial.RED_NETHER_BRICKS, 0x450101);
        put(XMaterial.RED_NETHER_BRICK_SLAB, 0x450101);
        put(XMaterial.RED_NETHER_BRICK_STAIRS, 0x450101);
        put(XMaterial.RED_NETHER_BRICK_WALL, 0x450101);

        // End blocks
        put(XMaterial.END_STONE, 0xDBDE9F);
        put(XMaterial.END_STONE_BRICKS, 0xDBE2A3);
        put(XMaterial.END_STONE_BRICK_SLAB, 0xDBE2A3);
        put(XMaterial.END_STONE_BRICK_STAIRS, 0xDBE2A3);
        put(XMaterial.END_STONE_BRICK_WALL, 0xDBE2A3);
        put(XMaterial.PURPUR_BLOCK, 0xA977A9);
        put(XMaterial.PURPUR_PILLAR, 0xAB84AB);
        put(XMaterial.PURPUR_SLAB, 0xA977A9);
        put(XMaterial.PURPUR_STAIRS, 0xA977A9);

        // Prismarine
        put(XMaterial.PRISMARINE, 0x63A58E);
        put(XMaterial.PRISMARINE_BRICKS, 0x63B69A);
        put(XMaterial.DARK_PRISMARINE, 0x336B52);
        put(XMaterial.PRISMARINE_SLAB, 0x63A58E);
        put(XMaterial.PRISMARINE_BRICK_SLAB, 0x63B69A);
        put(XMaterial.DARK_PRISMARINE_SLAB, 0x336B52);
        put(XMaterial.PRISMARINE_STAIRS, 0x63A58E);
        put(XMaterial.PRISMARINE_BRICK_STAIRS, 0x63B69A);
        put(XMaterial.DARK_PRISMARINE_STAIRS, 0x336B52);
        put(XMaterial.PRISMARINE_WALL, 0x63A58E);
        put(XMaterial.SEA_LANTERN, 0xACD8CC);

        // Quartz
        put(XMaterial.QUARTZ_BLOCK, 0xECE7DE);
        put(XMaterial.SMOOTH_QUARTZ, 0xECE7DE);
        put(XMaterial.CHISELED_QUARTZ_BLOCK, 0xE6E0D6);
        put(XMaterial.QUARTZ_BRICKS, 0xE8E2D8);
        put(XMaterial.QUARTZ_PILLAR, 0xEBE5DC);
        put(XMaterial.QUARTZ_SLAB, 0xECE7DE);
        put(XMaterial.QUARTZ_STAIRS, 0xECE7DE);
        put(XMaterial.SMOOTH_QUARTZ_SLAB, 0xECE7DE);
        put(XMaterial.SMOOTH_QUARTZ_STAIRS, 0xECE7DE);

        // Nether
        put(XMaterial.NETHERRACK, 0x6B2E2E);
        put(XMaterial.NETHER_GOLD_ORE, 0x7E4832);
        put(XMaterial.NETHER_QUARTZ_ORE, 0x6F3636);
        put(XMaterial.ANCIENT_DEBRIS, 0x654740);
        put(XMaterial.BASALT, 0x4E4E52);
        put(XMaterial.POLISHED_BASALT, 0x5A5A5E);
        put(XMaterial.SMOOTH_BASALT, 0x48484C);
        put(XMaterial.BLACKSTONE, 0x2C272E);
        put(XMaterial.POLISHED_BLACKSTONE, 0x353035);
        put(XMaterial.POLISHED_BLACKSTONE_BRICKS, 0x302B30);
        put(XMaterial.CHISELED_POLISHED_BLACKSTONE, 0x353035);
        put(XMaterial.GILDED_BLACKSTONE, 0x3C3434);
        put(XMaterial.GLOWSTONE, 0xAB8654);
        put(XMaterial.SHROOMLIGHT, 0xF09848);
        put(XMaterial.MAGMA_BLOCK, 0x8B3A18);
        put(XMaterial.WARPED_NYLIUM, 0x2B6B5A);
        put(XMaterial.CRIMSON_NYLIUM, 0x852020);
        put(XMaterial.WARPED_WART_BLOCK, 0x167A72);
        put(XMaterial.NETHER_WART_BLOCK, 0x730000);

        // Misc blocks
        put(XMaterial.BEDROCK, 0x333333);
        put(XMaterial.OBSIDIAN, 0x0D0716);
        put(XMaterial.CRYING_OBSIDIAN, 0x200631);
        put(XMaterial.BOOKSHELF, 0x735E3E);
        put(XMaterial.CHISELED_BOOKSHELF, 0x735E3E);
        put(XMaterial.NOTE_BLOCK, 0x6C3E2A);
        put(XMaterial.JUKEBOX, 0x6C3E2A);
        put(XMaterial.CRAFTING_TABLE, 0x7D5322);
        put(XMaterial.FURNACE, 0x8A8A8A);
        put(XMaterial.BLAST_FURNACE, 0x525252);
        put(XMaterial.SMOKER, 0x5E5E5E);
        put(XMaterial.TNT, 0xCC3333);
        put(XMaterial.SPONGE, 0xC2BC4A);
        put(XMaterial.WET_SPONGE, 0xA9B332);
        put(XMaterial.MELON, 0x6B9830);
        put(XMaterial.PUMPKIN, 0xC9820D);
        put(XMaterial.CARVED_PUMPKIN, 0xC9820D);
        put(XMaterial.JACK_O_LANTERN, 0xDBA013);
        put(XMaterial.HAY_BLOCK, 0xB5960C);
        put(XMaterial.BONE_BLOCK, 0xD1CCAC);
        put(XMaterial.HONEY_BLOCK, 0xE7A221);
        put(XMaterial.HONEYCOMB_BLOCK, 0xE5981E);
        put(XMaterial.SLIME_BLOCK, 0x6CC46A);
        put(XMaterial.DRIED_KELP_BLOCK, 0x333A22);
        put(XMaterial.SCULK, 0x0E1E24);
        put(XMaterial.SCULK_CATALYST, 0x0E2D2A);
        put(XMaterial.REDSTONE_LAMP, 0x6F4324);
        put(XMaterial.TERRACOTTA, 0x985E43);
        put(XMaterial.ENCHANTING_TABLE, 0x942020);
        put(XMaterial.CHEST, 0x6B4D30);
        put(XMaterial.TRAPPED_CHEST, 0x6B4D30);
        put(XMaterial.ENDER_CHEST, 0x0D1D24);
        put(XMaterial.ANVIL, 0x484848);
        put(XMaterial.CHIPPED_ANVIL, 0x484848);
        put(XMaterial.DAMAGED_ANVIL, 0x484848);
        put(XMaterial.LANTERN, 0x8A6A3E);
        put(XMaterial.SOUL_LANTERN, 0x486B70);
        put(XMaterial.CAMPFIRE, 0x6B5840);
        put(XMaterial.SOUL_CAMPFIRE, 0x3B5B5B);
        put(XMaterial.END_PORTAL_FRAME, 0x6D9A71);
        put(XMaterial.DAYLIGHT_DETECTOR, 0xB8A37E);
        put(XMaterial.CACTUS, 0x5B7C3E);
        put(XMaterial.CANDLE, 0xD5C9A4);
        put(XMaterial.BARREL, 0x6B5838);
        put(XMaterial.COMPOSTER, 0x6B5A34);
        put(XMaterial.CAULDRON, 0x4A4A4A);
        put(XMaterial.HOPPER, 0x4A4A4A);
        put(XMaterial.BREWING_STAND, 0x6A6A42);
        put(XMaterial.LECTERN, 0xAA8856);
        put(XMaterial.BEE_NEST, 0xC8A050);
        put(XMaterial.BEEHIVE, 0x9A7842);
        put(XMaterial.TARGET, 0xE0B0A0);
        put(XMaterial.OBSERVER, 0x6A6A6A);
        put(XMaterial.DROPPER, 0x7A7A7A);
        put(XMaterial.DISPENSER, 0x7A7A7A);
        put(XMaterial.PISTON, 0x9A8A5A);
        put(XMaterial.STICKY_PISTON, 0x8A9A4A);
        put(XMaterial.SPAWNER, 0x2A3640);
        put(XMaterial.BELL, 0xD8B83C);
        put(XMaterial.RESPAWN_ANCHOR, 0x2C0E3A);
        put(XMaterial.LODESTONE, 0x929292);
        put(XMaterial.DECORATED_POT, 0xA06040);

        // Thin / decorative blocks that used to be skipped entirely
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN")
                    || name.endsWith("_HANGING_SIGN") || name.endsWith("_WALL_HANGING_SIGN"))
                put(mat, inferWoodFamilyColor(name, 0xA88654));
            else if (name.equals("TORCH") || name.equals("WALL_TORCH"))
                put(mat, 0xB89242);
            else if (name.equals("SOUL_TORCH") || name.equals("SOUL_WALL_TORCH"))
                put(mat, 0x5C98B6);
            else if (name.equals("REDSTONE_TORCH") || name.equals("REDSTONE_WALL_TORCH"))
                put(mat, 0xC64A2B);
            else if (name.equals("LADDER"))
                put(mat, 0x9C7A44);
            else if (name.endsWith("_BUTTON"))
                put(mat, inferBaseColor(name) != null ? applyNameVariant(inferBaseColor(name), name) : 0x7D7D7D);
            else if (name.endsWith("_PRESSURE_PLATE"))
                put(mat, inferBaseColor(name) != null ? applyNameVariant(inferBaseColor(name), name) : 0x8A8A8A);
            else if (name.equals("RAIL") || name.equals("POWERED_RAIL")
                    || name.equals("DETECTOR_RAIL") || name.equals("ACTIVATOR_RAIL"))
                put(mat, name.equals("POWERED_RAIL") ? 0xC89E42 : 0x7A6A58);
            else if (name.endsWith("_SAPLING") || name.endsWith("_FLOWER")
                    || name.equals("DANDELION") || name.equals("POPPY")
                    || name.equals("BLUE_ORCHID") || name.equals("ALLIUM")
                    || name.equals("AZURE_BLUET") || name.equals("OXEYE_DAISY")
                    || name.equals("CORNFLOWER") || name.equals("LILY_OF_THE_VALLEY")
                    || name.equals("WITHER_ROSE") || name.equals("TORCHFLOWER")
                    || name.equals("SUNFLOWER") || name.equals("LILAC")
                    || name.equals("ROSE_BUSH") || name.equals("PEONY")
                    || name.equals("SHORT_GRASS") || name.equals("TALL_GRASS")
                    || name.equals("FERN") || name.equals("LARGE_FERN")
                    || name.equals("DEAD_BUSH") || name.endsWith("_TULIP"))
                put(mat, inferPlantColor(name));
            else if (name.equals("VINE"))
                put(mat, 0x4C7A34);
        }

        // Ice / Snow
        put(XMaterial.ICE, 0x91B3F3);
        put(XMaterial.PACKED_ICE, 0x8DAFE4);
        put(XMaterial.BLUE_ICE, 0x74A5DE);
        put(XMaterial.FROSTED_ICE, 0x91B3F3);
        put(XMaterial.SNOW_BLOCK, 0xF9FFFE);
        put(XMaterial.SNOW, 0xF9FFFE);
        put(XMaterial.POWDER_SNOW, 0xF9FFFE);

        // Fluids
        put(XMaterial.WATER, 0x2825F5);
        put(XMaterial.LAVA, 0xD05E10);

        // Corals (per-type)
        put(XMaterial.TUBE_CORAL_BLOCK, 0x3455D5);
        put(XMaterial.BRAIN_CORAL_BLOCK, 0xD1789A);
        put(XMaterial.BUBBLE_CORAL_BLOCK, 0xA61D89);
        put(XMaterial.FIRE_CORAL_BLOCK, 0xD82A20);
        put(XMaterial.HORN_CORAL_BLOCK, 0xD9C634);
        put(XMaterial.DEAD_TUBE_CORAL_BLOCK, 0x7E7567);
        put(XMaterial.DEAD_BRAIN_CORAL_BLOCK, 0x7E7567);
        put(XMaterial.DEAD_BUBBLE_CORAL_BLOCK, 0x7E7567);
        put(XMaterial.DEAD_FIRE_CORAL_BLOCK, 0x7E7567);
        put(XMaterial.DEAD_HORN_CORAL_BLOCK, 0x7E7567);

        // Additional blocks that don't match name-inference patterns well
        put(XMaterial.DRIPSTONE_BLOCK, 0x866B5C);
        put(XMaterial.GLOW_LICHEN, 0x7FA796);
        put(XMaterial.SCULK_VEIN, 0x052127);
        put(XMaterial.SCULK_SHRIEKER, 0x0E2924);

        // Froglight variants
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.equals("OCHRE_FROGLIGHT"))
                put(mat, 0xE8D68C);
            else if (name.equals("VERDANT_FROGLIGHT"))
                put(mat, 0x6BBE6B);
            else if (name.equals("PEARLESCENT_FROGLIGHT"))
                put(mat, 0xC5A3C8);
        }

        // Mangrove extras
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.equals("MANGROVE_ROOTS"))
                put(mat, 0x4E3728);
            else if (name.equals("MUDDY_MANGROVE_ROOTS"))
                put(mat, 0x4A3F2F);
        }

        // Fill remaining via name-based inference
        for (XMaterial mat : XMaterial.values())
            if (COLORS[mat.ordinal()] == NOT_MAPPED)
                COLORS[mat.ordinal()] = inferFromName(mat.name());

        // === Alpha values (translucency) ===
        putAlpha(XMaterial.GLASS, 100);
        putAlpha(XMaterial.GLASS_PANE, 100);
        putAlpha(XMaterial.WATER, 150);
        putAlpha(XMaterial.ICE, 180);
        putAlpha(XMaterial.FROSTED_ICE, 180);

        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.endsWith("_STAINED_GLASS") || name.endsWith("_STAINED_GLASS_PANE"))
                ALPHAS[mat.ordinal()] = 120;
            else if (name.endsWith("_LEAVES"))
                ALPHAS[mat.ordinal()] = 220;
            else if (name.equals("VINE") || name.endsWith("_SAPLING")
                    || name.endsWith("_FLOWER") || name.equals("DANDELION")
                    || name.equals("POPPY") || name.equals("BLUE_ORCHID")
                    || name.equals("ALLIUM") || name.equals("AZURE_BLUET")
                    || name.equals("OXEYE_DAISY") || name.equals("CORNFLOWER")
                    || name.equals("LILY_OF_THE_VALLEY") || name.equals("WITHER_ROSE")
                    || name.equals("TORCHFLOWER") || name.equals("SUNFLOWER")
                    || name.equals("LILAC") || name.equals("ROSE_BUSH")
                    || name.equals("PEONY") || name.equals("SHORT_GRASS")
                    || name.equals("TALL_GRASS") || name.equals("FERN")
                    || name.equals("LARGE_FERN") || name.equals("DEAD_BUSH")
                    || name.endsWith("_TULIP"))
                ALPHAS[mat.ordinal()] = 210;
        }

        // === Emissive blocks (always full brightness, no face shading) ===
        putEmissive(XMaterial.GLOWSTONE);
        putEmissive(XMaterial.SEA_LANTERN);
        putEmissive(XMaterial.JACK_O_LANTERN);
        putEmissive(XMaterial.REDSTONE_LAMP);
        putEmissive(XMaterial.LAVA);
        putEmissive(XMaterial.SHROOMLIGHT);
        putEmissive(XMaterial.MAGMA_BLOCK);
        putEmissive(XMaterial.LANTERN);
        putEmissive(XMaterial.SOUL_LANTERN);
        putEmissive(XMaterial.CAMPFIRE);
        putEmissive(XMaterial.SOUL_CAMPFIRE);

        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            if (name.endsWith("_FROGLIGHT"))
                EMISSIVE[mat.ordinal()] = true;
        }

        // === Color deduplication pass ===
        // Ensures no two non-transparent materials share the exact same RGB color.
        // On collision, increments the blue channel by 1 (wrapping to green) until unique.
        // Minimal visual impact — at most a few LSB changes per block.
        {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (XMaterial mat : XMaterial.values()) {
                int ord = mat.ordinal();
                int color = COLORS[ord];
                if (color == TRANSPARENT)
                    continue;
                int attempts = 0;
                while (seen.contains(color) && attempts < 256) {
                    int b = (color & 0xFF) + 1;
                    if (b > 255) {
                        b = 0;
                        int g = ((color >> 8) & 0xFF) + 1;
                        color = rgb((color >> 16) & 0xFF, Math.min(g, 255), b);
                    } else {
                        color = rgb((color >> 16) & 0xFF, (color >> 8) & 0xFF, b);
                    }
                    attempts++;
                }
                COLORS[ord] = color;
                seen.add(color);
            }
        }

        // Populate per-material flag bits from name-based helpers (executed once at class load)
        for (XMaterial mat : XMaterial.values()) {
            String name = mat.name();
            int flags = 0;
            if (isAxisBlock(name))
                flags |= FLAG_AXIS_BLOCK;
            if (isFacingFrontBlock(name))
                flags |= FLAG_FACING_FRONT;
            if (isPaneBlock(name))
                flags |= FLAG_PANE;
            if (isFenceBlock(name))
                flags |= FLAG_FENCE;
            if (isWallBlock(name))
                flags |= FLAG_WALL;
            if (isNeedsStateBlock(name))
                flags |= FLAG_NEEDS_STATE;
            BLOCK_FLAGS[mat.ordinal()] = flags;
        }
    }

    /**
     * Returns the base RGB color for a material, or {@code -1} if the block is transparent.
     *
     * @param material the block material
     * @return 24-bit RGB color, or {@code -1} for transparent blocks
     */
    public static int getColor(XMaterial material) {
        return COLORS[material.ordinal()];
    }

    /**
     * Returns the context-sensitive color for a block hit, accounting for face-dependent
     * textures (e.g., grass block top vs. side, log end vs. bark, furnace front face).
     *
     * @param scene    the scene for reading block state
     * @param x        world X coordinate
     * @param y        world Y coordinate
     * @param z        world Z coordinate
     * @param material the block material
     * @param hitFace  the face that was hit (0=X, 1=Y, 2=Z)
     * @param dx       ray direction X component (for determining face orientation)
     * @param dy       ray direction Y component
     * @param dz       ray direction Z component
     * @return 24-bit RGB color, or {@code -1} for transparent blocks
     */
    public static int getColor(SceneData scene, int x, int y, int z,
                               XMaterial material, int hitFace,
                               double dx, double dy, double dz) {
        int color = getColor(material);
        if (color == TRANSPARENT)
            return color;

        if (material == XMaterial.GRASS_BLOCK) {
            if (hitFace == 1)
                return dy < 0 ? 0x7CBD6B : 0x866043;
            return 0x7D8A58;
        }
        if (material == XMaterial.MYCELIUM) {
            if (hitFace == 1)
                return dy < 0 ? 0x81758A : 0x6B4F3E;
            return 0x776A73;
        }
        if (material == XMaterial.PODZOL) {
            if (hitFace == 1)
                return dy < 0 ? 0x6B5B28 : 0x866043;
            return 0x70552E;
        }

        int flags = BLOCK_FLAGS[material.ordinal()];
        if ((flags & (FLAG_AXIS_BLOCK | FLAG_FACING_FRONT)) == 0)
            return color;
        BlockRenderState state = BlockRenderState.of(scene, x, y, z);
        if ((flags & FLAG_AXIS_BLOCK) != 0)
            return colorForAxisBlock(color, state.axis(), hitFace);
        return colorForFacingBlock(color, frontColor(material, color), state.facing(), hitFace, dx, dy, dz);
    }

    /**
     * Returns the opacity for a material (0–255). 255 = fully opaque.
     *
     * @param material the block material
     * @return alpha value in range [0, 255]
     */
    public static int getAlpha(XMaterial material) {
        return ALPHAS[material.ordinal()];
    }

    /**
     * Returns whether the material is emissive (bypasses face shading, always full brightness).
     *
     * @param material the block material
     * @return {@code true} if the block emits light
     */
    public static boolean isEmissive(XMaterial material) {
        return EMISSIVE[material.ordinal()];
    }

    /**
     * Checks whether two adjacent translucent blocks should be treated as a single volume,
     * suppressing re-tinting at their shared boundary (e.g., adjacent water or glass blocks).
     *
     * @param first  the block the ray is leaving
     * @param second the block the ray is entering
     * @return {@code true} if the boundary should be skipped
     */
    public static boolean canMergeTranslucent(XMaterial first, XMaterial second) {
        if (first == second)
            return isMergeableTranslucent(first);
        if (!isMergeableTranslucent(first) || !isMergeableTranslucent(second))
            return false;
        return getTranslucentMergeFamily(first).equals(getTranslucentMergeFamily(second));
    }

    /**
     * Registers a base color for a material during static initialization.
     */
    private static void put(XMaterial xMaterial, int color) {
        COLORS[xMaterial.ordinal()] = color;
    }

    /**
     * Registers a custom alpha (opacity) for a material.
     */
    private static void putAlpha(XMaterial xMaterial, int alpha) {
        ALPHAS[xMaterial.ordinal()] = alpha;
    }

    /**
     * Marks a material as emissive (always rendered at full brightness).
     */
    private static void putEmissive(XMaterial xMaterial) {
        EMISSIVE[xMaterial.ordinal()] = true;
    }

    /**
     * Checks if a material supports boundary merging (volumetric translucency).
     */
    private static boolean isMergeableTranslucent(XMaterial material) {
        if (getColor(material) == TRANSPARENT)
            return false;
        if (getAlpha(material) >= 255)
            return false;
        String name = material.name();
        return name.equals("WATER")
                || name.equals("GLASS")
                || name.endsWith("_STAINED_GLASS")
                || name.equals("ICE")
                || name.equals("FROSTED_ICE");
    }

    /**
     * Returns the merge family identifier for a translucent material.
     * Blocks in the same family can share internal boundaries without re-tinting.
     */
    private static String getTranslucentMergeFamily(XMaterial material) {
        String name = material.name();
        if (name.equals("WATER"))
            return "WATER";
        if (name.equals("GLASS"))
            return "GLASS";
        if (name.endsWith("_STAINED_GLASS"))
            return name;
        if (name.equals("ICE"))
            return "ICE";
        if (name.equals("FROSTED_ICE"))
            return "FROSTED_ICE";
        return name;
    }

    /**
     * Infers a color for an unmapped material by analyzing its name.
     * Falls back to a hash-based color if no name pattern matches.
     */
    private static int inferFromName(String name) {
        Integer baseColor = inferBaseColor(name);
        if (baseColor != null)
            return applyNameVariant(baseColor, name);

        return hashedFallbackColor(name);
    }

    /**
     * Attempts to determine a base color from the material name by matching against
     * dye prefixes, wood family names, stone variants, metals, and other keywords.
     *
     * @return the inferred color, or {@code null} if no pattern matches
     */
    private static Integer inferBaseColor(String name) {
        // Dye-colored block families — use type-specific color palettes
        for (int i = 0; i < DYE_PREFIXES.length; i++)
            if (name.startsWith(DYE_PREFIXES[i])) {
                if (name.endsWith("_TERRACOTTA") || name.contains("GLAZED_TERRACOTTA"))
                    return TERRACOTTA_COLORS[i];
                if (name.endsWith("_CONCRETE_POWDER"))
                    return CONCRETE_POWDER_COLORS[i];
                if (name.endsWith("_CONCRETE"))
                    return CONCRETE_COLORS[i];
                return DYE_COLORS[i];
            }

        // Wood type inference (check more specific names first)
        if (name.contains("DARK_OAK"))
            return 0x422B12;
        if (name.contains("PALE_OAK"))
            return 0xD8D2C8;
        if (name.contains("CHERRY"))
            return 0xE7C3B5;
        if (name.contains("MANGROVE"))
            return 0x773535;
        if (name.contains("BAMBOO"))
            return 0xC8B348;
        if (name.contains("CRIMSON"))
            return 0x653046;
        if (name.contains("WARPED"))
            return 0x2B6D5B;
        if (name.contains("OAK"))
            return 0xA88654;
        if (name.contains("SPRUCE"))
            return 0x734B2E;
        if (name.contains("BIRCH"))
            return 0xC8B77A;
        if (name.contains("JUNGLE"))
            return 0xA0724B;
        if (name.contains("ACACIA"))
            return 0xA85A2C;

        // Stone variants
        if (name.contains("DEEPSLATE"))
            return 0x505050;
        if (name.contains("TUFF"))
            return 0x6C6C5E;
        if (name.contains("CALCITE"))
            return 0xDDDCC8;
        if (name.contains("BASALT"))
            return 0x4E4E52;
        if (name.contains("OBSIDIAN"))
            return 0x140C20;
        if (name.contains("BLACKSTONE"))
            return 0x2C272E;
        if (name.contains("NETHERRACK"))
            return 0x6B2E2E;
        if (name.contains("END_STONE"))
            return 0xDBDE9F;
        if (name.contains("PURPUR"))
            return 0xA977A9;
        if (name.contains("COBBLESTONE") || name.contains("COBBLED"))
            return 0x6B6B6B;
        if (name.contains("ANDESITE"))
            return 0x888888;
        if (name.contains("DIORITE"))
            return 0xBCBCBC;
        if (name.contains("GRANITE"))
            return 0x9A6C50;
        if (name.contains("TERRACOTTA"))
            return 0x985E43;
        if (name.contains("MUD_BRICK"))
            return 0x897158;
        if (name.contains("MUD"))
            return 0x5E4C42;
        if (name.contains("CLAY"))
            return 0x9EA5B1;
        if (name.contains("GRAVEL"))
            return 0x838080;
        if (name.contains("DRIPSTONE"))
            return 0x866B5C;
        if (name.contains("STONE"))
            return 0x7D7D7D;

        // Copper blocks with varying oxidation stages
        if (name.contains("COPPER")) {
            if (name.contains("OXIDIZED"))
                return 0x52A384;
            if (name.contains("WEATHERED"))
                return 0x6C9862;
            if (name.contains("EXPOSED"))
                return 0xA08258;
            return 0xC06840;
        }

        // Other material keywords
        if (name.contains("IRON"))
            return 0xDCDCDC;
        if (name.contains("NETHERITE"))
            return 0x434040;
        if (name.contains("GOLD"))
            return 0xF8D44E;
        if (name.contains("DIAMOND"))
            return 0x62EDD8;
        if (name.contains("EMERALD"))
            return 0x41C13E;
        if (name.contains("LAPIS"))
            return 0x2456A8;
        if (name.contains("REDSTONE"))
            return 0xAB1207;
        if (name.contains("COAL"))
            return 0x303030;
        if (name.contains("QUARTZ"))
            return 0xECE7DE;
        if (name.contains("PRISMARINE"))
            return 0x63A58E;
        if (name.contains("NETHER_BRICK"))
            return 0x2D1520;
        if (name.contains("BRICK"))
            return 0x966456;
        if (name.contains("SANDSTONE"))
            return 0xD8CB8A;
        if (name.contains("RED_SAND"))
            return 0xBE6621;
        if (name.contains("SAND"))
            return 0xDBCFA3;
        if (name.contains("GLASS"))
            return 0xC8E8F0;
        if (name.contains("ICE"))
            return 0x91B3F3;
        if (name.contains("SNOW"))
            return 0xF9FFFE;
        if (name.contains("WATER"))
            return 0x2825F5;
        if (name.contains("LAVA"))
            return 0xD05E10;

        // Coral variants with species-specific colors
        if (name.contains("CORAL")) {
            if (name.contains("DEAD"))
                return 0x7E7567;
            if (name.contains("TUBE"))
                return 0x3455D5;
            if (name.contains("BRAIN"))
                return 0xD1789A;
            if (name.contains("BUBBLE"))
                return 0xA61D89;
            if (name.contains("FIRE"))
                return 0xD82A20;
            if (name.contains("HORN"))
                return 0xD9C634;
            return 0xD8626C;
        }

        if (name.contains("AMETHYST"))
            return 0x8664C5;
        if (name.contains("SCULK"))
            return 0x0E1E24;
        if (name.contains("MOSS"))
            return 0x4D6B28;
        if (name.contains("GRASS"))
            return 0x6BAA36;
        if (name.contains("LEAVES"))
            return 0x4A7A32;
        if (name.contains("VINE"))
            return 0x4C7A34;
        if (name.contains("AZALEA"))
            return 0x6B8A38;
        if (name.contains("CACTUS"))
            return 0x5B7C3E;
        if (name.contains("MELON"))
            return 0x6B9830;
        if (name.contains("PUMPKIN"))
            return 0xC9820D;
        if (name.contains("HONEY"))
            return 0xE59B28;
        if (name.contains("SLIME"))
            return 0x6CC46A;
        if (name.contains("BONE"))
            return 0xD1CCAC;
        if (name.contains("BOOKSHELF"))
            return 0x735E3E;
        if (name.contains("CHEST"))
            return 0x6B4D30;
        if (name.contains("FURNACE"))
            return 0x7A7A7A;
        if (name.contains("LANTERN"))
            return 0x8A6A3E;
        if (name.contains("CANDLE"))
            return 0xD5C9A4;
        if (name.contains("WOOL"))
            return 0xB8B1A8;
        if (name.contains("CONCRETE_POWDER"))
            return 0xB0AAA0;
        if (name.contains("CONCRETE"))
            return 0x9C958B;
        if (name.contains("CERAMIC"))
            return 0xA8846C;
        if (name.contains("MUSHROOM"))
            return 0xA07060;
        if (name.contains("FROGLIGHT"))
            return 0xC8B58C;
        if (name.contains("SEA_PICKLE"))
            return 0x76A52E;
        if (name.contains("SPONGE"))
            return 0xC2BC4A;
        if (name.contains("HAY"))
            return 0xB5960C;

        return null;
    }

    /**
     * Adjusts a base color based on name modifiers (polished, cracked, mossy, etc.)
     * and block form (slab, stairs, wall, door, etc.). Adds a small hash-based
     * per-material color jitter to avoid identical colors for related blocks.
     */
    private static int applyNameVariant(int color, String name) {
        double brightness = 1.0;

        if (name.contains("POLISHED") || name.contains("SMOOTH") || name.contains("CUT"))
            brightness *= 1.06;
        if (name.contains("CHISELED") || name.contains("CARVED"))
            brightness *= 0.98;
        if (name.contains("CRACKED"))
            brightness *= 0.9;
        if (name.contains("MOSSY"))
            color = blend(color, 0x4D6B28, 0.22);
        if (name.contains("WAXED"))
            brightness *= 1.03;
        if (name.contains("STRIPPED"))
            brightness *= 1.08;
        if (name.contains("OXIDIZED"))
            brightness *= 0.96;
        if (name.contains("WEATHERED"))
            brightness *= 0.98;
        if (name.contains("EXPOSED"))
            brightness *= 1.01;
        if (name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE"))
            brightness *= 1.01;
        if (name.endsWith("_STAIRS"))
            brightness *= 0.97;
        if (name.endsWith("_SLAB"))
            brightness *= 1.03;
        if (name.endsWith("_WALL"))
            brightness *= 0.95;
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE"))
            brightness *= 0.94;
        if (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR"))
            brightness *= 0.92;
        if (name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE"))
            brightness *= 1.05;
        if (name.endsWith("_PILLAR"))
            brightness *= 1.04;
        if (name.endsWith("_BRICKS"))
            brightness *= 0.96;
        if (name.endsWith("_TILES"))
            brightness *= 0.93;
        if (name.endsWith("_ORE"))
            brightness *= 0.91;
        if (name.endsWith("_BLOCK"))
            brightness *= 1.02;
        if (name.contains("RAW_"))
            brightness *= 0.94;

        color = scaleBrightness(color, brightness);

        int hash = name.hashCode();
        int dr = (hash & 0x0F) - 7;
        int dg = ((hash >>> 4) & 0x0F) - 7;
        int db = ((hash >>> 8) & 0x0F) - 7;
        return rgb(
                clampChannel(((color >> 16) & 0xFF) + dr),
                clampChannel(((color >> 8) & 0xFF) + dg),
                clampChannel((color & 0xFF) + db)
        );
    }

    /**
     * Generates a deterministic fallback color from the material name hash.
     * Picks from a muted earth-tone palette and applies hash-based brightness
     * and channel jitter for visual variety.
     */
    private static int hashedFallbackColor(String name) {
        int hash = name.hashCode();
        // Bitmask instead of Math.abs: Math.abs(Integer.MIN_VALUE) is still negative (two's complement overflow)
        int seed = hash & 0x7FFFFFFF;

        int[] palette = {
                0x7D7D7D, 0x8A8177, 0x9A6C50, 0xA88654,
                0x6C6C5E, 0x5E4C42, 0x4D6B28, 0x6B7A99
        };

        int color = palette[seed % palette.length];
        double brightness = 0.88 + ((seed >>> 3) & 0x1F) / 100.0;
        color = scaleBrightness(color, brightness);

        int dr = ((seed >>> 8) & 0x0F) - 7;
        int dg = ((seed >>> 12) & 0x0F) - 7;
        int db = ((seed >>> 16) & 0x0F) - 7;
        return rgb(
                clampChannel(((color >> 16) & 0xFF) + dr),
                clampChannel(((color >> 8) & 0xFF) + dg),
                clampChannel((color & 0xFF) + db)
        );
    }

    /**
     * Linearly blends two colors. {@code ratio} = 0.0 returns {@code first}, 1.0 returns {@code second}.
     */
    private static int blend(int first, int second, double ratio) {
        double inv = 1.0 - ratio;
        return rgb(
                clampChannel((int) Math.round(((first >> 16) & 0xFF) * inv + ((second >> 16) & 0xFF) * ratio)),
                clampChannel((int) Math.round(((first >> 8) & 0xFF) * inv + ((second >> 8) & 0xFF) * ratio)),
                clampChannel((int) Math.round((first & 0xFF) * inv + (second & 0xFF) * ratio))
        );
    }

    /**
     * Scales all RGB channels of a color by a brightness factor, clamping to [0, 255].
     */
    private static int scaleBrightness(int color, double factor) {
        return rgb(
                clampChannel((int) Math.round(((color >> 16) & 0xFF) * factor)),
                clampChannel((int) Math.round(((color >> 8) & 0xFF) * factor)),
                clampChannel((int) Math.round((color & 0xFF) * factor))
        );
    }

    /**
     * Packs three 8-bit channels into a single 24-bit RGB integer.
     */
    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }

    /**
     * Returns whether the block has an orientation axis (logs, pillars, chains, etc.).
     */
    private static boolean isAxisBlock(String name) {
        return name.endsWith("_LOG")
                || name.endsWith("_WOOD")
                || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE")
                || name.equals("BAMBOO_BLOCK")
                || name.equals("STRIPPED_BAMBOO_BLOCK")
                || name.endsWith("_PILLAR")
                || name.equals("CHAIN");
    }

    /**
     * Returns whether the block has a distinct front face (furnace, observer, etc.).
     */
    private static boolean isFacingFrontBlock(String name) {
        return name.equals("FURNACE")
                || name.equals("BLAST_FURNACE")
                || name.equals("SMOKER")
                || name.equals("OBSERVER")
                || name.equals("DROPPER")
                || name.equals("DISPENSER")
                || name.equals("CARVED_PUMPKIN")
                || name.equals("JACK_O_LANTERN");
    }

    /**
     * Returns whether the block is a glass pane or iron bars. Used in static initializer for flag computation.
     */
    private static boolean isPaneBlock(String name) {
        return name.endsWith("_PANE") || name.equals("IRON_BARS");
    }

    /**
     * Returns whether the block is a fence (not a gate). Used in static initializer for flag computation.
     */
    private static boolean isFenceBlock(String name) {
        return name.endsWith("_FENCE") && !name.contains("GATE");
    }

    /**
     * Returns whether the block is a wall. Used in static initializer for flag computation.
     */
    private static boolean isWallBlock(String name) {
        return name.endsWith("_WALL");
    }

    /**
     * Returns whether the block's shape depends on block state properties. Used in static initializer for flag computation.
     */
    private static boolean isNeedsStateBlock(String name) {
        return name.endsWith("_SLAB")
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
    }

    /**
     * Returns the color for an axis-aligned block, using a lighter end-grain color
     * when the hit face is perpendicular to the block's axis.
     */
    private static int colorForAxisBlock(int sideColor, String axis, int hitFace) {
        boolean endFace = ("x".equals(axis) && hitFace == 0)
                || ("y".equals(axis) && hitFace == 1)
                || ("z".equals(axis) && hitFace == 2);
        if (!endFace)
            return sideColor;
        // Simple brightness scale for end-grain — avoids blending with tan that
        // washes out dark logs (spruce, dark oak).
        return scaleBrightness(sideColor, 1.15);
    }

    /**
     * Returns the front face color if the ray hit the block's facing side, otherwise the side color.
     */
    private static int colorForFacingBlock(int sideColor, int frontColor, String facing,
                                           int hitFace, double dx, double dy, double dz) {
        if (facing.equals(faceName(hitFace, dx, dy, dz)))
            return frontColor;
        return sideColor;
    }

    /**
     * Converts a hit face index and ray direction into a Minecraft facing name (north/south/east/west/up/down).
     */
    private static String faceName(int hitFace, double dx, double dy, double dz) {
        if (hitFace == 0)
            return dx > 0 ? "west" : "east";
        if (hitFace == 1)
            return dy > 0 ? "down" : "up";
        return dz > 0 ? "north" : "south";
    }

    /**
     * Returns the distinct front face color for specific blocks (furnace, observer, etc.).
     */
    private static int frontColor(XMaterial material, int fallback) {
        if (material == XMaterial.FURNACE || material == XMaterial.BLAST_FURNACE || material == XMaterial.SMOKER)
            return 0x3B332C;
        if (material == XMaterial.OBSERVER)
            return 0xB24A3A;
        if (material == XMaterial.DROPPER || material == XMaterial.DISPENSER)
            return 0x3C3C3C;
        if (material == XMaterial.CARVED_PUMPKIN || material == XMaterial.JACK_O_LANTERN)
            return 0x5A2E08;
        return scaleBrightness(fallback, 0.84);
    }

    /**
     * Returns a characteristic color for flowers, saplings, and other vegetation by name.
     */
    private static int inferPlantColor(String name) {
        if (name.contains("WITHER_ROSE"))
            return 0x402428;
        if (name.contains("ROSE") || name.contains("POPPY") || name.contains("TULIP"))
            return 0xC84B42;
        if (name.contains("DANDELION") || name.contains("SUNFLOWER"))
            return 0xD6B62E;
        if (name.contains("ALLIUM") || name.contains("LILAC"))
            return 0xA678C8;
        if (name.contains("BLUE") || name.contains("ORCHID") || name.contains("CORNFLOWER"))
            return 0x4D74D9;
        if (name.contains("AZURE"))
            return 0xB9D6E8;
        if (name.contains("LILY_OF_THE_VALLEY"))
            return 0xDDE8D6;
        if (name.contains("PEONY"))
            return 0xC96C88;
        if (name.contains("TORCHFLOWER"))
            return 0xD26A2E;
        if (name.contains("DEAD_BUSH"))
            return 0x8A6A3A;
        if (name.contains("FERN") || name.contains("GRASS") || name.contains("SAPLING"))
            return 0x5E8C34;
        return 0x6BAA36;
    }

    /**
     * Infers a color for sign blocks based on their wood family, with variant adjustments.
     */
    private static int inferWoodFamilyColor(String name, int fallback) {
        Integer base = inferBaseColor(name);
        if (base == null)
            return fallback;
        return applyNameVariant(base, name);
    }

    /**
     * Clamps a single color channel value to the valid [0, 255] range.
     */
    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

}

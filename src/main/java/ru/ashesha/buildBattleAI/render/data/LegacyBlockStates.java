package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.experimental.UtilityClass;

/**
 * Converts legacy (1.8–1.12) block data values to modern-format block state strings
 * for blocks whose shapes depend on state properties (stairs, slabs, trapdoors, etc.).
 * <p>
 * On 1.13+ servers this class is never used — block states come from
 * {@code BlockData.getAsString()} directly.
 *
 * @see ChunkScene#getBlockState(int, int, int)
 */
@UtilityClass
public class LegacyBlockStates {

    /**
     * Stair facing values indexed by legacy data bits 0–1.
     */
    private static final String[] STAIR_FACING = {"east", "west", "south", "north"};

    /**
     * Trapdoor facing values indexed by legacy data bits 0–1.
     */
    private static final String[] TRAPDOOR_FACING = {"south", "north", "east", "west"};

    /**
     * Door facing values indexed by legacy data bits 0–1 (lower-half only).
     */
    private static final String[] DOOR_FACING = {"east", "south", "west", "north"};

    /**
     * Fence gate facing values indexed by legacy data bits 0–1.
     */
    private static final String[] FENCE_GATE_FACING = {"south", "west", "north", "east"};

    /**
     * Wall-mounted block facing values indexed by raw data value.
     * Data 2–5 map to cardinal directions; other indices are unused.
     */
    private static final String[] WALL_MOUNTED_FACING = {null, null, "north", "south", "west", "east"};

    /**
     * Log/pillar axis values indexed by {@code (data >> 2) & 3}.
     */
    private static final String[] AXIS_VALUES = {"y", "x", "z"};

    /**
     * Converts a legacy material + data value to a modern-format block state string.
     * Returns {@code null} for blocks that don't need state-dependent shapes.
     *
     * @param material the XMaterial (already resolved from legacy name + data)
     * @param data     the raw legacy data byte
     * @return a block state string like {@code "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"},
     * or {@code null} if the block has no render-relevant state
     */
    public static String toBlockState(XMaterial material, byte data) {
        String name = material.name();
        int d = data & 0xFF;

        if (name.endsWith("_STAIRS"))
            return stairsState(name, d);
        if (name.endsWith("_SLAB"))
            return slabState(name, d);
        if (name.endsWith("_TRAPDOOR"))
            return trapdoorState(name, d);
        if (name.endsWith("_DOOR"))
            return doorState(name, d);
        if (name.endsWith("_FENCE_GATE"))
            return fenceGateState(name, d);
        // Standing signs (not wall signs)
        if (name.endsWith("_SIGN") && !name.endsWith("_WALL_SIGN"))
            return "minecraft:" + name.toLowerCase() + "[rotation=" + (d & 15) + "]";
        // Wall-mounted blocks: wall signs, ladders, wall torches
        if (name.endsWith("_WALL_SIGN") || name.equals("LADDER")
                || name.equals("WALL_TORCH") || name.equals("SOUL_WALL_TORCH")
                || name.equals("REDSTONE_WALL_TORCH"))
            return wallMountedState(name, d);
        // Axis-aligned blocks: logs, wood, pillars, chains, end rods
        if (name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.equals("BONE_BLOCK") || name.equals("HAY_BLOCK")
                || name.equals("PURPUR_PILLAR") || name.equals("QUARTZ_PILLAR")
                || name.equals("CHAIN") || name.equals("END_ROD"))
            return axisState(name, d);
        if (name.equals("SNOW")) {
            int layers = (d & 7) + 1;
            return "minecraft:snow[layers=" + layers + "]";
        }
        if (name.equals("VINE"))
            return vineState(d);

        return null;
    }

    /**
     * Stairs: bits 0–1 = facing, bit 2 = upside-down.
     * Shape is always "straight" — legacy data doesn't encode inner/outer corner shapes,
     * those are computed by the client at render time. The CpuRenderer uses "straight"
     * as the fallback anyway ({@link ru.ashesha.buildBattleAI.render.BlockRenderState#DEFAULT}).
     */
    private static String stairsState(String name, int d) {
        String facing = STAIR_FACING[d & 3];
        String half = (d & 4) != 0 ? "top" : "bottom";
        return "minecraft:" + name.toLowerCase()
                + "[facing=" + facing + ",half=" + half + ",shape=straight]";
    }

    /**
     * Slabs: bit 3 = top position. Sub-type is already resolved via XMaterial.
     */
    private static String slabState(String name, int d) {
        String type = (d & 8) != 0 ? "top" : "bottom";
        return "minecraft:" + name.toLowerCase() + "[type=" + type + "]";
    }

    /**
     * Trapdoors: bits 0–1 = facing, bit 2 = open, bit 3 = top half.
     */
    private static String trapdoorState(String name, int d) {
        String facing = TRAPDOOR_FACING[d & 3];
        boolean open = (d & 4) != 0;
        String half = (d & 8) != 0 ? "top" : "bottom";
        return "minecraft:" + name.toLowerCase()
                + "[facing=" + facing + ",half=" + half + ",open=" + open + "]";
    }

    /**
     * Doors: state is split across two halves.
     * Lower half (bit 3 = 0): bits 0–1 = facing, bit 2 = open.
     * Upper half (bit 3 = 1): bit 0 = hinge side (left/right).
     */
    private static String doorState(String name, int d) {
        String lower = name.toLowerCase();
        // Upper half — facing is unknown without reading the lower block.
        // Default to north; the renderer uses this only for shape, and doors
        // are rendered as thin cross panels regardless of facing.
        if ((d & 8) != 0)
            return "minecraft:" + lower + "[half=upper,facing=north,open=false]";
        String facing = DOOR_FACING[d & 3];
        boolean open = (d & 4) != 0;
        return "minecraft:" + lower + "[facing=" + facing + ",half=lower,open=" + open + "]";
    }

    /**
     * Fence gates: bits 0–1 = facing, bit 2 = open.
     */
    private static String fenceGateState(String name, int d) {
        String facing = FENCE_GATE_FACING[d & 3];
        boolean open = (d & 4) != 0;
        return "minecraft:" + name.toLowerCase() + "[facing=" + facing + ",open=" + open + "]";
    }

    /**
     * Wall-mounted blocks: data 2–5 maps to cardinal facing.
     */
    private static String wallMountedState(String name, int d) {
        String facing = (d >= 2 && d <= 5) ? WALL_MOUNTED_FACING[d] : "north";
        return "minecraft:" + name.toLowerCase() + "[facing=" + facing + "]";
    }

    /**
     * Axis-aligned blocks: bits 2–3 encode axis.
     * 0 = Y (vertical), 1 = X (east–west), 2 = Z (north–south).
     * Sub-type bits (0–1) are already handled by XMaterial matching.
     */
    private static String axisState(String name, int d) {
        int axisIdx = (d >> 2) & 3;
        String axis = axisIdx < AXIS_VALUES.length ? AXIS_VALUES[axisIdx] : "y";
        return "minecraft:" + name.toLowerCase() + "[axis=" + axis + "]";
    }

    /**
     * Vines: data is a bitmask — bit 0 = south, bit 1 = west, bit 2 = north, bit 3 = east.
     * The renderer treats vines as wall plates, so we pick the primary face.
     */
    private static String vineState(int d) {
        String facing;
        if ((d & 4) != 0)
            facing = "north";
        else if ((d & 1) != 0)
            facing = "south";
        else if ((d & 2) != 0)
            facing = "west";
        else if ((d & 8) != 0)
            facing = "east";
        else
            facing = "north";
        return "minecraft:vine[facing=" + facing + "]";
    }

}

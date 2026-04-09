package ru.ashesha.buildBattleAI.render;

import lombok.Value;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable snapshot of render-relevant block state properties.
 * <p>
 * Parses the Minecraft block state string (e.g., {@code "minecraft:oak_stairs[facing=north,half=bottom]"})
 * and extracts properties needed for shape resolution and face-dependent coloring.
 * Parsed results are cached in a thread-safe map for reuse across renders.
 */
@Value
@Accessors(fluent = true)
public class BlockRenderState {

    /** Default state used when no block state string is available. */
    public static final BlockRenderState DEFAULT = new BlockRenderState(
            "north",
            "y",
            "bottom",
            "bottom",
            "straight",
            false,
            1,
            0
    );

    /** Thread-safe cache keyed by raw block state strings. */
    private static final Map<String, BlockRenderState> CACHE = new ConcurrentHashMap<String, BlockRenderState>();

    /** Horizontal direction the block faces (north/south/east/west). */
    String facing;
    /** Orientation axis for logs, pillars, and chains (x/y/z). */
    String axis;
    /** Slab type: "bottom", "top", or "double". */
    String type;
    /** Vertical half for stairs and trapdoors: "bottom" or "top". */
    String half;
    /** Stair shape variant: "straight", "inner_left", etc. */
    String shape;
    /** Whether trapdoors and fence gates are in the open position. */
    boolean open;
    /** Number of snow layers (1–8). */
    int layers;
    /** Rotation value for standing signs (0–15). */
    int rotation;

    /**
     * Retrieves or parses the block render state at the given world coordinates.
     * Returns {@link #DEFAULT} if no block state string is available.
     *
     * @param scene the scene data source
     * @param x     world X coordinate
     * @param y     world Y coordinate
     * @param z     world Z coordinate
     * @return the parsed render state, never {@code null}
     */
    public static BlockRenderState of(SceneData scene, int x, int y, int z) {
        String blockState = scene.getBlockState(x, y, z);
        if (blockState == null || blockState.isEmpty()) {
            return DEFAULT;
        }
        BlockRenderState cached = CACHE.get(blockState);
        if (cached != null) {
            return cached;
        }
        BlockRenderState parsed = parse(blockState);
        CACHE.put(blockState, parsed);
        return parsed;
    }

    /** Parses all render-relevant properties from a raw block state string. */
    private static BlockRenderState parse(String blockState) {
        String facing = property(blockState, "facing", "north");
        String axis = property(blockState, "axis", "y");
        String type = property(blockState, "type", "bottom");
        String half = property(blockState, "half", "bottom");
        String shape = property(blockState, "shape", "straight");
        boolean open = "true".equals(property(blockState, "open", "false"));
        int layers = parseInt(property(blockState, "layers", "1"), 1);
        int rotation = parseInt(property(blockState, "rotation", "0"), 0);
        return new BlockRenderState(facing, axis, type, half, shape, open, layers, rotation);
    }

    /**
     * Extracts a single property value from a block state string.
     * Searches for {@code "key="} after the opening bracket and reads until the next
     * comma or closing bracket. Returns the fallback if the property is not present.
     */
    private static String property(String blockState, String key, String fallback) {
        int stateStart = blockState.indexOf('[');
        if (stateStart < 0) {
            return fallback;
        }

        String needle = key + "=";
        int valueStart = blockState.indexOf(needle, stateStart);
        if (valueStart < 0) {
            return fallback;
        }
        valueStart += needle.length();

        int valueEnd = blockState.indexOf(',', valueStart);
        if (valueEnd < 0) {
            valueEnd = blockState.indexOf(']', valueStart);
        }
        if (valueEnd < 0 || valueEnd <= valueStart) {
            return fallback;
        }
        return blockState.substring(valueStart, valueEnd);
    }

    /** Parses an integer value, returning the fallback on parse failure. */
    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

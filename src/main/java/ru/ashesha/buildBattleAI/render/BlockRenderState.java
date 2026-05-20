package ru.ashesha.buildBattleAI.render;

import lombok.Value;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Default state used when no block state string is available.
     */
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
    /**
     * Maximum cache size before eviction. Generous for steady-state working sets
     * (a typical build scene uses a few hundred unique block states) but prevents
     * unbounded growth on long-running servers. Clearing is safe — entries are purely
     * a performance optimization and will be recomputed on next access.
     */
    static final int MAX_CACHE_SIZE = 1024;
    /**
     * Thread-safe cache keyed by raw block state strings. Wrapped in an
     * {@link AtomicReference} so that the over-capacity replacement uses a CAS
     * and concurrent saturating writers collapse into a single eviction rather
     * than thrashing the cache (thundering-herd-safe).
     */
    private static final AtomicReference<Map<String, BlockRenderState>> CACHE_REF =
            new AtomicReference<Map<String, BlockRenderState>>(new ConcurrentHashMap<String, BlockRenderState>());

    // Pre-allocated property needles. The trailing `=` lets `parse()` reuse the
    // same string instances across calls instead of allocating `key + "="` each
    // time `property()` is invoked (8 needles × every parse() call).
    private static final String NEEDLE_FACING = "facing=";
    private static final String NEEDLE_AXIS = "axis=";
    private static final String NEEDLE_TYPE = "type=";
    private static final String NEEDLE_HALF = "half=";
    private static final String NEEDLE_SHAPE = "shape=";
    private static final String NEEDLE_OPEN = "open=";
    private static final String NEEDLE_LAYERS = "layers=";
    private static final String NEEDLE_ROTATION = "rotation=";
    /**
     * Horizontal direction the block faces (north/south/east/west).
     */
    String facing;
    /**
     * Orientation axis for logs, pillars, and chains (x/y/z).
     */
    String axis;
    /**
     * Slab type: "bottom", "top", or "double".
     */
    String type;
    /**
     * Vertical half for stairs and trapdoors: "bottom" or "top".
     */
    String half;
    /**
     * Stair shape variant: "straight", "inner_left", etc.
     */
    String shape;
    /**
     * Whether trapdoors and fence gates are in the open position.
     */
    boolean open;
    /**
     * Number of snow layers (1–8).
     */
    int layers;


    /**
     * Rotation value for standing signs (0–15).
     */
    int rotation;

    /**
     * Retrieves or parses the block render state at the given world coordinates.
     * Returns {@link #DEFAULT} if no block state string is available.
     * <p>
     * Convenience overload that performs the {@link SceneData#getBlockState}
     * lookup itself. Callers that already hold the raw state string (e.g.
     * {@link BlockShape#getStatefulShape} which fetched it for its own cache
     * key) should use {@link #of(String)} instead to avoid the duplicate
     * scene lookup.
     *
     * @param scene the scene data source
     * @param x     world X coordinate
     * @param y     world Y coordinate
     * @param z     world Z coordinate
     * @return the parsed render state, never {@code null}
     */
    public static BlockRenderState of(SceneData scene, int x, int y, int z) {
        return of(scene.getBlockState(x, y, z));
    }

    /**
     * Retrieves or parses the block render state from an already-fetched
     * raw block-state string. Useful for hot paths that already obtained the
     * string for another purpose (e.g. a cache key) — avoids a redundant
     * {@link SceneData#getBlockState} call.
     *
     * @param blockState the raw block state string, may be {@code null} or empty
     * @return the parsed render state, or {@link #DEFAULT} for null/empty input,
     *         never {@code null}
     */
    public static BlockRenderState of(String blockState) {
        if (blockState == null || blockState.isEmpty())
            return DEFAULT;
        Map<String, BlockRenderState> cache = CACHE_REF.get();
        BlockRenderState cached = cache.get(blockState);
        if (cached != null)
            return cached;
        // CAS-guarded eviction: concurrent over-cap detections collapse to a
        // single replacement instead of repeatedly throwing away just-cached
        // entries (thundering herd).
        if (cache.size() > MAX_CACHE_SIZE)
            CACHE_REF.compareAndSet(cache, new ConcurrentHashMap<String, BlockRenderState>());
        BlockRenderState parsed = parse(blockState);
        CACHE_REF.get().put(blockState, parsed);
        return parsed;
    }

    /**
     * Returns the current number of cached entries. Package-visible for testing.
     */
    static int cacheSize() {
        return CACHE_REF.get().size();
    }

    /**
     * Replaces the cache with a fresh empty map. Package-visible for testing.
     */
    static void clearCache() {
        CACHE_REF.set(new ConcurrentHashMap<String, BlockRenderState>());
    }

    /**
     * Parses all render-relevant properties from a raw block state string.
     */
    private static BlockRenderState parse(String blockState) {
        // Compute `stateStart` once and reuse across all 8 property lookups
        // instead of calling indexOf('[') eight times per parse.
        int stateStart = blockState.indexOf('[');
        String facing = property(blockState, stateStart, NEEDLE_FACING, "north");
        String axis = property(blockState, stateStart, NEEDLE_AXIS, "y");
        String type = property(blockState, stateStart, NEEDLE_TYPE, "bottom");
        String half = property(blockState, stateStart, NEEDLE_HALF, "bottom");
        String shape = property(blockState, stateStart, NEEDLE_SHAPE, "straight");
        boolean open = "true".equals(property(blockState, stateStart, NEEDLE_OPEN, "false"));
        int layers = parseInt(property(blockState, stateStart, NEEDLE_LAYERS, "1"), 1);
        int rotation = parseInt(property(blockState, stateStart, NEEDLE_ROTATION, "0"), 0);
        return new BlockRenderState(facing, axis, type, half, shape, open, layers, rotation);
    }

    /**
     * Extracts a single property value from a block state string.
     * Searches for {@code needle} (which already contains the trailing {@code "="})
     * after {@code stateStart} and reads until the next comma or closing bracket.
     * Returns {@code fallback} if {@code stateStart < 0} or the property is absent.
     */
    private static String property(String blockState, int stateStart, String needle, String fallback) {
        if (stateStart < 0)
            return fallback;

        int valueStart = blockState.indexOf(needle, stateStart);
        if (valueStart < 0)
            return fallback;
        valueStart += needle.length();

        int valueEnd = blockState.indexOf(',', valueStart);
        if (valueEnd < 0)
            valueEnd = blockState.indexOf(']', valueStart);
        if (valueEnd < 0 || valueEnd <= valueStart)
            return fallback;
        return blockState.substring(valueStart, valueEnd);
    }

    /**
     * Parses an integer value, returning the fallback on parse failure.
     */
    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;
import ru.ashesha.buildBattleAI.render.BlockPalette;

/**
 * Fast scene backed by a flat {@code short[]} of {@link XMaterial#ordinal()} values.
 * <p>
 * Array layout (X-major): {@code data[(x - minX) * sizeY * sizeZ + (y - minY) * sizeZ + (z - minZ)]}
 * <p>
 * Use {@link #fromSnapshot(ChunkScene)} to convert a chunk-based snapshot
 * into this format, or build the array yourself for maximum control.
 */
@Accessors(fluent = true)
@Getter
public class FlatScene implements SceneData {

    private static final XMaterial[] MATERIAL_VALUES = XMaterial.values();

    private final short[] data;
    private final byte[] legacyBlockData;
    private final String[] blockStates;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final int sizeX, sizeY, sizeZ;
    /**
     * Pre-computed {@code sizeY * sizeZ} to avoid repeated multiplication in {@link #indexOf(int, int, int)}.
     */
    private final int sizeYZ;
    private final SourceFormat sourceFormat;
    private final String sourceName;

    /**
     * @param data  flat array of {@link XMaterial#ordinal()} values,
     *              indexed as {@code (x - minX) * sizeY * sizeZ + (y - minY) * sizeZ + (z - minZ)}
     * @param minX  inclusive min block X
     * @param minY  inclusive min block Y
     * @param minZ  inclusive min block Z
     * @param sizeX region size along X axis
     * @param sizeY region size along Y axis
     * @param sizeZ region size along Z axis
     */
    public FlatScene(short @NonNull [] data, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this(data, null, null, minX, minY, minZ, sizeX, sizeY, sizeZ, SourceFormat.DIRECT, "direct");
    }

    /**
     * Creates a flat scene with full block data including legacy data and block states.
     *
     * @param data            flat array of {@link XMaterial#ordinal()} values (X-major layout)
     * @param legacyBlockData parallel array of legacy block data values, or {@code null}
     * @param blockStates     parallel array of block state strings, or {@code null}
     * @param minX            inclusive min block X
     * @param minY            inclusive min block Y
     * @param minZ            inclusive min block Z
     * @param sizeX           region size along X axis
     * @param sizeY           region size along Y axis
     * @param sizeZ           region size along Z axis
     * @param sourceFormat    how the scene data was created
     * @param sourceName      human-readable label for the data source
     */
    public FlatScene(short @NonNull [] data,
                     byte[] legacyBlockData,
                     String[] blockStates,
                     int minX, int minY, int minZ,
                     int sizeX, int sizeY, int sizeZ,
                     @NonNull SourceFormat sourceFormat,
                     @NonNull String sourceName) {
        // Validate that the supplied data array length matches the declared region volume.
        // Uses long arithmetic to avoid silent int overflow on huge dimensions.
        if ((long) sizeX * sizeY * sizeZ != data.length) {
            long expected = (long) sizeX * sizeY * sizeZ;
            throw new IllegalArgumentException(
                    "FlatScene data length mismatch: actual=" + data.length
                            + ", expected=" + expected
                            + " (sizeX=" + sizeX + " * sizeY=" + sizeY + " * sizeZ=" + sizeZ + ")");
        }
        this.data = data;
        this.legacyBlockData = legacyBlockData;
        this.blockStates = blockStates;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sizeYZ = sizeY * sizeZ;
        this.maxX = minX + sizeX - 1;
        this.maxY = minY + sizeY - 1;
        this.maxZ = minZ + sizeZ - 1;
        this.sourceFormat = sourceFormat;
        this.sourceName = sourceName;
    }

    /**
     * Convert a {@link ChunkScene} into a flat array scene.
     * Can be called from any thread (SceneSnapshot is thread-safe).
     * Preserves legacy block data on 1.8–1.12 servers for sub-type and state resolution.
     */
    public static FlatScene fromSnapshot(@NonNull ChunkScene snapshot) {
        int minX = snapshot.minX(), minY = snapshot.minY(), minZ = snapshot.minZ();
        int sizeX = snapshot.maxX() - minX + 1;
        int sizeY = snapshot.maxY() - minY + 1;
        int sizeZ = snapshot.maxZ() - minZ + 1;

        short[] data = new short[sizeX * sizeY * sizeZ];
        String[] blockStates = new String[data.length];
        boolean hasLegacy = snapshot.hasLegacyBlockData();
        byte[] legacyBlockData = hasLegacy ? new byte[data.length] : null;

        // Hoist X-stride and world-coordinate offsets out of the inner loops so they're
        // computed once per slab instead of being recomputed for every (x, y, z) voxel.
        // Flat-index layout is preserved (X-major): [x*sizeY*sizeZ + y*sizeZ + z].
        int sizeYZ = sizeY * sizeZ;
        for (int x = 0; x < sizeX; x++) {
            int xBase = x * sizeYZ;
            int worldX = x + minX;
            for (int y = 0; y < sizeY; y++) {
                int xyBase = xBase + y * sizeZ;
                int worldY = y + minY;
                for (int z = 0; z < sizeZ; z++) {
                    int index = xyBase + z;
                    int worldZ = z + minZ;
                    XMaterial mat = snapshot.getBlockType(worldX, worldY, worldZ);
                    data[index] = (short) mat.ordinal();
                    // F4: materialize the block-state string only for blocks
                    // whose collision shape actually depends on it. ~95% of
                    // blocks in a typical scene (stone, dirt, wool, glass, …)
                    // are stateless and never need this allocation. The
                    // downstream getStatefulShape() short-circuits on a null
                    // state, which matches the "stateless" behaviour exactly.
                    // Note: legacyBlockData[] is *not* gated by this flag —
                    // pre-1.13 legacy data is consumed by LegacyBlockStates
                    // for many stateless blocks (e.g. log axis) and must
                    // always be populated when available.
                    if (BlockPalette.needsBlockState(mat))
                        blockStates[index] = snapshot.getBlockState(worldX, worldY, worldZ);
                    if (legacyBlockData != null)
                        legacyBlockData[index] = snapshot.getLegacyBlockData(worldX, worldY, worldZ);
                }
            }
        }

        return new FlatScene(data, legacyBlockData, blockStates, minX, minY, minZ,
                sizeX, sizeY, sizeZ, SourceFormat.RUNTIME, "runtime");
    }

    @Override
    public XMaterial getBlockType(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0)
            return XMaterial.AIR;
        return MATERIAL_VALUES[data[index] & 0xFFFF];
    }

    public byte getLegacyBlockData(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0 || legacyBlockData == null)
            return 0;
        return legacyBlockData[index];
    }

    public String getBlockState(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0 || blockStates == null)
            return null;
        return blockStates[index];
    }

    /**
     * Returns a complete snapshot of all block data at the given world coordinates,
     * bundling material, legacy data, and block state into a single value object.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return snapshot containing material, legacy data, and block state
     */
    public BlockDataSnapshot getBlockDataSnapshot(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0)
            return new BlockDataSnapshot(XMaterial.AIR, (byte) 0, null);
        return new BlockDataSnapshot(
                // Mask with 0xFFFF to prevent sign-extension of short → int producing a negative index
                MATERIAL_VALUES[data[index] & 0xFFFF],
                legacyBlockData == null ? 0 : legacyBlockData[index],
                blockStates == null ? null : blockStates[index]
        );
    }

    /**
     * Returns whether this scene contains legacy block data.
     */
    public boolean hasLegacyBlockData() {
        return legacyBlockData != null;
    }

    /**
     * Returns whether this scene contains block state strings.
     */
    public boolean hasBlockStates() {
        return blockStates != null;
    }

    /**
     * Converts world coordinates to a flat array index using X-major layout.
     *
     * @return the flat array index, or {@code -1} if out of bounds
     */
    private int indexOf(int wx, int wy, int wz) {
        int lx = wx - minX;
        int ly = wy - minY;
        int lz = wz - minZ;
        if (lx < 0 || lx >= sizeX || ly < 0 || ly >= sizeY || lz < 0 || lz >= sizeZ)
            return -1;
        return lx * sizeYZ + ly * sizeZ + lz;
    }

    /**
     * Indicates how the flat scene data was created.
     */
    public enum SourceFormat {
        /**
         * Created at runtime from a {@link ChunkScene} snapshot.
         */
        RUNTIME,
        /**
         * Created directly by supplying a pre-built data array.
         */
        DIRECT
    }

    /**
     * Immutable value object bundling a block's material, legacy data, and state string.
     */
    @Value
    public static class BlockDataSnapshot {
        XMaterial material;
        byte legacyData;
        String blockState;
    }

}

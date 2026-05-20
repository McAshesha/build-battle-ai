package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Thread-safe snapshot of block data within a render region.
 * Must be created on the main server thread via {@link #capture(RenderRegion, boolean)}.
 * Once created, all methods are safe to call from any thread.
 * <p>
 * Multi-version: works on 1.8–1.21+.
 * <ul>
 *     <li>1.13+: uses {@code BlockData.getAsString()} for block states</li>
 *     <li>1.8–1.12: uses legacy data values + {@link LegacyBlockStates} for synthetic state strings</li>
 * </ul>
 */
@Accessors(fluent = true)
public class ChunkScene implements SceneData {

    /**
     * Pre-cached enum values array for fast ordinal-to-material lookup.
     */
    private static final XMaterial[] MATERIAL_VALUES = XMaterial.values();

    /**
     * Hard upper bound for any single axis of a captured region. Larger requests
     * are rejected up front rather than allocating a multi-gigabyte voxel array
     * or overflowing the int multiplication that backs the flat-index layout.
     * Matches the limit enforced by {@code TestRenderCommand}.
     */
    public static final int MAX_REGION_AXIS = 512;

    /**
     * Flat array of material ordinals, indexed by {@link #indexOf(int, int, int)}.
     */
    private final short[] data;

    /**
     * Legacy block data values (0–15) for each voxel. Only populated on 1.8–1.12 servers
     * where data values encode sub-types (wool colors, slab positions, stair facing, etc.).
     * {@code null} on 1.13+ servers.
     */
    private final byte[] legacyData;

    /**
     * Stored chunk snapshots for lazy block state resolution.
     * Block state strings are only created when the renderer requests them (rare — only for
     * stateful blocks like stairs, slabs, trapdoors), avoiding ~2M String allocations at capture time.
     * ChunkSnapshot is immutable/thread-safe per Bukkit contract, so lazy access from render threads is safe.
     */
    private final ChunkSnapshot[] snapshots;

    /**
     * Minimum chunk coordinates and Z-axis chunk count for snapshot grid lookup.
     */
    private final int minCx, minCz, czCount;

    /**
     * Region dimensions along Y and Z axes, used for flat index calculation.
     */
    private final int sizeY, sizeZ;

    /**
     * Pre-computed {@code sizeY * sizeZ} to avoid repeated multiplication in {@link #indexOf(int, int, int)}.
     */
    private final int sizeYZ;

    /**
     * Inclusive world coordinate bounds of the captured region.
     */
    @Getter
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    private ChunkScene(short[] data, byte[] legacyData, ChunkSnapshot[] snapshots,
                       int minCx, int minCz, int czCount,
                       int sizeY, int sizeZ, int sizeYZ,
                       int minX, int minY, int minZ,
                       int maxX, int maxY, int maxZ) {
        this.data = data;
        this.legacyData = legacyData;
        this.snapshots = snapshots;
        this.minCx = minCx;
        this.minCz = minCz;
        this.czCount = czCount;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.sizeYZ = sizeYZ;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    // ── Version detection ────────────────────────────────────────────────────

    /**
     * Capture chunk snapshots for the given region.
     * MUST be called on the main server thread.
     * <p>
     * On 1.8–1.12 servers ({@code legacy == true}), additionally captures legacy data values
     * via reflection and resolves materials through {@code XMaterial.matchXMaterial("NAME:data")}
     * for correct sub-type mapping (e.g., colored wool, wood variants).
     * <p>
     * The {@code legacy} flag is resolved once at plugin startup by
     * {@link ru.ashesha.buildBattleAI.render.RenderService} to avoid repeated
     * {@code ServerVersion} checks in this hot path.
     *
     * @param region the region to capture
     * @param legacy {@code true} on 1.8–1.12 servers (pre-flattening); {@code false} on 1.13+
     */
    public static ChunkScene capture(@NonNull RenderRegion region, boolean legacy) {
        // Validate region dimensions with long arithmetic. Prevents both
        // NegativeArraySizeException from int overflow on huge regions and
        // adversarial OOM via an unbounded admin-supplied region.
        long sx = (long) region.maxX() - region.minX() + 1L;
        long sy = (long) region.maxY() - region.minY() + 1L;
        long sz = (long) region.maxZ() - region.minZ() + 1L;
        if (sx <= 0 || sy <= 0 || sz <= 0)
            throw new IllegalArgumentException(
                    "Render region has non-positive dimension: " + sx + "x" + sy + "x" + sz);
        if (sx > MAX_REGION_AXIS || sy > MAX_REGION_AXIS || sz > MAX_REGION_AXIS)
            throw new IllegalArgumentException(
                    "Render region axis exceeds " + MAX_REGION_AXIS + ": " + sx + "x" + sy + "x" + sz);

        int sizeX = (int) sx;
        int sizeY = (int) sy;
        int sizeZ = (int) sz;
        int sizeYZ = sizeY * sizeZ;

        int minCx = region.minX() >> 4;
        int minCz = region.minZ() >> 4;
        int maxCx = region.maxX() >> 4;
        int maxCz = region.maxZ() >> 4;
        short[] data = new short[sizeX * sizeY * sizeZ];
        byte[] legacyDataArray = legacy ? new byte[data.length] : null;

        int cxCount = maxCx - minCx + 1;
        int czCount = maxCz - minCz + 1;
        ChunkSnapshot[] snapshots = new ChunkSnapshot[cxCount * czCount];
        World world = region.world();

        for (int cx = minCx; cx <= maxCx; cx++)
            for (int cz = minCz; cz <= maxCz; cz++) {
                int idx = (cx - minCx) * czCount + (cz - minCz);
                snapshots[idx] = world.getChunkAt(cx, cz).getChunkSnapshot();
            }

        // Eagerly populate material ordinals — fast and small (~4 MB for 2M voxels).
        // Block state strings are resolved lazily via getBlockState() to avoid ~2M String allocations.
        // Loop reorganized to X → Z → Y so that the chunk/snapshot resolution is hoisted
        // out of the Y-axis inner loop: cx, localX depend only on x; cz, localZ, snapshot,
        // snapshotIndex depend only on (x, z); only the per-voxel block lookup and the
        // flat-index final term depend on y. The flat-index layout
        //   [(x-minX)*sizeYZ + (y-minY)*sizeZ + (z-minZ)]
        // is unchanged — only iteration order changes; the resulting `data[]` contents
        // are bit-identical to the previous implementation.
        int minX = region.minX();
        int minY = region.minY();
        int minZ = region.minZ();
        int maxX = region.maxX();
        int maxY = region.maxY();
        int maxZ = region.maxZ();
        for (int x = minX; x <= maxX; x++) {
            int cx = x >> 4;
            int localX = x & 15;
            int xOffset = (x - minX) * sizeYZ;
            for (int z = minZ; z <= maxZ; z++) {
                int cz = z >> 4;
                int localZ = z & 15;
                int snapshotIndex = (cx - minCx) * czCount + (cz - minCz);
                ChunkSnapshot snapshot = snapshots[snapshotIndex];
                int xzBase = xOffset + (z - minZ);
                for (int y = minY; y <= maxY; y++) {
                    int flatIndex = xzBase + (y - minY) * sizeZ;
                    if (legacy) {
                        Material material = Version.getMaterialById(
                                Version.getBlockTypeId(snapshot, localX, y, localZ));
                        int ld = Version.getLegacyData(snapshot, localX, y, localZ);
                        XMaterial xMaterial = matchLegacyMaterial(material, ld);
                        data[flatIndex] = (short) xMaterial.ordinal();
                        legacyDataArray[flatIndex] = (byte) ld;
                    } else {
                        Material material = snapshot.getBlockType(localX, y, localZ);
                        // Fast path: skip matchModernMaterial for AIR-family materials
                        // (AIR, CAVE_AIR, VOID_AIR — covers ~70% of voxels in caves & arenas).
                        // Avoids XSeries enum lookup overhead. isAirLike() uses string
                        // comparison for the 1.13+ variants which don't exist as enum
                        // constants on 1.8–1.12.
                        XMaterial xMaterial = isAirLike(material)
                                ? XMaterial.AIR
                                : matchModernMaterial(material);
                        data[flatIndex] = (short) xMaterial.ordinal();
                    }
                }
            }
        }

        return new ChunkScene(data, legacyDataArray, snapshots, minCx, minCz, czCount,
                sizeY, sizeZ, sizeYZ,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether the material represents some flavor of air (AIR, CAVE_AIR, VOID_AIR).
     * Uses name-based comparison for the 1.13+ variants so the code compiles on 1.8 Spigot
     * where those Material enum constants do not exist.
     */
    private static boolean isAirLike(Material material) {
        if (material == Material.AIR)
            return true;
        String name = material.name();
        return name.equals("CAVE_AIR") || name.equals("VOID_AIR");
    }

    /**
     * Matches a legacy Material + data value to XMaterial using the {@code "NAME:data"} format.
     * Falls back to data-less matching, then to {@link XMaterial#AIR} on failure.
     * <p>
     * Handles blocks where data values encode both sub-type and state (e.g., slabs
     * use bits 0–2 for material variant and bit 3 for top/bottom position).
     */
    private static XMaterial matchLegacyMaterial(Material material, int data) {
        // Full data match handles most blocks: colored wool, stained glass, etc.
        Optional<XMaterial> opt = XMaterial.matchXMaterial(material.name() + ":" + data);
        if (opt.isPresent())
            return opt.get();

        // For logs/wood: data bits 0–1 = wood type, bits 2–3 = axis.
        // Try wood-type-only match (mask off axis bits) for data values 4+ where
        // the axis bits cause the full-data match to fail.
        if (data >= 4) {
            opt = XMaterial.matchXMaterial(material.name() + ":" + (data & 3));
            if (opt.isPresent())
                return opt.get();
        }

        // For slabs and other blocks: bit 3 often encodes state (top/bottom).
        // Mask to bits 0–2 for the material sub-type.
        if (data > 7) {
            opt = XMaterial.matchXMaterial(material.name() + ":" + (data & 7));
            if (opt.isPresent())
                return opt.get();
        }

        // Final fallback: material-only match (loses sub-type but won't return AIR
        // for valid blocks)
        try {
            return XMaterial.matchXMaterial(material);
        } catch (IllegalArgumentException e) {
            return XMaterial.AIR;
        }
    }

    /**
     * Matches a modern (1.13+) Material to XMaterial with fallback strategies.
     * Primary: XMaterial.matchXMaterial(Material). If that fails (unknown material),
     * falls back to name-based valueOf lookup, then to STONE as a visible sentinel.
     * Never returns AIR for a non-AIR input — ensures unknown blocks remain visible
     * in the render rather than disappearing.
     */
    private static XMaterial matchModernMaterial(Material material) {
        // Fast path — works for all materials known to XSeries
        try {
            return XMaterial.matchXMaterial(material);
        } catch (Throwable ignored) {
            // Swallow: material not in XSeries mapping
        }

        // Fallback: direct enum name match (handles materials added after XSeries release)
        try {
            return XMaterial.valueOf(material.name());
        } catch (IllegalArgumentException ignored) {
            // Swallow: material name not in XMaterial enum
        }

        // Fallback: name-based match via XSeries string resolver
        Optional<XMaterial> opt = XMaterial.matchXMaterial(material.name());
        if (opt.isPresent())
            return opt.get();

        // Last resort: visible fallback so unknown blocks don't vanish.
        // AIR-like materials return AIR; everything else returns STONE.
        if (material.name().contains("AIR"))
            return XMaterial.AIR;
        return XMaterial.STONE;
    }

    // ── Capture ──────────────────────────────────────────────────────────────

    /**
     * Get block material at world coordinates. Thread-safe.
     */
    public XMaterial getBlockType(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0)
            return XMaterial.AIR;
        return MATERIAL_VALUES[data[index] & 0xFFFF];
    }

    @Override
    public byte getLegacyBlockData(int wx, int wy, int wz) {
        if (legacyData == null)
            return 0;
        int index = indexOf(wx, wy, wz);
        if (index < 0)
            return 0;
        return legacyData[index];
    }

    // ── SceneData implementation ─────────────────────────────────────────────

    @Override
    public boolean hasLegacyBlockData() {
        return legacyData != null;
    }

    /**
     * Lazily resolves the block state string.
     * <ul>
     *     <li>1.13+: delegates to {@code ChunkSnapshot.getBlockData().getAsString()}
     *         via {@link ModernBlockState} (isolated to avoid loading {@code BlockData} on legacy servers)</li>
     *     <li>1.8–1.12: converts stored legacy data values to synthetic state strings
     *         via {@link LegacyBlockStates}</li>
     * </ul>
     * Thread-safe: ChunkSnapshot is immutable, and LegacyBlockStates is stateless.
     */
    @Override
    public String getBlockState(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0)
            return null;

        if (legacyData != null) {
            XMaterial material = MATERIAL_VALUES[data[index] & 0xFFFF];
            return LegacyBlockStates.toBlockState(material, legacyData[index]);
        }

        int cx = wx >> 4;
        int cz = wz >> 4;
        ChunkSnapshot snapshot = snapshots[(cx - minCx) * czCount + (cz - minCz)];
        return ModernBlockState.resolve(snapshot, wx & 15, wy, wz & 15);
    }

    /**
     * Converts world coordinates to a flat array index.
     * Uses X-major layout: {@code (wx - minX) * sizeY * sizeZ + (wy - minY) * sizeZ + (wz - minZ)}.
     *
     * @return the flat array index, or {@code -1} if out of bounds
     */
    private int indexOf(int wx, int wy, int wz) {
        int lx = wx - minX;
        int ly = wy - minY;
        int lz = wz - minZ;
        // Symmetric bounds check using local offsets only (matches FlatScene.indexOf style).
        // Logically equivalent to the previous `wx > maxX || wy > maxY || wz > maxZ` form.
        if (lx < 0 || lx > maxX - minX
                || ly < 0 || ly > maxY - minY
                || lz < 0 || lz > maxZ - minZ)
            return -1;
        return lx * sizeYZ + ly * sizeZ + lz;
    }

    /**
     * Defines the 3D world region to capture for rendering.
     * Implementations provide inclusive min/max bounds and the target world.
     */
    public interface RenderRegion {

        /**
         * Inclusive minimum world X coordinate.
         */
        int minX();

        /**
         * Inclusive minimum world Y coordinate.
         */
        int minY();

        /**
         * Inclusive minimum world Z coordinate.
         */
        int minZ();

        /**
         * Inclusive maximum world X coordinate.
         */
        int maxX();

        /**
         * Inclusive maximum world Y coordinate.
         */
        int maxY();

        /**
         * Inclusive maximum world Z coordinate.
         */
        int maxZ();

        /**
         * The Bukkit world to capture from.
         */
        World world();

        /**
         * Simple axis-aligned cuboid region defined by min/max coordinates.
         */
        @RequiredArgsConstructor
        @Accessors(fluent = true)
        @Getter
        class Cuboid implements RenderRegion {
            private final int minX, minY, minZ, maxX, maxY, maxZ;
            private final World world;
        }
    }

    /**
     * Holder for reflective handles to legacy (1.8–1.12) Bukkit APIs.
     * <p>
     * The class is loaded (and its static initializer runs) lazily on first access
     * to any of its members, i.e. only when a legacy capture is actually performed.
     * Each handle is resolved independently so that initialization does not fail
     * on 1.13+ servers, where these methods no longer exist. Callers MUST guard
     * usage with the {@code legacy} flag passed to {@link #capture(RenderRegion, boolean)}.
     * <p>
     * Thread-safe: JVM guarantees that class initialization is synchronized.
     */
    private static class Version {

        /**
         * Reflective handle to the legacy {@code ChunkSnapshot.getBlockTypeId(int, int, int)}
         * which returns {@code int} (block ID) on 1.8–1.12. {@code null} on 1.13+.
         * <p>
         * On 1.13+, the equivalent is {@code getBlockType(int, int, int)} returning
         * {@link Material}, but that method does not exist on 1.8–1.12.
         */
        static final Method GET_BLOCK_TYPE_ID;

        /**
         * Reflective handle to the legacy {@code ChunkSnapshot.getBlockData(int, int, int)}
         * which returns {@code int} (0–15) on 1.8–1.12. The method exists on 1.13+ as well
         * but with return type {@code BlockData}; callers must only invoke this on legacy servers.
         */
        static final Method GET_LEGACY_DATA;

        /**
         * Reflective handle to {@code Material.getMaterial(int)} which resolves a
         * numeric block ID to its {@link Material} enum constant. Only available
         * on 1.8–1.12; removed in 1.13+ (post-flattening). {@code null} on 1.13+.
         */
        static final Method GET_MATERIAL_BY_ID;

        static {
            GET_BLOCK_TYPE_ID = findMethod(ChunkSnapshot.class, "getBlockTypeId",
                    int.class, int.class, int.class);
            GET_LEGACY_DATA = findMethod(ChunkSnapshot.class, "getBlockData",
                    int.class, int.class, int.class);
            GET_MATERIAL_BY_ID = findMethod(Material.class, "getMaterial", int.class);
        }

        /**
         * Resolves a method reflectively, returning {@code null} if the method
         * does not exist on the running server's Bukkit version.
         */
        private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
            try {
                return owner.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }

        /**
         * Resolves a legacy numeric block ID to its {@link Material} via reflection.
         * Falls back to {@code Material.AIR} if the ID is unknown or reflection fails.
         *
         * @return the corresponding Material, or {@code Material.AIR} on failure
         */
        static Material getMaterialById(int id) {
            try {
                Material material = (Material) GET_MATERIAL_BY_ID.invoke(null, id);
                return material != null ? material : Material.AIR;
            } catch (Throwable e) {
                return Material.AIR;
            }
        }

        /**
         * Invokes the legacy {@code getBlockTypeId(int,int,int)} via reflection.
         *
         * @return the legacy block type ID, or 0 (AIR) on failure
         */
        static int getBlockTypeId(ChunkSnapshot snapshot, int x, int y, int z) {
            try {
                return (Integer) GET_BLOCK_TYPE_ID.invoke(snapshot, x, y, z);
            } catch (Throwable e) {
                return 0;
            }
        }

        /**
         * Invokes the legacy {@code getBlockData(int,int,int)} via reflection.
         *
         * @return the legacy data value (0–15), or 0 on failure
         */
        static int getLegacyData(ChunkSnapshot snapshot, int x, int y, int z) {
            try {
                return (Integer) GET_LEGACY_DATA.invoke(snapshot, x, y, z);
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    /**
     * Isolates the 1.13+ {@code BlockData} reference into a separate inner class
     * so that the JVM never attempts to resolve {@code org.bukkit.block.data.BlockData}
     * on 1.8–1.12 servers (where the class does not exist).
     */
    private static class ModernBlockState {
        static String resolve(ChunkSnapshot snapshot, int x, int y, int z) {
            return snapshot.getBlockData(x, y, z).getAsString();
        }
    }

}

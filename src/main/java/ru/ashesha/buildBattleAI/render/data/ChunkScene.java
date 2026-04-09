package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Thread-safe snapshot of block data within a render region.
 * Must be created on the main server thread via {@link #capture(RenderRegion)}.
 * Once created, all methods are safe to call from any thread.
 */
@Accessors(fluent = true)
public class ChunkScene implements SceneData {

    /** Pre-cached enum values array for fast ordinal-to-material lookup. */
    private static final XMaterial[] MATERIAL_VALUES = XMaterial.values();

    /** Flat array of material ordinals, indexed by {@link #indexOf(int, int, int)}. */
    private final short[] data;

    /**
     * Stored chunk snapshots for lazy block state resolution.
     * Block state strings are only created when the renderer requests them (rare — only for
     * stateful blocks like stairs, slabs, trapdoors), avoiding ~2M String allocations at capture time.
     * ChunkSnapshot is immutable/thread-safe per Bukkit contract, so lazy access from render threads is safe.
     */
    private final ChunkSnapshot[] snapshots;

    /** Minimum chunk coordinates and Z-axis chunk count for snapshot grid lookup. */
    private final int minCx, minCz, czCount;

    /** Region dimensions along Y and Z axes, used for flat index calculation. */
    private final int sizeY, sizeZ;

    /** Pre-computed {@code sizeY * sizeZ} to avoid repeated multiplication in {@link #indexOf(int, int, int)}. */
    private final int sizeYZ;

    /** Inclusive world coordinate bounds of the captured region. */
    @Getter
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    private ChunkScene(short[] data, ChunkSnapshot[] snapshots,
                       int minCx, int minCz, int czCount,
                       int sizeY, int sizeZ, int sizeYZ,
                       int minX, int minY, int minZ,
                       int maxX, int maxY, int maxZ) {
        this.data = data;
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

    /**
     * Capture chunk snapshots for the given region.
     * MUST be called on the main server thread.
     */
    public static ChunkScene capture(RenderRegion region) {
        int minCx = region.minX() >> 4;
        int minCz = region.minZ() >> 4;
        int maxCx = region.maxX() >> 4;
        int maxCz = region.maxZ() >> 4;
        int sizeX = region.maxX() - region.minX() + 1;
        int sizeY = region.maxY() - region.minY() + 1;
        int sizeZ = region.maxZ() - region.minZ() + 1;
        short[] data = new short[sizeX * sizeY * sizeZ];

        int cxCount = maxCx - minCx + 1;
        int czCount = maxCz - minCz + 1;
        ChunkSnapshot[] snapshots = new ChunkSnapshot[cxCount * czCount];
        World world = region.world();

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                int idx = (cx - minCx) * czCount + (cz - minCz);
                snapshots[idx] = world.getChunkAt(cx, cz).getChunkSnapshot();
            }
        }

        // Eagerly populate material ordinals — fast and small (~4 MB for 2M voxels).
        // Block state strings are resolved lazily via getBlockState() to avoid ~2M String allocations.
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    int cx = x >> 4;
                    int cz = z >> 4;
                    int snapshotIndex = (cx - minCx) * czCount + (cz - minCz);
                    ChunkSnapshot snapshot = snapshots[snapshotIndex];
                    int localX = x & 15;
                    int localZ = z & 15;
                    int flatIndex = (x - region.minX()) * sizeY * sizeZ
                            + (y - region.minY()) * sizeZ
                            + (z - region.minZ());

                    Material material = snapshot.getBlockType(localX, y, localZ);
                    XMaterial xMaterial = XMaterial.matchXMaterial(material);
                    data[flatIndex] = (short) (xMaterial == null ? XMaterial.AIR.ordinal() : xMaterial.ordinal());
                }
            }
        }

        return new ChunkScene(data, snapshots, minCx, minCz, czCount,
                sizeY, sizeZ, sizeY * sizeZ,
                region.minX(), region.minY(), region.minZ(),
                region.maxX(), region.maxY(), region.maxZ());
    }

    /**
     * Get block material at world coordinates. Thread-safe.
     */
    public XMaterial getBlockType(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0) return XMaterial.AIR;
        return MATERIAL_VALUES[data[index] & 0xFFFF];
    }

    // legacyBlockData removed — unused on 1.13+ servers, and the SceneData default returns 0.

    /**
     * Lazily resolves the block state string from the stored ChunkSnapshot.
     * Only called by the renderer for stateful blocks (stairs, slabs, etc.),
     * so the vast majority of voxels never allocate a String.
     * Thread-safe: ChunkSnapshot is immutable, and getAsString() creates a new String each call.
     */
    @Override
    public String getBlockState(int wx, int wy, int wz) {
        if (indexOf(wx, wy, wz) < 0) return null;
        int cx = wx >> 4;
        int cz = wz >> 4;
        ChunkSnapshot snapshot = snapshots[(cx - minCx) * czCount + (cz - minCz)];
        return snapshot.getBlockData(wx & 15, wy, wz & 15).getAsString();
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
        if (lx < 0 || wx > maxX || ly < 0 || wy > maxY || lz < 0 || wz > maxZ) {
            return -1;
        }
        return lx * sizeYZ + ly * sizeZ + lz;
    }

    /**
     * Defines the 3D world region to capture for rendering.
     * Implementations provide inclusive min/max bounds and the target world.
     */
    public interface RenderRegion {

        /** Inclusive minimum world X coordinate. */
        int minX();

        /** Inclusive minimum world Y coordinate. */
        int minY();

        /** Inclusive minimum world Z coordinate. */
        int minZ();

        /** Inclusive maximum world X coordinate. */
        int maxX();

        /** Inclusive maximum world Y coordinate. */
        int maxY();

        /** Inclusive maximum world Z coordinate. */
        int maxZ();

        /** The Bukkit world to capture from. */
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

}

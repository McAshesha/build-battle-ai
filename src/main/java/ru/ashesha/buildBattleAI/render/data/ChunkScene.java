package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import lombok.AccessLevel;
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
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public class ChunkScene implements SceneData {

    /** Pre-cached enum values array for fast ordinal-to-material lookup. */
    private static final XMaterial[] MATERIAL_VALUES = XMaterial.values();

    /** Flat array of material ordinals, indexed by {@link #indexOf(int, int, int)}. */
    private final short[] data;

    /** Parallel array of legacy block data values (pre-1.13 compatibility). */
    private final byte[] legacyBlockData;

    /** Parallel array of block state strings for shape/orientation resolution. */
    private final String[] blockStates;

    /** Region dimensions along Y and Z axes, used for flat index calculation. */
    private final int sizeY, sizeZ;

    /** Inclusive world coordinate bounds of the captured region. */
    @Getter
    private final int minX, minY, minZ, maxX, maxY, maxZ;

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
        byte[] legacyBlockData = new byte[data.length];
        String[] blockStates = new String[data.length];

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
                    legacyBlockData[flatIndex] = (byte) snapshot.getData(localX, y, localZ);
                    blockStates[flatIndex] = snapshot.getBlockData(localX, y, localZ).getAsString();
                }
            }
        }

        return new ChunkScene(data, legacyBlockData, blockStates, sizeY, sizeZ,
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

    @Override
    public byte getLegacyBlockData(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0) return 0;
        return legacyBlockData[index];
    }

    @Override
    public String getBlockState(int wx, int wy, int wz) {
        int index = indexOf(wx, wy, wz);
        if (index < 0) return null;
        return blockStates[index];
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
        return lx * sizeY * sizeZ + ly * sizeZ + lz;
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

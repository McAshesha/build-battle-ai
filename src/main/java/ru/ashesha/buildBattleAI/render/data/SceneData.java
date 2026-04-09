package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;

/**
 * Abstract source of block data for the renderer.
 * <p>
 * Defines a bounded 3D region of blocks with methods to query block type,
 * legacy data, and block state at world coordinates. Implementations must
 * be thread-safe — all methods can be called from any thread after construction.
 *
 * @see ChunkScene
 * @see FlatScene
 */
public interface SceneData {

    /** Inclusive minimum world X coordinate of the scene region. */
    int minX();

    /** Inclusive minimum world Y coordinate of the scene region. */
    int minY();

    /** Inclusive minimum world Z coordinate of the scene region. */
    int minZ();

    /** Inclusive maximum world X coordinate of the scene region. */
    int maxX();

    /** Inclusive maximum world Y coordinate of the scene region. */
    int maxY();

    /** Inclusive maximum world Z coordinate of the scene region. */
    int maxZ();

    /**
     * Returns the block material at the given world coordinates.
     * Returns {@link XMaterial#AIR} if the coordinates are outside the scene bounds.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return the block material, never {@code null}
     */
    XMaterial getBlockType(int wx, int wy, int wz);

    /**
     * Returns the legacy block data value at the given world coordinates.
     * Used for pre-1.13 block state disambiguation. Default returns 0.
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return legacy data byte
     */
    default byte getLegacyBlockData(int wx, int wy, int wz) {
        return 0;
    }

    /**
     * Returns the block state string at the given world coordinates
     * (e.g., {@code "minecraft:oak_stairs[facing=north,half=bottom]"}).
     * Default returns {@code null} (no state information available).
     *
     * @param wx world X coordinate
     * @param wy world Y coordinate
     * @param wz world Z coordinate
     * @return the block state string, or {@code null}
     */
    default String getBlockState(int wx, int wy, int wz) {
        return null;
    }

}

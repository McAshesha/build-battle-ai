package ru.ashesha.buildBattleAI.util;

import com.cryptomorin.xseries.XMaterial;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import ru.ashesha.buildBattleAI.render.BlockPalette;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import java.awt.image.BufferedImage;

/**
 * Stateless rendering utilities: image constants, pixel-buffer conversion,
 * and acceleration structures.
 * <p>
 * These helpers are pure functions with no mutable state and no dependency
 * on the {@link ru.ashesha.buildBattleAI.render.CpuRenderer} instance or its thread pool. They are safe to
 * call from any thread at any time, including tests that run without a
 * server or a live plugin.
 */
@UtilityClass
public class RendererUtils {

    /**
     * Output image width in pixels (matches typical ML classifier input size).
     */
    public final int WIDTH = 224;

    /**
     * Output image height in pixels (matches typical ML classifier input size).
     */
    public final int HEIGHT = 224;

    /**
     * Vertical field of view in degrees (matches Minecraft's default FOV).
     */
    public final double FOV = 70.0;

    /**
     * Converts a raw RGB byte array (row-major HWC layout) to a {@link BufferedImage}
     * suitable for PNG serialization or display.
     * <p>
     * Uses bulk {@code setRGB} for a single native transfer instead of per-pixel
     * calls, bypassing per-pixel ColorModel validation for 5–10× speedup.
     *
     * @param rgb byte array of size {@link #WIDTH}×{@link #HEIGHT}×3
     * @return a TYPE_INT_RGB image with the same pixel data
     */
    public BufferedImage toBufferedImage(byte @NonNull [] rgb) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            int r = rgb[i * 3] & 0xFF;
            int g = rgb[i * 3 + 1] & 0xFF;
            int b = rgb[i * 3 + 2] & 0xFF;
            pixels[i] = (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        return image;
    }

    /**
     * Maximum allowed column count (sizeX * sizeZ) for the height-map acceleration structure.
     * A 512x512 horizontal footprint is well above realistic Build Battle plot sizes, and
     * guards against pathological inputs that would otherwise allocate gigabytes or overflow int.
     */
    public final int MAX_HEIGHTMAP_AREA = 512 * 512;

    /**
     * Pre-computes per-column Y bounds for non-transparent blocks.
     * For each (x, z) column, stores the lowest and highest Y containing a visible block.
     * Returns {@code int[sizeX * sizeZ * 2]} where index {@code [i*2]} = minY,
     * {@code [i*2+1]} = maxY. If a column is empty, minY > maxY.
     * <p>
     * This acceleration structure allows the DDA traversal to skip block lookups
     * for voxels that are guaranteed to be empty based on their column's Y range.
     *
     * @param scene the scene to analyze
     * @return flat array of (minY, maxY) pairs per column
     * @throws IllegalArgumentException if the horizontal area exceeds {@link #MAX_HEIGHTMAP_AREA}
     */
    public int[] buildHeightMap(@NonNull SceneData scene) {
        int sizeX = scene.maxX() - scene.minX() + 1;
        int sizeZ = scene.maxZ() - scene.minZ() + 1;
        // Use long arithmetic to detect overflow before allocation, then cap to a sane upper bound.
        long area = (long) sizeX * sizeZ;
        if (area <= 0 || area > MAX_HEIGHTMAP_AREA)
            throw new IllegalArgumentException(
                    "heightmap area " + area + " out of range (max " + MAX_HEIGHTMAP_AREA + ")");
        int nCols = sizeX * sizeZ;
        // Hoist scene Y-bounds out of the init loop — they're constant for the duration of this call.
        int initialMaxY = scene.maxY() + 1;
        int initialMinY = scene.minY() - 1;
        int[] heightMap = new int[nCols * 2];
        // Initialize: minY = beyond max, maxY = below min (empty marker)
        for (int i = 0; i < nCols; i++) {
            int base = i << 1;
            heightMap[base] = initialMaxY;
            heightMap[base + 1] = initialMinY;
        }
        for (int x = scene.minX(); x <= scene.maxX(); x++)
            for (int z = scene.minZ(); z <= scene.maxZ(); z++) {
                int colIdx = ((x - scene.minX()) * sizeZ + (z - scene.minZ())) * 2;
                for (int y = scene.minY(); y <= scene.maxY(); y++) {
                    XMaterial mat = scene.getBlockType(x, y, z);
                    if (BlockPalette.getColor(mat) != -1) {
                        if (y < heightMap[colIdx])
                            heightMap[colIdx] = y;
                        if (y > heightMap[colIdx + 1])
                            heightMap[colIdx + 1] = y;
                    }
                }
            }
        return heightMap;
    }
}

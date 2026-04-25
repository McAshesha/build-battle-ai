package ru.ashesha.buildBattleAI.util;

import lombok.experimental.UtilityClass;

import java.awt.image.BufferedImage;

/**
 * Minecraft map color palette and image-to-map-bytes conversion utility.
 * <p>
 * Minecraft maps use a fixed palette of colors, where each pixel is represented
 * by a single byte index. The palette consists of base colors, each with four
 * brightness variants (multiplied by 180/255, 220/255, 255/255, and 135/255).
 * <p>
 * This implementation uses the 1.8-compatible palette (36 base colors, 144 total
 * indices) to guarantee cross-version compatibility. The first four indices (0–3)
 * are transparent and are used for empty padding areas when preserving aspect ratio.
 * <p>
 * Color matching uses a pre-computed lookup table that quantizes RGB space into a
 * 32×32×32 grid (32 KB), giving O(1) per-pixel matching instead of the naive
 * O(palette_size) linear scan. The table is built once at class-load time.
 * <p>
 * Two input paths are supported:
 * <ul>
 *     <li>{@link BufferedImage} — uses native Graphics2D for resize, then bulk
 *         {@code getRGB} extraction. Convenient for file-loaded images.</li>
 *     <li>{@code int[]} packed RGB — pure-Java bilinear resize, no AWT objects.
 *         Zero-copy path for callers that already have raw pixel data (e.g. the
 *         voxel renderer).</li>
 * </ul>
 */
@UtilityClass
public class MapPalette {

    /**
     * Width and height of a single Minecraft map tile in pixels.
     */
    public final int MAP_SIZE = 128;

    /**
     * Transparent map color index. Used for padding areas when the source image
     * does not fill the entire tile (aspect ratio preservation). Indices 0–3 are
     * all transparent in Minecraft's map rendering.
     */
    public final byte TRANSPARENT = 0;

    /**
     * The 36 base colors available since Minecraft 1.8 (map color IDs 0–35).
     * Each entry is an RGB triplet {@code {R, G, B}}. Base color 0 is transparent
     * and excluded from opaque matching — only opaque pixels use indices 4+.
     */
    final int[][] BASE_COLORS = {
            {0, 0, 0},         //  0: NONE (transparent, never matched)
            {127, 178, 56},    //  1: GRASS
            {247, 233, 163},   //  2: SAND
            {199, 199, 199},   //  3: WOOL
            {255, 0, 0},       //  4: FIRE
            {160, 160, 255},   //  5: ICE
            {167, 167, 167},   //  6: METAL
            {0, 124, 0},       //  7: PLANT
            {255, 255, 255},   //  8: SNOW
            {164, 168, 184},   //  9: CLAY
            {151, 109, 77},    // 10: DIRT
            {112, 112, 112},   // 11: STONE
            {64, 64, 255},     // 12: WATER
            {143, 119, 72},    // 13: WOOD
            {255, 252, 245},   // 14: QUARTZ
            {216, 127, 51},    // 15: COLOR_ORANGE
            {178, 76, 216},    // 16: COLOR_MAGENTA
            {102, 153, 216},   // 17: COLOR_LIGHT_BLUE
            {229, 229, 51},    // 18: COLOR_YELLOW
            {127, 204, 25},    // 19: COLOR_LIGHT_GREEN
            {242, 127, 165},   // 20: COLOR_PINK
            {76, 76, 76},      // 21: COLOR_GRAY
            {153, 153, 153},   // 22: COLOR_LIGHT_GRAY
            {76, 127, 153},    // 23: COLOR_CYAN
            {127, 63, 178},    // 24: COLOR_PURPLE
            {51, 76, 178},     // 25: COLOR_BLUE
            {102, 76, 51},     // 26: COLOR_BROWN
            {102, 127, 51},    // 27: COLOR_GREEN
            {153, 51, 51},     // 28: COLOR_RED
            {25, 25, 25},      // 29: COLOR_BLACK
            {250, 238, 77},    // 30: GOLD
            {92, 219, 213},    // 31: DIAMOND
            {74, 128, 255},    // 32: LAPIS
            {0, 217, 58},      // 33: EMERALD
            {129, 86, 49},     // 34: PODZOL
            {112, 2, 0},       // 35: NETHER
    };

    /**
     * Pre-computed palette: each entry is a packed RGB color at the corresponding
     * map color index. Indices 0–3 are transparent (unused for opaque matching);
     * indices 4–143 are the four shade variants of base colors 1–35.
     */
    final int[] PALETTE;

    /**
     * Shade multipliers applied to each base color to produce four brightness variants.
     * Index 0 = darkest (180/255), index 1 = medium (220/255),
     * index 2 = full brightness, index 3 = deep shadow (135/255).
     */
    private final double[] SHADE_MULTIPLIERS = {180.0 / 255, 220.0 / 255, 1.0, 135.0 / 255};

    /**
     * Number of bits to shift when quantizing each RGB channel for the lookup table.
     * With 5 bits kept (shift of 3), we get a 32×32×32 = 32768-entry table (32 KB).
     */
    private final int QUANTIZE_SHIFT = 3;

    /**
     * Number of buckets per channel in the quantized color space.
     */
    private final int QUANTIZE_SIZE = 256 >> QUANTIZE_SHIFT; // 32

    /**
     * Precomputed QUANTIZE_SIZE² for index arithmetic.
     */
    private final int Q2 = QUANTIZE_SIZE * QUANTIZE_SIZE;

    /**
     * Pre-computed lookup table for O(1) color matching. Maps a quantized RGB
     * triple to the closest palette index. Built once at class-load time.
     * <p>
     * Indexed by: {@code (r >> 3) * 1024 + (g >> 3) * 32 + (b >> 3)}.
     * Size: 32 × 32 × 32 = 32 KB.
     */
    private final byte[] COLOR_LOOKUP;

    static {
        int totalColors = BASE_COLORS.length * 4;
        PALETTE = new int[totalColors];

        for (int base = 0; base < BASE_COLORS.length; base++) {
            int[] rgb = BASE_COLORS[base];
            for (int shade = 0; shade < 4; shade++) {
                int index = base * 4 + shade;
                int r = clamp((int) (rgb[0] * SHADE_MULTIPLIERS[shade]));
                int g = clamp((int) (rgb[1] * SHADE_MULTIPLIERS[shade]));
                int b = clamp((int) (rgb[2] * SHADE_MULTIPLIERS[shade]));
                PALETTE[index] = (r << 16) | (g << 8) | b;
            }
        }

        COLOR_LOOKUP = new byte[QUANTIZE_SIZE * Q2];
        buildLookupTable();
    }

    /**
     * Populates {@link #COLOR_LOOKUP} by scanning every cell in the quantized
     * 32×32×32 RGB grid and finding the nearest opaque palette color via
     * Euclidean distance. Called once during class initialization.
     */
    private void buildLookupTable() {
        int half = 1 << (QUANTIZE_SHIFT - 1);

        for (int ri = 0; ri < QUANTIZE_SIZE; ri++) {
            int r = (ri << QUANTIZE_SHIFT) + half;
            for (int gi = 0; gi < QUANTIZE_SIZE; gi++) {
                int g = (gi << QUANTIZE_SHIFT) + half;
                for (int bi = 0; bi < QUANTIZE_SIZE; bi++) {
                    int b = (bi << QUANTIZE_SHIFT) + half;
                    COLOR_LOOKUP[ri * Q2 + gi * QUANTIZE_SIZE + bi] = matchColorLinear(r, g, b);
                }
            }
        }
    }

    /**
     * Linear-scan nearest-color match against the palette. Used only during
     * lookup table construction — never at runtime.
     */
    private byte matchColorLinear(int r, int g, int b) {
        int bestIndex = 4;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 4; i < PALETTE.length; i++) {
            int pr = (PALETTE[i] >> 16) & 0xFF;
            int pg = (PALETTE[i] >> 8) & 0xFF;
            int pb = PALETTE[i] & 0xFF;

            int dr = r - pr;
            int dg = g - pg;
            int db = b - pb;
            int dist = dr * dr + dg * dg + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = i;
                if (dist == 0)
                    break;
            }
        }

        return (byte) bestIndex;
    }

    /**
     * Finds the closest map palette index for the given RGB color in O(1) time
     * using the pre-computed quantized lookup table.
     *
     * @param rgb packed RGB color ({@code 0xRRGGBB})
     * @return the closest map palette index (4–143)
     */
    public byte matchColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        return COLOR_LOOKUP[(r >> QUANTIZE_SHIFT) * Q2
                + (g >> QUANTIZE_SHIFT) * QUANTIZE_SIZE
                + (b >> QUANTIZE_SHIFT)];
    }

    // ── BufferedImage path ─────────────────────────────────────────────────

    /**
     * Converts a {@link BufferedImage} to a 128×128 map palette byte array.
     * <p>
     * The image is scaled to 128×128 via native Graphics2D bilinear resize,
     * then bulk-converted to palette indices.
     *
     * @param image the source image (any size)
     * @return a 128×128 byte array of palette indices
     */
    public byte[] imageToMapColors(BufferedImage image) {
        BufferedImage scaled = resizeImage(image, MAP_SIZE, MAP_SIZE);
        int[] pixels = scaled.getRGB(0, 0, MAP_SIZE, MAP_SIZE, null, 0, MAP_SIZE);
        return rgbToMapColors(pixels);
    }

    /**
     * Converts a {@link BufferedImage} to canvas palette bytes, preserving
     * aspect ratio. Uses native Graphics2D for the resize step.
     * <p>
     * The image is scaled to fit inside the canvas while preserving its aspect
     * ratio. The scaled image is centered, and remaining border area is filled
     * with {@link #TRANSPARENT}.
     *
     * @param image        the source image (any size)
     * @param canvasWidth  total canvas width in pixels (typically gridWidth × 128)
     * @param canvasHeight total canvas height in pixels (typically gridHeight × 128)
     * @return a {@code canvasWidth × canvasHeight} byte array of palette indices
     */
    public byte[] imageToCanvasColors(BufferedImage image,
                                      int canvasWidth, int canvasHeight) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();

        // Fit-inside: find the largest scale that fits within the canvas
        double scale = Math.min((double) canvasWidth / srcW, (double) canvasHeight / srcH);
        int scaledW = Math.max(1, (int) (srcW * scale));
        int scaledH = Math.max(1, (int) (srcH * scale));

        // Native bilinear resize via Graphics2D, then bulk pixel extraction
        BufferedImage scaled = resizeImage(image, scaledW, scaledH);
        int[] scaledPixels = scaled.getRGB(0, 0, scaledW, scaledH, null, 0, scaledW);

        int offsetX = (canvasWidth - scaledW) / 2;
        int offsetY = (canvasHeight - scaledH) / 2;
        return buildCanvas(scaledPixels, scaledW, scaledH, canvasWidth, canvasHeight, offsetX, offsetY);
    }

    // ── Raw int[] pixel path (no AWT objects) ──────────────────────────────

    /**
     * Converts packed RGB pixels to a 128×128 map palette byte array.
     * Pure-Java path — no {@link BufferedImage} created.
     *
     * @param pixels packed RGB array ({@code 0xRRGGBB}), length must be at least
     *               128×128
     * @return a 128×128 byte array of palette indices
     */
    public byte[] rgbToMapColors(int[] pixels) {
        int total = MAP_SIZE * MAP_SIZE;
        byte[] colors = new byte[total];

        for (int i = 0; i < total; i++) {
            int rgb = pixels[i];
            colors[i] = COLOR_LOOKUP[
                    (((rgb >> 16) & 0xFF) >> QUANTIZE_SHIFT) * Q2
                            + (((rgb >> 8) & 0xFF) >> QUANTIZE_SHIFT) * QUANTIZE_SIZE
                            + ((rgb & 0xFF) >> QUANTIZE_SHIFT)];
        }

        return colors;
    }

    /**
     * Converts packed RGB pixels to canvas palette bytes, preserving aspect ratio.
     * Pure-Java path — uses {@link #bilinearResize} instead of Graphics2D.
     * <p>
     * Ideal for callers that already hold raw pixel data (e.g. the voxel renderer)
     * and want to avoid constructing a {@link BufferedImage} just to pass it here.
     *
     * @param pixels       packed RGB array ({@code 0xRRGGBB}), row-major,
     *                     length must be at least {@code srcW × srcH}
     * @param srcW         source image width
     * @param srcH         source image height
     * @param canvasWidth  total canvas width in pixels
     * @param canvasHeight total canvas height in pixels
     * @return a {@code canvasWidth × canvasHeight} byte array of palette indices
     */
    public byte[] rgbToCanvasColors(int[] pixels, int srcW, int srcH,
                                    int canvasWidth, int canvasHeight) {
        double scale = Math.min((double) canvasWidth / srcW, (double) canvasHeight / srcH);
        int scaledW = Math.max(1, (int) (srcW * scale));
        int scaledH = Math.max(1, (int) (srcH * scale));

        // Pure-Java bilinear resize — no BufferedImage, no Graphics2D
        int[] scaledPixels = bilinearResize(pixels, srcW, srcH, scaledW, scaledH);

        int offsetX = (canvasWidth - scaledW) / 2;
        int offsetY = (canvasHeight - scaledH) / 2;
        return buildCanvas(scaledPixels, scaledW, scaledH, canvasWidth, canvasHeight, offsetX, offsetY);
    }

    // ── Raw byte[] HWC pixel path (renderer output) ─────────────────────

    /**
     * Converts HWC byte pixels to canvas palette bytes, preserving aspect ratio.
     * Zero-intermediate-allocation path for the voxel renderer's native output
     * format: a flat {@code byte[]} of interleaved R, G, B triplets in row-major
     * order (Height-Width-Channel).
     * <p>
     * Performs bilinear resize and palette lookup in a single fused pass —
     * no intermediate {@code int[]}, no {@link BufferedImage}, no {@code Graphics2D}.
     * The only allocations are the output canvas and a small per-row scratch
     * (the source pixel sampling is done inline).
     *
     * @param hwcPixels    HWC byte array ({@code [R,G,B, R,G,B, ...]}, row-major),
     *                     length must be at least {@code srcW × srcH × 3}
     * @param srcW         source image width
     * @param srcH         source image height
     * @param canvasWidth  total canvas width in pixels
     * @param canvasHeight total canvas height in pixels
     * @return a {@code canvasWidth × canvasHeight} byte array of palette indices
     */
    public byte[] hwcToCanvasColors(byte[] hwcPixels, int srcW, int srcH,
                                    int canvasWidth, int canvasHeight) {
        double scale = Math.min((double) canvasWidth / srcW, (double) canvasHeight / srcH);
        int scaledW = Math.max(1, (int) (srcW * scale));
        int scaledH = Math.max(1, (int) (srcH * scale));

        int offsetX = (canvasWidth - scaledW) / 2;
        int offsetY = (canvasHeight - scaledH) / 2;

        byte[] canvas = new byte[canvasWidth * canvasHeight];

        // Scale factors for bilinear sampling
        double xRatio = srcW > 1 ? (double) (srcW - 1) / (scaledW - 1) : 0;
        double yRatio = srcH > 1 ? (double) (srcH - 1) / (scaledH - 1) : 0;

        for (int y = 0; y < scaledH; y++) {
            double sy = y * yRatio;
            int y0 = (int) sy;
            int y1 = Math.min(y0 + 1, srcH - 1);
            double fy = sy - y0;
            double ify = 1.0 - fy;

            int srcRow0 = y0 * srcW * 3;
            int srcRow1 = y1 * srcW * 3;

            int canvasRowStart = (offsetY + y) * canvasWidth + offsetX;

            for (int x = 0; x < scaledW; x++) {
                double sx = x * xRatio;
                int x0 = (int) sx;
                int x1 = Math.min(x0 + 1, srcW - 1);
                double fx = sx - x0;
                double ifx = 1.0 - fx;

                double w00 = ifx * ify;
                double w10 = fx * ify;
                double w01 = ifx * fy;
                double w11 = fx * fy;

                // Sample four HWC pixels and blend per channel
                int i00 = srcRow0 + x0 * 3;
                int i10 = srcRow0 + x1 * 3;
                int i01 = srcRow1 + x0 * 3;
                int i11 = srcRow1 + x1 * 3;

                int r = (int) ((hwcPixels[i00] & 0xFF) * w00 + (hwcPixels[i10] & 0xFF) * w10
                        + (hwcPixels[i01] & 0xFF) * w01 + (hwcPixels[i11] & 0xFF) * w11);
                int g = (int) ((hwcPixels[i00 + 1] & 0xFF) * w00 + (hwcPixels[i10 + 1] & 0xFF) * w10
                        + (hwcPixels[i01 + 1] & 0xFF) * w01 + (hwcPixels[i11 + 1] & 0xFF) * w11);
                int b = (int) ((hwcPixels[i00 + 2] & 0xFF) * w00 + (hwcPixels[i10 + 2] & 0xFF) * w10
                        + (hwcPixels[i01 + 2] & 0xFF) * w01 + (hwcPixels[i11 + 2] & 0xFF) * w11);

                // Straight to palette lookup — no intermediate int[] or byte[]
                canvas[canvasRowStart + x] = COLOR_LOOKUP[
                        (r >> QUANTIZE_SHIFT) * Q2
                                + (g >> QUANTIZE_SHIFT) * QUANTIZE_SIZE
                                + (b >> QUANTIZE_SHIFT)];
            }
        }

        return canvas;
    }

    // ── shared internals ───────────────────────────────────────────────────

    /**
     * Builds the final canvas byte array by converting scaled RGB pixels directly
     * to palette indices at their correct canvas positions. No intermediate
     * {@code byte[]} is allocated — each pixel goes straight from
     * {@code int[] scaledPixels} to the output canvas.
     * <p>
     * Padding areas (where the scaled image does not cover the canvas) are left
     * as zero ({@link #TRANSPARENT}) from the array's default initialization.
     *
     * @param scaledPixels resized packed RGB pixels, row-major
     * @param scaledW      width of the scaled pixel data
     * @param scaledH      height of the scaled pixel data
     * @param canvasW      total canvas width
     * @param canvasH      total canvas height
     * @param offsetX      horizontal offset to center the image
     * @param offsetY      vertical offset to center the image
     * @return the canvas byte array
     */
    private byte[] buildCanvas(int[] scaledPixels, int scaledW, int scaledH,
                               int canvasW, int canvasH, int offsetX, int offsetY) {
        byte[] canvas = new byte[canvasW * canvasH];

        for (int y = 0; y < scaledH; y++) {
            int canvasRowStart = (offsetY + y) * canvasW + offsetX;
            int srcRowStart = y * scaledW;

            for (int x = 0; x < scaledW; x++) {
                int rgb = scaledPixels[srcRowStart + x];
                canvas[canvasRowStart + x] = COLOR_LOOKUP[
                        (((rgb >> 16) & 0xFF) >> QUANTIZE_SHIFT) * Q2
                                + (((rgb >> 8) & 0xFF) >> QUANTIZE_SHIFT) * QUANTIZE_SIZE
                                + ((rgb & 0xFF) >> QUANTIZE_SHIFT)];
            }
        }

        return canvas;
    }

    /**
     * Extracts a single 128×128 tile from a pre-computed canvas byte array.
     * Pure array copies — no image processing.
     *
     * @param canvas      the full canvas palette bytes
     * @param canvasWidth total canvas width in pixels
     * @param tileX       zero-based column index of the tile
     * @param tileY       zero-based row index of the tile
     * @return a 128×128 byte array for this tile
     */
    public byte[] extractTile(byte[] canvas, int canvasWidth,
                              int tileX, int tileY) {
        byte[] tile = new byte[MAP_SIZE * MAP_SIZE];
        int srcX = tileX * MAP_SIZE;
        int srcY = tileY * MAP_SIZE;

        for (int y = 0; y < MAP_SIZE; y++)
            System.arraycopy(canvas, (srcY + y) * canvasWidth + srcX,
                    tile, y * MAP_SIZE, MAP_SIZE);

        return tile;
    }

    /**
     * Pure-Java bilinear resize of packed RGB pixel data.
     * <p>
     * Interpolates between the four nearest source pixels for each output pixel,
     * producing smooth scaling without any AWT dependency. For map art where the
     * palette is only 140 colors, the quality is indistinguishable from Graphics2D.
     *
     * @param src  source pixels (packed RGB, row-major)
     * @param srcW source width
     * @param srcH source height
     * @param dstW target width
     * @param dstH target height
     * @return resized packed RGB array of size {@code dstW × dstH}
     */
    int[] bilinearResize(int[] src, int srcW, int srcH, int dstW, int dstH) {
        int[] dst = new int[dstW * dstH];

        // Precompute scale factors. We map the center of each output pixel to
        // the corresponding position in the source image.
        double xRatio = srcW > 1 ? (double) (srcW - 1) / (dstW - 1) : 0;
        double yRatio = srcH > 1 ? (double) (srcH - 1) / (dstH - 1) : 0;

        for (int y = 0; y < dstH; y++) {
            double sy = y * yRatio;
            int y0 = (int) sy;
            int y1 = Math.min(y0 + 1, srcH - 1);
            double fy = sy - y0;
            double ify = 1.0 - fy;

            int srcRow0 = y0 * srcW;
            int srcRow1 = y1 * srcW;

            for (int x = 0; x < dstW; x++) {
                double sx = x * xRatio;
                int x0 = (int) sx;
                int x1 = Math.min(x0 + 1, srcW - 1);
                double fx = sx - x0;
                double ifx = 1.0 - fx;

                // Sample the four neighboring pixels
                int c00 = src[srcRow0 + x0];
                int c10 = src[srcRow0 + x1];
                int c01 = src[srcRow1 + x0];
                int c11 = src[srcRow1 + x1];

                // Bilinear weights
                double w00 = ifx * ify;
                double w10 = fx * ify;
                double w01 = ifx * fy;
                double w11 = fx * fy;

                // Blend each channel independently
                int r = (int) (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                        + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11);
                int g = (int) (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                        + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11);
                int b = (int) ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
                        + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11);

                dst[y * dstW + x] = (r << 16) | (g << 8) | b;
            }
        }

        return dst;
    }

    /**
     * Scales a {@link BufferedImage} using native Graphics2D bilinear interpolation.
     *
     * @param source the source image
     * @param width  target width
     * @param height target height
     * @return a new image of the target size
     */
    public BufferedImage resizeImage(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = result.createGraphics();
        g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return result;
    }

    /**
     * Clamps a value to the [0, 255] range.
     */
    private int clamp(int value) {
        if (value < 0)
            return 0;
        return Math.min(value, 255);
    }
}

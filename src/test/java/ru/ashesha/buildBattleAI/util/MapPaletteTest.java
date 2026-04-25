package ru.ashesha.buildBattleAI.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MapPalette} — Minecraft map color palette utility.
 * <p>
 * These tests verify the lookup-table-based color matching, aspect-ratio-preserving
 * canvas conversion, tile extraction, and image scaling. All tests run without
 * a Bukkit server.
 */
class MapPaletteTest {

    // ── palette structure tests ────────────────────────────────────────────

    @Test
    void paletteHasExpectedSize() {
        // 36 base colors × 4 shade variants = 144 entries
        assertEquals(144, MapPalette.PALETTE.length);
    }

    @Test
    void transparentIndicesAreBlack() {
        // Base color 0 is (0,0,0), so all four shade variants are also (0,0,0)
        for (int i = 0; i < 4; i++)
            assertEquals(0, MapPalette.PALETTE[i]);
    }

    @Test
    void baseColorsHaveFourShadeVariants() {
        // Grass base color (index 1) = {127, 178, 56}
        // Shade 2 (full brightness) is at index 1*4+2 = 6
        int grassFull = MapPalette.PALETTE[6];
        int grassR = (grassFull >> 16) & 0xFF;
        int grassG = (grassFull >> 8) & 0xFF;
        int grassB = grassFull & 0xFF;

        assertEquals(127, grassR);
        assertEquals(178, grassG);
        assertEquals(56, grassB);
    }

    @Test
    void darkShadeIsDarkerThanFull() {
        // Shade 0 (darkest, 180/255) should have lower RGB than shade 2 (full)
        int darkGrass = MapPalette.PALETTE[4]; // base 1, shade 0
        int fullGrass = MapPalette.PALETTE[6]; // base 1, shade 2

        int darkR = (darkGrass >> 16) & 0xFF;
        int fullR = (fullGrass >> 16) & 0xFF;
        assertTrue(darkR < fullR, "Dark shade R should be less than full: " + darkR + " < " + fullR);
    }

    // ── matchColor tests (lookup-table based) ──────────────────────────────

    @Test
    void exactColorMatchReturnsCorrectIndex() {
        // Full brightness grass = palette index 6 = RGB(127, 178, 56)
        byte index = MapPalette.matchColor(0x7FB238);
        assertEquals(6, index & 0xFF);
    }

    @Test
    void pureWhiteMatchesSnowColor() {
        // Pure white should match SNOW base color (index 8) full shade (index 34)
        byte index = MapPalette.matchColor(0xFFFFFF);
        int matched = index & 0xFF;

        // Snow full = index 34 (base 8, shade 2): RGB(255, 255, 255)
        assertEquals(34, matched);
    }

    @Test
    void pureBlackMatchesBlackColor() {
        // Pure black should match COLOR_BLACK (base 29) deep shade (index 119)
        byte index = MapPalette.matchColor(0x000000);
        int matched = index & 0xFF;

        // Should be one of the COLOR_BLACK variants (indices 116-119)
        assertTrue(matched >= 116 && matched <= 119,
                "Black should match COLOR_BLACK range, got " + matched);
    }

    @Test
    void pureRedMatchesFireColor() {
        // Pure red (255,0,0) should match FIRE base color (index 4) full shade
        byte index = MapPalette.matchColor(0xFF0000);
        int matched = index & 0xFF;

        // Fire full = index 18 (base 4, shade 2): RGB(255, 0, 0)
        assertEquals(18, matched);
    }

    @Test
    void matchColorNeverReturnsTransparent() {
        // No matter what color we pass, indices 0-3 should never be returned
        byte black = MapPalette.matchColor(0x000000);
        assertTrue((black & 0xFF) >= 4, "Should not return transparent index");

        byte white = MapPalette.matchColor(0xFFFFFF);
        assertTrue((white & 0xFF) >= 4, "Should not return transparent index");
    }

    @Test
    void matchColorResultIsInValidRange() {
        // Test a variety of colors and verify results are in [4, 143]
        int[] testColors = {0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF,
                0x808080, 0xFFA500, 0x800080, 0x123456, 0xABCDEF};
        for (int color : testColors) {
            int index = MapPalette.matchColor(color) & 0xFF;
            assertTrue(index >= 4 && index < 144,
                    "Index " + index + " out of range for color 0x"
                            + Integer.toHexString(color));
        }
    }

    @Test
    void lookupTableCoversAllQuantizedCells() {
        // Every possible quantized RGB value should produce a valid index.
        // Test a sample of values across the full 0–255 range per channel.
        for (int r = 0; r < 256; r += 17)
            for (int g = 0; g < 256; g += 17)
                for (int b = 0; b < 256; b += 17) {
                    int rgb = (r << 16) | (g << 8) | b;
                    int index = MapPalette.matchColor(rgb) & 0xFF;
                    assertTrue(index >= 4 && index < 144,
                            "Invalid index " + index + " for rgb("
                                    + r + "," + g + "," + b + ")");
                }
    }

    // ── imageToMapColors tests ─────────────────────────────────────────────

    @Test
    void imageToMapColorsProducesCorrectSize() {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        byte[] colors = MapPalette.imageToMapColors(img);
        assertEquals(MapPalette.MAP_SIZE * MapPalette.MAP_SIZE, colors.length);
    }

    @Test
    void solidColorImageProducesUniformResult() {
        // Create a solid red image
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++)
                img.setRGB(x, y, 0xFF0000);

        byte[] colors = MapPalette.imageToMapColors(img);

        // All pixels should have the same palette index
        byte expected = colors[0];
        for (int i = 1; i < colors.length; i++)
            assertEquals(expected, colors[i], "Pixel " + i + " differs from expected");
    }

    @Test
    void smallImageIsUpscaled() {
        // A 1x1 red pixel image should produce a uniform 128x128 map
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFF0000);

        byte[] colors = MapPalette.imageToMapColors(img);
        assertEquals(128 * 128, colors.length);

        // All should match red
        byte redIndex = MapPalette.matchColor(0xFF0000);
        for (int i = 0; i < colors.length; i++)
            assertEquals(redIndex, colors[i]);
    }

    // ── imageToCanvasColors tests (aspect ratio) ───────────────────────────

    @Test
    void squareImageOnSquareCanvasHasNoPadding() {
        // 100×100 image on 128×128 canvas — fills entirely, no transparent pixels
        BufferedImage img = solidImage(100, 100, 0xFF0000);
        byte[] canvas = MapPalette.imageToCanvasColors(img, 128, 128);

        assertEquals(128 * 128, canvas.length);
        // No transparent pixels — image fills the whole canvas
        for (byte b : canvas)
            assertNotEquals(MapPalette.TRANSPARENT, b,
                    "Square image on square canvas should have no padding");
    }

    @Test
    void wideImageGetsPaddingTopAndBottom() {
        // 200×50 image (4:1 aspect) on 128×128 canvas.
        // Scaled to 128×32, centered vertically → 48 rows padding top, 48 bottom.
        BufferedImage img = solidImage(200, 50, 0xFF0000);
        byte[] canvas = MapPalette.imageToCanvasColors(img, 128, 128);

        // Top-left corner should be transparent (padding)
        assertEquals(MapPalette.TRANSPARENT, canvas[0]);

        // Center pixel should be opaque (the image)
        int centerIdx = 64 * 128 + 64;
        assertNotEquals(MapPalette.TRANSPARENT, canvas[centerIdx]);

        // Bottom-left corner should be transparent (padding)
        assertEquals(MapPalette.TRANSPARENT, canvas[127 * 128]);
    }

    @Test
    void tallImageGetsPaddingLeftAndRight() {
        // 50×200 image (1:4 aspect) on 128×128 canvas.
        // Scaled to 32×128, centered horizontally → 48 cols padding left, 48 right.
        BufferedImage img = solidImage(50, 200, 0xFF0000);
        byte[] canvas = MapPalette.imageToCanvasColors(img, 128, 128);

        // Top-left corner should be transparent (padding)
        assertEquals(MapPalette.TRANSPARENT, canvas[0]);

        // Center pixel should be opaque
        int centerIdx = 64 * 128 + 64;
        assertNotEquals(MapPalette.TRANSPARENT, canvas[centerIdx]);

        // Top-right corner should be transparent (padding)
        assertEquals(MapPalette.TRANSPARENT, canvas[127]);
    }

    @Test
    void canvasColorsSizeMatchesDimensions() {
        BufferedImage img = solidImage(100, 100, 0xFF0000);
        // 3×2 grid = 384×256 canvas
        byte[] canvas = MapPalette.imageToCanvasColors(img, 384, 256);
        assertEquals(384 * 256, canvas.length);
    }

    @Test
    void wideImageOnMultiTileCanvasPreservesAspectRatio() {
        // 400×100 image (4:1) on a 3×3 canvas (384×384).
        // Should scale to 384×96, with (384-96)/2 = 144 rows of padding top/bottom.
        BufferedImage img = solidImage(400, 100, 0x00FF00);
        byte[] canvas = MapPalette.imageToCanvasColors(img, 384, 384);

        // Row 0 should be fully transparent (padding area)
        for (int x = 0; x < 384; x++)
            assertEquals(MapPalette.TRANSPARENT, canvas[x],
                    "Top padding row should be transparent at x=" + x);

        // A row in the center should have opaque pixels
        int centerRow = 192;
        boolean hasOpaque = false;
        for (int x = 0; x < 384; x++)
            if (canvas[centerRow * 384 + x] != MapPalette.TRANSPARENT)
                hasOpaque = true;
        assertTrue(hasOpaque, "Center row should have opaque pixels");
    }

    // ── extractTile tests ──────────────────────────────────────────────────

    @Test
    void extractTileFromSingleTileCanvas() {
        // Single tile (128×128) canvas — extracting (0,0) should be identity
        byte[] canvas = new byte[128 * 128];
        for (int i = 0; i < canvas.length; i++)
            canvas[i] = (byte) ((i % 140) + 4); // fill with valid palette indices

        byte[] tile = MapPalette.extractTile(canvas, 128, 0, 0);
        assertArrayEquals(canvas, tile);
    }

    @Test
    void extractTileFromMultiTileCanvas() {
        // 2×2 grid (256×256 canvas). Fill each quadrant with a different value.
        byte[] canvas = new byte[256 * 256];
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                byte val;
                if (x < 128 && y < 128)
                    val = 10; // top-left
                else if (x >= 128 && y < 128)
                    val = 20; // top-right
                else if (x < 128)
                    val = 30; // bottom-left
                else
                    val = 40; // bottom-right
                canvas[y * 256 + x] = val;
            }

        // Tile (0,0) = top-left
        byte[] tl = MapPalette.extractTile(canvas, 256, 0, 0);
        assertEquals(10, tl[0]);
        assertEquals(10, tl[128 * 128 - 1]);

        // Tile (1,0) = top-right
        byte[] tr = MapPalette.extractTile(canvas, 256, 1, 0);
        assertEquals(20, tr[0]);

        // Tile (0,1) = bottom-left
        byte[] bl = MapPalette.extractTile(canvas, 256, 0, 1);
        assertEquals(30, bl[0]);

        // Tile (1,1) = bottom-right
        byte[] br = MapPalette.extractTile(canvas, 256, 1, 1);
        assertEquals(40, br[0]);
    }

    @Test
    void extractTileSize() {
        byte[] canvas = new byte[384 * 384];
        byte[] tile = MapPalette.extractTile(canvas, 384, 2, 2);
        assertEquals(128 * 128, tile.length);
    }

    // ── resizeImage tests ──────────────────────────────────────────────────

    @Test
    void resizeImageProducesCorrectDimensions() {
        BufferedImage src = new BufferedImage(500, 300, BufferedImage.TYPE_INT_RGB);
        BufferedImage result = MapPalette.resizeImage(src, 128, 128);

        assertEquals(128, result.getWidth());
        assertEquals(128, result.getHeight());
    }

    @Test
    void resizeImagePreservesColor() {
        // A solid-color image should remain solid after resize
        BufferedImage src = solidImage(50, 50, 0x336699);

        BufferedImage result = MapPalette.resizeImage(src, 10, 10);
        // Center pixel should be close to the original color
        int centerColor = result.getRGB(5, 5) & 0xFFFFFF;
        assertEquals(0x336699, centerColor);
    }

    // ── rgbToCanvasColors (raw pixel path) ────────────────────────────────

    @Test
    void rgbToCanvasColorsSameAsImagePath() {
        // A solid red 100×100 image should produce the same canvas via both paths
        BufferedImage img = solidImage(100, 100, 0xFF0000);
        byte[] fromImage = MapPalette.imageToCanvasColors(img, 128, 128);

        int[] pixels = solidPixels(100, 100, 0xFF0000);
        byte[] fromRgb = MapPalette.rgbToCanvasColors(pixels, 100, 100, 128, 128);

        assertArrayEquals(fromImage, fromRgb);
    }

    @Test
    void rgbToCanvasColorsPreservesAspectRatio() {
        // 200×50 image (4:1) on 128×128 canvas — same test as BufferedImage version
        int[] pixels = solidPixels(200, 50, 0xFF0000);
        byte[] canvas = MapPalette.rgbToCanvasColors(pixels, 200, 50, 128, 128);

        assertEquals(128 * 128, canvas.length);
        // Top-left corner is padding
        assertEquals(MapPalette.TRANSPARENT, canvas[0]);
        // Center has image content
        assertNotEquals(MapPalette.TRANSPARENT, canvas[64 * 128 + 64]);
    }

    @Test
    void rgbToMapColorsProducesCorrectSize() {
        int[] pixels = solidPixels(128, 128, 0x0000FF);
        byte[] colors = MapPalette.rgbToMapColors(pixels);
        assertEquals(128 * 128, colors.length);
    }

    @Test
    void rgbToMapColorsMatchesImagePath() {
        BufferedImage img = solidImage(128, 128, 0x0000FF);
        byte[] fromImage = MapPalette.imageToMapColors(img);

        int[] pixels = img.getRGB(0, 0, 128, 128, null, 0, 128);
        byte[] fromRgb = MapPalette.rgbToMapColors(pixels);

        assertArrayEquals(fromImage, fromRgb);
    }

    // ── bilinearResize tests ───────────────────────────────────────────────

    @Test
    void bilinearResizeSolidColor() {
        // A solid-color image should remain solid after resize.
        // Allow ±1 per channel for floating-point rounding in weight computation.
        int[] src = solidPixels(50, 50, 0x336699);
        int[] dst = MapPalette.bilinearResize(src, 50, 50, 10, 10);

        assertEquals(100, dst.length);
        for (int pixel : dst) {
            int dr = Math.abs(((pixel >> 16) & 0xFF) - 0x33);
            int dg = Math.abs(((pixel >> 8) & 0xFF) - 0x66);
            int db = Math.abs((pixel & 0xFF) - 0x99);
            assertTrue(dr <= 1 && dg <= 1 && db <= 1,
                    "Pixel 0x" + Integer.toHexString(pixel) + " too far from 0x336699");
        }
    }

    @Test
    void bilinearResizePreservesDimensions() {
        int[] src = solidPixels(200, 100, 0xFF0000);
        int[] dst = MapPalette.bilinearResize(src, 200, 100, 50, 25);
        assertEquals(50 * 25, dst.length);
    }

    @Test
    void bilinearResizeSinglePixel() {
        int[] src = {0xAABBCC};
        int[] dst = MapPalette.bilinearResize(src, 1, 1, 10, 10);
        assertEquals(100, dst.length);
        // All output pixels should be the same as the single source pixel
        for (int pixel : dst)
            assertEquals(0xAABBCC, pixel);
    }

    // ── hwcToCanvasColors (renderer byte[] path) ─────────────────────────

    @Test
    void hwcSolidRedMatchesIntPath() {
        // Solid red 10×10 image, compare HWC path vs int[] path
        int w = 10, h = 10;
        int[] intPixels = solidPixels(w, h, 0xFF0000);
        byte[] hwcPixels = solidHwcPixels(w, h, 255, 0, 0);

        byte[] fromInt = MapPalette.rgbToCanvasColors(intPixels, w, h, 128, 128);
        byte[] fromHwc = MapPalette.hwcToCanvasColors(hwcPixels, w, h, 128, 128);

        assertArrayEquals(fromInt, fromHwc);
    }

    @Test
    void hwcPreservesAspectRatio() {
        // 200×50 HWC image on 128×128 canvas — padding at top and bottom
        byte[] hwcPixels = solidHwcPixels(200, 50, 255, 0, 0);
        byte[] canvas = MapPalette.hwcToCanvasColors(hwcPixels, 200, 50, 128, 128);

        assertEquals(128 * 128, canvas.length);
        assertEquals(MapPalette.TRANSPARENT, canvas[0]);
        assertNotEquals(MapPalette.TRANSPARENT, canvas[64 * 128 + 64]);
    }

    @Test
    void hwcCanvasSize() {
        byte[] hwcPixels = solidHwcPixels(50, 50, 0, 255, 0);
        byte[] canvas = MapPalette.hwcToCanvasColors(hwcPixels, 50, 50, 384, 256);
        assertEquals(384 * 256, canvas.length);
    }

    @Test
    void hwcSinglePixelFillsCanvas() {
        // A 1×1 green pixel should fill the entire 128×128 canvas with one color
        byte[] hwcPixels = {0, (byte) 255, 0}; // R=0, G=255, B=0
        byte[] canvas = MapPalette.hwcToCanvasColors(hwcPixels, 1, 1, 128, 128);

        byte expected = MapPalette.matchColor(0x00FF00);
        for (int i = 0; i < canvas.length; i++)
            assertEquals(expected, canvas[i], "Pixel " + i + " differs");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Creates a solid-color HWC byte pixel array. */
    private static byte[] solidHwcPixels(int w, int h, int r, int g, int b) {
        byte[] pixels = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            pixels[i * 3] = (byte) r;
            pixels[i * 3 + 1] = (byte) g;
            pixels[i * 3 + 2] = (byte) b;
        }
        return pixels;
    }


    /** Creates a solid-color packed RGB pixel array. */
    private static int[] solidPixels(int w, int h, int rgb) {
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, rgb);
        return pixels;
    }

    /** Creates a solid-color BufferedImage. */
    private static BufferedImage solidImage(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }
}

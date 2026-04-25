package ru.ashesha.buildBattleAI.util;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.FlatScene;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RendererUtils}: image conversion and height-map construction.
 * <p>
 * These are pure utility functions with no Bukkit dependency — they can run
 * without a server or live plugin instance.
 */
class RendererUtilsTest {

    private static final short AIR = (short) XMaterial.AIR.ordinal();

    // -- constants ----------------------------------------------------------

    @Test
    void imageConstantsAre224() {
        assertEquals(224, RendererUtils.WIDTH);
        assertEquals(224, RendererUtils.HEIGHT);
    }

    @Test
    void fovIs70() {
        assertEquals(70.0, RendererUtils.FOV, 0.001);
    }

    // -- toBufferedImage tests ----------------------------------------------

    @Test
    void toBufferedImageReturnsCorrectDimensions() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        assertEquals(RendererUtils.WIDTH, image.getWidth());
        assertEquals(RendererUtils.HEIGHT, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
    }

    @Test
    void toBufferedImageBlackPixels() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        // All zeros = black
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        // Check a sample of pixels
        assertEquals(0x000000, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x000000, image.getRGB(100, 100) & 0xFFFFFF);
    }

    @Test
    void toBufferedImageWhitePixels() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        Arrays.fill(rgb, (byte) 0xFF);
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        assertEquals(0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0xFFFFFF, image.getRGB(223, 223) & 0xFFFFFF);
    }

    @Test
    void toBufferedImagePreservesRedChannel() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        // Set first pixel to pure red (R=255, G=0, B=0)
        rgb[0] = (byte) 0xFF;
        rgb[1] = 0;
        rgb[2] = 0;
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        assertEquals(0xFF0000, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void toBufferedImagePreservesGreenChannel() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        // Set first pixel to pure green
        rgb[0] = 0;
        rgb[1] = (byte) 0xFF;
        rgb[2] = 0;
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        assertEquals(0x00FF00, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void toBufferedImagePreservesBlueChannel() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        // Set first pixel to pure blue
        rgb[0] = 0;
        rgb[1] = 0;
        rgb[2] = (byte) 0xFF;
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        assertEquals(0x0000FF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void toBufferedImageMixedColor() {
        byte[] rgb = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];
        // Set pixel at (1, 0) to R=128, G=64, B=32
        int pixelOffset = 1 * 3;
        rgb[pixelOffset] = (byte) 128;
        rgb[pixelOffset + 1] = (byte) 64;
        rgb[pixelOffset + 2] = (byte) 32;
        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        int pixel = image.getRGB(1, 0) & 0xFFFFFF;
        assertEquals(128, (pixel >> 16) & 0xFF);
        assertEquals(64, (pixel >> 8) & 0xFF);
        assertEquals(32, pixel & 0xFF);
    }

    // -- buildHeightMap tests -----------------------------------------------

    @Test
    void heightMapEmptySceneHasInvertedBounds() {
        // A scene full of AIR should produce columns where minY > maxY
        short[] data = airArray(4 * 4 * 4);
        FlatScene scene = new FlatScene(data, 0, 0, 0, 4, 4, 4);

        int[] heightMap = RendererUtils.buildHeightMap(scene);
        // Each column should have minY > maxY (empty marker)
        int sizeZ = 4;
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 4; z++) {
                int colIdx = (x * sizeZ + z) * 2;
                assertTrue(heightMap[colIdx] > heightMap[colIdx + 1],
                        "Empty column (" + x + "," + z + ") should have minY > maxY");
            }
    }

    @Test
    void heightMapSingleBlock() {
        // Place one STONE block at (0, 2, 0) in a 1x4x1 scene
        short[] data = airArray(1 * 4 * 1);
        short stone = (short) XMaterial.STONE.ordinal();
        // Index: (0-0)*4*1 + (2-0)*1 + (0-0) = 2
        data[2] = stone;
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 4, 1);

        int[] heightMap = RendererUtils.buildHeightMap(scene);
        // Single column (0,0): minY=2, maxY=2
        assertEquals(2, heightMap[0]);
        assertEquals(2, heightMap[1]);
    }

    @Test
    void heightMapMultipleBlocksSameColumn() {
        // Place blocks at Y=1 and Y=5 in a 1x8x1 scene
        short[] data = airArray(1 * 8 * 1);
        short stone = (short) XMaterial.STONE.ordinal();
        data[1] = stone; // Y=1
        data[5] = stone; // Y=5
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 8, 1);

        int[] heightMap = RendererUtils.buildHeightMap(scene);
        assertEquals(1, heightMap[0]); // minY
        assertEquals(5, heightMap[1]); // maxY
    }

    @Test
    void heightMapMultipleColumns() {
        // 2x4x2 scene, place blocks in different columns
        int sizeX = 2, sizeY = 4, sizeZ = 2;
        short[] data = airArray(sizeX * sizeY * sizeZ);
        short stone = (short) XMaterial.STONE.ordinal();

        // Column (0,0): block at Y=1 → index = 0*4*2 + 1*2 + 0 = 2
        data[2] = stone;
        // Column (1,1): block at Y=3 → index = 1*4*2 + 3*2 + 1 = 15
        data[15] = stone;

        FlatScene scene = new FlatScene(data, 0, 0, 0, sizeX, sizeY, sizeZ);
        int[] heightMap = RendererUtils.buildHeightMap(scene);

        // Column (0,0): colIdx = (0*2 + 0)*2 = 0
        assertEquals(1, heightMap[0]); // minY
        assertEquals(1, heightMap[1]); // maxY

        // Column (0,1): colIdx = (0*2 + 1)*2 = 2 — should be empty
        assertTrue(heightMap[2] > heightMap[3], "Column (0,1) should be empty");

        // Column (1,0): colIdx = (1*2 + 0)*2 = 4 — should be empty
        assertTrue(heightMap[4] > heightMap[5], "Column (1,0) should be empty");

        // Column (1,1): colIdx = (1*2 + 1)*2 = 6
        assertEquals(3, heightMap[6]); // minY
        assertEquals(3, heightMap[7]); // maxY
    }

    @Test
    void heightMapWithNonZeroOrigin() {
        // Scene with minX=10, minY=64, minZ=20
        int sizeX = 2, sizeY = 4, sizeZ = 2;
        short[] data = airArray(sizeX * sizeY * sizeZ);
        short stone = (short) XMaterial.STONE.ordinal();

        // Block at world (10, 66, 20) → local (0, 2, 0) → index = 0*4*2 + 2*2 + 0 = 4
        data[4] = stone;

        FlatScene scene = new FlatScene(data, 10, 64, 20, sizeX, sizeY, sizeZ);
        int[] heightMap = RendererUtils.buildHeightMap(scene);

        // Column (0,0): minY=66, maxY=66
        assertEquals(66, heightMap[0]);
        assertEquals(66, heightMap[1]);
    }

    @Test
    void heightMapCorrectArraySize() {
        int sizeX = 3, sizeY = 5, sizeZ = 7;
        short[] data = airArray(sizeX * sizeY * sizeZ);
        FlatScene scene = new FlatScene(data, 0, 0, 0, sizeX, sizeY, sizeZ);

        int[] heightMap = RendererUtils.buildHeightMap(scene);
        assertEquals(sizeX * sizeZ * 2, heightMap.length);
    }

    // -- helpers ------------------------------------------------------------

    private static short[] airArray(int size) {
        short[] data = new short[size];
        Arrays.fill(data, AIR);
        return data;
    }
}

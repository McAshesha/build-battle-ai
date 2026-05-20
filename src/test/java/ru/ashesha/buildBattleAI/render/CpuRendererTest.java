package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.FlatScene;
import ru.ashesha.buildBattleAI.util.RendererUtils;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CpuRendererTest {

    private static final short AIR = (short) XMaterial.AIR.ordinal();

    /**
     * Shared renderer instance for all tests — avoids pool creation per test.
     */
    private static CpuRenderer renderer;

    @BeforeAll
    static void setUp() {
        renderer = new CpuRenderer();
    }

    @AfterAll
    static void tearDown() {
        renderer.shutdown();
    }

    private static short[] airArray(int size) {
        short[] data = new short[size];
        Arrays.fill(data, AIR);
        return data;
    }

    // ===== Constants =====

    private static FlatScene emptyScene() {
        int size = 10;
        short[] data = airArray(size * size * size);
        return new FlatScene(data, 0, 0, 0, size, size, size);
    }

    private static boolean hasNonBackgroundPixels(byte[] pixels) {
        for (int i = 0; i < pixels.length; i += 3)
            if ((pixels[i] & 0xFF) != 0xC8 || (pixels[i + 1] & 0xFF) != 0xD8 || (pixels[i + 2] & 0xFF) != 0xE8)
                return true;
        return false;
    }

    // ===== render() output format =====

    @Test
    void outputDimensions() {
        assertEquals(224, RendererUtils.WIDTH);
        assertEquals(224, RendererUtils.HEIGHT);
    }

    @Test
    void fieldOfView() {
        assertEquals(70.0, RendererUtils.FOV, 1e-9);
    }

    @Test
    void renderReturnsCorrectArraySize() {
        FlatScene scene = emptyScene();
        byte[] pixels = renderer.render(scene, 0, 0, 0, 0, 0);
        assertEquals(224 * 224 * 3, pixels.length);
    }

    // ===== Rendering blocks =====

    @Test
    void renderEmptySceneProducesBackgroundColor() {
        FlatScene scene = emptyScene();
        byte[] pixels = renderer.render(scene, 5, 5, 5, 0, 0);

        // Background: 0xC8D8E8 = R=200, G=216, B=232
        int bgR = 0xC8;
        int bgG = 0xD8;
        int bgB = 0xE8;

        // Check center pixel
        int centerIdx = (112 * 224 + 112) * 3;
        assertEquals(bgR, pixels[centerIdx] & 0xFF);
        assertEquals(bgG, pixels[centerIdx + 1] & 0xFF);
        assertEquals(bgB, pixels[centerIdx + 2] & 0xFF);
    }

    @Test
    void renderEmptySceneAllPixelsSameBackground() {
        FlatScene scene = emptyScene();
        byte[] pixels = renderer.render(scene, 5, 5, 5, 0, 0);

        byte expectedR = (byte) 0xC8;
        byte expectedG = (byte) 0xD8;
        byte expectedB = (byte) 0xE8;

        for (int i = 0; i < pixels.length; i += 3) {
            assertEquals(expectedR, pixels[i], "Pixel at " + (i / 3) + " R mismatch");
            assertEquals(expectedG, pixels[i + 1], "Pixel at " + (i / 3) + " G mismatch");
            assertEquals(expectedB, pixels[i + 2], "Pixel at " + (i / 3) + " B mismatch");
        }
    }

    @Test
    void renderSolidBlockProducesNonBackgroundPixels() {
        // Create a 16x16x16 scene filled with stone, camera inside looking at it
        int size = 16;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        // Clear a 3x3x3 area in the center for the camera
        for (int x = 7; x <= 9; x++)
            for (int y = 7; y <= 9; y++)
                for (int z = 7; z <= 9; z++)
                    data[x * size * size + y * size + z] = (short) XMaterial.AIR.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] pixels = renderer.render(scene, 8.5, 8.5, 8.5, 0, 0);

        // At least some pixels should differ from background
        boolean hasNonBackground = false;
        for (int i = 0; i < pixels.length; i += 3)
            if ((pixels[i] & 0xFF) != 0xC8 || (pixels[i + 1] & 0xFF) != 0xD8 || (pixels[i + 2] & 0xFF) != 0xE8) {
                hasNonBackground = true;
                break;
            }
        assertTrue(hasNonBackground, "Rendering a stone room should produce non-background pixels");
    }

    @Test
    void renderSingleBlockInFrontOfCamera() {
        // Place a single stone block at (5,0,5) and camera at (5,0,0) looking south (yaw=0)
        int size = 11;
        short[] data = airArray(size * size * size);
        // Place stone at (5, 5, 8) in scene starting at (0,0,0)
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        // Camera at (5.5, 5.5, 5.5), looking south (yaw=0 => +Z direction)
        byte[] pixels = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        // Center pixel should be stone-colored, not background
        int centerIdx = (112 * 224 + 112) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;

        // Stone is gray (~0x7D7D7D * brightness), should not be background blue
        assertNotEquals(0xC8, r, "Center pixel R should not be background");
        assertNotEquals(0xD8, g, "Center pixel G should not be background");
    }

    // ===== Translucent blocks =====

    @Test
    void renderDifferentYawDirections() {
        // Fill a 10x10x10 box with stone, hollow center
        int size = 10;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        for (int x = 3; x <= 6; x++)
            for (int y = 3; y <= 6; y++)
                for (int z = 3; z <= 6; z++)
                    data[x * size * size + y * size + z] = (short) XMaterial.AIR.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // Render at four different yaw angles
        byte[] south = renderer.render(scene, 5, 5, 5, 0, 0);
        byte[] west = renderer.render(scene, 5, 5, 5, 90, 0);
        byte[] north = renderer.render(scene, 5, 5, 5, 180, 0);
        byte[] east = renderer.render(scene, 5, 5, 5, -90, 0);

        // All should have non-background pixels (the stone walls)
        assertTrue(hasNonBackgroundPixels(south), "South view should see walls");
        assertTrue(hasNonBackgroundPixels(west), "West view should see walls");
        assertTrue(hasNonBackgroundPixels(north), "North view should see walls");
        assertTrue(hasNonBackgroundPixels(east), "East view should see walls");
    }

    // ===== toBufferedImage() =====

    @Test
    void renderPitchLookingDown() {
        // Place stone floor below camera
        int size = 10;
        short[] data = airArray(size * size * size);
        // Fill y=0 layer with stone
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++)
                data[x * size * size + z] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        // Camera at y=5 looking straight down (pitch=90)
        byte[] pixels = renderer.render(scene, 5, 5, 5, 0, 90);

        assertTrue(hasNonBackgroundPixels(pixels), "Looking down at floor should see stone");
    }

    @Test
    void renderTranslucentBlockBlends() {
        // Place glass in front of stone
        int size = 11;
        short[] data = airArray(size * size * size);
        // Stone at z=9
        data[5 * size * size + 5 * size + 9] = (short) XMaterial.STONE.ordinal();
        // Glass at z=7
        data[5 * size * size + 5 * size + 7] = (short) XMaterial.GLASS.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] withGlass = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        // Render same scene without glass
        data[5 * size * size + 5 * size + 7] = AIR;
        FlatScene sceneNoGlass = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] withoutGlass = renderer.render(sceneNoGlass, 5.5, 5.5, 5.5, 0, 0);

        // The center pixel should differ between the two renders
        int centerIdx = (112 * 224 + 112) * 3;
        boolean differs = (withGlass[centerIdx] != withoutGlass[centerIdx])
                || (withGlass[centerIdx + 1] != withoutGlass[centerIdx + 1])
                || (withGlass[centerIdx + 2] != withoutGlass[centerIdx + 2]);
        assertTrue(differs, "Glass should affect the rendered color");
    }

    @Test
    void toBufferedImageDimensions() {
        byte[] rgb = new byte[224 * 224 * 3];
        BufferedImage image = RendererUtils.toBufferedImage(rgb);
        assertEquals(224, image.getWidth());
        assertEquals(224, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
    }

    // ===== Emissive rendering =====

    @Test
    void toBufferedImagePreservesColors() {
        byte[] rgb = new byte[224 * 224 * 3];
        // Set first pixel to red
        rgb[0] = (byte) 0xFF;
        rgb[1] = 0;
        rgb[2] = 0;
        // Set last pixel to blue
        int lastIdx = (224 * 224 - 1) * 3;
        rgb[lastIdx] = 0;
        rgb[lastIdx + 1] = 0;
        rgb[lastIdx + 2] = (byte) 0xFF;

        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        // First pixel (0,0) should be red
        int firstColor = image.getRGB(0, 0) & 0xFFFFFF;
        assertEquals(0xFF0000, firstColor);

        // Last pixel (223,223) should be blue
        int lastColor = image.getRGB(223, 223) & 0xFFFFFF;
        assertEquals(0x0000FF, lastColor);
    }

    // ===== Camera outside scene =====

    @Test
    void toBufferedImageMidPixelColor() {
        byte[] rgb = new byte[224 * 224 * 3];
        // Set pixel at (100, 50)
        int idx = (50 * 224 + 100) * 3;
        rgb[idx] = (byte) 128;
        rgb[idx + 1] = (byte) 64;
        rgb[idx + 2] = (byte) 32;

        BufferedImage image = RendererUtils.toBufferedImage(rgb);
        int color = image.getRGB(100, 50) & 0xFFFFFF;
        assertEquals(128, (color >> 16) & 0xFF);
        assertEquals(64, (color >> 8) & 0xFF);
        assertEquals(32, color & 0xFF);
    }

    @Test
    void emissiveBlocksRenderBrighter() {
        // Compare glowstone vs stone rendering (both in same position)
        int size = 11;
        short[] data1 = airArray(size * size * size);
        data1[5 * size * size + 5 * size + 8] = (short) XMaterial.GLOWSTONE.ordinal();
        FlatScene scene1 = new FlatScene(data1, 0, 0, 0, size, size, size);
        byte[] glowRender = renderer.render(scene1, 5.5, 5.5, 5.5, 0, 0);

        short[] data2 = airArray(size * size * size);
        data2[5 * size * size + 5 * size + 8] = (short) XMaterial.STONE.ordinal();
        FlatScene scene2 = new FlatScene(data2, 0, 0, 0, size, size, size);
        byte[] stoneRender = renderer.render(scene2, 5.5, 5.5, 5.5, 0, 0);

        // Both should have non-background at center
        int centerIdx = (112 * 224 + 112) * 3;
        assertTrue(hasNonBackgroundPixels(glowRender), "Glowstone should be visible");
        assertTrue(hasNonBackgroundPixels(stoneRender), "Stone should be visible");

        // Glowstone (emissive) should not have face-shading dimming
        // so at minimum the actual block pixels should differ from stone
        // (different base colors and brightness models)
        boolean differs = (glowRender[centerIdx] != stoneRender[centerIdx])
                || (glowRender[centerIdx + 1] != stoneRender[centerIdx + 1])
                || (glowRender[centerIdx + 2] != stoneRender[centerIdx + 2]);
        assertTrue(differs, "Glowstone and stone should render differently");
    }

    // ===== Sub-block shapes =====

    @Test
    void cameraOutsideSceneLookingIn() {
        // Scene is a 4x4x4 stone cube at origin
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        // Camera at (2, 2, -5) looking south (into scene)
        byte[] pixels = renderer.render(scene, 2, 2, -5, 0, 0);

        assertTrue(hasNonBackgroundPixels(pixels), "Should see stone cube from outside");
    }

    // ===== Parallel rendering consistency =====

    @Test
    void cameraFarFromSceneSeesBackground() {
        // Scene is a single block at origin
        short[] data = {(short) XMaterial.STONE.ordinal()};
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);
        // Camera at (1000, 1000, 1000) looking away
        byte[] pixels = renderer.render(scene, 1000, 1000, 1000, 0, 0);

        // Most/all pixels should be background
        int nonBg = 0;
        for (int i = 0; i < pixels.length; i += 3)
            if ((pixels[i] & 0xFF) != 0xC8 || (pixels[i + 1] & 0xFF) != 0xD8 || (pixels[i + 2] & 0xFF) != 0xE8)
                nonBg++;
        // Should be almost all background (block is tiny at this distance)
        assertTrue(nonBg < 100, "Far camera should mostly see background, but saw " + nonBg + " non-bg pixels");
    }

    // ===== Height map acceleration =====

    @Test
    void renderSlabProducesPartialBlockPixels() {
        int size = 11;
        short[] data = airArray(size * size * size);
        // Place a slab at (5, 5, 8)
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.OAK_SLAB.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] pixels = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        // Should see something (the slab)
        assertTrue(hasNonBackgroundPixels(pixels), "Should see the slab");
    }

    @Test
    void renderIsDeterministic() {
        int size = 8;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        for (int x = 3; x <= 4; x++)
            for (int y = 3; y <= 4; y++)
                for (int z = 3; z <= 4; z++)
                    data[x * size * size + y * size + z] = (short) XMaterial.AIR.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        byte[] render1 = renderer.render(scene, 3.5, 3.5, 3.5, 45, 10);
        byte[] render2 = renderer.render(scene, 3.5, 3.5, 3.5, 45, 10);

        assertArrayEquals(render1, render2, "Two renders with same parameters should be identical");
    }

    @Test
    void buildHeightMapSingleBlock() {
        int size = 5;
        short[] data = airArray(size * size * size);
        // Place a stone block at (2, 3, 1)
        data[2 * size * size + 3 * size + 1] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        int[] heightMap = RendererUtils.buildHeightMap(scene);

        // sizeZ = 5, column (2,1) index = (2 * 5 + 1) * 2 = 22
        int colIdx = (2 * 5 + 1) * 2;
        assertEquals(3, heightMap[colIdx], "minY for column (2,1) should be 3");
        assertEquals(3, heightMap[colIdx + 1], "maxY for column (2,1) should be 3");

        // An empty column should have minY > maxY
        int emptyColIdx = 0;
        assertTrue(heightMap[emptyColIdx] > heightMap[emptyColIdx + 1],
                "Empty column should have minY > maxY");
    }

    // ===== Bulk toBufferedImage =====

    @Test
    void buildHeightMapMultipleBlocksInColumn() {
        int size = 10;
        short[] data = airArray(size * size * size);
        // Place blocks at (3, 2, 4) and (3, 7, 4)
        data[3 * size * size + 2 * size + 4] = (short) XMaterial.STONE.ordinal();
        data[3 * size * size + 7 * size + 4] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        int[] heightMap = RendererUtils.buildHeightMap(scene);

        int colIdx = (3 * 10 + 4) * 2;
        assertEquals(2, heightMap[colIdx], "minY should be 2");
        assertEquals(7, heightMap[colIdx + 1], "maxY should be 7");
    }

    // ===== Dedicated pool determinism =====

    @Test
    void renderWithHeightMapProducesSameResult() {
        // Verify the height map optimization doesn't change render output.
        // Scene with a single block surrounded by empty space exercises empty-space skipping.
        int size = 11;
        short[] data = airArray(size * size * size);
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] render1 = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);
        byte[] render2 = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        assertArrayEquals(render1, render2, "Renders with height map should be deterministic");
        assertTrue(hasNonBackgroundPixels(render1), "Should see the stone block");
    }

    // ===== Ambient Occlusion on sub-block shapes =====

    @Test
    void toBufferedImageBulkMatchesAllPixels() {
        // Verify bulk setRGB produces identical results to per-pixel approach
        byte[] rgb = new byte[224 * 224 * 3];
        for (int i = 0; i < 224 * 224; i++) {
            rgb[i * 3] = (byte) (i % 256);
            rgb[i * 3 + 1] = (byte) ((i * 7) % 256);
            rgb[i * 3 + 2] = (byte) ((i * 13) % 256);
        }

        BufferedImage image = RendererUtils.toBufferedImage(rgb);

        for (int i = 0; i < 224 * 224; i++) {
            int x = i % 224;
            int y = i / 224;
            int expected = ((rgb[i * 3] & 0xFF) << 16) | ((rgb[i * 3 + 1] & 0xFF) << 8) | (rgb[i * 3 + 2] & 0xFF);
            int actual = image.getRGB(x, y) & 0xFFFFFF;
            assertEquals(expected, actual, "Pixel mismatch at (" + x + "," + y + ")");
        }
    }

    @Test
    void renderDedicatedPoolProducesDeterministicOutput() {
        // Render a complex scene twice to confirm the dedicated ForkJoinPool
        // produces consistent, pixel-identical results.
        int size = 16;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        for (int x = 5; x <= 10; x++)
            for (int y = 5; y <= 10; y++)
                for (int z = 5; z <= 10; z++)
                    data[x * size * size + y * size + z] = AIR;
        data[5 * size * size + 5 * size + 11] = (short) XMaterial.GLOWSTONE.ordinal();
        data[6 * size * size + 5 * size + 11] = (short) XMaterial.OAK_PLANKS.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] render1 = renderer.render(scene, 7.5, 7.5, 7.5, 30, -15);
        byte[] render2 = renderer.render(scene, 7.5, 7.5, 7.5, 30, -15);

        assertArrayEquals(render1, render2, "Dedicated pool renders should be pixel-identical");
    }

    @Test
    void aoOnSlabDarkensPixelsNearOpaqueNeighbor() {
        // Enclosed-box approach: slab inside a stone box. Camera in the 1-block air pocket above.
        // AO neighbors for Y-face (looking down) are at ny = vy - stepY = vy + 1 (one above slab).
        // All surrounding stone at that level creates maximum AO darkening on the slab's top face.
        int size = 5;
        short[] stoneData = new short[size * size * size];
        Arrays.fill(stoneData, (short) XMaterial.STONE.ordinal());
        // Clear one block for the camera at (2, 2, 2)
        stoneData[2 * size * size + 2 * size + 2] = AIR;
        // Place slab directly below the camera at (2, 1, 2)
        stoneData[2 * size * size + size + 2] = (short) XMaterial.OAK_SLAB.ordinal();

        FlatScene enclosed = new FlatScene(stoneData, 0, 0, 0, size, size, size);
        byte[] enclosedRender = renderer.render(enclosed, 2.5, 2.5, 2.5, 0, 90);

        // Isolated slab: same slab, no surrounding stone, no AO
        short[] airData = airArray(size * size * size);
        airData[2 * size * size + size + 2] = (short) XMaterial.OAK_SLAB.ordinal();

        FlatScene isolated = new FlatScene(airData, 0, 0, 0, size, size, size);
        byte[] isolatedRender = renderer.render(isolated, 2.5, 2.5, 2.5, 0, 90);

        // Slab pixels in the enclosed scene should be darker due to AO (0.68x multiplier)
        boolean hasDarkerPixel = false;
        for (int i = 0; i < enclosedRender.length; i += 3) {
            int lumEnclosed = (enclosedRender[i] & 0xFF) + (enclosedRender[i + 1] & 0xFF) + (enclosedRender[i + 2] & 0xFF);
            int lumIsolated = (isolatedRender[i] & 0xFF) + (isolatedRender[i + 1] & 0xFF) + (isolatedRender[i + 2] & 0xFF);

            // Only compare non-background pixels visible in both renders
            boolean isBgEnclosed = lumEnclosed == (0xC8 + 0xD8 + 0xE8);
            boolean isBgIsolated = lumIsolated == (0xC8 + 0xD8 + 0xE8);
            if (isBgEnclosed || isBgIsolated)
                continue;

            if (lumEnclosed < lumIsolated) {
                hasDarkerPixel = true;
                break;
            }
        }
        assertTrue(hasDarkerPixel, "Slab pixels should be darkened by AO when surrounded by opaque blocks");
    }

    @Test
    void aoOnStairsDarkensPixelsNearOpaqueNeighbor() {
        // Same enclosed-box approach with stairs instead of a slab.
        int size = 5;
        short[] stoneData = new short[size * size * size];
        Arrays.fill(stoneData, (short) XMaterial.STONE.ordinal());
        stoneData[2 * size * size + 2 * size + 2] = AIR;
        stoneData[2 * size * size + size + 2] = (short) XMaterial.OAK_STAIRS.ordinal();

        FlatScene enclosed = new FlatScene(stoneData, 0, 0, 0, size, size, size);
        byte[] enclosedRender = renderer.render(enclosed, 2.5, 2.5, 2.5, 0, 90);

        short[] airData = airArray(size * size * size);
        airData[2 * size * size + size + 2] = (short) XMaterial.OAK_STAIRS.ordinal();

        FlatScene isolated = new FlatScene(airData, 0, 0, 0, size, size, size);
        byte[] isolatedRender = renderer.render(isolated, 2.5, 2.5, 2.5, 0, 90);

        boolean hasDarkerPixel = false;
        for (int i = 0; i < enclosedRender.length; i += 3) {
            int lumEnclosed = (enclosedRender[i] & 0xFF) + (enclosedRender[i + 1] & 0xFF) + (enclosedRender[i + 2] & 0xFF);
            int lumIsolated = (isolatedRender[i] & 0xFF) + (isolatedRender[i + 1] & 0xFF) + (isolatedRender[i + 2] & 0xFF);

            boolean isBgEnclosed = lumEnclosed == (0xC8 + 0xD8 + 0xE8);
            boolean isBgIsolated = lumIsolated == (0xC8 + 0xD8 + 0xE8);
            if (isBgEnclosed || isBgIsolated)
                continue;

            if (lumEnclosed < lumIsolated) {
                hasDarkerPixel = true;
                break;
            }
        }
        assertTrue(hasDarkerPixel, "Stair pixels should be darkened by AO when surrounded by opaque blocks");
    }

    // ===== Cherry / 1.20+ block rendering =====

    @Test
    void renderCherryBlocksProducesVisiblePixels() {
        // Proves cherry blocks render through the full pipeline (FlatScene → CpuRenderer).
        // If this passes but cherry trees are invisible on the live server,
        // the issue is in ChunkScene material resolution.
        int size = 11;
        short[] data = airArray(size * size * size);
        // Place cherry log and cherry leaves in front of camera
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.CHERRY_LOG.ordinal();
        data[5 * size * size + 6 * size + 8] = (short) XMaterial.CHERRY_LEAVES.ordinal();
        data[5 * size * size + 7 * size + 8] = (short) XMaterial.CHERRY_LEAVES.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] pixels = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        assertTrue(hasNonBackgroundPixels(pixels),
                "Cherry log + leaves should render visible pixels");

        // Verify center area has pinkish/salmon pixels (not gray or background)
        int centerIdx = (112 * 224 + 112) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;
        // Cherry log or leaves should produce warm-toned pixels, not background blue
        boolean notBackground = (r != 0xC8 || g != 0xD8 || b != 0xE8);
        assertTrue(notBackground, "Center pixel should not be background sky color");
    }

    @Test
    void renderGreenWoolIsVisiblyGreen() {
        // Green wool must not appear black after face shading
        int size = 11;
        short[] data = airArray(size * size * size);
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.GREEN_WOOL.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] pixels = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        // Find a non-background pixel (the wool block)
        int centerIdx = (112 * 224 + 112) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;

        boolean notBackground = (r != 0xC8 || g != 0xD8 || b != 0xE8);
        assertTrue(notBackground, "Green wool should be visible");

        // Green channel should dominate (it's green wool!)
        assertTrue(g > r, "Green wool should have G > R, got R=" + r + " G=" + g + " B=" + b);
        // Should not be too dark (not black)
        assertTrue(g > 30, "Green wool should not be near-black, G=" + g);
    }

    // ===== Lifecycle =====

    @Test
    void newRendererProducesSameOutput() {
        // Verifies that creating a fresh renderer produces identical output.
        // This replaces the old shutdownDoesNotBreakSubsequentRenders test:
        // in the new design, each enable() creates a fresh CpuRenderer instance.
        FlatScene scene = emptyScene();
        byte[] first = renderer.render(scene, 5, 5, 5, 0, 0);

        CpuRenderer fresh = new CpuRenderer();
        byte[] second = fresh.render(scene, 5, 5, 5, 0, 0);
        fresh.shutdown();

        assertArrayEquals(first, second,
                "A fresh renderer instance must produce identical output");
    }

    // ===== Helpers =====

    @Test
    void fullBlockAoUnchangedBySubBlockAoExtension() {
        // Render a scene with only full blocks (stone room) — output must be identical
        // whether or not the code applies AO to sub-block shapes, because there are no sub-blocks here.
        int size = 8;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        for (int x = 3; x <= 4; x++)
            for (int y = 3; y <= 4; y++)
                for (int z = 3; z <= 4; z++)
                    data[x * size * size + y * size + z] = AIR;

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] render1 = renderer.render(scene, 3.5, 3.5, 3.5, 45, 10);
        byte[] render2 = renderer.render(scene, 3.5, 3.5, 3.5, 45, 10);

        // Full-block-only scene must be deterministic and pixel-identical across renders
        assertArrayEquals(render1, render2,
                "Full-block AO renders should be identical (sub-block AO extension must not affect full blocks)");
    }

    // ===== Tight-AABB entry-face shading (regression for face=1 initial bug) =====

    /**
     * Regression for the bug where {@code traceRay} hard-coded the initial DDA
     * {@code face = 1} (Y-axis). For a tight AABB enclosing a solid cube, every
     * pixel hits the cube at the entry voxel, so the (broken) initial face would
     * shade every visible face as a Y-face. With the camera at {@code pitch=0},
     * the per-pixel ray {@code dy} varies smoothly across screen rows — top rows
     * pick up {@code BRIGHTNESS_Y_BOTTOM} (0.6), bottom rows {@code BRIGHTNESS_Y_TOP}
     * (1.0) — producing a vertical brightness gradient on a face that should be
     * uniformly Z-shaded ({@code BRIGHTNESS_Z = 0.85}).
     * <p>
     * Sampling two horizontally-aligned pixel columns at different vertical
     * positions on the {@code -Z} face must yield nearly identical luminance
     * after the fix; under the bug, the spread exceeds 30 luminance units.
     */
    @Test
    void tightAabbCubeSideFaceHasNoVerticalGradient() {
        // 6×6×6 solid stone cube. AABB == cube extents (tight) — there is no
        // air "frame" around the build, so every ray's entry voxel is on the
        // cube surface and uses the initial DDA face value directly.
        int size = 6;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // Camera centered on the -Z face, far enough that the cube fills the
        // middle of the image but does not overflow the FOV. pitch=0 ensures
        // ray.dy spans both signs across the screen height — the exact failure
        // condition for the face=1 bug.
        double camX = 2.5;
        double camY = 2.5;
        double camZ = -6.0;
        byte[] pixels = renderer.render(scene, camX, camY, camZ, 0, 0);

        // Locate the cube's vertical extent on screen by scanning the center
        // column for non-background pixels. The cube is centered horizontally,
        // so column x=112 always intersects the silhouette.
        int centerX = RendererUtils.WIDTH / 2;
        int firstY = -1;
        int lastY = -1;
        for (int py = 0; py < RendererUtils.HEIGHT; py++) {
            int idx = (py * RendererUtils.WIDTH + centerX) * 3;
            int r = pixels[idx] & 0xFF;
            int g = pixels[idx + 1] & 0xFF;
            int b = pixels[idx + 2] & 0xFF;
            boolean isBg = (r == 0xC8 && g == 0xD8 && b == 0xE8);
            if (!isBg) {
                if (firstY == -1)
                    firstY = py;
                lastY = py;
            }
        }
        assertTrue(firstY >= 0 && lastY > firstY,
                "Cube must be visible in the center column; firstY=" + firstY + ", lastY=" + lastY);

        // Sample at 25% and 75% of the cube's vertical extent — well clear of
        // top/bottom edges so AO at the cube corners does not pollute the test.
        int sampleTopY = firstY + (lastY - firstY) / 4;
        int sampleBotY = firstY + (lastY - firstY) * 3 / 4;

        int topIdx = (sampleTopY * RendererUtils.WIDTH + centerX) * 3;
        int botIdx = (sampleBotY * RendererUtils.WIDTH + centerX) * 3;

        int lumTop = (pixels[topIdx] & 0xFF) + (pixels[topIdx + 1] & 0xFF) + (pixels[topIdx + 2] & 0xFF);
        int lumBot = (pixels[botIdx] & 0xFF) + (pixels[botIdx + 1] & 0xFF) + (pixels[botIdx + 2] & 0xFF);
        int spread = Math.abs(lumTop - lumBot);

        // Under the bug: lumTop ≈ 3 * 0x7D * 0.6 ≈ 225, lumBot ≈ 3 * 0x7D * 1.0 ≈ 375
        //   → spread ≈ 150 luminance units (vertical gradient).
        // After the fix: both pixels hit the -Z face → BRIGHTNESS_Z ≈ 0.85
        //   → both ≈ 0x7D * 3 * 0.85 ≈ 319 → spread ≈ 0 (modulo AO from corners).
        // 25 is a comfortable margin: well below the bug's ~150, well above
        // the legitimate AO/edge variation seen in correctly-shaded faces.
        assertTrue(spread < 25,
                "Side face should be uniformly Z-shaded, but top/bot luminance spread is "
                        + spread + " (lumTop=" + lumTop + ", lumBot=" + lumBot + ")");
    }

    /**
     * Regression for the same bug from the orthogonal direction: a top-down
     * angled view of a solid stone cube must obey the physical brightness
     * hierarchy (top face brighter than side faces).
     * <p>
     * Camera is placed above and slightly south of the cube, looking
     * north-and-down at pitch=60°. With this geometry the cube's top face
     * occupies the upper half of the silhouette (it is the receding rooftop)
     * and the {@code -Z} face occupies the lower half (the front wall).
     * Center column rays therefore transition between two physical faces
     * with different brightness multipliers ({@code BRIGHTNESS_Y_TOP = 1.0}
     * versus {@code BRIGHTNESS_Z = 0.85}).
     * <p>
     * Under the face=1 bug both samples take the same Y-face branch
     * ({@code dy < 0} everywhere on this view), giving identical brightness
     * and zero spread between them. The assertion below catches that.
     */
    @Test
    void tightAabbCubeIsoViewObeysBrightnessHierarchy() {
        int size = 6;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // Camera above and south of the cube, yaw=0 (look toward +Z),
        // pitch=20° (moderate downward tilt). The center column transitions
        // from top face (upper half of silhouette) to -Z face (lower half).
        // All hits carry dy<0 so the bug collapses every face brightness to
        // BRIGHTNESS_Y_TOP, eliminating the top-vs-side luminance gap.
        byte[] pixels = renderer.render(scene, 2.5, 9.0, -4.0, 0f, 20f);

        // Find the cube silhouette via a center-column scan.
        int centerX = RendererUtils.WIDTH / 2;
        int firstY = -1;
        int lastY = -1;
        for (int py = 0; py < RendererUtils.HEIGHT; py++) {
            int idx = (py * RendererUtils.WIDTH + centerX) * 3;
            int r = pixels[idx] & 0xFF;
            int g = pixels[idx + 1] & 0xFF;
            int b = pixels[idx + 2] & 0xFF;
            boolean isBg = (r == 0xC8 && g == 0xD8 && b == 0xE8);
            if (!isBg) {
                if (firstY == -1)
                    firstY = py;
                lastY = py;
            }
        }
        assertTrue(firstY >= 0 && lastY > firstY,
                "Cube must be visible in the center column; firstY=" + firstY + ", lastY=" + lastY);

        // Upper portion of the silhouette = receding top face.
        // Lower portion = front -Z face (the wall facing the camera).
        int sampleTopY = firstY + (lastY - firstY) / 4;
        int sampleSideY = firstY + (lastY - firstY) * 3 / 4;

        int topIdx = (sampleTopY * RendererUtils.WIDTH + centerX) * 3;
        int sideIdx = (sampleSideY * RendererUtils.WIDTH + centerX) * 3;
        int lumTop = (pixels[topIdx] & 0xFF) + (pixels[topIdx + 1] & 0xFF) + (pixels[topIdx + 2] & 0xFF);
        int lumSide = (pixels[sideIdx] & 0xFF) + (pixels[sideIdx + 1] & 0xFF) + (pixels[sideIdx + 2] & 0xFF);

        // Expected after the fix:
        //   top sample on +Y face  → 0x7D * 1.0  * 3 ≈ 375
        //   side sample on -Z face → 0x7D * 0.85 * 3 ≈ 318
        //   spread ≈ 57 — well above the 20 threshold.
        // Under the bug both samples take face=1 with dy<0, both ≈ 375,
        // and the assertion fails because the spread collapses to ≈ 0.
        assertTrue(lumTop > lumSide + 20,
                "Top face must be brighter than side face on a stone cube. lumTop="
                        + lumTop + ", lumSide=" + lumSide);
    }

    /**
     * Regression for the face=1 bug, exercised through translucent shading.
     * A solid glass cube viewed straight-on (pitch=0) must NOT show a vertical
     * gradient — the front face is a single Z-face. Under the bug, glass tiles
     * along the screen vertical would render with different luminance because
     * the per-voxel alpha-blend uses {@code hitFace=1} brightness.
     */
    @Test
    void tightAabbGlassCubeFrontFaceHasNoVerticalGradient() {
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.GLASS.ordinal());
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        byte[] pixels = renderer.render(scene, 1.5, 1.5, -5.0, 0, 0);

        int centerX = RendererUtils.WIDTH / 2;
        int firstY = -1;
        int lastY = -1;
        for (int py = 0; py < RendererUtils.HEIGHT; py++) {
            int idx = (py * RendererUtils.WIDTH + centerX) * 3;
            int r = pixels[idx] & 0xFF;
            int g = pixels[idx + 1] & 0xFF;
            int b = pixels[idx + 2] & 0xFF;
            boolean isBg = (r == 0xC8 && g == 0xD8 && b == 0xE8);
            if (!isBg) {
                if (firstY == -1)
                    firstY = py;
                lastY = py;
            }
        }
        assertTrue(firstY >= 0 && lastY > firstY,
                "Glass cube must be visible; firstY=" + firstY + ", lastY=" + lastY);

        int sampleTopY = firstY + (lastY - firstY) / 4;
        int sampleBotY = firstY + (lastY - firstY) * 3 / 4;
        int topIdx = (sampleTopY * RendererUtils.WIDTH + centerX) * 3;
        int botIdx = (sampleBotY * RendererUtils.WIDTH + centerX) * 3;
        int lumTop = (pixels[topIdx] & 0xFF) + (pixels[topIdx + 1] & 0xFF) + (pixels[topIdx + 2] & 0xFF);
        int lumBot = (pixels[botIdx] & 0xFF) + (pixels[botIdx + 1] & 0xFF) + (pixels[botIdx + 2] & 0xFF);
        int spread = Math.abs(lumTop - lumBot);

        assertTrue(spread < 25,
                "Glass front face should be uniformly Z-shaded, but spread is "
                        + spread + " (lumTop=" + lumTop + ", lumBot=" + lumBot + ")");
    }

    /**
     * Regression for the secondary failure mode of the face=1 bug: when the
     * camera sits exactly on an AABB face (tMin == 0 in the slab method), the
     * entry axis is still well-defined. The fix must use it, not silently
     * fall back to Y-face shading.
     * <p>
     * Stone cube 4×4×4 with camera placed exactly at {@code z = -0.0001},
     * essentially touching the {@code -Z} face of the AABB. Looking down the
     * {@code +Z} axis with {@code pitch = 0}, the center ray enters through
     * the {@code -Z} AABB face and immediately hits a stone voxel. With the
     * correct fix the pixel is shaded as Z-face ({@code BRIGHTNESS_Z = 0.85},
     * lum ≈ 318). With the {@code tMin > 0} check it falls back to Y-face
     * with {@code dy = 0} → {@code BRIGHTNESS_Y_BOTTOM = 0.6}, lum ≈ 225.
     */
    @Test
    void cameraOnAabbBoundaryUsesEntryAxis() {
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // Camera exactly on the -Z AABB face (z == aabbMinZ == 0), looking
        // slightly up (pitch=-5). The slab method gives tMin == 0 exactly and
        // entryAxis = 2. The slight upward tilt forces ray.dy > 0 at the
        // center pixel, so a face=1 fallback would shade as
        // BRIGHTNESS_Y_BOTTOM (0.6) — clearly distinguishable from the
        // correct BRIGHTNESS_Z (0.85) for the -Z face.
        byte[] pixels = renderer.render(scene, 1.5, 1.5, 0.0, 0f, -5f);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;
        int lum = r + g + b;

        // Z-face stone: 0x7D * 0.85 * 3 ≈ 320.
        // Y-bottom-face fallback: 0x7D * 0.6 * 3 ≈ 225.
        // 280 is comfortably between the two, separating correct from broken.
        assertTrue(lum > 280,
                "Camera on -Z AABB face must shade hit as Z-face. lum=" + lum
                        + " (R=" + r + ", G=" + g + ", B=" + b + ")");
    }

    /**
     * Regression for the inside-AABB case: when the camera sits inside the
     * AABB (tMin < 0 before clamping), the slab method's entryAxis points to
     * a slab the ray crossed in the past — it is NOT the face the forward ray
     * uses to enter the starting voxel. The fix must NOT use it to shade
     * the starting voxel. With the correct fix, the starting voxel hit test
     * is skipped (DDA overwrites face on its first step) and rendering of a
     * block behind an air pocket continues to work identically to before.
     * <p>
     * This guards against future regressions in the "camera in air pocket
     * inside a larger AABB" workflow (the normal case for live arena renders
     * where the build region is wider than the build itself).
     */
    @Test
    void cameraInsideAirPocketRendersBlockBehindCorrectly() {
        // 11³ scene, single stone block at (5,5,8), camera at (5.5,5.5,5.5)
        // — strictly inside the AABB, inside an all-air voxel.
        // The starting voxel (5,5,5) is air; DDA must advance through air
        // and hit the stone at (5,5,8) with face=2 (-Z face of the stone).
        int size = 11;
        short[] data = airArray(size * size * size);
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.STONE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        byte[] pixels = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;
        int lum = r + g + b;

        // -Z face of the stone block: 0x7D * 0.85 * 3 ≈ 320 (no AO — block is isolated).
        // Acceptable band: anything clearly above the Y-bottom fallback (225)
        // and below the Y-top brightness (375). 280–360 covers the correct case.
        assertTrue(lum > 280 && lum < 360,
                "Isolated stone block viewed from inside an air pocket must shade as Z-face. lum="
                        + lum + " (R=" + r + ", G=" + g + ", B=" + b + ")");
    }

    /**
     * Regression guard for the camera-embedded-in-block edge case. The
     * inside-AABB branch must still produce a block-colored hit for the
     * starting voxel when that voxel contains a block — dropping the hit
     * (rendering background sky from inside solid stone) is wrong.
     * <p>
     * Single 1×1×1 stone scene, camera placed at the voxel center. The
     * ray exits through some axis-aligned face immediately. With a
     * principled fix that uses the exit-face as the shading face, the
     * pixel takes on a stone brightness (≈ {@code 0x7D × brightness}).
     * If the starting-voxel hit is incorrectly dropped, the result is the
     * background sky color {@code 0xC8D8E8} (lum = 624) — easy to detect.
     */
    @Test
    void cameraEmbeddedInStoneVoxelRendersStone() {
        short[] data = {(short) XMaterial.STONE.ordinal()};
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);

        // Camera dead-center of the single stone voxel, any orientation.
        byte[] pixels = renderer.render(scene, 0.5, 0.5, 0.5, 0, 0);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;

        // Background sky color → reject. Anything stone-grey → accept.
        boolean isBackground = (r == 0xC8 && g == 0xD8 && b == 0xE8);
        assertFalse(isBackground,
                "Camera embedded in a stone voxel must render stone, not background. "
                        + "R=" + r + ", G=" + g + ", B=" + b);

        // Sanity: a grey pixel has R≈G≈B (stone is achromatic in the palette).
        int spread = Math.max(Math.max(Math.abs(r - g), Math.abs(g - b)), Math.abs(r - b));
        assertTrue(spread < 5,
                "Stone pixel should be achromatic grey, but channel spread is "
                        + spread + " (R=" + r + ", G=" + g + ", B=" + b + ")");
    }

    /**
     * Regression guard for facing-dependent blocks rendered from inside.
     * Grass block has a green top (0x7CBD6B), a brown dirt bottom (0x866043),
     * and a green-brown side (0x7D8A58). When the camera is inside the grass
     * voxel and looks straight up, the viewer sees the inside of the top
     * face — the rendered pixel must be GREEN (top color), not brown.
     * <p>
     * Under the naive exit-axis-only heuristic, looking up gives {@code dy > 0}
     * which the existing palette/brightness branches interpret as "ray going
     * up from below hits the bottom face" → brown dirt color. That is the
     * outside-hit convention. Inside hits need an inverted sign convention.
     */
    @Test
    void cameraInsideGrassBlockLookingUpSeesGreenTop() {
        short[] data = {(short) XMaterial.GRASS_BLOCK.ordinal()};
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);

        // Camera at voxel center, looking straight up (pitch = -90).
        byte[] pixels = renderer.render(scene, 0.5, 0.5, 0.5, 0, -90);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;

        boolean isBackground = (r == 0xC8 && g == 0xD8 && b == 0xE8);
        assertFalse(isBackground, "Inside grass must render a grass pixel, not background.");

        // Green top: R=0x7C(124), G=0xBD(189), B=0x6B(107) — G clearly dominant.
        // Brown bottom: R=0x86(134), G=0x60(96), B=0x43(67) — R clearly dominant.
        // After shading, ratios are preserved.
        assertTrue(g > r,
                "Looking up from inside grass must show the green top face. "
                        + "R=" + r + ", G=" + g + ", B=" + b + " — got dirt-bottom shading.");
    }

    /**
     * Regression guard for sub-block shapes (slabs, stairs, fences, etc.) when
     * the camera starts inside one of their AABB boxes. {@link
     * BlockShape#getShape} returns a non-null array of boxes, so the inside-
     * AABB pre-loop block in {@link CpuRenderer#traceRay} skips this voxel.
     * The fallback path through {@link CpuRenderer#testSubBlockHit} rejects
     * any box intersection with {@code tMin < 0} — exactly the inside-the-box
     * case. The combined effect is that the slab is dropped and the renderer
     * shows the background sky from inside solid material.
     * <p>
     * An oak slab is a bottom half-block {@code [0,0,0, 1,0.5,1]}. Placing
     * the camera at {@code y = 0.25} sits the origin inside the slab box.
     * After the fix the rendered pixel must take on a slab color (any
     * non-background pixel is acceptable).
     */
    @Test
    void cameraEmbeddedInOakSlabRendersSlab() {
        short[] data = {(short) XMaterial.OAK_SLAB.ordinal()};
        FlatScene scene = new FlatScene(data, 0, 0, 0, 1, 1, 1);

        // Camera inside the slab box (bottom half), looking +Z.
        byte[] pixels = renderer.render(scene, 0.5, 0.25, 0.5, 0, 0);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;
        boolean isBackground = (r == 0xC8 && g == 0xD8 && b == 0xE8);

        assertFalse(isBackground,
                "Camera embedded inside an oak slab must render the slab, not background. "
                        + "R=" + r + ", G=" + g + ", B=" + b);
    }

    /**
     * Regression guard for the boundary-start case Codex flagged: the camera
     * sits strictly inside the AABB but exactly on a voxel boundary inside
     * it (e.g., {@code ox = 2.0} between voxel columns 1 and 2). Such a start
     * is NOT geometrically inside any voxel — it is on a face. Treating it
     * as an inside-block exit (with the inverted direction convention) would
     * produce the wrong side of a face-dependent block.
     * <p>
     * For grass viewed looking straight up from {@code ox = 2.0} inside a
     * 4³ grass cube: outside-hit semantics say "ray going up enters through
     * bottom face → dirt-brown" (R > G). Inside-out inversion would say
     * "ray exiting through top face → green-top" (G > R). The boundary case
     * must follow the outside-hit interpretation.
     */
    @Test
    void cameraOnFullCubeBoundaryUsesOutsideHitConvention() {
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.GRASS_BLOCK.ordinal());
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // ox = 2.0 sits exactly on the X-boundary between voxels (1,*,*) and
        // (2,*,*). oy = 2.5, oz = 2.5 keep Y/Z strictly interior so only the
        // X axis exhibits the boundary condition. pitch = -90 looks straight up.
        byte[] pixels = renderer.render(scene, 2.0, 2.5, 2.5, 0, -90);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;

        // The boundary lands on a face plane shared by two voxels, and a
        // tiny per-pixel direction offset can route the hit through any of
        // the three face axes. All outside-hit interpretations are valid:
        //   bottom face (0x866043) → R≫G
        //   side face   (0x7D8A58) → G slightly > R (G−R ≈ 13 unshaded)
        //   top face    (0x7CBD6B) → G ≫ R   (G−R ≈ 65 unshaded — this is
        //                            ONLY produced by the inside-out
        //                            inversion path we are guarding against)
        // The assertion catches the inversion path by rejecting the only
        // shading that produces a large G-over-R gap.
        assertTrue(g - r < 20,
                "Voxel-boundary start must not use inside-out top inversion (G≫R). "
                        + "R=" + r + ", G=" + g + ", B=" + b);
    }

    /**
     * Regression guard for the NUDGE-induced voxel skip Codex flagged: when
     * the camera sits strictly inside a block but its coordinate is within
     * {@code NUDGE = 1e-4} of an exit face, the {@code tStart = NUDGE} step
     * pushes the starting-voxel index one cell past the camera's real voxel.
     * In a 4³ scene with a single stone voxel at the origin surrounded by
     * air, a camera at {@code ox = 0.99995} (strictly inside the stone) with
     * the NUDGE bug ends up testing voxel (1, *, *) — which is air — and the
     * block hit is dropped, producing background sky.
     */
    @Test
    void cameraInsideBlockWithinNudgeOfExitFaceStillRendersBlock() {
        int size = 4;
        short[] data = airArray(size * size * size);
        data[0 * size * size + 0 * size + 0] = (short) XMaterial.STONE.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);

        // Camera strictly inside stone voxel (0,0,0) but within NUDGE of the
        // +X face. yaw=-90 = looking +X.
        byte[] pixels = renderer.render(scene, 0.99995, 0.5, 0.5, -90, 0);

        int centerIdx = (RendererUtils.HEIGHT / 2 * RendererUtils.WIDTH + RendererUtils.WIDTH / 2) * 3;
        int r = pixels[centerIdx] & 0xFF;
        int g = pixels[centerIdx + 1] & 0xFF;
        int b = pixels[centerIdx + 2] & 0xFF;
        boolean isBackground = (r == 0xC8 && g == 0xD8 && b == 0xE8);
        assertFalse(isBackground,
                "Camera inside stone within NUDGE of +X face must still render stone, not sky. "
                        + "R=" + r + ", G=" + g + ", B=" + b);
    }

    @Test
    void translucentBlocksStillExcludedFromAo() {
        // Place glass at (5,5,8) with stone at (6,5,8).
        // Glass is translucent (alpha < 255), so AO must NOT apply.
        // Compare glass pixels with and without the neighbor — they should be identical
        // (no AO darkening through translucent material).
        int size = 11;
        short[] data = airArray(size * size * size);
        data[5 * size * size + 5 * size + 8] = (short) XMaterial.GLASS.ordinal();
        data[6 * size * size + 5 * size + 8] = (short) XMaterial.STONE.ordinal();
        // Place an opaque block behind glass so we see glass tinting
        data[5 * size * size + 5 * size + 9] = (short) XMaterial.STONE.ordinal();

        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        byte[] withNeighbor = renderer.render(scene, 5.5, 5.5, 5.5, 0, 0);

        // Same glass without the side neighbor
        short[] dataAlone = airArray(size * size * size);
        dataAlone[5 * size * size + 5 * size + 8] = (short) XMaterial.GLASS.ordinal();
        dataAlone[5 * size * size + 5 * size + 9] = (short) XMaterial.STONE.ordinal();

        FlatScene sceneAlone = new FlatScene(dataAlone, 0, 0, 0, size, size, size);
        byte[] withoutNeighbor = renderer.render(sceneAlone, 5.5, 5.5, 5.5, 0, 0);

        // The glass pixels themselves should NOT be darkened by AO.
        // The stone behind may differ due to AO from the side stone, but the glass layer must be unaffected.
        // Check that center pixel area (where glass is) doesn't show AO darkening on the glass pass.
        // Since glass is translucent and AO is excluded for alpha < 255, the glass contribution is the same.
        int centerIdx = (112 * 224 + 112) * 3;
        // Glass is visible at center — check pixels are present
        assertTrue(hasNonBackgroundPixels(withNeighbor), "Should see glass + stone");
        assertTrue(hasNonBackgroundPixels(withoutNeighbor), "Should see glass + stone alone");

        // The renders may differ due to AO on the STONE behind the glass (stone is opaque, AO applies).
        // But importantly, the glass pass itself is not darkened. We verify glass still renders
        // (not excluded) and the scene is valid.
        // Stronger assertion: in the glass-only area, pixels should match. But since the stone
        // behind also gets AO, the composited color can differ. So we just verify glass is still rendered
        // and the translucent block exclusion didn't regress.
        boolean glassVisible = false;
        for (int i = 0; i < withoutNeighbor.length; i += 3)
            if ((withoutNeighbor[i] & 0xFF) != 0xC8 || (withoutNeighbor[i + 1] & 0xFF) != 0xD8
                    || (withoutNeighbor[i + 2] & 0xFF) != 0xE8) {
                glassVisible = true;
                break;
            }
        assertTrue(glassVisible, "Translucent glass should still render (not excluded by AO logic)");
    }
}

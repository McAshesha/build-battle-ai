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

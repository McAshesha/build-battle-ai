package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import ru.ashesha.buildBattleAI.render.data.FlatScene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden snapshot tests for the CPU voxel renderer.
 * <p>
 * Each test builds a fixed {@link FlatScene}, renders it with a fixed camera,
 * and asserts the SHA-256 of the resulting RGB byte buffer against a hash
 * persisted in {@code src/test/resources/golden/renderer/<name>.sha256}. The
 * renderer is fully deterministic — same scene + camera = byte-identical
 * output — so an unexpected hash change implies a real regression in
 * shading, AO, palette lookup, ray traversal, or block-shape resolution.
 * <p>
 * <b>First-run blessing.</b> If a golden hash file does not exist, the test
 * writes it and passes. This bootstraps the suite from any developer machine
 * without a separate fixture step; the second {@code mvn test} on the same
 * commit will then enforce stability. To re-bless after an intentional
 * renderer change, delete the relevant {@code .sha256} files (or set
 * {@code GOLDEN_UPDATE=1}/{@code -Dgolden.update=true}) and re-run the suite.
 * <p>
 * On hash mismatch, the actual rendered image is dumped as a PNG under
 * {@code target/golden-actual/<name>.png} so the regression can be inspected
 * visually.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class RendererGoldenSnapshotTest {

    /** Directory where golden SHA-256 hashes are persisted (under VCS). */
    private static final Path GOLDEN_DIR = Paths.get("src/test/resources/golden/renderer");

    /** Directory where the actual rendered image is dumped on mismatch (gitignored). */
    private static final Path ACTUAL_DIR = Paths.get("target/golden-actual");

    /**
     * Whether to rewrite golden hashes instead of comparing.
     * <p>Toggle via env var {@code GOLDEN_UPDATE=1} or system property
     * {@code -Dgolden.update=true}.
     */
    private static final boolean UPDATE = "1".equals(System.getenv("GOLDEN_UPDATE"))
            || Boolean.parseBoolean(System.getProperty("golden.update", "false"));

    /** Shared renderer instance — its thread pool is expensive to create. */
    private static CpuRenderer renderer;

    @BeforeAll
    static void setUp() throws IOException {
        renderer = new CpuRenderer();
        Files.createDirectories(GOLDEN_DIR);
        Files.createDirectories(ACTUAL_DIR);
    }

    @AfterAll
    static void tearDown() {
        if (renderer != null)
            renderer.shutdown();
    }

    /** All-AIR scene — exercises the sky/ambient fallback path. */
    @Test
    void emptyAirScene() {
        FlatScene scene = new FlatScene(filled(8, 8, 8, XMaterial.AIR), 0, 0, 0, 8, 8, 8);
        assertGolden("empty_air", scene, 4.0, 4.0, 4.0, 0f, 0f);
    }

    /** Single STONE block centered in air — exercises ray-block hit + AO. */
    @Test
    void singleStoneCentered() {
        int size = 16;
        short[] data = filled(size, size, size, XMaterial.AIR);
        set(data, size, 8, 8, 8, XMaterial.STONE);
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        assertGolden("single_stone_centered", scene, 8.0, 8.0, 4.0, 0f, 0f);
    }

    /** Hollow STONE box with a 14^3 air pocket inside — exercises interior shading. */
    @Test
    void stoneRoom() {
        int size = 16;
        short[] data = filled(size, size, size, XMaterial.STONE);
        for (int x = 1; x < size - 1; x++)
            for (int y = 1; y < size - 1; y++)
                for (int z = 1; z < size - 1; z++)
                    data[x * size * size + y * size + z] = (short) XMaterial.AIR.ordinal();
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        assertGolden("stone_room_diagonal", scene, 8.5, 8.5, 8.5, 45f, 10f);
    }

    /**
     * Vertical tower mixing opaque, translucent, emissive, and face-dependent
     * materials. Catches palette regressions independently of shading.
     */
    @Test
    void mixedPaletteTower() {
        int size = 8;
        short[] data = filled(size, size, size, XMaterial.AIR);
        set(data, size, 4, 0, 4, XMaterial.STONE);
        set(data, size, 4, 1, 4, XMaterial.GRASS_BLOCK);
        set(data, size, 4, 2, 4, XMaterial.OAK_PLANKS);
        set(data, size, 4, 3, 4, XMaterial.GLASS);
        set(data, size, 4, 4, 4, XMaterial.GLOWSTONE);
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        // Camera at z=1 staring straight along +Z (yaw=0 in MC convention) so
        // the tower at column (x=4, z=4) is dead centre of the frame.
        assertGolden("mixed_palette_tower", scene, 4.5, 2.5, 1.0, 0f, 0f);
    }

    /** Diagonal STONE wall — exercises directional face shading. */
    @Test
    void stoneWall() {
        int size = 12;
        short[] data = filled(size, size, size, XMaterial.AIR);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                set(data, size, x, y, 6, XMaterial.STONE);
        FlatScene scene = new FlatScene(data, 0, 0, 0, size, size, size);
        assertGolden("stone_wall_z6", scene, 6.0, 6.0, 1.0, 0f, 0f);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Allocates a flat X-major scene buffer of the given dimensions filled with one material.
     */
    private static short[] filled(int sx, int sy, int sz, XMaterial fill) {
        short[] arr = new short[sx * sy * sz];
        Arrays.fill(arr, (short) fill.ordinal());
        return arr;
    }

    /**
     * Sets a single cell in a cubic X-major buffer of side {@code size}.
     */
    private static void set(short[] data, int size, int x, int y, int z, XMaterial m) {
        data[x * size * size + y * size + z] = (short) m.ordinal();
    }

    /**
     * Computes the lowercase hex SHA-256 of the given byte array.
     * Used as the canonical golden fingerprint — collision-free for our purposes
     * and stable across JVM versions.
     */
    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available — JVM is broken", e);
        }
    }

    /**
     * Writes the rendered byte buffer out as a PNG under {@link #ACTUAL_DIR}
     * for human inspection when a golden mismatch occurs.
     */
    private static void writeActualPng(String name, byte[] rgb) throws IOException {
        BufferedImage img = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 224; y++)
            for (int x = 0; x < 224; x++) {
                int i = (y * 224 + x) * 3;
                int r = rgb[i] & 0xFF;
                int g = rgb[i + 1] & 0xFF;
                int b = rgb[i + 2] & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        Files.createDirectories(ACTUAL_DIR);
        ImageIO.write(img, "png", ACTUAL_DIR.resolve(name + ".png").toFile());
    }

    /**
     * Renders the scene with the given camera, then asserts byte-for-byte
     * stability against the persisted golden hash for {@code name}.
     */
    private void assertGolden(String name, FlatScene scene,
                              double camX, double camY, double camZ,
                              float yaw, float pitch) {
        byte[] pixels = renderer.render(scene, camX, camY, camZ, yaw, pitch);
        assertEquals(224 * 224 * 3, pixels.length,
                "rendered buffer size must be 224×224×3");
        String actualHash = sha256(pixels);
        Path goldenFile = GOLDEN_DIR.resolve(name + ".sha256");

        try {
            if (!Files.exists(goldenFile) || UPDATE) {
                // First-run / explicit-update path: write the hash and a PNG
                // reference, then accept. Re-running mvn test on the same
                // codebase will then strictly enforce the new golden.
                Files.write(goldenFile, actualHash.getBytes(StandardCharsets.UTF_8));
                writeActualPng(name, pixels);
                System.out.println("[golden] wrote " + goldenFile.toAbsolutePath()
                        + " (hash=" + actualHash + ")");
                return;
            }
            String expected = new String(Files.readAllBytes(goldenFile),
                    StandardCharsets.UTF_8).trim();
            if (!expected.equals(actualHash)) {
                writeActualPng(name, pixels);
                fail("Golden mismatch for '" + name + "'.\n"
                        + "  expected = " + expected + "\n"
                        + "  actual   = " + actualHash + "\n"
                        + "  actual PNG: " + ACTUAL_DIR.resolve(name + ".png").toAbsolutePath() + "\n"
                        + "  Re-bless with `GOLDEN_UPDATE=1 mvn test` or delete "
                        + goldenFile + " after confirming the change is intentional.");
            }
        } catch (IOException ioe) {
            fail("I/O error during golden check for '" + name + "': " + ioe.getMessage());
        }
    }
}

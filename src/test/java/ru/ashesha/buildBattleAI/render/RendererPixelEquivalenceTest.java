package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.FlatScene;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pixel-equivalence baseline tests for the CPU voxel renderer.
 * <p>
 * Each test renders a small, deterministic scene with fixed camera parameters,
 * computes a SHA-256 of the produced byte[] pixel buffer, and compares it
 * against a hex baseline stored under
 * {@code src/test/resources/render/baselines/<scene>.sha256}.
 * <p>
 * The intent of this suite is to act as a defensive layer ahead of upcoming
 * performance/architectural changes inside the renderer: any change that alters
 * a single output pixel will flip the SHA-256 and break a test. The baselines
 * are captured BEFORE those changes are applied; after each refactor the
 * tests must continue to pass to guarantee no rendering regression.
 * <p>
 * Bootstrapping behaviour: on the very first run a baseline file may not
 * exist yet. In that case the computed hash is written to disk and the test
 * passes with a {@code BASELINE WRITTEN} note on stdout. On every subsequent
 * run the recorded baseline is enforced via {@link org.junit.jupiter.api.Assertions#assertEquals}.
 */
class RendererPixelEquivalenceTest {

    /**
     * Pre-cached AIR ordinal — {@link XMaterial#AIR} is NOT ordinal 0,
     * so arrays must be explicitly filled rather than relying on zero-init.
     */
    private static final short AIR = (short) XMaterial.AIR.ordinal();

    /**
     * Fixed camera X for every baseline scene. Chosen so the camera sits
     * outside the build at the {@code -X, +Y, -Z} corner — far enough back
     * to see the entire build, with a yaw of 45° pointing it at the geometry.
     */
    private static final double CAM_X = -3.0;

    /**
     * Fixed camera Y. Slightly above the scene so the top faces are visible
     * after the downward pitch tilt.
     */
    private static final double CAM_Y = 4.0;

    /**
     * Fixed camera Z mirroring {@link #CAM_X} for the diagonal viewpoint.
     */
    private static final double CAM_Z = -3.0;

    /**
     * Yaw: -45° points the camera toward the {@code +X/+Z} corner where every
     * build sits, given Minecraft's yaw convention ({@code yaw=0} faces
     * {@code +Z}, positive yaw rotates clockwise viewed from above, so
     * negative yaw rotates toward {@code +X}).
     */
    private static final float CAM_YAW = -45.0f;

    /**
     * Pitch: 20° downward tilt — exposes top faces while still seeing sides.
     */
    private static final float CAM_PITCH = 20.0f;

    /**
     * Directory holding the persisted baseline hashes (relative to the
     * project root — Maven Surefire runs with {@code basedir} as cwd).
     */
    private static final String BASELINE_DIR = "src/test/resources/render/baselines";

    /**
     * Shared renderer instance to amortise the {@link java.util.concurrent.ForkJoinPool}
     * allocation across every test — same pattern as {@code CpuRendererTest}.
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

    /**
     * Allocates a flat block-data array and fills it with {@link #AIR}.
     */
    private static short[] airArray(int size) {
        short[] data = new short[size];
        Arrays.fill(data, AIR);
        return data;
    }

    /**
     * Flat-index helper matching {@link FlatScene}'s X-major layout
     * ({@code (x * sizeY + y) * sizeZ + z}).
     */
    private static int idx(int x, int y, int z, int sizeY, int sizeZ) {
        return x * sizeY * sizeZ + y * sizeZ + z;
    }

    /**
     * Computes the SHA-256 of a byte array and returns it as lowercase hex.
     */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 16)
                    sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Background sky color emitted by {@link CpuRenderer} when a ray misses
     * every voxel — used by {@link #assertHasNonBackgroundPixels} to detect
     * a misaligned camera that frames pure sky.
     */
    private static final int BG_R = 0xC8;
    private static final int BG_G = 0xD8;
    private static final int BG_B = 0xE8;

    /**
     * Sanity guard: fails fast if the entire render is the background sky
     * color. An all-sky render would still produce a stable hash but would
     * give the test zero diagnostic value — the baseline must actually
     * exercise the scene geometry.
     */
    private static void assertHasNonBackgroundPixels(String sceneName, byte[] pixels) {
        for (int i = 0; i < pixels.length; i += 3) {
            int r = pixels[i] & 0xFF;
            int g = pixels[i + 1] & 0xFF;
            int b = pixels[i + 2] & 0xFF;
            if (r != BG_R || g != BG_G || b != BG_B)
                return;
        }
        fail("Scene '" + sceneName + "' rendered as 100% background sky — "
                + "camera does not see the build geometry. Adjust position/yaw/pitch.");
    }

    /**
     * Compares the rendered output's SHA-256 against the stored baseline.
     * Bootstraps the baseline file if it does not yet exist.
     */
    private static void assertBaseline(String sceneName, byte[] pixels) {
        assertHasNonBackgroundPixels(sceneName, pixels);
        String actual = sha256Hex(pixels);
        File file = new File(BASELINE_DIR, sceneName + ".sha256");

        if (!file.exists()) {
            // Bootstrap mode: persist the freshly computed hash so the next
            // run enforces it. Print a clear note so it is visible in CI logs
            // that a baseline was created rather than verified.
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs())
                    fail("Failed to create baseline directory: " + parent.getAbsolutePath());
                Files.write(file.toPath(), (actual + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                fail("Failed to write baseline for " + sceneName + ": " + e.getMessage());
            }
            System.out.println("BASELINE WRITTEN for " + sceneName + " -> " + actual);
            return;
        }

        String expected;
        try {
            expected = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            fail("Failed to read baseline for " + sceneName + ": " + e.getMessage());
            return;
        }
        assertEquals(expected, actual,
                "Pixel hash regression for scene '" + sceneName + "'. "
                        + "Expected baseline " + expected + " but got " + actual + ". "
                        + "If this change is intentional, delete "
                        + file.getPath() + " and re-run to regenerate.");
    }

    // ===== Scene 1: opaque cubes =====

    /**
     * 4×4×4 solid cube of stone. Pure full-cube opaque blocks — exercises
     * the fast-path DDA traversal with no sub-block geometry or translucency.
     */
    @Test
    void opaqueCubes() {
        int sizeX = 4, sizeY = 4, sizeZ = 4;
        short[] data = new short[sizeX * sizeY * sizeZ];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());

        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0,
                sizeX, sizeY, sizeZ, FlatScene.SourceFormat.DIRECT, "baseline-opaqueCubes");

        byte[] pixels = renderer.render(scene, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
        assertBaseline("opaqueCubes", pixels);
    }

    // ===== Scene 2: mixed opaque (platform + building) =====

    /**
     * 8×4×8 mixed-material scene: a dirt platform at y=0 with an oak-plank
     * structure stacked on top. Exercises multi-material palette lookups and
     * stacked AO contributions between distinct opaque neighbours.
     */
    @Test
    void mixedOpaque() {
        int sizeX = 8, sizeY = 4, sizeZ = 8;
        short[] data = airArray(sizeX * sizeY * sizeZ);

        // Dirt platform at y=0 across the full 8×8 footprint.
        for (int x = 0; x < sizeX; x++)
            for (int z = 0; z < sizeZ; z++)
                data[idx(x, 0, z, sizeY, sizeZ)] = (short) XMaterial.DIRT.ordinal();

        // 4×3×4 oak-planks building above the platform at x=2..5, y=1..3, z=2..5.
        for (int x = 2; x <= 5; x++)
            for (int y = 1; y <= 3; y++)
                for (int z = 2; z <= 5; z++)
                    data[idx(x, y, z, sizeY, sizeZ)] = (short) XMaterial.OAK_PLANKS.ordinal();

        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0,
                sizeX, sizeY, sizeZ, FlatScene.SourceFormat.DIRECT, "baseline-mixedOpaque");

        byte[] pixels = renderer.render(scene, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
        assertBaseline("mixedOpaque", pixels);
    }

    // ===== Scene 3: sub-block shapes (slab + stairs + trapdoor) =====

    /**
     * 3×2×1 row of sub-block shapes side by side: an oak slab (bottom half),
     * an oak-stairs block facing north (default), and a closed oak trapdoor.
     * Y=1 row left as air so the stairs' "top step" has room to project above
     * the base slab. Block state strings are supplied explicitly so the shape
     * resolver returns deterministic sub-block geometry rather than the
     * {@link BlockRenderState#DEFAULT} fallback for everything.
     */
    @Test
    void subBlock() {
        int sizeX = 3, sizeY = 2, sizeZ = 1;
        short[] data = airArray(sizeX * sizeY * sizeZ);
        String[] blockStates = new String[data.length];

        // Slab at (0,0,0) — bottom half.
        int slabIdx = idx(0, 0, 0, sizeY, sizeZ);
        data[slabIdx] = (short) XMaterial.OAK_SLAB.ordinal();
        blockStates[slabIdx] = "minecraft:oak_slab[type=bottom]";

        // Stairs at (1,0,0) — facing north, bottom half (default-ish, but explicit).
        int stairsIdx = idx(1, 0, 0, sizeY, sizeZ);
        data[stairsIdx] = (short) XMaterial.OAK_STAIRS.ordinal();
        blockStates[stairsIdx] = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]";

        // Trapdoor at (2,0,0) — closed, bottom half, facing north.
        int trapIdx = idx(2, 0, 0, sizeY, sizeZ);
        data[trapIdx] = (short) XMaterial.OAK_TRAPDOOR.ordinal();
        blockStates[trapIdx] = "minecraft:oak_trapdoor[facing=north,half=bottom,open=false]";

        FlatScene scene = new FlatScene(data, null, blockStates, 0, 0, 0,
                sizeX, sizeY, sizeZ, FlatScene.SourceFormat.DIRECT, "baseline-subBlock");

        byte[] pixels = renderer.render(scene, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
        assertBaseline("subBlock", pixels);
    }

    // ===== Scene 4: fence connectivity =====

    /**
     * 5×1×1 row of oak fences. Sub-block shape selection for fences depends
     * on neighbour connectivity (the renderer queries adjacent voxels), so
     * the inner fences should render as straight connected posts and the
     * end fences as posts with a single arm.
     */
    @Test
    void connectivity() {
        int sizeX = 5, sizeY = 1, sizeZ = 1;
        short[] data = airArray(sizeX * sizeY * sizeZ);
        for (int x = 0; x < sizeX; x++)
            data[idx(x, 0, 0, sizeY, sizeZ)] = (short) XMaterial.OAK_FENCE.ordinal();

        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0,
                sizeX, sizeY, sizeZ, FlatScene.SourceFormat.DIRECT, "baseline-connectivity");

        byte[] pixels = renderer.render(scene, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
        assertBaseline("connectivity", pixels);
    }

    // ===== Scene 5: translucent shell around emissive core =====

    /**
     * 3×3×3 cube of glass surrounding a single glowstone block in the centre.
     * Exercises alpha-blended translucent traversal layered over an emissive
     * core — the heaviest combination of optional shading branches.
     */
    @Test
    void translucent() {
        int sizeX = 3, sizeY = 3, sizeZ = 3;
        short[] data = new short[sizeX * sizeY * sizeZ];
        Arrays.fill(data, (short) XMaterial.GLASS.ordinal());

        // Replace the centre voxel (1,1,1) with glowstone.
        data[idx(1, 1, 1, sizeY, sizeZ)] = (short) XMaterial.GLOWSTONE.ordinal();

        FlatScene scene = new FlatScene(data, null, null, 0, 0, 0,
                sizeX, sizeY, sizeZ, FlatScene.SourceFormat.DIRECT, "baseline-translucent");

        byte[] pixels = renderer.render(scene, CAM_X, CAM_Y, CAM_Z, CAM_YAW, CAM_PITCH);
        assertBaseline("translucent", pixels);
    }
}

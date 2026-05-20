package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.render.data.SceneData;
import ru.ashesha.buildBattleAI.util.RendererUtils;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

/**
 * CPU-based voxel ray caster.
 * Produces a 224×224 RGB image of blocks within a {@link SceneData}.
 * Rays are parallelized across scanline stripes via a dedicated {@link ForkJoinPool}.
 * <p>
 * Instance-based: each {@code CpuRenderer} owns its own thread pool.
 * The pool is created once in the constructor and released via {@link #shutdown()}.
 * After shutdown, the renderer must not be used — create a new instance instead.
 * <p>
 * Features:
 * <ul>
 *     <li>Per-face ambient shading (Minecraft-style directional lighting)</li>
 *     <li>Ambient Occlusion (AO) — quadrant-based corner neighbor sampling for all opaque blocks</li>
 *     <li>Emissive block bypass — light-emitting blocks always render at full brightness</li>
 *     <li>Semi-transparency — alpha-blended color accumulation through translucent blocks (glass, water, ice)</li>
 *     <li>Sub-block shapes — ray-AABB intersection for non-full-cube blocks</li>
 * </ul>
 * The {@link #render} method is thread-safe: multiple concurrent calls share
 * the pool safely because each invocation operates on independent pixel buffers.
 */
public class CpuRenderer {

    /**
     * Default background (sky) color as packed 24-bit RGB.
     */
    private static final int BG_COLOR = 0xC8D8E8;
    /**
     * Pre-extracted background color channels for fast blending in the ray loop.
     */
    private static final double BG_R = (BG_COLOR >> 16) & 0xFF;
    private static final double BG_G = (BG_COLOR >> 8) & 0xFF;
    private static final double BG_B = BG_COLOR & 0xFF;
    /**
     * Small epsilon to nudge the ray entry point forward, preventing self-intersection at voxel boundaries.
     */
    private static final double NUDGE = 1e-4;

    /**
     * Maximum number of scanlines a leaf task will render directly.
     */
    private static final int ROW_THRESHOLD = 16;

    // Face brightness multipliers (Minecraft-style directional shading).
    // Raised above vanilla values (vanilla: bottom=0.5, X=0.6, Z=0.8) because the
    // flat-color renderer has no texture detail — darker multipliers make blocks
    // unrecognizable, especially dark greens and browns.
    private static final double BRIGHTNESS_Y_TOP = 1.0;
    private static final double BRIGHTNESS_Y_BOTTOM = 0.6;
    private static final double BRIGHTNESS_X = 0.7;
    private static final double BRIGHTNESS_Z = 0.85;

    /**
     * AO darkening table: index = number of solid corner neighbors (0–3).
     */
    private static final double[] AO_TABLE = {1.0, 0.88, 0.78, 0.68};

    /**
     * Stop tracing through translucent blocks when remaining opacity is below this threshold.
     */
    private static final double MIN_REMAINING = 0.01;

    /**
     * Dedicated pool for render tasks — avoids contention with the shared common pool,
     * which is used by CompletableFuture, parallel streams, and Bukkit scheduler internals.
     * Created once in the constructor, released in {@link #shutdown()}.
     */
    private final ForkJoinPool pool;

    /**
     * Creates a renderer with a pool sized to available processors.
     * <p>
     * Worker threads are produced by a custom {@link ForkJoinPool.ForkJoinWorkerThreadFactory}
     * that names each thread {@code bbai-renderer-<poolIndex>} and marks it as a daemon.
     * Named threads make CPU profiles and thread dumps far easier to read, and daemon
     * status ensures the JVM is never held alive by a forgotten renderer pool after
     * {@link #shutdown()} fails to terminate cleanly.
     */
    public CpuRenderer() {
        this(new ForkJoinPool(
                Runtime.getRuntime().availableProcessors(),
                new ForkJoinPool.ForkJoinWorkerThreadFactory() {
                    @Override
                    public ForkJoinWorkerThread newThread(ForkJoinPool p) {
                        ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                        t.setName("bbai-renderer-" + t.getPoolIndex());
                        t.setDaemon(true);
                        return t;
                    }
                },
                null,
                false));
    }

    /**
     * Creates a renderer backed by the given pool.
     * Useful for testing with controlled parallelism.
     *
     * @param pool the thread pool to use for parallel rendering
     */
    public CpuRenderer(@NonNull ForkJoinPool pool) {
        this.pool = pool;
    }

    // ===== Ray tracing algorithm (private static — pure functions) =====

    /**
     * Trace a single ray through the scene using DDA voxel traversal.
     * Accumulates color through translucent blocks (front-to-back compositing)
     * and returns the final blended pixel color.
     */
    private static int traceRay(SceneData scene,
                                double ox, double oy, double oz,
                                double dx, double dy, double dz,
                                double aabbMinX, double aabbMinY, double aabbMinZ,
                                double aabbMaxX, double aabbMaxY, double aabbMaxZ,
                                int regionMinX, int regionMinY, int regionMinZ,
                                int regionMaxX, int regionMaxY, int regionMaxZ,
                                int[] heightMap) {
        // Ray-AABB intersection (slab method).
        // Also tracks which axis-aligned slab produced the final tMin — this
        // is the AABB face the ray enters through. The DDA traversal needs
        // that as the initial value of `face` so that a starting voxel which
        // already contains a block (tight-AABB case) is shaded with the
        // correct face brightness instead of the bogus default.
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;
        int entryAxis = -1;

        if (dx != 0) {
            double invD = 1.0 / dx;
            double t1 = (aabbMinX - ox) * invD;
            double t2 = (aabbMaxX - ox) * invD;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
                entryAxis = 0;
            }
            tMax = Math.min(tMax, t2);
        } else if (ox < aabbMinX || ox >= aabbMaxX)
            return BG_COLOR;

        if (dy != 0) {
            double invD = 1.0 / dy;
            double t1 = (aabbMinY - oy) * invD;
            double t2 = (aabbMaxY - oy) * invD;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
                entryAxis = 1;
            }
            tMax = Math.min(tMax, t2);
        } else if (oy < aabbMinY || oy >= aabbMaxY)
            return BG_COLOR;

        if (dz != 0) {
            double invD = 1.0 / dz;
            double t1 = (aabbMinZ - oz) * invD;
            double t2 = (aabbMaxZ - oz) * invD;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) {
                tMin = t1;
                entryAxis = 2;
            }
            tMax = Math.min(tMax, t2);
        } else if (oz < aabbMinZ || oz >= aabbMaxZ)
            return BG_COLOR;

        if (tMin >= tMax || tMax <= 0)
            return BG_COLOR;

        // Entry point into the AABB.
        //
        // When the ray enters the AABB from outside (tMin > 0), nudge
        // slightly forward so floor() lands on the first voxel *inside*
        // the AABB instead of the boundary voxel — the standard
        // safety margin against floating-point boundary roundoff.
        //
        // When the camera is already inside the AABB (tMin <= 0), keep
        // tStart = 0. Adding NUDGE here would advance startX/Y/Z one
        // epsilon along the ray and, if the camera coordinate happens to
        // sit within NUDGE of a voxel boundary, push the floor() to the
        // *next* voxel. That misidentifies the starting voxel, breaks
        // strict containment, and drops a legitimate inside-block hit
        // (the camera's real voxel may be a block while the post-nudge
        // voxel is air).
        double tStart;
        if (tMin > 0.0)
            tStart = tMin + NUDGE;
        else
            tStart = 0.0;
        if (tStart >= tMax)
            return BG_COLOR;

        double startX = ox + dx * tStart;
        double startY = oy + dy * tStart;
        double startZ = oz + dz * tStart;

        // Starting voxel
        int vx = (int) Math.floor(startX);
        int vy = (int) Math.floor(startY);
        int vz = (int) Math.floor(startZ);

        // Clamp to region bounds (safety for floating point edge cases)
        if (vx < regionMinX)
            vx = regionMinX;
        if (vx > regionMaxX)
            vx = regionMaxX;
        if (vy < regionMinY)
            vy = regionMinY;
        if (vy > regionMaxY)
            vy = regionMaxY;
        if (vz < regionMinZ)
            vz = regionMinZ;
        if (vz > regionMaxZ)
            vz = regionMaxZ;

        // DDA setup
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = dx != 0 ? Math.abs(1.0 / dx) : Double.MAX_VALUE;
        double tDeltaY = dy != 0 ? Math.abs(1.0 / dy) : Double.MAX_VALUE;
        double tDeltaZ = dz != 0 ? Math.abs(1.0 / dz) : Double.MAX_VALUE;

        double tMaxX, tMaxY, tMaxZ;
        if (dx > 0)
            tMaxX = (vx + 1.0 - startX) / dx;
        else if (dx < 0)
            tMaxX = (vx - startX) / dx;
        else
            tMaxX = Double.MAX_VALUE;

        if (dy > 0)
            tMaxY = (vy + 1.0 - startY) / dy;
        else if (dy < 0)
            tMaxY = (vy - startY) / dy;
        else
            tMaxY = Double.MAX_VALUE;

        if (dz > 0)
            tMaxZ = (vz + 1.0 - startZ) / dz;
        else if (dz < 0)
            tMaxZ = (vz - startZ) / dz;
        else
            tMaxZ = Double.MAX_VALUE;

        // Track which face was crossed to reach current voxel (for shading).
        // 0 = X face, 1 = Y face, 2 = Z face. A sentinel -1 is used only
        // when the camera is strictly inside the starting voxel's shape and
        // the inside-AABB block below has already shaded that voxel out-of-
        // band; the DDA loop's iteration-0 hit check is gated on
        // `hitFace >= 0` so the sentinel suppresses the second pass.
        //
        // For inside-AABB starts where the camera is NOT strictly inside
        // any shape box (the starting position lies on a voxel face or in
        // empty space between sub-block boxes), the inside-AABB block below
        // overrides `face` with the AABB exit axis so DDA can hit the
        // voxel from its natural outside-hit convention.
        int face;
        if (tMin >= 0.0 && entryAxis >= 0)
            face = entryAxis;
        else
            face = -1;

        // Translucency accumulation (front-to-back compositing)
        double accR = 0, accG = 0, accB = 0;
        double remaining = 1.0;

        // ─── Camera-inside-AABB starting voxel ──────────────────────────
        // When tMin < 0, the camera sits inside the AABB and the ray
        // originates inside the starting voxel rather than entering it
        // through a face. The DDA loop's per-voxel shading assumes an
        // "outside-in" hit and uses the ray direction's sign to pick
        // both the brightness side (Y_TOP vs Y_BOTTOM) and the
        // face-dependent color (e.g. grass top vs dirt bottom). Applying
        // that convention to a ray that *exits* the voxel through the
        // same face would render the wrong side.
        //
        // Handle the starting voxel here with an *inverted* direction
        // convention: a viewer inside a voxel looking out through some
        // face is geometrically equivalent to a viewer outside the
        // voxel hitting the same face with a ray going the opposite way.
        // Inverting dx/dy/dz and stepX/Y/Z for this one shading call
        // routes the brightness branch, color lookup, and AO sampling
        // to the correct side of the exit face.
        if (tMin < 0.0) {
            // Exit face: the axis the ray will leave the starting voxel
            // through first (smallest tMax{X,Y,Z}).
            int exitAxis;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ)
                exitAxis = 0;
            else if (tMaxY <= tMaxZ)
                exitAxis = 1;
            else
                exitAxis = 2;

            XMaterial startBlock = scene.getBlockType(vx, vy, vz);
            int startColor = BlockPalette.getColor(startBlock);
            // For inside-AABB starts where the camera lies on a voxel face
            // (boundary case) the natural semantics is the regular DDA
            // outside-hit through that face — *not* inside-out inversion.
            // Pre-set `face` to the AABB exit axis so DDA's iteration 0 can
            // shade the starting voxel with the standard convention. If the
            // strict-containment check below succeeds the value is
            // overridden to -1 (sentinel) and the voxel is shaded here
            // out-of-band instead.
            face = exitAxis;
            if (startColor != -1) {
                double[][] startShape = BlockShape.getShape(scene, vx, vy, vz, startBlock);

                // Pick the AABB the inside-out shading applies to. For a
                // full cube it is the voxel itself; for sub-block shapes
                // (slabs, stairs, fences, walls, ...) it is the first
                // shape box that strictly contains the camera origin.
                // Strict containment is mandatory in both cases: a point
                // on a box face is geometrically *on* the boundary, not
                // inside, and the inverted-direction convention would
                // pick the wrong side of a face-dependent block (top vs
                // bottom for grass/mycelium/podzol, etc.).
                double bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;
                boolean cameraInsideShape = false;
                bMinX = bMinY = bMinZ = 0;
                bMaxX = bMaxY = bMaxZ = 0;
                if (startShape == null) {
                    double xLo = vx, yLo = vy, zLo = vz;
                    double xHi = vx + 1.0, yHi = vy + 1.0, zHi = vz + 1.0;
                    if (ox > xLo && ox < xHi
                            && oy > yLo && oy < yHi
                            && oz > zLo && oz < zHi) {
                        bMinX = xLo;
                        bMinY = yLo;
                        bMinZ = zLo;
                        bMaxX = xHi;
                        bMaxY = yHi;
                        bMaxZ = zHi;
                        cameraInsideShape = true;
                    }
                } else {
                    for (double[] box : startShape) {
                        double xLo = vx + box[0], yLo = vy + box[1], zLo = vz + box[2];
                        double xHi = vx + box[3], yHi = vy + box[4], zHi = vz + box[5];
                        if (ox > xLo && ox < xHi
                                && oy > yLo && oy < yHi
                                && oz > zLo && oz < zHi) {
                            bMinX = xLo;
                            bMinY = yLo;
                            bMinZ = zLo;
                            bMaxX = xHi;
                            bMaxY = yHi;
                            bMaxZ = zHi;
                            cameraInsideShape = true;
                            break;
                        }
                    }
                }

                if (cameraInsideShape) {
                    // We are about to shade the starting voxel here. Mark
                    // the DDA loop's iteration 0 hit check as already
                    // satisfied so the same voxel is not re-shaded with the
                    // outside-hit convention.
                    face = -1;

                    // Exit face of the containing AABB: axis with the
                    // smallest forward-going time to a box face.
                    double tExitX = dx > 0 ? (bMaxX - ox) / dx
                                  : dx < 0 ? (bMinX - ox) / dx : Double.MAX_VALUE;
                    double tExitY = dy > 0 ? (bMaxY - oy) / dy
                                  : dy < 0 ? (bMinY - oy) / dy : Double.MAX_VALUE;
                    double tExitZ = dz > 0 ? (bMaxZ - oz) / dz
                                  : dz < 0 ? (bMinZ - oz) / dz : Double.MAX_VALUE;
                    int boxExitAxis;
                    if (tExitX <= tExitY && tExitX <= tExitZ)
                        boxExitAxis = 0;
                    else if (tExitY <= tExitZ)
                        boxExitAxis = 1;
                    else
                        boxExitAxis = 2;

                    // For full-cube starts the voxel-exit axis was the
                    // same as the AABB-exit axis (kept above as a faster
                    // path). For sub-block starts we use the box's own
                    // exit axis instead.
                    int effectiveExitAxis = (startShape == null) ? exitAxis : boxExitAxis;

                    // Inverted direction for inside-out shading. AO is
                    // skipped, so only dx/dy/dz are inverted (stepX/Y/Z
                    // would have been inverted for AO neighbor lookup).
                    double idx = -dx;
                    double idy = -dy;
                    double idz = -dz;

                    int alpha = BlockPalette.getAlpha(startBlock);
                    double brightness;
                    if (BlockPalette.isEmissive(startBlock))
                        brightness = 1.0;
                    else {
                        if (effectiveExitAxis == 0)
                            brightness = BRIGHTNESS_X;
                        else if (effectiveExitAxis == 1)
                            brightness = idy < 0 ? BRIGHTNESS_Y_TOP : BRIGHTNESS_Y_BOTTOM;
                        else
                            brightness = BRIGHTNESS_Z;
                        // AO is intentionally skipped for inside-voxel
                        // hits: occlusion from "neighbors on the outward
                        // side" makes little physical sense when the
                        // viewer is enclosed by the block itself.
                    }

                    int shadedColor = BlockPalette.getColor(scene, vx, vy, vz, startBlock,
                            effectiveExitAxis, idx, idy, idz);
                    double sr = ((shadedColor >> 16) & 0xFF) * brightness;
                    double sg = ((shadedColor >> 8) & 0xFF) * brightness;
                    double sb = (shadedColor & 0xFF) * brightness;

                    if (alpha >= 255) {
                        // Opaque starting voxel — return immediately.
                        int finalR = Math.min(255, (int) sr);
                        int finalG = Math.min(255, (int) sg);
                        int finalB = Math.min(255, (int) sb);
                        return (finalR << 16) | (finalG << 8) | finalB;
                    }
                    // Translucent — accumulate and continue the DDA loop
                    // from the *next* voxel. The first iteration's hit
                    // check is gated on `hitFace >= 0` so this voxel is
                    // not re-shaded with the wrong (outside-in) convention.
                    double a = alpha / 255.0;
                    accR = a * sr;
                    accG = a * sg;
                    accB = a * sb;
                    remaining = 1.0 - a;
                }
            }
        }

        // Column stride for height map index computation
        int hmSizeZ = regionMaxZ - regionMinZ + 1;

        // DDA traversal
        for (int i = 0; i < 512; i++) {
            // Height map acceleration: skip block lookup if Y is outside column's occupied range.
            // For each (x,z) column, heightMap stores [minY, maxY] of non-transparent blocks.
            // If current Y is outside this range, the voxel is guaranteed empty — skip to DDA step.
            int hmCol = ((vx - regionMinX) * hmSizeZ + (vz - regionMinZ)) * 2;
            if (vy >= heightMap[hmCol] && vy <= heightMap[hmCol + 1]) {
                XMaterial blockType = scene.getBlockType(vx, vy, vz);
                int color = BlockPalette.getColor(blockType);
                if (color != -1) {
                    double[][] shape = BlockShape.getShape(scene, vx, vy, vz, blockType);
                    int hitFace = face;
                    if (shape != null)
                        hitFace = testSubBlockHit(ox, oy, oz, dx, dy, dz, vx, vy, vz, shape);
                    if (hitFace >= 0) {
                        int alpha = BlockPalette.getAlpha(blockType);

                        // Adjacent full translucent blocks of the same medium should read as one volume.
                        // Skip internal boundaries so glass/water/ice do not re-tint on every voxel.
                        if (alpha < 255 && shape == null && shouldSkipTranslucentBlend(scene, blockType, vx, vy, vz,
                                hitFace, stepX, stepY, stepZ,
                                regionMinX, regionMinY, regionMinZ,
                                regionMaxX, regionMaxY, regionMaxZ))
                            continue;

                        // Compute brightness (emissive bypass / face shading + AO)
                        double brightness;
                        if (BlockPalette.isEmissive(blockType))
                            brightness = 1.0;
                        else {
                            if (hitFace == 0)
                                brightness = BRIGHTNESS_X;
                            else if (hitFace == 1)
                                brightness = dy < 0 ? BRIGHTNESS_Y_TOP : BRIGHTNESS_Y_BOTTOM;
                            else
                                brightness = BRIGHTNESS_Z;
                            // AO for all opaque blocks (full blocks and sub-block shapes like slabs/stairs)
                            if (alpha >= 255)
                                brightness *= computeAO(scene, ox, oy, oz, dx, dy, dz,
                                        vx, vy, vz, hitFace, stepX, stepY, stepZ,
                                        regionMinX, regionMinY, regionMinZ,
                                        regionMaxX, regionMaxY, regionMaxZ);
                        }

                        // Shade the color
                        int shadedColor = BlockPalette.getColor(scene, vx, vy, vz, blockType, hitFace, dx, dy, dz);
                        double sr = ((shadedColor >> 16) & 0xFF) * brightness;
                        double sg = ((shadedColor >> 8) & 0xFF) * brightness;
                        double sb = (shadedColor & 0xFF) * brightness;

                        if (alpha >= 255) {
                            // Opaque hit — accumulate and stop
                            accR += remaining * sr;
                            accG += remaining * sg;
                            accB += remaining * sb;
                            remaining = 0;
                            break;
                        } else {
                            // Translucent hit — blend and continue
                            double a = alpha / 255.0;
                            accR += remaining * a * sr;
                            accG += remaining * a * sg;
                            accB += remaining * a * sb;
                            remaining *= (1.0 - a);
                            if (remaining < MIN_REMAINING)
                                break;
                        }
                    }
                }
            }

            // Advance to next voxel boundary
            if (tMaxX < tMaxY)
                if (tMaxX < tMaxZ) {
                    vx += stepX;
                    tMaxX += tDeltaX;
                    face = 0;
                } else {
                    vz += stepZ;
                    tMaxZ += tDeltaZ;
                    face = 2;
                }
            else if (tMaxY < tMaxZ) {
                vy += stepY;
                tMaxY += tDeltaY;
                face = 1;
            } else {
                vz += stepZ;
                tMaxZ += tDeltaZ;
                face = 2;
            }

            // Check if we've left the region
            if (vx < regionMinX || vx > regionMaxX ||
                    vy < regionMinY || vy > regionMaxY ||
                    vz < regionMinZ || vz > regionMaxZ)
                break;
        }

        // Blend remaining opacity with background
        int finalR = Math.min(255, (int) (accR + remaining * BG_R));
        int finalG = Math.min(255, (int) (accG + remaining * BG_G));
        int finalB = Math.min(255, (int) (accB + remaining * BG_B));
        return (finalR << 16) | (finalG << 8) | finalB;
    }

    /**
     * Compute ambient occlusion multiplier for a face hit.
     * Checks 3 corner neighbors (2 edge + 1 diagonal) on the normal side of the face.
     * AO is evaluated for all 4 face corners and smoothly interpolated by the hit position
     * to avoid harsh quadrant transitions.
     *
     * @return AO multiplier in [0.68, 1.0] (0.68 = all 3 corner neighbors solid)
     */
    private static double computeAO(SceneData scene,
                                    double ox, double oy, double oz,
                                    double dx, double dy, double dz,
                                    int vx, int vy, int vz,
                                    int hitFace,
                                    int stepX, int stepY, int stepZ,
                                    int rMinX, int rMinY, int rMinZ,
                                    int rMaxX, int rMaxY, int rMaxZ) {
        double fracU, fracV;

        if (hitFace == 0) {
            // X face — tangent axes: Y, Z
            double boundary = stepX > 0 ? (double) vx : (double) (vx + 1);
            double t = dx != 0 ? (boundary - ox) / dx : 0;
            fracU = (oy + dy * t) - vy;
            fracV = (oz + dz * t) - vz;
        } else if (hitFace == 1) {
            // Y face — tangent axes: X, Z
            double boundary = stepY > 0 ? (double) vy : (double) (vy + 1);
            double t = dy != 0 ? (boundary - oy) / dy : 0;
            fracU = (ox + dx * t) - vx;
            fracV = (oz + dz * t) - vz;
        } else {
            // Z face — tangent axes: X, Y
            double boundary = stepZ > 0 ? (double) vz : (double) (vz + 1);
            double t = dz != 0 ? (boundary - oz) / dz : 0;
            fracU = (ox + dx * t) - vx;
            fracV = (oy + dy * t) - vy;
        }

        fracU = clamp01(fracU);
        fracV = clamp01(fracV);

        double u = smoothstep(fracU);
        double v = smoothstep(fracV);

        double ao00 = computeCornerAO(scene, vx, vy, vz, hitFace, stepX, stepY, stepZ, -1, -1,
                rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ);
        double ao10 = computeCornerAO(scene, vx, vy, vz, hitFace, stepX, stepY, stepZ, 1, -1,
                rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ);
        double ao01 = computeCornerAO(scene, vx, vy, vz, hitFace, stepX, stepY, stepZ, -1, 1,
                rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ);
        double ao11 = computeCornerAO(scene, vx, vy, vz, hitFace, stepX, stepY, stepZ, 1, 1,
                rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ);

        double ao0 = lerp(ao00, ao10, u);
        double ao1 = lerp(ao01, ao11, u);
        return lerp(ao0, ao1, v);
    }

    /**
     * Determines whether to skip color blending at the boundary between two adjacent
     * translucent blocks of the same medium. This prevents water, glass, and ice from
     * re-tinting on every internal voxel boundary within a continuous volume.
     *
     * @return {@code true} if the previous voxel along the ray is the same translucent medium
     */
    private static boolean shouldSkipTranslucentBlend(SceneData scene,
                                                      XMaterial blockType,
                                                      int vx, int vy, int vz,
                                                      int hitFace,
                                                      int stepX, int stepY, int stepZ,
                                                      int minX, int minY, int minZ,
                                                      int maxX, int maxY, int maxZ) {
        int prevX = vx;
        int prevY = vy;
        int prevZ = vz;

        if (hitFace == 0)
            prevX -= stepX;
        else if (hitFace == 1)
            prevY -= stepY;
        else
            prevZ -= stepZ;

        if (prevX < minX || prevX > maxX || prevY < minY || prevY > maxY || prevZ < minZ || prevZ > maxZ)
            return false;

        XMaterial previous = scene.getBlockType(prevX, prevY, prevZ);
        return BlockPalette.canMergeTranslucent(previous, blockType);
    }

    /**
     * Computes the AO value for a single face corner by counting solid neighbors
     * in three positions: two edge-adjacent and one diagonal. The neighbor positions
     * are offset along the face's tangent axes by {@code du} and {@code dv} (-1 or +1),
     * shifted one voxel along the face normal (toward the viewer).
     *
     * @param du offset along the first tangent axis (-1 or +1)
     * @param dv offset along the second tangent axis (-1 or +1)
     * @return AO value from {@link #AO_TABLE}, indexed by the number of solid neighbors (0–3)
     */
    private static double computeCornerAO(SceneData scene,
                                          int vx, int vy, int vz,
                                          int hitFace,
                                          int stepX, int stepY, int stepZ,
                                          int du, int dv,
                                          int rMinX, int rMinY, int rMinZ,
                                          int rMaxX, int rMaxY, int rMaxZ) {
        int n1x;
        int n1y;
        int n1z;
        int n2x;
        int n2y;
        int n2z;
        int n3x;
        int n3y;
        int n3z;

        if (hitFace == 0) {
            int nx = vx - stepX;
            n1x = nx;
            n1y = vy + du;
            n1z = vz;
            n2x = nx;
            n2y = vy;
            n2z = vz + dv;
            n3x = nx;
            n3y = vy + du;
            n3z = vz + dv;
        } else if (hitFace == 1) {
            int ny = vy - stepY;
            n1x = vx + du;
            n1y = ny;
            n1z = vz;
            n2x = vx;
            n2y = ny;
            n2z = vz + dv;
            n3x = vx + du;
            n3y = ny;
            n3z = vz + dv;
        } else {
            int nz = vz - stepZ;
            n1x = vx + du;
            n1y = vy;
            n1z = nz;
            n2x = vx;
            n2y = vy + dv;
            n2z = nz;
            n3x = vx + du;
            n3y = vy + dv;
            n3z = nz;
        }

        int count = 0;
        if (isSolidForAO(scene, n1x, n1y, n1z, rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ))
            count++;
        if (isSolidForAO(scene, n2x, n2y, n2z, rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ))
            count++;
        if (isSolidForAO(scene, n3x, n3y, n3z, rMinX, rMinY, rMinZ, rMaxX, rMaxY, rMaxZ))
            count++;
        return AO_TABLE[count];
    }

    /**
     * Clamps a value to the [0, 1] range.
     */
    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Hermite smoothstep interpolation for smooth AO transitions between face quadrants.
     */
    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    /**
     * Linearly interpolates between {@code a} and {@code b} by factor {@code t}.
     */
    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Check if a block is solid and opaque for AO purposes.
     * Translucent blocks (glass, water) do not cast AO shadows.
     */
    private static boolean isSolidForAO(SceneData scene, int x, int y, int z,
                                        int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
            return false;
        XMaterial mat = scene.getBlockType(x, y, z);
        return BlockPalette.getColor(mat) != -1 && BlockPalette.getAlpha(mat) >= 255;
    }

    /**
     * Test ray against sub-block AABB boxes within a voxel.
     * Returns the hit face (0=X, 1=Y, 2=Z) or -1 if the ray misses all boxes.
     */
    private static int testSubBlockHit(double ox, double oy, double oz,
                                       double dx, double dy, double dz,
                                       int vx, int vy, int vz,
                                       double[][] boxes) {
        double closestT = Double.MAX_VALUE;
        int hitFace = -1;

        for (double[] box : boxes) {
            double bMinX = vx + box[0], bMinY = vy + box[1], bMinZ = vz + box[2];
            double bMaxX = vx + box[3], bMaxY = vy + box[4], bMaxZ = vz + box[5];

            double tMin = Double.NEGATIVE_INFINITY;
            double tMax = Double.POSITIVE_INFINITY;
            int entryFace = -1;

            // X slabs
            if (dx != 0) {
                double invD = 1.0 / dx;
                double t1 = (bMinX - ox) * invD;
                double t2 = (bMaxX - ox) * invD;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    entryFace = 0;
                }
                tMax = Math.min(tMax, t2);
            } else if (ox < bMinX || ox >= bMaxX)
                continue;

            // Y slabs
            if (dy != 0) {
                double invD = 1.0 / dy;
                double t1 = (bMinY - oy) * invD;
                double t2 = (bMaxY - oy) * invD;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    entryFace = 1;
                }
                tMax = Math.min(tMax, t2);
            } else if (oy < bMinY || oy >= bMaxY)
                continue;

            // Z slabs
            if (dz != 0) {
                double invD = 1.0 / dz;
                double t1 = (bMinZ - oz) * invD;
                double t2 = (bMaxZ - oz) * invD;
                if (t1 > t2) {
                    double tmp = t1;
                    t1 = t2;
                    t2 = tmp;
                }
                if (t1 > tMin) {
                    tMin = t1;
                    entryFace = 2;
                }
                tMax = Math.min(tMax, t2);
            } else if (oz < bMinZ || oz >= bMaxZ)
                continue;

            if (tMin >= tMax || tMin < 0)
                continue;

            if (tMin < closestT) {
                closestT = tMin;
                hitFace = entryFace;
            }
        }

        return hitFace;
    }

    /**
     * Renders the scene from the given camera position and orientation.
     * Safe to call from any thread concurrently — each invocation operates
     * on its own pixel buffer and submits independent fork/join tasks.
     * <p>
     * <strong>This method blocks the calling thread</strong> until all pixels are rendered.
     * It must never be called from the Bukkit main thread — use {@code Bukkit.getScheduler().runTaskAsynchronously()}.
     *
     * @param scene the captured scene data (thread-safe)
     * @param camX  camera X position
     * @param camY  camera Y position
     * @param camZ  camera Z position
     * @param yaw   camera yaw (Minecraft convention: 0=south, 90=west, 180=north)
     * @param pitch camera pitch (-90=up, 0=horizontal, 90=down)
     * @return byte array of size 224×224×3 containing RGB pixel data in row-major HWC order
     */
    public byte[] render(@NonNull SceneData scene,
                         double camX, double camY, double camZ,
                         float yaw, float pitch) {
        byte[] pixels = new byte[RendererUtils.WIDTH * RendererUtils.HEIGHT * 3];

        // Camera basis vectors (Minecraft coordinate system)
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);

        // Forward: direction the player is looking
        double fwdX = -sinYaw * cosPitch;
        double fwdY = -sinPitch;
        double fwdZ = cosYaw * cosPitch;

        // Right: player's right-hand direction
        double rgtX = -cosYaw;
        double rgtZ = -sinYaw;
        // rgtY = 0 always

        // Up = right × forward
        double upX = -rgtZ * fwdY;
        double upY = rgtZ * fwdX - rgtX * fwdZ;
        double upZ = rgtX * fwdY;

        double halfTan = Math.tan(Math.toRadians(RendererUtils.FOV * 0.5));

        // Pre-compute per-column Y bounds for empty-space skipping during DDA traversal
        int[] heightMap = RendererUtils.buildHeightMap(scene);

        RenderContext ctx = new RenderContext(
                pixels, scene,
                camX, camY, camZ,
                fwdX, fwdY, fwdZ,
                rgtX, rgtZ,
                upX, upY, upZ,
                halfTan,
                scene.minX(), scene.minY(), scene.minZ(),
                scene.maxX() + 1.0, scene.maxY() + 1.0, scene.maxZ() + 1.0,
                scene.minX(), scene.minY(), scene.minZ(),
                scene.maxX(), scene.maxY(), scene.maxZ(),
                heightMap
        );

        pool.invoke(new RenderTask(ctx, 0, RendererUtils.HEIGHT));

        return pixels;
    }

    /**
     * Shuts down the dedicated thread pool, releasing worker threads back to the OS.
     * After this call, the renderer instance is no longer usable — discard it and
     * create a new {@code CpuRenderer} if rendering is needed again.
     * <p>
     * Performs a graceful shutdown: rejects new tasks, waits up to 5 seconds for
     * already-submitted render tasks to complete, then escalates to
     * {@link ForkJoinPool#shutdownNow()} if any worker is still running. This
     * prevents partially-rendered frames from being silently abandoned during
     * plugin reload, while still guaranteeing that {@code shutdown()} returns
     * in bounded time even if a render is stuck.
     * <p>
     * If the calling thread is interrupted while waiting, the interrupt status
     * is preserved and {@link ForkJoinPool#shutdownNow()} is invoked immediately.
     */
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5L, TimeUnit.SECONDS))
                pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ===== Parallel rendering infrastructure =====

    /**
     * Shared immutable context for all render tasks within a single frame.
     * Holds camera parameters, scene reference, and the output pixel buffer.
     */
    @RequiredArgsConstructor
    static class RenderContext {
        final byte[] pixels;
        final SceneData scene;
        final double camX, camY, camZ;
        final double fwdX, fwdY, fwdZ;
        final double rgtX, rgtZ;
        final double upX, upY, upZ;
        final double halfTan;
        final double aabbMinX, aabbMinY, aabbMinZ;
        final double aabbMaxX, aabbMaxY, aabbMaxZ;
        final int regionMinX, regionMinY, regionMinZ;
        final int regionMaxX, regionMaxY, regionMaxZ;
        final int[] heightMap;
    }

    /**
     * Fork/join task that renders a horizontal stripe of scanlines.
     * Recursively splits until the stripe is ≤ {@link #ROW_THRESHOLD} rows,
     * then traces all rays in that stripe directly.
     */
    @RequiredArgsConstructor
    static class RenderTask extends RecursiveAction {

        private final RenderContext ctx;
        private final int startRow;
        private final int endRow;

        @Override
        protected void compute() {
            if (endRow - startRow <= ROW_THRESHOLD) {
                renderRows();
                return;
            }
            int mid = (startRow + endRow) >>> 1;
            invokeAll(
                    new RenderTask(ctx, startRow, mid),
                    new RenderTask(ctx, mid, endRow)
            );
        }

        /**
         * Traces one ray per pixel for scanlines [{@code startRow}, {@code endRow}).
         */
        private void renderRows() {
            for (int py = startRow; py < endRow; py++) {
                double ndcY = (1.0 - 2.0 * (py + 0.5) / RendererUtils.HEIGHT) * ctx.halfTan;

                for (int px = 0; px < RendererUtils.WIDTH; px++) {
                    double ndcX = (2.0 * (px + 0.5) / RendererUtils.WIDTH - 1.0) * ctx.halfTan;

                    // Ray direction = forward + ndcX * right + ndcY * up
                    double rdx = ctx.fwdX + ndcX * ctx.rgtX + ndcY * ctx.upX;
                    double rdy = ctx.fwdY + ndcY * ctx.upY;
                    double rdz = ctx.fwdZ + ndcX * ctx.rgtZ + ndcY * ctx.upZ;

                    // Normalize
                    double invLen = 1.0 / Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
                    rdx *= invLen;
                    rdy *= invLen;
                    rdz *= invLen;

                    int color = traceRay(ctx.scene, ctx.camX, ctx.camY, ctx.camZ, rdx, rdy, rdz,
                            ctx.aabbMinX, ctx.aabbMinY, ctx.aabbMinZ,
                            ctx.aabbMaxX, ctx.aabbMaxY, ctx.aabbMaxZ,
                            ctx.regionMinX, ctx.regionMinY, ctx.regionMinZ,
                            ctx.regionMaxX, ctx.regionMaxY, ctx.regionMaxZ,
                            ctx.heightMap);

                    int idx = (py * RendererUtils.WIDTH + px) * 3;
                    ctx.pixels[idx] = (byte) ((color >> 16) & 0xFF);
                    ctx.pixels[idx + 1] = (byte) ((color >> 8) & 0xFF);
                    ctx.pixels[idx + 2] = (byte) (color & 0xFF);
                }
            }
        }
    }
}

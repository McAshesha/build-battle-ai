package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.render.data.FlatScene;
import ru.ashesha.buildBattleAI.util.RendererUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stress test for {@link CpuRenderer} thread-safety under concurrent
 * {@code render()} invocations.
 * <p>
 * {@link CpuRenderer}'s Javadoc declares the {@code render()} method as
 * thread-safe (the dedicated {@link java.util.concurrent.ForkJoinPool} owns
 * one task per row and the heavy state lives in per-call locals). This suite
 * exercises that claim by hammering a single shared renderer from four worker
 * threads at once and verifying:
 * <ul>
 *   <li>no thread throws an exception;</li>
 *   <li>every returned buffer has the correct {@code 224×224×3} size;</li>
 *   <li>every buffer contains non-background pixels (the cube is actually
 *       rendered, not silently dropped);</li>
 *   <li>buffers from threads that used different yaw angles are not all
 *       identical — which would indicate that some per-call state is being
 *       shared between concurrent invocations.</li>
 * </ul>
 * <p>
 * The scene is a 4×4×4 solid stone cube — the same geometry used by
 * {@code RendererPixelEquivalenceTest#opaqueCubes()}. It is small, fast to
 * render, and produces a clearly non-background image from every angle.
 */
class RendererConcurrentStressTest {

    /**
     * Pre-cached AIR ordinal — {@link XMaterial#AIR} is NOT ordinal 0,
     * so any future variant of this scene must avoid relying on default
     * zero-initialisation.
     */
    @SuppressWarnings("unused")
    private static final short AIR = (short) XMaterial.AIR.ordinal();

    /** Number of worker threads hammering the renderer in parallel. */
    private static final int THREADS = 4;

    /** Number of renders each worker performs in its loop. */
    private static final int RENDERS_PER_THREAD = 25;

    /** Total renders produced by the stress test. */
    private static final int TOTAL_RENDERS = THREADS * RENDERS_PER_THREAD;

    /** Hard timeout for the whole stress run — well above worst-case CI cost. */
    private static final long TIMEOUT_SECONDS = 30L;

    /**
     * Shared renderer instance — one {@link java.util.concurrent.ForkJoinPool}
     * for the whole test class. Matches the {@code CpuRendererTest} pattern.
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
     * Builds the fixed 4×4×4 solid-stone scene shared by every worker. The
     * scene is immutable after construction, so it is safe to read from many
     * threads simultaneously.
     */
    private static FlatScene buildScene() {
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        return new FlatScene(data, 0, 0, 0, size, size, size);
    }

    /**
     * Returns {@code true} if at least one RGB triplet in the buffer differs
     * from the renderer's background sky colour {@code 0xC8D8E8}.
     */
    private static boolean hasNonBackgroundPixels(byte[] pixels) {
        for (int i = 0; i < pixels.length; i += 3)
            if ((pixels[i] & 0xFF) != 0xC8 || (pixels[i + 1] & 0xFF) != 0xD8 || (pixels[i + 2] & 0xFF) != 0xE8)
                return true;
        return false;
    }

    /**
     * Spawns {@link #THREADS} workers, each executing {@link #RENDERS_PER_THREAD}
     * renders against a shared {@link CpuRenderer}. Every worker uses a unique
     * yaw so that a leaked per-call state (e.g. a shared pixel buffer) would
     * manifest as suspiciously identical outputs across threads.
     */
    @Test
    void concurrentRendersAreSafeAndIndependent() throws InterruptedException {
        FlatScene scene = buildScene();

        // Latch ensures all workers start their render loop simultaneously,
        // maximising the chance of triggering thread-safety issues.
        CountDownLatch startLatch = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<byte[][]>> futures = new ArrayList<Future<byte[][]>>(THREADS);

        try {
            for (int t = 0; t < THREADS; t++) {
                // Each worker captures its own yaw — distinct camera angles
                // for every thread ensure the outputs would naturally diverge
                // unless something is being clobbered behind the scenes. The
                // base yaw of -45° aims at the cube from the (-X, -Z) corner;
                // small per-thread deltas keep every render facing it.
                final float threadYaw = -45.0f + (t - (THREADS - 1) / 2.0f) * 4.0f;
                futures.add(executor.submit(new java.util.concurrent.Callable<byte[][]>() {
                    @Override
                    public byte[][] call() throws Exception {
                        startLatch.await();
                        byte[][] results = new byte[RENDERS_PER_THREAD][];
                        for (int i = 0; i < RENDERS_PER_THREAD; i++)
                            // Camera placed outside the cube looking at it from
                            // the worker's unique yaw. Pitch and per-iteration
                            // jitter exercise the full pipeline.
                            results[i] = renderer.render(scene, -3.0, 4.0, -3.0, threadYaw + i * 0.01f, 20.0f);
                        return results;
                    }
                }));
            }

            // Release all workers at once.
            startLatch.countDown();

            executor.shutdown();
            if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                fail("Workers did not finish within " + TIMEOUT_SECONDS + "s — possible deadlock or thread leak");
        } finally {
            // Defensive: if assertion failure or timeout occurred above, force-cancel.
            if (!executor.isTerminated())
                executor.shutdownNow();
        }

        // Gather the per-worker buffers, asserting that none of them threw.
        byte[][] allRenders = new byte[TOTAL_RENDERS][];
        int writeIdx = 0;
        for (int t = 0; t < THREADS; t++) {
            byte[][] perThread;
            try {
                perThread = futures.get(t).get(1, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                fail("Worker " + t + " threw: " + e.getCause(), e);
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                fail("Worker " + t + " did not return a result", e);
                return;
            }
            assertNotNull(perThread, "Worker " + t + " returned a null result array");
            assertEquals(RENDERS_PER_THREAD, perThread.length,
                    "Worker " + t + " produced wrong number of renders");
            for (int i = 0; i < RENDERS_PER_THREAD; i++) {
                byte[] pixels = perThread[i];
                assertNotNull(pixels, "Worker " + t + ", render " + i + " returned null");
                assertEquals(RendererUtils.WIDTH * RendererUtils.HEIGHT * 3, pixels.length,
                        "Worker " + t + ", render " + i + " wrong size: " + pixels.length);
                assertTrue(hasNonBackgroundPixels(pixels),
                        "Worker " + t + ", render " + i
                                + " produced an all-background image — geometry was dropped");
                allRenders[writeIdx++] = pixels;
            }
        }

        // Cross-thread independence check: workers used different yaws, so the
        // last render from each thread must NOT all be byte-identical. If two
        // threads ended up with the same buffer, some per-call state was
        // shared (e.g. a hypothetical accidental static pixel array).
        boolean foundDifference = false;
        byte[] firstThreadLast = allRenders[RENDERS_PER_THREAD - 1];
        for (int t = 1; t < THREADS && !foundDifference; t++) {
            byte[] otherThreadLast = allRenders[(t + 1) * RENDERS_PER_THREAD - 1];
            if (!Arrays.equals(firstThreadLast, otherThreadLast))
                foundDifference = true;
        }
        assertTrue(foundDifference,
                "All threads produced identical buffers despite using different yaws — "
                        + "indicates shared per-call state across concurrent render() invocations");
    }
}

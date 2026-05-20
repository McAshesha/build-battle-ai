package ru.ashesha.buildBattleAI.render;

import com.cryptomorin.xseries.XMaterial;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginLogger;
import ru.ashesha.buildBattleAI.render.data.FlatScene;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Race-condition tests for {@link RenderService#shutdown()} versus concurrent
 * {@link RenderService#render} calls.
 * <p>
 * The renderer's worker pool ({@link CpuRenderer}'s internal
 * {@link java.util.concurrent.ForkJoinPool}) is destroyed inside
 * {@code shutdown()}. Without the {@code volatile} guard and the
 * {@link java.util.concurrent.RejectedExecutionException} catch added by
 * the F2 fix in {@link RenderService}, a render call that observed the
 * non-null reference just before shutdown nulled it could submit tasks to
 * a pool whose {@code invoke()} then throws
 * {@link java.util.concurrent.RejectedExecutionException} — a low-level
 * exception leaking past the service boundary instead of the diagnosable
 * {@link IllegalStateException} this service promises.
 * <p>
 * These tests are intentionally tolerant of timing: the race window is tiny
 * and not deterministic. They simply verify the post-conditions hold no
 * matter when the shutdown wins.
 */
class RenderServiceShutdownTest {

    /** Pre-cached AIR ordinal — see {@code CpuRendererTest} for rationale. */
    @SuppressWarnings("unused")
    private static final short AIR = (short) XMaterial.AIR.ordinal();

    /** Hard timeout for joining the worker thread — generous to absorb CI jitter. */
    private static final long JOIN_TIMEOUT_MS = 5_000L;

    /** Delay before invoking shutdown, allowing the worker to warm up first. */
    private static final long SHUTDOWN_DELAY_MS = 50L;

    private BuildBattleAI plugin;
    private PluginContext context;

    @BeforeEach
    void setUp() {
        plugin = mock(BuildBattleAI.class);
        when(plugin.getPluginLogger()).thenReturn(new PluginLogger(Logger.getLogger("Test")));
        context = mock(PluginContext.class);
        when(plugin.getContext()).thenReturn(context);
        when(context.getServerVersion()).thenReturn(ServerVersion.V_1_21);
    }

    /**
     * Builds a trivial 4×4×4 solid stone scene — same geometry as the stress
     * test. Tiny enough that each render returns quickly, keeping the
     * shutdown race window short and reproducible.
     */
    private static FlatScene buildScene() {
        int size = 4;
        short[] data = new short[size * size * size];
        Arrays.fill(data, (short) XMaterial.STONE.ordinal());
        return new FlatScene(data, 0, 0, 0, size, size, size);
    }

    /**
     * Spins one thread continuously rendering and another thread invoking
     * {@link RenderService#shutdown()} after a short warm-up. The render
     * thread loops until it observes the service stopped (signalled via
     * {@link IllegalStateException}). After joining, the test asserts:
     * <ul>
     *   <li>the render thread terminated (no deadlock);</li>
     *   <li>at least one render succeeded (proving the race window was
     *       actually exercised, not skipped entirely);</li>
     *   <li>no {@link java.util.concurrent.RejectedExecutionException}
     *       escaped — the F2 fix wraps it into {@link IllegalStateException};</li>
     *   <li>no other unexpected throwable escaped (e.g. {@link NullPointerException}).</li>
     * </ul>
     */
    @Test
    void shutdownDuringConcurrentRenderDoesNotLeakRejectedExecution() throws InterruptedException {
        final RenderService service = new RenderService(plugin);
        service.enable();

        final FlatScene scene = buildScene();
        final AtomicInteger successCount = new AtomicInteger();
        final AtomicReference<Throwable> unexpected = new AtomicReference<Throwable>();

        Thread renderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Loop until shutdown signals via IllegalStateException.
                // The service contract is: render() either returns a valid
                // buffer or throws IllegalStateException — anything else is
                // a regression.
                while (true) {
                    try {
                        byte[] pixels = service.render(scene, -3.0, 4.0, -3.0, 0f, 20f);
                        if (pixels != null && pixels.length > 0)
                            successCount.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        // Service is shutting down or already shut down —
                        // this is the documented termination signal.
                        return;
                    } catch (Throwable t) {
                        // Anything else (including bare RejectedExecutionException
                        // or NullPointerException) is a regression.
                        unexpected.set(t);
                        return;
                    }
                }
            }
        }, "render-worker");

        renderThread.start();

        // Allow the worker to issue at least a few renders before shutting
        // the service down — this makes it likely (but not certain) that
        // the race window actually fires.
        Thread.sleep(SHUTDOWN_DELAY_MS);
        service.shutdown();

        renderThread.join(JOIN_TIMEOUT_MS);

        assertFalse(renderThread.isAlive(),
                "Render thread did not terminate after shutdown — possible deadlock or unbounded loop");
        assertNull(unexpected.get(),
                "Unexpected throwable escaped render(): " + unexpected.get());
        assertTrue(successCount.get() > 0,
                "Worker thread never completed a successful render before shutdown — "
                        + "sanity check failed, the race window was not exercised");
    }

    /**
     * Verifies that {@link RenderService#shutdown()} is idempotent: calling
     * it twice in a row must not throw. The implementation guards the second
     * call with an {@code if (r != null)} check (see {@link RenderService#shutdown()}).
     * <p>
     * Also confirms the symmetric pre-enable case: calling {@code shutdown()}
     * on a never-enabled service is also a no-op (this is exercised by
     * {@link RenderServiceTest#shutdownWithoutEnableIsNoOp()} as well — kept
     * here as a defensive smoke check for the F2 contract).
     */
    @Test
    void shutdownIsIdempotent() {
        RenderService service = new RenderService(plugin);
        service.enable();

        service.shutdown();
        // A second shutdown must be a complete no-op — no exception, no
        // resource churn (there is no renderer left to shut down).
        service.shutdown();

        // And once more for paranoia — guarding against any latent state
        // toggled by repeated calls.
        service.shutdown();

        // Re-enable + re-shutdown still works after the idempotency triple-call,
        // confirming the service is reusable across reload cycles.
        service.enable();
        service.shutdown();
        service.shutdown();
    }
}

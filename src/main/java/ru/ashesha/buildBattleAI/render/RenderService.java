package ru.ashesha.buildBattleAI.render;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginContext;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.render.data.ChunkScene;
import ru.ashesha.buildBattleAI.render.data.SceneData;

/**
 * Centralized entry point for all rendering operations.
 * <p>
 * Owns the {@link CpuRenderer} instance and its lifecycle: the renderer
 * (along with its dedicated thread pool) is created in {@link #enable()}
 * and destroyed in {@link #shutdown()}. This guarantees that no worker
 * threads linger after the plugin is disabled, and that a fresh pool is
 * available on each reload cycle.
 * <p>
 * Also owns the server-version-dependent legacy flag (1.8–1.12 servers
 * require reflection-based block lookups) and passes it into every
 * {@link #capture(ChunkScene.RenderRegion)} call so that
 * {@link ChunkScene} does not need to resolve the version itself.
 * <p>
 * Game logic should always go through this service — never instantiate
 * {@link CpuRenderer} directly outside of tests.
 */
@RequiredArgsConstructor
public class RenderService implements PluginService {

    /**
     * The plugin instance, used to reach {@link PluginContext#getServerVersion()} during {@link #enable()}.
     */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Whether the server is running a pre-1.13 (pre-flattening) version.
     * Resolved in {@link #enable()} and passed into every capture call.
     */
    private boolean legacy;

    /**
     * The renderer instance, created in {@link #enable()} and destroyed in {@link #shutdown()}.
     * {@code null} while the service is not enabled.
     * <p>
     * Marked {@code volatile} so that {@link #render} can safely read the field
     * without locking from any thread: a concurrent {@link #shutdown()} on the
     * main thread publishes the {@code null} write to every reader, and the
     * explicit {@link #render} method captures the reference into a local before
     * dereferencing it. This produces a deterministic
     * {@link IllegalStateException} when {@code render} races with
     * {@link #shutdown} instead of the silent
     * {@link NullPointerException} that Lombok's {@code @Delegate} would have
     * produced when the field was nulled out.
     */
    private volatile CpuRenderer renderer;

    /**
     * Resolves the server-version-dependent legacy flag and creates the renderer.
     * Deferred from the constructor because services are created inside
     * {@link PluginContext}'s constructor, before the plugin publishes its context.
     * <p>
     * After this call, {@link #render} is safe to invoke from any thread; the
     * renderer reference is published via the {@code volatile} write to
     * {@link #renderer}.
     */
    @Override
    public void enable() {
        this.legacy = !plugin.getContext().getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_13);
        this.renderer = new CpuRenderer();
        plugin.getPluginLogger().debug("RenderService enabled (legacy: %s).", legacy);
    }

    /**
     * Captures chunk snapshots for the given render region.
     * MUST be called on the main server thread — once captured, the returned
     * {@link ChunkScene} is safe to access from any thread.
     *
     * @param region the region to capture
     * @return a thread-safe scene snapshot ready for rendering
     */
    public ChunkScene capture(@NonNull ChunkScene.RenderRegion region) {
        return ChunkScene.capture(region, legacy);
    }

    /**
     * Renders the scene from the given camera position and orientation.
     * Delegates to {@link CpuRenderer#render(SceneData, double, double, double, float, float)}
     * after confirming that the service has been enabled.
     * <p>
     * Throws {@link IllegalStateException} if invoked before {@link #enable()}
     * or after {@link #shutdown()} — this turns a latent {@link NullPointerException}
     * into an explicit, diagnosable error message at the service boundary.
     * <p>
     * Thread-safety: safe to call from any thread (including concurrent calls)
     * once the service is enabled. The underlying renderer's thread pool
     * processes each invocation on independent pixel buffers.
     *
     * @param scene the captured scene data (thread-safe)
     * @param camX  camera X position
     * @param camY  camera Y position
     * @param camZ  camera Z position
     * @param yaw   camera yaw (Minecraft convention: 0=south, 90=west, 180=north)
     * @param pitch camera pitch (-90=up, 0=horizontal, 90=down)
     * @return byte array of size 224×224×3 containing RGB pixel data in row-major HWC order
     * @throws IllegalStateException if the service is not enabled
     */
    public byte[] render(@NonNull SceneData scene,
                         double camX, double camY, double camZ,
                         float yaw, float pitch) {
        // Capture the volatile reference into a local: this guarantees that
        // even if shutdown() nulls the field concurrently, the in-flight
        // render completes against the captured CpuRenderer instance rather
        // than tripping a NullPointerException mid-call.
        CpuRenderer r = renderer;
        if (r == null)
            throw new IllegalStateException("RenderService is not enabled");
        return r.render(scene, camX, camY, camZ, yaw, pitch);
    }

    /**
     * Shuts down the renderer's dedicated thread pool and releases the instance.
     * After this call, {@link #render} will throw {@link IllegalStateException}
     * until the next {@link #enable()} recreates the renderer.
     */
    @Override
    public void shutdown() {
        CpuRenderer r = renderer;
        if (r != null) {
            // Null the volatile reference *first* so that any concurrent
            // render() call observes the null and fails fast with
            // IllegalStateException instead of submitting tasks to a pool
            // that is about to terminate.
            renderer = null;
            r.shutdown();
            plugin.getPluginLogger().debug("RenderService shut down — thread pool released.");
        }
    }
}

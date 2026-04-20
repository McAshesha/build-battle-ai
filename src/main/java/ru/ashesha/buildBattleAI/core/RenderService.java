package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.render.CpuRenderer;
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

    /** The plugin instance, used to reach {@link PluginContext#getServerVersion()} during {@link #enable()}. */
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
     */
    private CpuRenderer renderer;

    /**
     * Resolves the server-version-dependent legacy flag and creates the renderer.
     * Deferred from the constructor because services are created inside
     * {@link PluginContext}'s constructor, before the plugin publishes its context.
     */
    @Override
    public void enable() {
        this.legacy = !plugin.getContext().getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_13);
        this.renderer = new CpuRenderer();
    }

    /**
     * Renders a captured scene from the given camera pose.
     * Safe to call from any thread.
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
        return renderer.render(scene, camX, camY, camZ, yaw, pitch);
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
     * Shuts down the renderer's dedicated thread pool and releases the instance.
     * After this call, {@link #render} must not be called until the next
     * {@link #enable()} recreates the renderer.
     */
    @Override
    public void shutdown() {
        if (renderer != null) {
            renderer.shutdown();
            renderer = null;
        }
    }
}

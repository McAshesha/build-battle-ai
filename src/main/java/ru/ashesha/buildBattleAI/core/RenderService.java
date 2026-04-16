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
 * Owns the server-version-dependent legacy flag (1.8–1.12 servers require
 * reflection-based block lookups) and delegates the actual work to the
 * stateless {@link CpuRenderer} and {@link ChunkScene} utilities.
 * <p>
 * Replaces direct calls to {@code CpuRenderer.render()} and
 * {@code ChunkScene.capture()} from game logic, providing a single place
 * where the plugin instance (and therefore {@link PluginContext#getServerVersion()})
 * is wired into the rendering pipeline.
 * <p>
 * Implements {@link PluginService} so the renderer participates in the
 * uniform plugin lifecycle. {@link #enable()} resolves the legacy flag from
 * the server version (deferred from the constructor because the service is
 * instantiated inside {@link PluginContext}'s constructor, before the plugin
 * has published its context reference). {@link #shutdown()} tears down the
 * dedicated {@link CpuRenderer} thread pool to release worker threads; a
 * subsequent {@link #enable()} call (or simply the next {@link #render}
 * invocation) transparently rebuilds the pool, so the service survives hot
 * reloads without leaving the plugin in a broken state.
 */
@RequiredArgsConstructor
public class RenderService implements PluginService {

    /** The plugin instance, used to reach {@link PluginContext#getServerVersion()} during {@link #enable()}. */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Whether the server is running a pre-1.13 (pre-flattening) version.
     * Resolved in {@link #enable()} from {@link PluginContext#getServerVersion()}
     * and passed into every {@link #capture(ChunkScene.RenderRegion)} call,
     * so that {@link ChunkScene} does not need to look up the server version itself.
     * <p>
     * Non-final because resolution is deferred to {@link #enable()} — the
     * service is created inside {@link PluginContext}'s constructor, at which
     * point the plugin has not yet exposed its context reference.
     */
    private boolean legacy;

    /**
     * Resolves the server-version-dependent legacy flag. Deferred from the
     * constructor because services are created inside {@link PluginContext}'s
     * constructor, before the plugin publishes its context; by the time
     * {@link PluginContext#enable()} runs, {@link BuildBattleAI#getContext()}
     * returns a fully-initialised context and {@link PluginContext#getServerVersion()}
     * is safe to call.
     */
    @Override
    public void enable() {
        this.legacy = !plugin.getContext().getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_13);
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
     * @return byte array of size 224*224*3 containing RGB pixel data in row-major HWC order
     */
    public byte[] render(@NonNull SceneData scene,
                         double camX, double camY, double camZ,
                         float yaw, float pitch) {
        return CpuRenderer.render(scene, camX, camY, camZ, yaw, pitch);
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
     * Shuts down the renderer's dedicated thread pool to release worker threads.
     * <p>
     * Unlike a permanent disposal, this shutdown is fully recoverable:
     * {@link CpuRenderer#shutdown()} nulls the pool reference, and the next
     * {@link #render} call (or a subsequent {@link #enable()}) lazily rebuilds
     * it. That is what lets the plugin go through a clean {@code shutdown} +
     * {@code enable} cycle without leaving the renderer in an unusable state.
     */
    @Override
    public void shutdown() {
        CpuRenderer.shutdown();
    }
}

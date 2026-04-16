package ru.ashesha.buildBattleAI.core;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.NonNull;
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
 * where the plugin instance (and therefore {@link BuildBattleAI#getServerVersion()})
 * is wired into the rendering pipeline.
 */
public class RenderService {

    /** The plugin instance, retained for future expansion (e.g. scheduler access). */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Whether the server is running a pre-1.13 (pre-flattening) version.
     * Resolved once at construction from {@link BuildBattleAI#getServerVersion()}
     * and passed into every {@link #capture(ChunkScene.RenderRegion)} call,
     * so that {@link ChunkScene} does not need to look up the server version itself.
     */
    private final boolean legacy;

    /**
     * Creates the render service and resolves the legacy flag from the server version.
     *
     * @param plugin the plugin instance
     */
    public RenderService(@NonNull BuildBattleAI plugin) {
        this.plugin = plugin;
        this.legacy = !plugin.getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_13);
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
     * Shuts down the renderer's thread pool. Called during plugin disable.
     * After shutdown, subsequent {@link #render} calls will throw
     * {@link java.util.concurrent.RejectedExecutionException}.
     */
    public void shutdown() {
        CpuRenderer.shutdown();
    }
}

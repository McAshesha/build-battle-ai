package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.CommandService.PluginCommand;
import ru.ashesha.buildBattleAI.ml.MLService;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.render.RendererUtils;
import ru.ashesha.buildBattleAI.render.data.ChunkScene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Test command for the ML classification pipeline.
 * <p>
 * Usage: {@code /testml} — captures a scene around the player, renders it from the
 * player's viewpoint, sends the image to the ML microservice, and prints all available
 * information from every endpoint (health, centroids, predict) to the player's chat.
 * <p>
 * The scene capture (chunk snapshots) runs on the main thread as required by Bukkit.
 * Rendering and all ML HTTP calls run asynchronously to avoid blocking the server.
 */
public class TestMLCommand extends PluginCommand {

    /**
     * Horizontal radius (in blocks) of the capture region around the player.
     * 50 blocks covers a generous area for typical build battle plots.
     */
    private static final int CAPTURE_RADIUS = 50;

    /** Blocks below the player to include in the capture region. */
    private static final int CAPTURE_DEPTH = 20;

    /** Blocks above the player to include in the capture region. */
    private static final int CAPTURE_HEIGHT = 40;

    /** Number of top predictions to request from the ML service. */
    private static final int TOP_K = 10;

    /** Timestamp format for saved render file names. */
    private static final SimpleDateFormat FILE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS");

    /**
     * Creates the test ML command.
     *
     * @param plugin the plugin instance
     */
    public TestMLCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "testml", "Test ML classification pipeline (render + predict)", "");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return;
        }
        Player player = (Player) sender;
        BBAIMLService mlService = plugin.getContext().getMlService();
        RenderService renderService = plugin.getContext().getRenderService();
        Location loc = player.getLocation();

        player.sendMessage("§7Capturing scene...");

        // Build the capture region centered on the player.
        // Y is clamped to valid world bounds (0–255).
        int minX = loc.getBlockX() - CAPTURE_RADIUS;
        int maxX = loc.getBlockX() + CAPTURE_RADIUS;
        int minY = Math.max(0, loc.getBlockY() - CAPTURE_DEPTH);
        int maxY = Math.min(255, loc.getBlockY() + CAPTURE_HEIGHT);
        int minZ = loc.getBlockZ() - CAPTURE_RADIUS;
        int maxZ = loc.getBlockZ() + CAPTURE_RADIUS;

        // Capture chunk snapshots on the main thread (Bukkit requirement)
        ChunkScene.RenderRegion region = new ChunkScene.RenderRegion.Cuboid(
                minX, minY, minZ, maxX, maxY, maxZ, loc.getWorld()
        );
        ChunkScene scene = renderService.capture(region);

        // Camera at eye level: player Y + 1.62 (standing eye height)
        final double camX = loc.getX();
        final double camY = loc.getY() + 1.62;
        final double camZ = loc.getZ();
        final float yaw = loc.getYaw();
        final float pitch = loc.getPitch();

        // All remaining work (render + ML REST calls) runs asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runPipeline(player, mlService, renderService, scene, camX, camY, camZ, yaw, pitch);
            } catch (Exception e) {
                player.sendMessage("§cML test failed: §f" + e.getMessage());
                plugin.getLogger().warning("TestML failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Executes the full render → classify pipeline and prints results to chat.
     * Must be called from an async thread.
     */
    private void runPipeline(Player player, BBAIMLService mlService, RenderService renderService,
                             ChunkScene scene,
                             double camX, double camY, double camZ, float yaw, float pitch) {
        // ── 1. Render ──────────────────────────────────────────────────────
        player.sendMessage("§7Rendering image...");
        long renderStart = System.currentTimeMillis();
        byte[] pixels = renderService.render(scene, camX, camY, camZ, yaw, pitch);
        long renderMs = System.currentTimeMillis() - renderStart;
        player.sendMessage("§aRender complete §7(" + renderMs + "ms)");

        // Save the rendered image to the plugin data folder with a timestamp filename
        String savedPath = saveRender(pixels);
        if (savedPath != null)
            player.sendMessage("§7Saved: §f" + savedPath);
        else
            player.sendMessage("§cFailed to save render to disk");

        // ── 2. Health endpoint ─────────────────────────────────────────────
        player.sendMessage("");
        player.sendMessage("§6§l══════ Health ══════");
        long healthStart = System.currentTimeMillis();
        MLService.HealthInfo health = mlService.health();
        long healthMs = System.currentTimeMillis() - healthStart;

        player.sendMessage("§7Response time: §e" + healthMs + "ms");
        player.sendMessage("§7Status: §a" + health.status());
        player.sendMessage("§7Model: §e" + health.modelName());
        player.sendMessage("§7Device: §e" + health.device());
        player.sendMessage("§7Num classes: §e" + health.numClasses());
        player.sendMessage("§7Embedding dim: §e" + health.embeddingDim());
        player.sendMessage("§7Input size: §e" + health.inputSize() + "×" + health.inputSize());
        player.sendMessage("§7Classes: §f" + joinStrings(health.classes()));

        // ── 3. Centroids endpoint ──────────────────────────────────────────
        player.sendMessage("");
        player.sendMessage("§6§l══════ Centroids ══════");
        long centroidsStart = System.currentTimeMillis();
        Map<String, float[]> centroids = mlService.centroids();
        long centroidsMs = System.currentTimeMillis() - centroidsStart;

        player.sendMessage("§7Response time: §e" + centroidsMs + "ms");
        player.sendMessage("§7Total classes: §e" + centroids.size());
        for (Map.Entry<String, float[]> entry : centroids.entrySet())
            player.sendMessage("§e" + entry.getKey() + " §7" + formatVector(entry.getValue()));

        // ── 4. Predict endpoint ────────────────────────────────────────────
        player.sendMessage("");
        player.sendMessage("§6§l══════ Prediction ══════");
        long predictStart = System.currentTimeMillis();
        MLService.PredictionResult result = mlService.predict(
                pixels, RendererUtils.WIDTH, RendererUtils.HEIGHT, TOP_K
        );
        long predictMs = System.currentTimeMillis() - predictStart;

        player.sendMessage("§7Response time: §e" + predictMs + "ms");
        player.sendMessage("§7Model: §e" + result.modelName());
        player.sendMessage("");
        player.sendMessage("§a§lPredicted: §e§l" + result.predictedClass()
                + " §7(score: §f" + formatFloat(result.predictedScore()) + "§7)");

        // Top-K ranking
        player.sendMessage("");
        player.sendMessage("§6── Top-" + result.topK().size() + " ──");
        for (int i = 0; i < result.topK().size(); i++) {
            MLService.TopKEntry entry = result.topK().get(i);
            String bar = buildScoreBar(entry.score());
            player.sendMessage("§7 " + (i + 1) + ". §e" + entry.className()
                    + " §7" + bar + " §f" + formatFloat(entry.score()));
        }

        // Embedding vector (first 8 components)
        player.sendMessage("");
        player.sendMessage("§6── Embedding ──");
        player.sendMessage("§7Dimension: §e" + result.embedding().length);
        player.sendMessage("§7Values: §f" + formatVector(result.embedding()));

        // Predicted centroid
        player.sendMessage("");
        player.sendMessage("§6── Predicted centroid ──");
        player.sendMessage("§7" + formatVector(result.predictedCentroid()));

        // Available classes
        player.sendMessage("");
        player.sendMessage("§6── Available classes ──");
        player.sendMessage("§7" + joinStrings(result.availableClasses()));

        // Summary
        player.sendMessage("");
        player.sendMessage("§6§l═══════════════════════");
        player.sendMessage("§aPipeline complete! §7Render: " + renderMs + "ms"
                + " | Health: " + healthMs + "ms"
                + " | Centroids: " + centroidsMs + "ms"
                + " | Predict: " + predictMs + "ms");
    }

    // ── image saving ─────────────────────────────────────────────────────────

    /**
     * Saves raw RGB pixel data as a PNG file in the plugin's {@code renders/} subdirectory.
     * The file is named with a timestamp (e.g. {@code 2026-04-14_01-23-45-678.png}).
     *
     * @param rgbPixels row-major RGB byte array from CpuRenderer
     * @return the saved file path relative to the server root, or {@code null} on failure
     */
    private String saveRender(byte[] rgbPixels) {
        try {
            File dir = new File(plugin.getDataFolder(), "renders");
            if (!dir.exists()) dir.mkdirs();

            String timestamp = FILE_DATE_FORMAT.format(new Date());
            File file = new File(dir, timestamp + ".png");

            BufferedImage image = new BufferedImage(
                    RendererUtils.WIDTH, RendererUtils.HEIGHT, BufferedImage.TYPE_INT_RGB
            );
            for (int y = 0; y < RendererUtils.HEIGHT; y++)
                for (int x = 0; x < RendererUtils.WIDTH; x++) {
                    int idx = (y * RendererUtils.WIDTH + x) * 3;
                    int r = rgbPixels[idx] & 0xFF;
                    int g = rgbPixels[idx + 1] & 0xFF;
                    int b = rgbPixels[idx + 2] & 0xFF;
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            ImageIO.write(image, "PNG", file);
            return file.getPath();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save render: " + e.getMessage());
            return null;
        }
    }

    // ── formatting helpers ──────────────────────────────────────────────────

    /**
     * Formats a float to 4 decimal places.
     */
    private static String formatFloat(float v) {
        return String.format("%.4f", v);
    }

    /**
     * Formats a float array as a truncated vector string, showing the first 8 components.
     * Example: {@code [0.1234, -0.5678, 0.9012, ... (128d)]}
     */
    private static String formatVector(float[] vec) {
        int show = Math.min(8, vec.length);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatFloat(vec[i]));
        }
        if (vec.length > show) sb.append(", ...");
        sb.append("] (").append(vec.length).append("d)");
        return sb.toString();
    }

    /**
     * Joins a string list with commas.
     */
    private static String joinStrings(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    /**
     * Builds a visual score bar for chat display.
     * Score 0.0–1.0 is mapped to a 10-character bar using green/gray block characters.
     */
    private static String buildScoreBar(float score) {
        int filled = Math.round(score * 10);
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            if (i == filled) sb.append("§8");
            sb.append("|");
        }
        return sb.toString();
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}

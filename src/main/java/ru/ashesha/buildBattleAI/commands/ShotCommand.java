package ru.ashesha.buildBattleAI.commands;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.commands.base.PluginCommand;
import ru.ashesha.buildBattleAI.render.CpuRenderer;
import ru.ashesha.buildBattleAI.render.data.ChunkScene;
import ru.ashesha.buildBattleAI.render.data.ChunkScene.RenderRegion;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Debug command ({@code /shot <fileName>}) that captures the player's current view
 * as a 224x224 PNG image using the CPU voxel renderer.
 * <p>
 * Workflow:
 * <ol>
 *     <li>Captures a {@link ChunkScene} snapshot on the main thread (required for thread safety)</li>
 *     <li>Renders the scene asynchronously using {@link CpuRenderer}</li>
 *     <li>Saves the result as a PNG file in the plugin's {@code renders/} directory</li>
 * </ol>
 * The render region is a temporary cube centered on the player's position and will be
 * replaced by the arena/plot region once game logic is implemented.
 */
public class ShotCommand extends PluginCommand {

    /** Half-size of the cubic capture region around the camera, in blocks. */
    private static final int REGION_RADIUS = 64;

    public ShotCommand(BuildBattleAI plugin) {
        super(plugin, "shot");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by a player.");
            return;
        }
        Player player = (Player) sender;

        if (args.length < 1) {
            sender.sendMessage("§cUsage: /shot <fileName>");
            return;
        }

        // Validate file name to prevent path traversal and special characters
        String fileName = args[0];
        if (!fileName.matches("[a-zA-Z0-9_\\-]+")) {
            sender.sendMessage("§cFile name must contain only letters, digits, underscores, and hyphens.");
            return;
        }

        // Extract camera position and orientation from the player's current view
        Location loc = player.getLocation();
        double camX = loc.getX();
        double camY = loc.getY() + player.getEyeHeight();
        double camZ = loc.getZ();
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();

        // Build a temporary render region centered on the camera, clamped to world height limits.
        // This will be replaced by the arena/plot region once game logic is implemented.
        int cx = (int) Math.floor(camX);
        int cy = (int) Math.floor(camY);
        int cz = (int) Math.floor(camZ);
        RenderRegion region = new RenderRegion.Cuboid(
                cx - REGION_RADIUS,
                Math.max(-64, cy - REGION_RADIUS),
                cz - REGION_RADIUS,
                cx + REGION_RADIUS,
                Math.min(319, cy + REGION_RADIUS),
                cz + REGION_RADIUS,
                player.getWorld()
        );

        // Capture scene data on the main thread — ChunkSnapshot creation must happen here,
        // but the resulting ChunkScene is thread-safe for async rendering.
        long captureStart = System.currentTimeMillis();
        ChunkScene scene = ChunkScene.capture(region);
        long captureMs = System.currentTimeMillis() - captureStart;

        player.sendMessage("§eScene captured (" + captureMs + "ms). Rendering...");

        // Offload the CPU-intensive ray casting and PNG encoding to an async thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            long renderStart = System.currentTimeMillis();
            byte[] rgb = CpuRenderer.render(scene, camX, camY, camZ, yaw, pitch);
            long renderMs = System.currentTimeMillis() - renderStart;

            BufferedImage image = CpuRenderer.toBufferedImage(rgb);
            File renderDir = new File(plugin.getDataFolder(), "renders");
            renderDir.mkdirs();
            File outputFile = new File(renderDir, fileName + ".png");

            try {
                ImageIO.write(image, "png", outputFile);
                // Report success back on the main thread so the player message is safe
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage("§aRender done (" + renderMs + "ms). Saved: " + outputFile.getPath())
                );
            } catch (Throwable e) {
                plugin.getLogger().warning("Failed to save render: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage("§cFailed to save image: " + e.getMessage())
                );
            }
        });
    }

    @Override
    public List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1) return Collections.singletonList("screenshot");
        return Collections.emptyList();
    }
}

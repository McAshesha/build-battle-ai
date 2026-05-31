package ru.ashesha.buildBattleAI.commands;

import com.cryptomorin.xseries.XMaterial;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.listeners.MLTestListener;
import ru.ashesha.buildBattleAI.ml.api.BBAIMLService;
import ru.ashesha.buildBattleAI.ml.api.PredictionResult;
import ru.ashesha.buildBattleAI.ml.api.TopKEntry;
import ru.ashesha.buildBattleAI.render.RenderService;
import ru.ashesha.buildBattleAI.render.data.ChunkScene;
import ru.ashesha.buildBattleAI.util.SoundPalette;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic command {@code /bbaitest} that gives the player a selection
 * wand, captures a build region marked with that wand, renders it from the
 * player's current viewpoint, and reports the full ML pipeline diagnostics
 * back via chat.
 * <p>
 * The flow is intentionally minimal and exists only for manual end-to-end
 * verification of the renderer + ML stack — it is not part of the regular
 * Build Battle game loop. Selection state is maintained inside
 * {@link MLTestListener}; both pieces share the same display-name marker so
 * the wand survives across sessions and reloads.
 * <p>
 * Subcommands:
 * <ul>
 *     <li>(no args) — gives the player a wand (legacy {@code WOOD_AXE} on
 *         1.8, modern {@code WOODEN_AXE} on 1.13+; resolved via XSeries).</li>
 *     <li>{@code run} — triggered by a clickable chat message after both
 *         corners are selected; captures the region, renders it, feeds the
 *         image to the ML service, and prints the result.</li>
 *     <li>{@code run -tta} — same flow but uses the TTA-fused predictor
 *         ({@code predictWithTTA}) so the user can compare speed and
 *         accuracy against the single-view baseline on real builds.</li>
 * </ul>
 */
public class MLTestCommand extends CommandService.PluginCommand {

    /**
     * Display name attached to the selector item so the listener can
     * recognize a wand handed out by us, even after a reload.
     */
    public static final String WAND_NAME = "§6BBAI ML Test Selector";

    /**
     * Lore line burned into the item — second redundancy in case another
     * plugin renames the item; lets us still identify it.
     */
    public static final String WAND_LORE = "§7Left-click: corner 1  §r| §7Right-click: corner 2";

    /**
     * Creates the test command.
     *
     * @param plugin the plugin instance
     */
    public MLTestCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "bbaitest", "BuildBattleAI ML diagnostic test command",
                "[run [-tta]]");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            giveWand(player);
            return;
        }

        if ("run".equalsIgnoreCase(args[0])) {
            // Parse the optional -tta flag. Anything else after "run" is
            // rejected so typos like "/bbaitest run tta" don't silently turn
            // TTA off.
            boolean tta = false;
            for (int i = 1; i < args.length; i++) {
                if ("-tta".equalsIgnoreCase(args[i])) {
                    tta = true;
                } else {
                    plugin.getContext().getMessageService().sendChat(player,
                            "&cUnknown flag '" + args[i] + "'. Usage: /bbaitest run [-tta]");
                    return;
                }
            }
            runMlTest(player, tta);
            return;
        }

        plugin.getContext().getMessageService().sendChat(player,
                "&cUsage: /bbaitest  (or /bbaitest run [-tta] after selecting two corners)");
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        // "run" is meant to be triggered via clickable chat — hide from tab
        // completion to keep the surface area small.
        return Collections.emptyList();
    }

    // ── /bbaitest — issue the wand ─────────────────────────────────────────

    /**
     * Gives the player a wooden axe stamped with our display name + lore so
     * {@link MLTestListener} can recognize it on interaction. Refuses to
     * issue a duplicate if the player already carries one.
     */
    private void giveWand(Player player) {
        if (hasWand(player)) {
            plugin.getContext().getMessageService().sendChat(player,
                    "&eYou already have the test wand in your inventory.");
            return;
        }

        ItemStack wand = XMaterial.WOODEN_AXE.parseItem();
        if (wand == null) {
            // Defensive: should never happen — WOODEN_AXE exists on every
            // supported server version.
            plugin.getContext().getMessageService().sendChat(player,
                    "&cFailed to create the test wand on this server version.");
            return;
        }
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(WAND_NAME);
            meta.setLore(Arrays.asList(WAND_LORE));
            wand.setItemMeta(meta);
        }
        player.getInventory().addItem(wand);
        SoundPalette.WELCOME.play(player);

        plugin.getContext().getMessageService().sendChat(player,
                "&aTest wand granted. &7Left-click a block for corner 1, right-click for corner 2.");
    }

    /**
     * Checks whether the player already carries an ML test wand. Cheaper than
     * scanning every slot when the wand is missing — this returns on first
     * match.
     */
    private boolean hasWand(Player player) {
        for (ItemStack item : player.getInventory().getContents())
            if (MLTestListener.isWand(item))
                return true;
        return false;
    }

    // ── /bbaitest run — execute the pipeline ───────────────────────────────

    /**
     * Kicks off the capture → render → ML pipeline for this player.
     * <p>
     * Capture runs on the main thread because Bukkit world reads must be
     * synchronous; everything past capture (render + ML inference + chat
     * reporting) is dispatched to an async task so the main tick is not
     * blocked while the model crunches numbers.
     *
     * @param player the player whose viewpoint and selection drive the test
     * @param useTta {@code true} to route through {@code predictWithTTA}
     *               instead of the single-view {@code predictRgb} — exposes
     *               the speed/accuracy trade-off of TTA on real builds
     */
    private void runMlTest(Player player, boolean useTta) {
        MLTestListener.Selection selection = MLTestListener.getSelection(player.getUniqueId());
        if (selection == null || !selection.isComplete()) {
            plugin.getContext().getMessageService().sendChat(player,
                    "&cBoth corners must be selected first. Run /bbaitest to get the wand.");
            SoundPalette.DENY.play(player);
            return;
        }

        // Capture the player's viewpoint right now — the player might walk
        // away before the async task runs, and we want the camera anchored
        // to the moment they clicked the chat button.
        Location loc = player.getEyeLocation();
        double camX = loc.getX();
        double camY = loc.getY();
        double camZ = loc.getZ();
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();

        World world = selection.world();
        if (world == null) {
            plugin.getContext().getMessageService().sendChat(player,
                    "&cSelection world is no longer loaded.");
            return;
        }

        plugin.getContext().getMessageService().sendChat(player,
                "&7Capturing region " + describeCorner(selection.minX(), selection.minY(), selection.minZ())
                        + " &7to " + describeCorner(selection.maxX(), selection.maxY(), selection.maxZ())
                        + "&7...");

        // 1) Synchronous capture on the main thread — touches Bukkit.
        long captureStart = System.nanoTime();
        ChunkScene scene;
        try {
            ChunkScene.RenderRegion region = new ChunkScene.RenderRegion.Cuboid(
                    selection.minX(), selection.minY(), selection.minZ(),
                    selection.maxX(), selection.maxY(), selection.maxZ(),
                    world);
            scene = plugin.getContext().getRenderService().capture(region);
        } catch (Throwable t) {
            plugin.getContext().getMessageService().sendChat(player,
                    "&cCapture failed: " + safeMessage(t));
            return;
        }
        long captureMs = (System.nanoTime() - captureStart) / 1_000_000L;

        // 2) Async — render + ML. We hand off only thread-safe state.
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                new RenderAndPredictTask(player, scene, camX, camY, camZ, yaw, pitch, captureMs, useTta));
    }

    // ── async worker ───────────────────────────────────────────────────────

    /**
     * Background job that does the heavy lifting: renderer ray-casts the
     * captured scene into a 224×224 RGB buffer, then the ML service embeds
     * the image and matches it against the centroid table. All result
     * reporting routes back through the synchronous chat service — Bukkit
     * APIs that touch player state aren't safe off-thread, but
     * {@code MessageService} is packet-based and tolerates async sends.
     */
    private class RenderAndPredictTask implements Runnable {

        private final Player player;
        private final ChunkScene scene;
        private final double camX, camY, camZ;
        private final float yaw, pitch;
        private final long captureMs;
        private final boolean useTta;

        RenderAndPredictTask(Player player, ChunkScene scene,
                             double camX, double camY, double camZ,
                             float yaw, float pitch, long captureMs, boolean useTta) {
            this.player = player;
            this.scene = scene;
            this.camX = camX;
            this.camY = camY;
            this.camZ = camZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.captureMs = captureMs;
            this.useTta = useTta;
        }

        @Override
        public void run() {
            try {
                runUnsafe();
            } catch (Throwable t) {
                plugin.getContext().getMessageService().sendChat(player,
                        "&cML test failed: " + safeMessage(t));
                plugin.getPluginLogger().error("ML test pipeline failed: %s", t.toString());
            }
        }

        private void runUnsafe() {
            RenderService renderService = plugin.getContext().getRenderService();
            BBAIMLService mlService = plugin.getContext().getMlService();

            // Render
            long renderStart = System.nanoTime();
            byte[] rgb = renderService.render(scene, camX, camY, camZ, yaw, pitch);
            long renderMs = (System.nanoTime() - renderStart) / 1_000_000L;

            // Snapshot the rendered frame to disk so the player can inspect
            // what the ML pipeline actually sees. Runs on the async thread —
            // safe to do I/O. Failures are non-fatal: the test still proceeds.
            File savedRender = saveRenderToFile(rgb);

            // Single ML call — predict* already runs the embed step
            // internally and exposes the embedding through PredictionResult,
            // so calling embed* separately would double the inference cost
            // (cold-batched recompile on CoreML + full TTA preprocessing)
            // for no extra information.
            long mlStart = System.nanoTime();
            PredictionResult prediction = useTta
                    ? mlService.predictWithTTA(rgb, 224, 224, 5)
                    : mlService.predictRgb(rgb, 224, 224, 5);
            long mlMs = (System.nanoTime() - mlStart) / 1_000_000L;
            float[] embedding = prediction.embedding();

            reportResults(mlService, embedding, prediction, renderMs, mlMs, savedRender);
        }

        private void reportResults(BBAIMLService mlService,
                                   float[] embedding,
                                   PredictionResult prediction,
                                   long renderMs,
                                   long mlMs,
                                   File savedRender) {
            // Layout the report. Each line is sent independently so the player
            // sees output streaming as we build it.
            plugin.getContext().getMessageService().sendChat(player,
                    "&8&m-----&r &6&lML Test Report &8&m-----");
            String modeLabel = useTta
                    ? "&dTTA &7(x" + mlService.ttaViews() + " views, fused)"
                    : "&bsingle &7(no TTA)";
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Mode: " + modeLabel);
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Backend: &f" + mlService.backend());
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Embedding dim: &f" + mlService.embeddingDim()
                            + " &8(showing first 4) &f" + formatFirst(embedding, 4));
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Capture: &a" + captureMs + "ms&7  Render: &a" + renderMs
                            + "ms&7  ML: &a" + mlMs + "ms &8(embed + classify)");
            if (savedRender != null)
                plugin.getContext().getMessageService().sendChat(player,
                        "&7Render saved to &f" + savedRender.getPath());
            else
                plugin.getContext().getMessageService().sendChat(player,
                        "&7Render snapshot: &cfailed to save &8(check console)");

            // Centroid summary — names + scalar magnitude. Magnitudes should
            // all be ~1.0 (L2-normalized) but printing them makes accidental
            // regressions obvious.
            StringBuilder centroids = new StringBuilder("&7Classes &f(")
                    .append(mlService.classNames().size())
                    .append("): &f");
            boolean first = true;
            for (String name : mlService.classNames()) {
                if (!first)
                    centroids.append("&7, &f");
                centroids.append(name);
                first = false;
            }
            plugin.getContext().getMessageService().sendChat(player, centroids.toString());

            plugin.getContext().getMessageService().sendChat(player,
                    "&7Centroid L2 norms: &f" + formatCentroidNorms(mlService.centroids()));

            // Top-K ranking.
            plugin.getContext().getMessageService().sendChat(player,
                    "&7Top-" + prediction.topK().size() + " predictions:");
            int rank = 1;
            for (TopKEntry entry : prediction.topK()) {
                String marker = rank == 1 ? "&e&l★ " : "&8" + rank + ". ";
                plugin.getContext().getMessageService().sendChat(player,
                        "  " + marker + "&f" + entry.className()
                                + " &7score=&a" + String.format("%.4f", entry.score()));
                rank++;
            }
            plugin.getContext().getMessageService().sendChat(player,
                    "&8&m--------------------------------");
            SoundPalette.SCORE.play(player);
        }

        /**
         * Writes the raw 224×224 RGB frame to a timestamped PNG under
         * {@code <plugin-data>/renders/}, returning the resulting file (or
         * {@code null} on I/O failure). Used for visual debugging of the
         * voxel renderer + ML pipeline: lets the operator open the exact
         * image that was fed into the classifier.
         */
        private File saveRenderToFile(byte[] rgb) {
            try {
                BufferedImage image = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 224; y++) {
                    for (int x = 0; x < 224; x++) {
                        int i = (y * 224 + x) * 3;
                        int r = rgb[i] & 0xFF;
                        int g = rgb[i + 1] & 0xFF;
                        int b = rgb[i + 2] & 0xFF;
                        image.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
                File dir = new File(plugin.getDataFolder(), "renders");
                if (!dir.exists() && !dir.mkdirs())
                    plugin.getPluginLogger().warn("Could not create renders directory at %s",
                            dir.getAbsolutePath());
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
                String suffix = useTta ? "-tta" : "";
                File out = new File(dir, "render-" + player.getName() + "-" + stamp + suffix + ".png");
                ImageIO.write(image, "PNG", out);
                return out;
            } catch (IOException e) {
                plugin.getPluginLogger().warn("Failed to save render snapshot: %s", e.getMessage());
                return null;
            }
        }
    }

    // ── formatting helpers ─────────────────────────────────────────────────

    /**
     * Returns the first {@code count} components of an embedding formatted
     * as a fixed-precision array, e.g. {@code [0.0123, -0.0456, 0.0789, …]}.
     * Defensive: gracefully degrades if the embedding is shorter than asked.
     */
    private static String formatFirst(float[] v, int count) {
        if (v == null)
            return "[]";
        int n = Math.min(count, v.length);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(String.format("%.4f", v[i]));
        }
        if (v.length > n)
            sb.append(", …");
        sb.append("]");
        return sb.toString();
    }

    /**
     * Returns a compact "name=norm" summary of every centroid in the map,
     * truncated to 4 decimal places. Useful for at-a-glance verification
     * that every centroid is unit-length.
     */
    private static String formatCentroidNorms(Map<String, float[]> centroids) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, float[]> e : centroids.entrySet()) {
            if (!first)
                sb.append("&7, &f");
            double sum = 0;
            for (float x : e.getValue())
                sum += (double) x * x;
            sb.append(e.getKey())
                    .append("=")
                    .append(String.format("%.3f", Math.sqrt(sum)));
            first = false;
        }
        return sb.toString();
    }

    /**
     * Formats a block coordinate triple as {@code (x,y,z)} for inline logging.
     */
    private static String describeCorner(int x, int y, int z) {
        return "&f(" + x + ", " + y + ", " + z + ")";
    }

    /**
     * Returns a non-null short message for a throwable — falls back to the
     * exception class name when {@link Throwable#getMessage()} is {@code null}.
     */
    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }
}

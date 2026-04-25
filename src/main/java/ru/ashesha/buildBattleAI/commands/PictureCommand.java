package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.entity.picture.PictureService;
import ru.ashesha.buildBattleAI.entity.picture.api.BBAIPictureService;
import ru.ashesha.buildBattleAI.util.RendererUtils;
import ru.ashesha.buildBattleAI.render.data.ChunkScene;
import ru.ashesha.buildBattleAI.render.data.SceneData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test command for the {@link BBAIPictureService}.
 * <p>
 * Allows players to create, update, and remove packet-based pictures from image
 * files in the plugin's data folder. Each player can have one active picture at
 * a time, managed through a per-player state map.
 * <p>
 * Usage:
 * <ul>
 *     <li>{@code /picture create [file] [width] [height] [face]} — creates and displays
 *         a picture. If no file is given, renders the scene from the player's viewpoint
 *         via the voxel renderer. Width/height default to 3, face defaults to the
 *         player's facing direction.</li>
 *     <li>{@code /picture update [file]} — updates the current picture with a new image,
 *         or re-renders from the player's viewpoint if no file is given</li>
 *     <li>{@code /picture remove} — removes the current picture</li>
 * </ul>
 */
public class PictureCommand extends CommandService.PluginCommand {

    /** Subcommand names for tab completion. */
    private static final List<String> SUBCOMMANDS = Arrays.asList("create", "update", "remove");

    /** Supported facing directions for tab completion. */
    private static final List<String> FACES = Arrays.asList("north", "south", "east", "west");

    /**
     * Per-player state tracking for active pictures. Maps player name to their
     * current picture handle. Only one picture per player at a time — creating
     * a new one automatically despawns the old one.
     */
    private final Map<String, PictureService.Picture> activePictures = new HashMap<>();

    /**
     * Creates the picture test command.
     *
     * @param plugin the plugin instance
     */
    public PictureCommand(@NonNull BuildBattleAI plugin) {
        super(plugin, "picture", "Manage packet-based pictures",
                "<create|update|remove> [args...]");
    }

    @Override
    protected void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("Usage: /picture <create|update|remove> [args...]");
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create":
                handleCreate(player, args);
                break;
            case "update":
                handleUpdate(player, args);
                break;
            case "remove":
                handleRemove(player);
                break;
            default:
                player.sendMessage("Unknown subcommand: " + sub);
                break;
        }
    }

    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 1)
            return filterStartsWith(SUBCOMMANDS, args[0]);

        String sub = args[0].toLowerCase();

        // File name suggestions for create and update
        if (("create".equals(sub) || "update".equals(sub)) && args.length == 2)
            return filterStartsWith(listImageFiles(), args[1]);

        // Width/height suggestions for create
        if ("create".equals(sub) && (args.length == 3 || args.length == 4))
            return filterStartsWith(Arrays.asList("1", "2", "3", "4", "5"), args[args.length - 1]);

        // Face suggestions for create
        if ("create".equals(sub) && args.length == 5)
            return filterStartsWith(FACES, args[4]);

        return Collections.emptyList();
    }

    /**
     * Handles the {@code create} subcommand: loads an image from the plugin's
     * data folder or renders the scene from the player's viewpoint, then
     * displays it as a picture on the wall in front of the player.
     *
     * @param player the player creating the picture
     * @param args   command arguments: {@code create [file] [width] [height] [face]}
     */
    private void handleCreate(Player player, String[] args) {
        // Detect whether the first argument is a file name or a dimension number.
        // If absent or parseable as int → no file → use renderer.
        boolean hasFile = args.length >= 2 && !isInteger(args[1]);
        int argOffset = hasFile ? 1 : 0;

        // Parse grid dimensions (default 3x3)
        int width = parseIntOrDefault(args, 1 + argOffset, 3);
        int height = parseIntOrDefault(args, 2 + argOffset, 3);

        if (width < 1 || width > 20 || height < 1 || height > 20) {
            player.sendMessage("Dimensions must be between 1 and 20.");
            return;
        }

        // Parse facing direction (default: player's facing direction)
        BlockFace face = parseFace(args, 3 + argOffset, getPlayerFacing(player));
        if (face == null) {
            player.sendMessage("Invalid face. Use: north, south, east, west");
            return;
        }

        // Remove any existing picture for this player
        removeExisting(player);

        // Create the picture handle
        BBAIPictureService pictureService = plugin.getContext().getPictureService();
        PictureService.Picture picture = pictureService.createPicture(width, height);

        // Place 2 blocks in front of the player at eye level
        int anchorX = player.getLocation().getBlockX() + face.getModX() * 2;
        int anchorY = player.getLocation().getBlockY();
        int anchorZ = player.getLocation().getBlockZ() + face.getModZ() * 2;

        if (hasFile) {
            // File path provided — load image from plugin data folder
            BufferedImage image = loadImage(player, args[1]);
            if (image == null)
                return;

            pictureService.spawn(player, picture, anchorX, anchorY, anchorZ, face, image);
        } else {
            // No file — render the scene from the player's perspective
            byte[] hwcPixels = renderFromPlayer(player);
            if (hwcPixels == null)
                return;

            pictureService.spawn(player, picture, anchorX, anchorY, anchorZ, face,
                    hwcPixels, RendererUtils.WIDTH, RendererUtils.HEIGHT);
        }

        activePictures.put(player.getName(), picture);
        String source = hasFile ? args[1] : "renderer";
        player.sendMessage("Picture created (" + width + "x" + height + ") facing "
                + face.name().toLowerCase() + " from " + source + ".");
    }

    /**
     * Handles the {@code update} subcommand: replaces the image on the player's
     * current picture without respawning the frames. If no file is given,
     * re-renders from the player's current viewpoint.
     *
     * @param player the player updating the picture
     * @param args   command arguments: {@code update [file]}
     */
    private void handleUpdate(Player player, String[] args) {
        PictureService.Picture picture = activePictures.get(player.getName());
        if (picture == null) {
            player.sendMessage("No active picture. Use /picture create first.");
            return;
        }

        BBAIPictureService pictureService = plugin.getContext().getPictureService();

        if (args.length >= 2) {
            // File name provided
            BufferedImage image = loadImage(player, args[1]);
            if (image == null)
                return;

            pictureService.update(player, picture, image);
            player.sendMessage("Picture updated from " + args[1] + ".");
        } else {
            // No file — re-render from player's viewpoint
            byte[] hwcPixels = renderFromPlayer(player);
            if (hwcPixels == null)
                return;

            pictureService.update(player, picture, hwcPixels,
                    RendererUtils.WIDTH, RendererUtils.HEIGHT);
            player.sendMessage("Picture updated from renderer.");
        }
    }

    /**
     * Handles the {@code remove} subcommand: despawns the player's current picture.
     *
     * @param player the player removing the picture
     */
    private void handleRemove(Player player) {
        if (!removeExisting(player))
            player.sendMessage("No active picture to remove.");
        else
            player.sendMessage("Picture removed.");
    }

    /**
     * Removes and despawns the player's active picture if one exists.
     *
     * @param player the player
     * @return {@code true} if a picture was removed, {@code false} if none existed
     */
    private boolean removeExisting(Player player) {
        PictureService.Picture picture = activePictures.remove(player.getName());
        if (picture == null)
            return false;

        plugin.getContext().getPictureService().despawn(player, picture);
        return true;
    }

    // ── utility methods ────────────────────────────────────────────────────

    /**
     * Captures the world around the player and renders it via the voxel renderer.
     * <p>
     * Capture runs on the main thread (required by Bukkit world access), render
     * is synchronous here for simplicity — the renderer uses its own ForkJoinPool
     * internally. The capture region is a 64-block cube centered on the player.
     *
     * @param player the player whose viewpoint to render from
     * @return HWC byte array of rendered pixels, or {@code null} on failure
     */
    private byte[] renderFromPlayer(Player player) {
        try {
            Location loc = player.getLocation();
            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();
            int radius = 32;

            // Capture must happen on the main thread — we're already here
            ChunkScene.RenderRegion region = new ChunkScene.RenderRegion.Cuboid(
                    bx - radius, Math.max(0, by - radius), bz - radius,
                    bx + radius, Math.min(255, by + radius), bz + radius,
                    player.getWorld()
            );
            SceneData scene = plugin.getContext().getRenderService().capture(region);

            // Render from the player's eye position
            double eyeY = loc.getY() + player.getEyeHeight();
            return plugin.getContext().getRenderService().render(
                    scene, loc.getX(), eyeY, loc.getZ(),
                    loc.getYaw(), loc.getPitch()
            );
        } catch (Exception e) {
            player.sendMessage("Render failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads an image from the plugin's data folder.
     *
     * @param player   the player (for error messages)
     * @param fileName the image file name
     * @return the loaded image, or {@code null} if loading failed
     */
    private BufferedImage loadImage(Player player, String fileName) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists())
            dataFolder.mkdirs();

        File imageFile = new File(dataFolder, fileName);
        if (!imageFile.exists()) {
            player.sendMessage("File not found: " + fileName
                    + " (place images in " + dataFolder.getPath() + ")");
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                player.sendMessage("Could not read image: " + fileName);
                return null;
            }
            return image;
        } catch (Exception e) {
            player.sendMessage("Error loading image: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lists image file names in the plugin's data folder for tab completion.
     */
    private List<String> listImageFiles() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists())
            return Collections.emptyList();

        File[] files = dataFolder.listFiles();
        if (files == null)
            return Collections.emptyList();

        List<String> names = new ArrayList<>();
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".gif") || name.endsWith(".bmp"))
                names.add(file.getName());
        }

        return names;
    }

    /**
     * Determines the cardinal block face closest to the player's current yaw.
     *
     * @param player the player
     * @return the cardinal direction the player is facing
     */
    private static BlockFace getPlayerFacing(Player player) {
        float yaw = player.getLocation().getYaw() % 360;
        if (yaw < 0)
            yaw += 360;

        if (yaw >= 315 || yaw < 45)
            return BlockFace.SOUTH;
        if (yaw < 135)
            return BlockFace.WEST;
        if (yaw < 225)
            return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Parses a facing direction from command arguments, or returns a default.
     *
     * @param args        the command arguments
     * @param index       the argument index to check
     * @param defaultFace the default face if the argument is absent
     * @return the parsed face, or {@code null} if the argument is present but invalid
     */
    private static BlockFace parseFace(String[] args, int index, BlockFace defaultFace) {
        if (args.length <= index)
            return defaultFace;

        switch (args[index].toLowerCase()) {
            case "north":
                return BlockFace.NORTH;
            case "south":
                return BlockFace.SOUTH;
            case "east":
                return BlockFace.EAST;
            case "west":
                return BlockFace.WEST;
            default:
                return null;
        }
    }

    /**
     * Parses an integer from command arguments at the given index,
     * returning a default value if the index is out of bounds or the value is not a number.
     */
    private static int parseIntOrDefault(String[] args, int index, int defaultValue) {
        if (args.length <= index)
            return defaultValue;
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Returns {@code true} if the string is parseable as an integer.
     */
    private static boolean isInteger(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Filters a list of strings to those starting with the given prefix (case-insensitive).
     */
    private static List<String> filterStartsWith(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options)
            if (option.toLowerCase().startsWith(lower))
                result.add(option);
        return result;
    }
}

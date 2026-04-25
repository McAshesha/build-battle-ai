package ru.ashesha.buildBattleAI.entity.picture.api;

import lombok.NonNull;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.entity.picture.PictureService;

import java.awt.image.BufferedImage;
import java.util.Collection;

/**
 * Service for creating and managing client-side packet-based pictures via item frames.
 * <p>
 * Pictures are grids of invisible item frames, each displaying a map tile that together
 * compose a full image. They are purely packet-based — the server never tracks these
 * entities or maps. Each viewer can see the same picture independently; all visibility
 * is managed by the caller.
 * <p>
 * <b>Stateless design:</b> pictures store only their entity IDs and map IDs — no
 * location, no image data. All positional and visual state is the caller's
 * responsibility, consistent with the NPC and hologram service patterns.
 * <p>
 * The image is split into a grid of 128x128 pixel tiles, one per item frame. Each
 * tile is converted to Minecraft's map color palette and sent as a map data packet.
 * <p>
 * Supported facing directions: {@code NORTH}, {@code SOUTH}, {@code EAST}, {@code WEST}.
 * The anchor location is the bottom-left corner of the picture as placed in the world
 * (minimum horizontal coordinate, minimum Y).
 * <p>
 * All packets are sent through PacketEvents, providing fully cross-version picture
 * display (1.8–1.21+) without NMS reflection.
 */
public interface BBAIPictureService {

    /**
     * Creates a new picture handle with the given grid dimensions.
     * <p>
     * The picture is not yet visible to any player — call {@link #spawn} to show it.
     * Each call allocates unique entity IDs and map IDs for every tile in the grid.
     *
     * @param width  number of tile columns (each tile = 128 pixels wide)
     * @param height number of tile rows (each tile = 128 pixels tall)
     * @return a new picture instance ready to be spawned
     * @throws IllegalArgumentException if width or height is less than 1
     */
    @NonNull
    PictureService.Picture createPicture(int width, int height);

    /**
     * Shows the picture to the specified viewer at the given location.
     * <p>
     * Spawns invisible item frames in a grid, sets each frame's displayed item to a
     * filled map, and sends map data packets with the image tiles. The image is
     * automatically scaled and split to fit the picture's grid dimensions.
     *
     * @param viewer   the player who will see the picture
     * @param picture  the picture to display
     * @param anchorX  block X coordinate of the bottom-left corner
     * @param anchorY  block Y coordinate of the bottom row
     * @param anchorZ  block Z coordinate of the bottom-left corner
     * @param face     the direction the picture faces ({@code NORTH}, {@code SOUTH},
     *                 {@code EAST}, or {@code WEST})
     * @param image    the image to display (any size — will be scaled to fit)
     */
    void spawn(@NonNull Player viewer, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face, @NonNull BufferedImage image);

    /**
     * Shows the picture to the specified viewers at the given location.
     *
     * @param viewers  the players who will see the picture
     * @param picture  the picture to display
     * @param anchorX  block X coordinate of the bottom-left corner
     * @param anchorY  block Y coordinate of the bottom row
     * @param anchorZ  block Z coordinate of the bottom-left corner
     * @param face     the direction the picture faces
     * @param image    the image to display
     * @see #spawn(Player, PictureService.Picture, int, int, int, BlockFace, BufferedImage)
     */
    void spawn(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face, @NonNull BufferedImage image);

    /**
     * Removes the picture from the specified viewer's client.
     * Sends entity destroy packets for all item frames in the grid.
     *
     * @param viewer  the player who will no longer see the picture
     * @param picture the picture to hide
     */
    void despawn(@NonNull Player viewer, @NonNull PictureService.Picture picture);

    /**
     * Removes the picture from the specified viewers' clients.
     *
     * @param viewers the players who will no longer see the picture
     * @param picture the picture to hide
     * @see #despawn(Player, PictureService.Picture)
     */
    void despawn(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture);

    /**
     * Updates the image displayed on an already-spawned picture.
     * <p>
     * Sends new map data packets for each tile without respawning the item frames.
     * The new image is scaled and split to match the picture's grid dimensions.
     *
     * @param viewer  the player who will see the updated image
     * @param picture the picture to update
     * @param image   the new image to display
     */
    void update(@NonNull Player viewer, @NonNull PictureService.Picture picture,
                @NonNull BufferedImage image);

    /**
     * Updates the image displayed on an already-spawned picture.
     *
     * @param viewers the players who will see the updated image
     * @param picture the picture to update
     * @param image   the new image to display
     * @see #update(Player, PictureService.Picture, BufferedImage)
     */
    void update(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
                @NonNull BufferedImage image);

    // ── raw pixel overloads (no BufferedImage) ─────────────────────────────

    /**
     * Shows the picture using raw packed RGB pixel data.
     * <p>
     * Accepts an {@code int[]} of packed {@code 0xRRGGBB} values in row-major order
     * instead of a {@link BufferedImage}. This avoids constructing an intermediate
     * AWT image when the caller already holds raw pixels (e.g. from the voxel renderer).
     * <p>
     * The pixel data is resized via pure-Java bilinear interpolation and
     * aspect-ratio-preserving fit, then converted to map palette bytes.
     *
     * @param viewer     the player who will see the picture
     * @param picture    the picture to display
     * @param anchorX    block X coordinate of the bottom-left corner
     * @param anchorY    block Y coordinate of the bottom row
     * @param anchorZ    block Z coordinate of the bottom-left corner
     * @param face       the direction the picture faces
     * @param rgbPixels  packed RGB pixel array, row-major, length ≥ {@code imageWidth × imageHeight}
     * @param imageWidth source image width in pixels
     * @param imageHeight source image height in pixels
     */
    void spawn(@NonNull Player viewer, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face,
               @NonNull int[] rgbPixels, int imageWidth, int imageHeight);

    /**
     * Shows the picture using raw packed RGB pixel data.
     *
     * @see #spawn(Player, PictureService.Picture, int, int, int, BlockFace, int[], int, int)
     */
    void spawn(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face,
               @NonNull int[] rgbPixels, int imageWidth, int imageHeight);

    /**
     * Updates the picture image using raw packed RGB pixel data.
     *
     * @param viewer      the player who will see the updated image
     * @param picture     the picture to update
     * @param rgbPixels   packed RGB pixel array, row-major
     * @param imageWidth  source image width in pixels
     * @param imageHeight source image height in pixels
     */
    void update(@NonNull Player viewer, @NonNull PictureService.Picture picture,
                @NonNull int[] rgbPixels, int imageWidth, int imageHeight);

    /**
     * Updates the picture image using raw packed RGB pixel data.
     *
     * @see #update(Player, PictureService.Picture, int[], int, int)
     */
    void update(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
                @NonNull int[] rgbPixels, int imageWidth, int imageHeight);

    // ── raw byte[] HWC pixel overloads (renderer output) ───────────────────

    /**
     * Shows the picture using raw HWC byte pixel data.
     * <p>
     * Accepts the voxel renderer's native output format: a flat {@code byte[]} of
     * interleaved {@code [R,G,B, R,G,B, ...]} triplets in row-major order.
     * No intermediate {@code int[]} or {@link BufferedImage} is created — bilinear
     * resize and palette conversion happen in a single fused pass.
     *
     * @param viewer      the player who will see the picture
     * @param picture     the picture to display
     * @param anchorX     block X coordinate of the bottom-left corner
     * @param anchorY     block Y coordinate of the bottom row
     * @param anchorZ     block Z coordinate of the bottom-left corner
     * @param face        the direction the picture faces
     * @param hwcPixels   HWC byte array ({@code [R,G,B, R,G,B, ...]}), row-major,
     *                    length must be at least {@code imageWidth × imageHeight × 3}
     * @param imageWidth  source image width in pixels
     * @param imageHeight source image height in pixels
     */
    void spawn(@NonNull Player viewer, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face,
               byte @NonNull [] hwcPixels, int imageWidth, int imageHeight);

    /**
     * Shows the picture using raw HWC byte pixel data.
     *
     * @see #spawn(Player, PictureService.Picture, int, int, int, BlockFace, byte[], int, int)
     */
    void spawn(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
               int anchorX, int anchorY, int anchorZ,
               @NonNull BlockFace face,
               byte @NonNull [] hwcPixels, int imageWidth, int imageHeight);

    /**
     * Updates the picture image using raw HWC byte pixel data.
     *
     * @param viewer      the player who will see the updated image
     * @param picture     the picture to update
     * @param hwcPixels   HWC byte array ({@code [R,G,B, R,G,B, ...]}), row-major
     * @param imageWidth  source image width in pixels
     * @param imageHeight source image height in pixels
     */
    void update(@NonNull Player viewer, @NonNull PictureService.Picture picture,
                byte @NonNull [] hwcPixels, int imageWidth, int imageHeight);

    /**
     * Updates the picture image using raw HWC byte pixel data.
     *
     * @see #update(Player, PictureService.Picture, byte[], int, int)
     */
    void update(@NonNull Collection<Player> viewers, @NonNull PictureService.Picture picture,
                byte @NonNull [] hwcPixels, int imageWidth, int imageHeight);
}

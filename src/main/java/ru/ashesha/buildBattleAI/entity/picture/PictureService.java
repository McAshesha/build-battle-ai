package ru.ashesha.buildBattleAI.entity.picture;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.BuildBattleAI;
import ru.ashesha.buildBattleAI.core.PluginService;
import ru.ashesha.buildBattleAI.entity.picture.api.BBAIPictureService;
import ru.ashesha.buildBattleAI.util.MapPalette;

import java.awt.image.BufferedImage;
import java.util.*;
import ru.ashesha.buildBattleAI.util.EntityUtils;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PacketEvents-based implementation of {@link BBAIPictureService}.
 * <p>
 * Displays images on walls using grids of invisible item frames, each holding a
 * filled map with custom pixel data. The service is <b>stateless</b>: pictures
 * store only entity IDs and map IDs — no location or image data. All positional
 * and visual state is the caller's responsibility.
 * <p>
 * The service resolves all version-dependent details once at startup:
 * <ul>
 *     <li>Item frame item metadata index — shifts across major versions (4–8)
 *         as Mojang adds new metadata entries to the Entity base class</li>
 *     <li>Item frame facing direction encoding — changes between pre-1.13
 *         (4-direction ordinal) and 1.13+ (6-direction ordinal)</li>
 *     <li>Filled map item construction — legacy data (pre-1.13), NBT tag
 *         (1.13–1.20.4), or component (1.20.5+) for encoding the map ID</li>
 * </ul>
 */
@RequiredArgsConstructor
public class PictureService implements BBAIPictureService, PluginService {

    /**
     * The plugin instance used for packet sending.
     */
    @NonNull
    private final BuildBattleAI plugin;

    /**
     * Monotonically increasing counter for map IDs.
     * Starts from a moderately high value to avoid collisions with real
     * server-tracked maps while staying within the short range for 1.8
     * compatibility (where the filled_map item's damage value encodes the map ID).
     */
    private final AtomicInteger mapIdCounter = new AtomicInteger(8192);

    // ── version-resolved factories and constants ────────────────────────────

    /**
     * Version-dependent entity metadata index for the item displayed in an item frame.
     * This index shifts across major versions as Mojang adds new metadata entries
     * to the Entity base class. Resolved in {@link #enable()}.
     */
    private int itemFrameItemIndex;

    /**
     * Factory for creating filled map {@link ItemStack} instances with a specific map ID.
     * Handles the legacy data (pre-1.13), NBT tag (1.13–1.20.4), and component
     * (1.20.5+) encoding differences. Resolved in {@link #enable()}.
     */
    private MapItemFactory mapItemFactory;

    /**
     * Factory for resolving the spawn entity data value for a given {@link BlockFace}.
     * Encodes the direction the item frame faces. Resolved in {@link #enable()}.
     */
    private FaceDataFactory faceDataFactory;

    /**
     * Resolves the image column index for a given frame column, accounting for
     * horizontal mirroring based on the facing direction.
     * <p>
     * Minecraft renders map content on item frames such that the image left
     * corresponds to different world directions depending on the frame's facing.
     * For SOUTH and WEST-facing pictures, columns are mirrored so the image
     * appears correct when viewed from the front.
     *
     * @param col   the frame grid column (0 = leftmost frame)
     * @param width the total number of columns
     * @param face  the direction the picture faces
     * @return the image tile column index
     */
    static int resolveImageCol(int col, int width, BlockFace face) {
        // NORTH and EAST-facing frames render map content mirrored relative to the
        // frame grid layout, so image columns need to be reversed for these faces.
        // SOUTH and WEST display map-left aligned with the grid's column order.
        if (face == BlockFace.NORTH || face == BlockFace.EAST)
            return width - 1 - col;
        return col;
    }

    /**
     * Returns the X offset per column for the given facing direction.
     * Frames extend horizontally perpendicular to the face direction.
     */
    static int deltaX(BlockFace face) {
        switch (face) {
            case SOUTH:
            case NORTH:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Returns the Z offset per column for the given facing direction.
     * Frames extend horizontally perpendicular to the face direction.
     */
    static int deltaZ(BlockFace face) {
        switch (face) {
            case EAST:
            case WEST:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Resolves all version-dependent factories from the server version.
     * Deferred from the constructor because services are created inside
     * {@link ru.ashesha.buildBattleAI.core.PluginContext}'s constructor,
     * before the context reference is published.
     */
    @Override
    public void enable() {
        ServerVersion version = plugin.getContext().getServerVersion();
        this.itemFrameItemIndex = resolveItemFrameItemIndex(version);
        this.mapItemFactory = resolveMapItemFactory(version);
        this.faceDataFactory = resolveFaceDataFactory(version);
        plugin.getPluginLogger().debug("PictureService enabled (version: %s, itemFrameIndex: %d).",
                version.name(), itemFrameItemIndex);
    }

    /**
     * No explicit cleanup needed — the service holds no runtime resources
     * beyond resolved factories.
     */
    @Override
    public void shutdown() {
        // Intentionally empty — no runtime resources to release.
    }

    // ── public API ──────────────────────────────────────────────────────────

    @Override
    @NonNull
    public Picture createPicture(int width, int height) {
        if (width < 1 || height < 1)
            throw new IllegalArgumentException(
                    "Picture dimensions must be at least 1x1, got " + width + "x" + height);

        int total = width * height;
        int[] entityIds = new int[total];
        int[] mapIds = new int[total];
        for (int i = 0; i < total; i++) {
            entityIds[i] = EntityUtils.nextEntityId();
            mapIds[i] = mapIdCounter.getAndIncrement();
        }

        return new Picture(entityIds, mapIds, width, height);
    }

    @Override
    public void spawn(@NonNull Player viewer, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face, @NonNull BufferedImage image) {
        spawn(Collections.singletonList(viewer), picture, anchorX, anchorY, anchorZ, face, image);
    }

    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face, @NonNull BufferedImage image) {
        if (viewers.isEmpty())
            return;

        int width = picture.width;
        int height = picture.height;

        int canvasW = width * MapPalette.MAP_SIZE;
        int canvasH = height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.imageToCanvasColors(image, canvasW, canvasH);

        spawnWithCanvas(viewers, picture, anchorX, anchorY, anchorZ, face, canvas, canvasW);
    }

    @Override
    public void despawn(@NonNull Player viewer, @NonNull Picture picture) {
        despawn(Collections.singletonList(viewer), picture);
    }

    @Override
    public void despawn(@NonNull Collection<Player> viewers, @NonNull Picture picture) {
        if (viewers.isEmpty())
            return;

        WrapperPlayServerDestroyEntities destroy =
                new WrapperPlayServerDestroyEntities(picture.entityIds);
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, destroy);
    }

    @Override
    public void update(@NonNull Player viewer, @NonNull Picture picture,
                       @NonNull BufferedImage image) {
        update(Collections.singletonList(viewer), picture, image);
    }

    @Override
    public void update(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                       @NonNull BufferedImage image) {
        if (viewers.isEmpty())
            return;

        int canvasW = picture.width * MapPalette.MAP_SIZE;
        int canvasH = picture.height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.imageToCanvasColors(image, canvasW, canvasH);

        updateWithCanvas(viewers, picture, canvas, canvasW);
    }

    // ── raw pixel overloads ────────────────────────────────────────────────

    @Override
    public void spawn(@NonNull Player viewer, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face,
                      int @NonNull [] rgbPixels, int imageWidth, int imageHeight) {
        spawn(Collections.singletonList(viewer), picture, anchorX, anchorY, anchorZ,
                face, rgbPixels, imageWidth, imageHeight);
    }

    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face,
                      int @NonNull [] rgbPixels, int imageWidth, int imageHeight) {
        if (viewers.isEmpty())
            return;

        int width = picture.width;
        int height = picture.height;
        int canvasW = width * MapPalette.MAP_SIZE;
        int canvasH = height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.rgbToCanvasColors(
                rgbPixels, imageWidth, imageHeight, canvasW, canvasH);

        spawnWithCanvas(viewers, picture, anchorX, anchorY, anchorZ, face, canvas, canvasW);
    }

    @Override
    public void update(@NonNull Player viewer, @NonNull Picture picture,
                       int @NonNull [] rgbPixels, int imageWidth, int imageHeight) {
        update(Collections.singletonList(viewer), picture, rgbPixels, imageWidth, imageHeight);
    }

    @Override
    public void update(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                       int @NonNull [] rgbPixels, int imageWidth, int imageHeight) {
        if (viewers.isEmpty())
            return;

        int width = picture.width;
        int height = picture.height;
        int canvasW = width * MapPalette.MAP_SIZE;
        int canvasH = height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.rgbToCanvasColors(
                rgbPixels, imageWidth, imageHeight, canvasW, canvasH);

        updateWithCanvas(viewers, picture, canvas, canvasW);
    }

    @Override
    public void spawn(@NonNull Player viewer, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face,
                      byte @NonNull [] hwcPixels, int imageWidth, int imageHeight) {
        spawn(Collections.singletonList(viewer), picture, anchorX, anchorY, anchorZ,
                face, hwcPixels, imageWidth, imageHeight);
    }

    // ── raw byte[] HWC pixel overloads ────────────────────────────────────

    @Override
    public void spawn(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                      int anchorX, int anchorY, int anchorZ,
                      @NonNull BlockFace face,
                      byte @NonNull [] hwcPixels, int imageWidth, int imageHeight) {
        if (viewers.isEmpty())
            return;

        int canvasW = picture.width * MapPalette.MAP_SIZE;
        int canvasH = picture.height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.hwcToCanvasColors(
                hwcPixels, imageWidth, imageHeight, canvasW, canvasH);

        spawnWithCanvas(viewers, picture, anchorX, anchorY, anchorZ, face, canvas, canvasW);
    }

    @Override
    public void update(@NonNull Player viewer, @NonNull Picture picture,
                       byte @NonNull [] hwcPixels, int imageWidth, int imageHeight) {
        update(Collections.singletonList(viewer), picture, hwcPixels, imageWidth, imageHeight);
    }

    @Override
    public void update(@NonNull Collection<Player> viewers, @NonNull Picture picture,
                       byte @NonNull [] hwcPixels, int imageWidth, int imageHeight) {
        if (viewers.isEmpty())
            return;

        int canvasW = picture.width * MapPalette.MAP_SIZE;
        int canvasH = picture.height * MapPalette.MAP_SIZE;
        byte[] canvas = MapPalette.hwcToCanvasColors(
                hwcPixels, imageWidth, imageHeight, canvasW, canvasH);

        updateWithCanvas(viewers, picture, canvas, canvasW);
    }

    // ── internal helpers ────────────────────────────────────────────────────

    /**
     * Spawns item frames and sends map tile data from a pre-computed canvas.
     * Shared core for all three input paths (BufferedImage, int[], byte[] HWC).
     */
    private void spawnWithCanvas(Collection<Player> viewers, Picture picture,
                                 int anchorX, int anchorY, int anchorZ,
                                 BlockFace face, byte[] canvas, int canvasW) {
        int faceData = faceDataFactory.resolve(face);
        int width = picture.width;
        int height = picture.height;

        for (int col = 0; col < width; col++)
            for (int row = 0; row < height; row++) {
                int tileIndex = col * height + row;
                int entityId = picture.entityIds[tileIndex];
                int mapId = picture.mapIds[tileIndex];

                double frameX = anchorX + col * deltaX(face);
                double frameY = anchorY + row;
                double frameZ = anchorZ + col * deltaZ(face);

                // 1. Spawn the invisible item frame
                PacketWrapper<?> spawn = new WrapperPlayServerSpawnEntity(
                        entityId, Optional.of(UUID.randomUUID()), EntityTypes.ITEM_FRAME,
                        new Vector3d(frameX, frameY, frameZ),
                        0f, 0f, 0f, faceData, Optional.empty()
                );
                for (Player viewer : viewers)
                    plugin.getContext().sendPacket(viewer, spawn);

                // 2. Set metadata: invisible + filled map item
                List<EntityData<?>> metadata = buildFrameMetadata(mapId);
                WrapperPlayServerEntityMetadata metaPacket =
                        new WrapperPlayServerEntityMetadata(entityId, metadata);
                for (Player viewer : viewers)
                    plugin.getContext().sendPacket(viewer, metaPacket);

                // 3. Extract tile from pre-computed canvas and send map data
                int imageCol = resolveImageCol(col, width, face);
                int imageRow = height - 1 - row;
                byte[] mapColors = MapPalette.extractTile(canvas, canvasW, imageCol, imageRow);
                sendMapData(viewers, mapId, mapColors);
            }
    }

    /**
     * Sends updated map tile data from a pre-computed canvas without respawning frames.
     * Shared core for both {@link BufferedImage} and raw {@code int[]} paths.
     */
    private void updateWithCanvas(Collection<Player> viewers, Picture picture,
                                  byte[] canvas, int canvasW) {
        int width = picture.width;
        int height = picture.height;

        for (int col = 0; col < width; col++)
            for (int row = 0; row < height; row++) {
                int tileIndex = col * height + row;
                int mapId = picture.mapIds[tileIndex];
                int imageRow = height - 1 - row;
                byte[] mapColors = MapPalette.extractTile(canvas, canvasW, col, imageRow);
                sendMapData(viewers, mapId, mapColors);
            }
    }

    /**
     * Sends a map data packet with the given pixel data to all viewers.
     *
     * @param viewers   the players to send the map data to
     * @param mapId     the map ID
     * @param mapColors the 128x128 byte array of map palette indices
     */
    private void sendMapData(Collection<Player> viewers, int mapId, byte[] mapColors) {
        WrapperPlayServerMapData mapData = new WrapperPlayServerMapData(
                mapId, (byte) 0, false, false,
                Collections.emptyList(),
                MapPalette.MAP_SIZE, MapPalette.MAP_SIZE,
                0, 0, mapColors
        );
        for (Player viewer : viewers)
            plugin.getContext().sendPacket(viewer, mapData);
    }

    /**
     * Builds the entity metadata for an item frame tile: invisible flag + displayed item.
     *
     * @param mapId the map ID for the filled map item
     * @return the metadata entries to apply
     */
    private List<EntityData<?>> buildFrameMetadata(int mapId) {
        List<EntityData<?>> data = new ArrayList<>(2);

        // Entity flags byte — 0x20 = invisible
        data.add(new EntityData<>(0, EntityDataTypes.BYTE, (byte) 0x20));

        // Displayed item: filled map with the given map ID
        ItemStack mapItem = mapItemFactory.create(mapId);
        data.add(new EntityData<>(itemFrameItemIndex, EntityDataTypes.ITEMSTACK, mapItem));

        return data;
    }

    // ── version-resolved factory builders ───────────────────────────────────

    /**
     * Resolves the entity metadata index for the item displayed in an item frame.
     * <p>
     * Item Frame extends HangingEntity extends Entity. The item index equals the
     * sum of metadata fields in Entity + HangingEntity, which grows across versions:
     * <pre>
     *   1.8        → index 5  (Entity: 0–3)
     *   1.9        → index 5  (Entity: 0–4, Silent added)
     *   1.10–1.13  → index 6  (Entity: 0–5, NoGravity added)
     *   1.14–1.16  → index 7  (Entity: 0–6, Pose added)
     *   1.17–1.21.1→ index 8  (Entity: 0–7, FrozenTicks added)
     *   1.21.2+    → index 9  (HangingEntity adds DATA_DIRECTION at index 8)
     * </pre>
     */
    private int resolveItemFrameItemIndex(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_21_2))
            return 9;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_17))
            return 8;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_14))
            return 7;
        if (version.isNewerThanOrEquals(ServerVersion.V_1_10))
            return 6;
        return 5;
    }

    /**
     * Resolves the factory for creating filled map {@link ItemStack} instances.
     * <ul>
     *     <li>1.20.5+ — component-based: sets {@code MAP_ID} component</li>
     *     <li>1.13–1.20.4 — NBT-based: sets {@code "map"} integer tag</li>
     *     <li>&lt;1.13 — legacy: uses the item's damage/data value as the map ID</li>
     * </ul>
     */
    private MapItemFactory resolveMapItemFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_20_5))
            return mapId -> ItemStack.builder()
                    .type(ItemTypes.FILLED_MAP)
                    .amount(1)
                    .component(ComponentTypes.MAP_ID, mapId)
                    .build();
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13))
            return mapId -> {
                NBTCompound nbt = new NBTCompound();
                nbt.setTag("map", new NBTInt(mapId));
                return ItemStack.builder()
                        .type(ItemTypes.FILLED_MAP)
                        .amount(1)
                        .nbt(nbt)
                        .build();
            };
        return mapId -> ItemStack.builder()
                .type(ItemTypes.FILLED_MAP)
                .amount(1)
                .legacyData(mapId)
                .build();
    }

    /**
     * Resolves the factory for converting a {@link BlockFace} to the spawn entity
     * data value for item frames.
     * <p>
     * The encoding changed in 1.13 when floor/ceiling item frames were added:
     * <ul>
     *     <li>1.13+ — 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST</li>
     *     <li>&lt;1.13 — 0=SOUTH, 1=WEST, 2=NORTH, 3=EAST (walls only)</li>
     * </ul>
     */
    private FaceDataFactory resolveFaceDataFactory(ServerVersion version) {
        if (version.isNewerThanOrEquals(ServerVersion.V_1_13))
            return face -> {
                switch (face) {
                    case NORTH:
                        return 2;
                    case SOUTH:
                        return 3;
                    case WEST:
                        return 4;
                    case EAST:
                        return 5;
                    default:
                        throw new IllegalArgumentException("Unsupported face: " + face);
                }
            };
        return face -> {
            switch (face) {
                case SOUTH:
                    return 0;
                case WEST:
                    return 1;
                case NORTH:
                    return 2;
                case EAST:
                    return 3;
                default:
                    throw new IllegalArgumentException("Unsupported face: " + face);
            }
        };
    }

    // ── version-dispatched functional interfaces ────────────────────────────

    /**
     * Factory for creating filled map items with a specific map ID.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface MapItemFactory {
        ItemStack create(int mapId);
    }

    /**
     * Factory for resolving the spawn entity data value from a block face.
     * Resolved once at startup based on the server version.
     */
    @FunctionalInterface
    private interface FaceDataFactory {
        int resolve(BlockFace face);
    }

    // ── Picture data class ─────────────────────────────────────────────────

    /**
     * Packet-based picture composed of a grid of invisible item frames with maps.
     * <p>
     * Pictures are <b>stateless</b>: they store only synthetic entity IDs and map
     * IDs, not their location, image, or facing direction. This matches the NPC
     * and hologram service patterns where all state is the caller's responsibility.
     * <p>
     * The tile layout is column-major: tile index {@code i = col * height + row},
     * where {@code col} is the horizontal grid position and {@code row} is the
     * vertical position (0 = bottom).
     * <p>
     * Create instances via {@link BBAIPictureService#createPicture(int, int)}.
     */
    public static final class Picture {

        /**
         * Synthetic entity IDs for each item frame, indexed column-major.
         */
        final int[] entityIds;

        /**
         * Map IDs for each tile's filled map, indexed column-major.
         */
        final int[] mapIds;

        /**
         * Number of tile columns in the grid.
         */
        @Getter
        final int width;

        /**
         * Number of tile rows in the grid.
         */
        @Getter
        final int height;

        /**
         * Package-private constructor — instances are created by {@link PictureService}.
         *
         * @param entityIds the synthetic entity IDs (one per tile)
         * @param mapIds    the map IDs (one per tile)
         * @param width     number of tile columns
         * @param height    number of tile rows
         */
        Picture(int[] entityIds, int[] mapIds, int width, int height) {
            this.entityIds = entityIds;
            this.mapIds = mapIds;
            this.width = width;
            this.height = height;
        }

        /**
         * Returns the total number of tiles (frames) in the picture.
         *
         * @return width * height
         */
        public int getTileCount() {
            return entityIds.length;
        }

        /**
         * Returns the entity ID for the tile at the given grid position.
         *
         * @param col column index (0 = leftmost)
         * @param row row index (0 = bottom)
         * @return the entity ID
         */
        public int getEntityId(int col, int row) {
            return entityIds[col * height + row];
        }

        /**
         * Returns the map ID for the tile at the given grid position.
         *
         * @param col column index (0 = leftmost)
         * @param row row index (0 = bottom)
         * @return the map ID
         */
        public int getMapId(int col, int row) {
            return mapIds[col * height + row];
        }
    }
}
